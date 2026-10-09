package de.dressler.twizy.core

import de.dressler.twizy.core.canopen.ObjectAddress
import de.dressler.twizy.core.canopen.SdoAbortException
import de.dressler.twizy.core.params.DataType
import de.dressler.twizy.core.params.SevconCatalog
import de.dressler.twizy.core.sevcon.ControllerType
import de.dressler.twizy.core.sevcon.NmtState
import de.dressler.twizy.core.tuning.DriveTuning
import de.dressler.twizy.core.tuning.DrivingFeel
import de.dressler.twizy.core.tuning.DrivingFeelPlanner
import de.dressler.twizy.core.tuning.DrivingFeelReader
import de.dressler.twizy.core.tuning.MapKind
import de.dressler.twizy.core.tuning.MapPoint
import de.dressler.twizy.core.tuning.Profile
import de.dressler.twizy.core.tuning.ProfilePlanner
import de.dressler.twizy.core.tuning.ProfileStore
import de.dressler.twizy.core.tuning.TorqueSpeedMapPlanner
import de.dressler.twizy.core.tuning.TorqueSpeedMaps
import de.dressler.twizy.core.tuning.TuningException
import de.dressler.twizy.core.write.PreOpGuard
import de.dressler.twizy.core.write.SafeWriter
import de.dressler.twizy.core.write.WriteRequest
import de.dressler.twizy.core.write.WriteResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writer(stack: SimStack, onGuard: () -> Unit = {}) = SafeWriter(stack.sevcon, PreOpGuard { onGuard(); true })

    @Test
    fun `factory feel and maps reproduce the factory values`() {
        for (type in ControllerType.entries) {
            for (r in DrivingFeelPlanner.plan(type, DrivingFeel.factory(type))) {
                assertEquals("$type ${r.parameter}", r.parameter.defaultFor(type), r.value)
            }
            val maps = TorqueSpeedMaps.factory(type)
            assertTrue(TorqueSpeedMapPlanner.plan(maps, maps).all { it.value == it.parameter.defaultFor(type) })
        }
    }

    @Test
    fun `factory profile on an untouched controller writes nothing and reads back as factory`() = runTest {
        for (type in ControllerType.entries) {
            val stack = SimStack(type).init()
            val requests = ProfilePlanner.plan(stack.sevcon, type, Profile.factory(type))
            assertTrue(writer(stack).prepare(requests).isEmpty)
            assertEquals(Profile.factory(type), Profile.read(stack.sevcon, type, "Factory"))
        }
    }

    @Test
    fun `recuperation is capped at 1000 per mille`() {
        val r = DrivingFeelPlanner.plan(ControllerType.TWIZY80, DrivingFeel.factory(ControllerType.TWIZY80).copy(neutralRecupPercent = 100))
        assertEquals(1000L, r.first { it.parameter.address == ObjectAddress(0x2920, 0x03) }.value)
    }

    @Test
    fun `every feel within its ranges is safe`() {
        for (type in ControllerType.entries) for (p in 0..250 step 5) {
            val f = DrivingFeel(
                drivePercent = p.coerceIn(DrivingFeel.DRIVE_RANGE), neutralRecupPercent = p.coerceIn(0, 100),
                brakeRecupPercent = (100 - p).coerceIn(0, 100), rampStartPermille = p.coerceIn(DrivingFeel.RAMP_START_RANGE),
                rampAccelPercent = p.coerceIn(1, 100), rampDecelPercent = p.coerceIn(0, 100), rampNeutralPercent = p.coerceIn(0, 100),
                rampBrakePercent = p.coerceIn(0, 100), rampLimitAccelPercent = p.coerceIn(1, 100), rampLimitDecelPercent = p.coerceIn(0, 100),
                smoothingPercent = p.coerceIn(0, 100),
            )
            assertEquals("$type $f", emptyList<String>(), SafeWriter.validate(type, DrivingFeelPlanner.plan(type, f)))
        }
    }

    @Test
    fun `feel changes are written without pre-op`() = runTest {
        val stack = SimStack().init()
        var asked = 0
        val feel = DrivingFeel.factory(ControllerType.TWIZY80).copy(neutralRecupPercent = 30, smoothingPercent = 40)
        val w = writer(stack) { asked++ }
        assertTrue(w.execute(w.prepare(DrivingFeelPlanner.plan(ControllerType.TWIZY80, feel))) is WriteResult.Success)
        assertEquals(0, asked)
        assertEquals(feel, DrivingFeelReader.read(stack.sevcon, ControllerType.TWIZY80))
    }

    @Test
    fun `naive map write order is refused by the controller`() = runTest {
        val stack = SimStack().init()
        stack.sevcon.login()
        stack.sevcon.enterPreOperational()
        // drive map point 1 is at 3000 rpm, point 2 at 3500: moving point 1 to 4000 first is out of order
        val p1 = SevconCatalog[0x3813, MapKind.DRIVE.rpmSubIndex(0)]
        assertThrows(SdoAbortException::class.java) { runBlocking { stack.sevcon.write(p1.address, DataType.U16, 4000) } }
    }

    @Test
    fun `map points are moved in an order the controller accepts`() = runTest {
        val type = ControllerType.TWIZY80
        val stack = SimStack(type).init()
        val factory = TorqueSpeedMaps.factory(type)
        // shift the whole drive map up, past the old positions of the neighbours
        val shifted = factory.drive.mapIndexed { i, p -> MapPoint.ofPercent(100 - i * 10, p.rpm + 3000) }
        val target = factory.with(MapKind.DRIVE, shifted)

        val w = writer(stack)
        val result = w.execute(w.prepare(TorqueSpeedMapPlanner.plan(factory, target)))
        assertTrue(result.toString(), result is WriteResult.Success)
        assertEquals(target, TorqueSpeedMaps.read(stack.sevcon))

        // and back down again
        val back = w.execute(w.prepare(TorqueSpeedMapPlanner.plan(target, factory)))
        assertTrue(back.toString(), back is WriteResult.Success)
        assertEquals(factory, TorqueSpeedMaps.read(stack.sevcon))
    }

    @Test
    fun `decreasing map speeds are rejected`() {
        val factory = TorqueSpeedMaps.factory(ControllerType.TWIZY80)
        val broken = factory.with(MapKind.BRAKE, factory.brake.reversed())
        assertThrows(TuningException::class.java) { TorqueSpeedMapPlanner.plan(factory, broken) }
    }

    @Test
    fun `a full profile is applied with op values first and can be read back`() = runTest {
        val type = ControllerType.TWIZY80
        val stack = SimStack(type).init()
        val factory = Profile.factory(type)
        val profile = factory.copy(
            name = "Sporty",
            drive = DriveTuning(92, 100, 120, 115, 110),
            feel = factory.feel.copy(neutralRecupPercent = 30, rampAccelPercent = 40),
            maps = factory.maps.with(MapKind.NEUTRAL, factory.maps.neutral.map { it.copy(level = 32_767) }),
        )
        var stateWhenAsked: Long? = null
        var recupWhenAsked: Long? = null
        val w = writer(stack) {
            stateWhenAsked = stack.sim.state
            recupWhenAsked = stack.sim.value(ObjectAddress(0x2920, 0x03))
        }
        val result = w.execute(w.prepare(ProfilePlanner.plan(stack.sevcon, type, profile)))

        assertTrue(result.toString(), result is WriteResult.Success)
        assertEquals(NmtState.OPERATIONAL.code, stateWhenAsked)     // asked while still operational
        assertEquals(303L, recupWhenAsked)                          // op-mode value (182 * 30 / 18) already written
        assertEquals(NmtState.OPERATIONAL.code, stack.sim.state)
        val read = Profile.read(stack.sevcon, type, "Sporty")
        assertEquals(profile.feel, read.feel)
        assertEquals(profile.maps, read.maps)
        assertEquals(92, read.drive.maxKph)
    }

    @Test
    fun `profiles from the other model are refused`() = runTest {
        val stack = SimStack(ControllerType.TWIZY80).init()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { ProfilePlanner.plan(stack.sevcon, ControllerType.TWIZY80, Profile.factory(ControllerType.TWIZY45)) }
        }
    }

    @Test
    fun `profile store round trip`() {
        val store = ProfileStore(tmp.root)
        val p = Profile.factory(ControllerType.TWIZY80).copy(name = "Winter / Snow")
        store.save(p)
        store.save(p.copy(feel = p.feel.copy(neutralRecupPercent = 5)))  // same name replaces
        assertEquals(listOf(5), store.list().map { it.feel.neutralRecupPercent })
        assertEquals(p.copy(feel = p.feel.copy(neutralRecupPercent = 5)), Profile.fromJson(store.list().single().toJson()))
        assertTrue(store.delete(store.list().single()))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `rejected pre-op rolls back op values already written`() = runTest {
        val type = ControllerType.TWIZY80
        val stack = SimStack(type).init()
        stack.sim.refusePreOp = true
        val factory = Profile.factory(type)
        val profile = factory.copy(feel = factory.feel.copy(neutralRecupPercent = 30), drive = factory.drive.copy(maxKph = 70))
        val w = writer(stack)
        val result = w.execute(w.prepare(ProfilePlanner.plan(stack.sevcon, type, profile)))
        assertTrue(result is WriteResult.Failed)
        assertEquals(182L, stack.sim.value(ObjectAddress(0x2920, 0x03)))
        assertEquals(7_250L, stack.sim.value(ObjectAddress(0x2920, 0x05)))
    }

    @Test
    fun `write order puts op values first`() = runTest {
        val stack = SimStack().init()
        val p = WriteRequest(SevconCatalog[0x2920, 0x05], 8000)
        val o = WriteRequest(SevconCatalog[0x2920, 0x01], 900)
        val w = writer(stack)
        assertTrue(w.execute(w.prepare(listOf(p, o))) is WriteResult.Success)
    }
}
