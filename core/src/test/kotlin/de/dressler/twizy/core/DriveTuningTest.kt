package de.dressler.twizy.core

import de.dressler.twizy.core.canopen.ObjectAddress
import de.dressler.twizy.core.params.SevconCatalog
import de.dressler.twizy.core.sevcon.ControllerType
import de.dressler.twizy.core.tuning.DriveTuning
import de.dressler.twizy.core.tuning.DriveTuningPlanner
import de.dressler.twizy.core.tuning.DriveTuningReader
import de.dressler.twizy.core.tuning.TuningException
import de.dressler.twizy.core.write.PreOpGuard
import de.dressler.twizy.core.write.SafeWriter
import de.dressler.twizy.core.write.WriteRequest
import de.dressler.twizy.core.write.WriteResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveTuningTest {

    private fun List<WriteRequest>.value(index: Int, sub: Int): Long =
        first { it.parameter.address == ObjectAddress(index, sub) }.value

    @Test
    fun `factory tuning reproduces the factory values of both models`() {
        for (type in ControllerType.entries) {
            for (request in DriveTuningPlanner.plan(type, DriveTuning.factory(type))) {
                assertEquals("$type ${request.parameter}", request.parameter.defaultFor(type), request.value)
            }
        }
    }

    @Test
    fun `factory tuning on an untouched controller writes nothing`() = runTest {
        for (type in ControllerType.entries) {
            val stack = SimStack(type).init()
            val plan = SafeWriter(stack.sevcon, PreOpGuard { true }).prepare(DriveTuningPlanner.plan(type, DriveTuning.factory(type)))
            assertTrue("$type: ${plan.changes}", plan.isEmpty)
        }
    }

    @Test
    fun `92 kph on a Twizy 80 uses the OVMS offsets`() {
        val r = DriveTuningPlanner.plan(ControllerType.TWIZY80, DriveTuning.factory(ControllerType.TWIZY80).copy(maxKph = 92, warnKph = 100))
        assertEquals(8_337L, r.value(0x2920, 0x05))       // 7250 * 92 / 80
        assertEquals(900L, r.value(0x2920, 0x06))         // reverse stays at default
        assertEquals(8_737L, r.value(0x3813, 0x33))
        assertEquals(9_137L, r.value(0x3813, 0x35))
        assertEquals(9_587L, r.value(0x3813, 0x3B))
        assertEquals(9_044L, r.value(0x3813, 0x34))       // 8050 * 100 / 89
        assertEquals(8_494L, r.value(0x3813, 0x3C))
        // the power map is stretched to the new max rpm
        assertEquals(8_337L, r.value(0x4611, 0x12))
        assertEquals(2_115L, r.value(0x4611, 0x04))
    }

    @Test
    fun `130 percent torque is capped at the flux map limit and keeps the default flux map`() {
        val r = DriveTuningPlanner.plan(ControllerType.TWIZY80, DriveTuning.factory(ControllerType.TWIZY80).copy(torquePercent = 130))
        assertEquals(70_125L, r.value(0x6076, 0x00))
        assertEquals(1_122L, r.value(0x4611, 0x01))
        // OVMS only switches to the extended flux map above 1122, which needs a current boost
        assertEquals(listOf(964L, 9_728L, 1_122L, 9_984L), (0x0F..0x12).map { r.value(0x4610, it) })
        assertEquals(450L, r.value(0x4641, 0x02))         // currents stay at default
        assertEquals(450_000L, r.value(0x6075, 0x00))
    }

    @Test
    fun `Twizy 45 torque includes the map delta`() {
        val r = DriveTuningPlanner.plan(ControllerType.TWIZY45, DriveTuning.factory(ControllerType.TWIZY45).copy(torquePercent = 130))
        assertEquals(36_500L, r.value(0x6076, 0x00))
        assertEquals(576L, r.value(0x4611, 0x01))
    }

    @Test
    fun `max speed ceiling per model`() {
        assertEquals(111, DriveTuning.maxKphFor(ControllerType.TWIZY80)) // rpm capped at 10000 anyway
        assertEquals(73, DriveTuning.maxKphFor(ControllerType.TWIZY45))
        val e = assertThrows(TuningException::class.java) {
            DriveTuningPlanner.plan(ControllerType.TWIZY45, DriveTuning(80, 80, 100, 100, 100))
        }
        assertTrue(e.problems.single().contains("73 km/h"))
    }

    @Test
    fun `invalid input is rejected with readable messages`() {
        val e = assertThrows(TuningException::class.java) {
            DriveTuningPlanner.plan(ControllerType.TWIZY80, DriveTuning(150, 100, 200, 100, 100))
        }
        assertEquals(3, e.problems.size) // speed, torque, warning below max
    }

    @Test
    fun `every input combination is either rejected or safe`() {
        var planned = 0
        var rejected = 0
        for (type in ControllerType.entries) {
            for (kph in DriveTuning.KPH_RANGE step 7) for (torque in DriveTuning.TORQUE_RANGE step 10)
                for (low in DriveTuning.POWER_LOW_RANGE step 11) for (high in DriveTuning.POWER_HIGH_RANGE step 10) {
                    val tuning = DriveTuning(kph, maxOf(kph, 20), torque, low, high)
                    val requests = try {
                        DriveTuningPlanner.plan(type, tuning)
                    } catch (e: TuningException) {
                        rejected++
                        continue
                    }
                    val problems = SafeWriter.validate(type, requests)
                    assertTrue("$type $tuning: $problems", problems.isEmpty())
                    planned++
                }
        }
        assertTrue(planned > 1000)
        println("drive tuning sweep: $planned safe plans, $rejected rejected")
    }

    @Test
    fun `applied tuning can be read back`() = runTest {
        val stack = SimStack().init()
        val tuning = DriveTuning(maxKph = 92, warnKph = 100, torquePercent = 120, powerLowPercent = 115, powerHighPercent = 110)
        val writer = SafeWriter(stack.sevcon, PreOpGuard { true })
        val result = writer.execute(writer.prepare(DriveTuningPlanner.plan(ControllerType.TWIZY80, tuning)))
        assertTrue(result.toString(), result is WriteResult.Success)
        assertEquals(1, stack.sim.mapCommits)

        val read = DriveTuningReader.read(stack.sevcon, ControllerType.TWIZY80)
        assertEquals(92, read.maxKph)
        assertEquals(100, read.warnKph)
        assertEquals(120, read.torquePercent)
        assertTrue("power low ${read.powerLowPercent}", read.powerLowPercent in 114..116)
        assertTrue("power high ${read.powerHighPercent}", read.powerHighPercent in 109..111)
    }

    @Test
    fun `factory controller reads back as factory tuning`() = runTest {
        for (type in ControllerType.entries) {
            assertEquals(DriveTuning.factory(type), DriveTuningReader.read(SimStack(type).init().sevcon, type))
        }
    }
}
