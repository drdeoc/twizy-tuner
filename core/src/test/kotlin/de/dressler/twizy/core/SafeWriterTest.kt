package de.dressler.twizy.core

import de.dressler.twizy.core.params.SevconCatalog
import de.dressler.twizy.core.sevcon.ControllerType
import de.dressler.twizy.core.sevcon.NmtState
import de.dressler.twizy.core.write.PreOpGuard
import de.dressler.twizy.core.write.SafeWriter
import de.dressler.twizy.core.write.UnsafeWriteException
import de.dressler.twizy.core.write.WriteRequest
import de.dressler.twizy.core.write.WriteResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeWriterTest {

    private val maxRpm = SevconCatalog[0x2920, 0x05]
    private val warnOn = SevconCatalog[0x3813, 0x34]
    private val driveLevel = SevconCatalog[0x2920, 0x01]
    private val torque1 = SevconCatalog[0x4611, 0x01]
    private val ratedVoltage = SevconCatalog[0x4640, 0x01]

    private var guardAsked = 0
    private fun writer(stack: SimStack, confirm: Boolean = true) =
        SafeWriter(stack.sevcon, PreOpGuard { guardAsked++; confirm })

    @Test
    fun `read-only and out of range values are refused before anything is written`() = runTest {
        val problems = SafeWriter.validate(
            ControllerType.TWIZY80,
            listOf(WriteRequest(ratedVoltage, 600), WriteRequest(maxRpm, 12_000), WriteRequest(torque1, 1_200)),
        )
        assertEquals(3, problems.size)
    }

    @Test
    fun `limits depend on the controller type`() {
        val t45 = SafeWriter.validate(ControllerType.TWIZY45, listOf(WriteRequest(torque1, 800)))
        val t80 = SafeWriter.validate(ControllerType.TWIZY80, listOf(WriteRequest(torque1, 800)))
        assertEquals(1, t45.size)
        assertTrue(t80.isEmpty())
    }

    @Test
    fun `prepare throws for unsafe requests`() = runTest {
        val stack = SimStack().init()
        assertThrows(UnsafeWriteException::class.java) {
            runBlocking { writer(stack).prepare(listOf(WriteRequest(maxRpm, 20_000))) }
        }
    }

    @Test
    fun `pre-op writes ask the guard, verify, and return to operational`() = runTest {
        val stack = SimStack().init()
        val writer = writer(stack)
        val plan = writer.prepare(listOf(WriteRequest(maxRpm, 8_338), WriteRequest(warnOn, 9_000)))
        assertEquals(7_250L, plan.changes.first { it.parameter == maxRpm }.oldValue)

        val result = writer.execute(plan)

        assertTrue(result is WriteResult.Success)
        assertEquals(1, guardAsked)
        assertEquals(8_338L, stack.sim.value(maxRpm.address))
        assertEquals(9_000L, stack.sim.value(warnOn.address))
        assertEquals(NmtState.OPERATIONAL.code, stack.sim.state)
        assertEquals(0, stack.sim.mapCommits)
    }

    @Test
    fun `operational writes do not need pre-op`() = runTest {
        val stack = SimStack().init()
        val writer = writer(stack)
        val result = writer.execute(writer.prepare(listOf(WriteRequest(driveLevel, 800))))
        assertTrue(result is WriteResult.Success)
        assertEquals(0, guardAsked)
        assertEquals(800L, stack.sim.value(driveLevel.address))
    }

    @Test
    fun `map changes are committed`() = runTest {
        val stack = SimStack().init()
        val writer = writer(stack)
        writer.execute(writer.prepare(listOf(WriteRequest(torque1, 1_000))))
        assertEquals(1, stack.sim.mapCommits)
    }

    @Test
    fun `failure rolls back earlier writes and returns to operational`() = runTest {
        val stack = SimStack().init()
        stack.sim.failingWrites += warnOn.address
        val writer = writer(stack)
        val result = writer.execute(writer.prepare(listOf(WriteRequest(maxRpm, 8_338), WriteRequest(warnOn, 9_000))))

        result as WriteResult.Failed
        assertEquals(7_250L, stack.sim.value(maxRpm.address))
        assertEquals(listOf(maxRpm, warnOn), result.rolledBack.map { it.parameter })
        assertTrue(result.rollbackFailures.isEmpty())
        assertTrue(result.operationalRestored)
        assertEquals(NmtState.OPERATIONAL.code, stack.sim.state)
    }

    @Test
    fun `nothing is written when the driver does not confirm`() = runTest {
        val stack = SimStack().init()
        val writer = writer(stack, confirm = false)
        val result = writer.execute(writer.prepare(listOf(WriteRequest(maxRpm, 8_338))))
        assertTrue(result is WriteResult.Failed)
        assertEquals(7_250L, stack.sim.value(maxRpm.address))
        assertEquals(NmtState.OPERATIONAL.code, stack.sim.state)
    }

    @Test
    fun `stale plans are not executed`() = runTest {
        val stack = SimStack().init()
        val writer = writer(stack)
        val plan = writer.prepare(listOf(WriteRequest(driveLevel, 800)))
        writer.execute(writer.prepare(listOf(WriteRequest(driveLevel, 700))))
        val result = writer.execute(plan)
        assertTrue(result is WriteResult.Failed)
        assertEquals(700L, stack.sim.value(driveLevel.address))
    }

    @Test
    fun `refused pre-op leaves everything untouched`() = runTest {
        val stack = SimStack().init()
        stack.sim.refusePreOp = true
        val writer = writer(stack)
        val result = writer.execute(writer.prepare(listOf(WriteRequest(maxRpm, 8_338))))
        result as WriteResult.Failed
        assertTrue(result.rolledBack.isEmpty())
        assertEquals(7_250L, stack.sim.value(maxRpm.address))
        assertEquals(NmtState.OPERATIONAL.code, stack.sim.state)
    }

    @Test
    fun `unchanged values are dropped from the plan`() = runTest {
        val stack = SimStack().init()
        val plan = writer(stack).prepare(listOf(WriteRequest(maxRpm, 7_250)))
        assertTrue(plan.isEmpty)
    }
}
