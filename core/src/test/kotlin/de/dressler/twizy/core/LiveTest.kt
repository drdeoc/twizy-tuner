package de.dressler.twizy.core

import de.dressler.twizy.core.elm.hexToBytes
import de.dressler.twizy.core.live.Frames
import de.dressler.twizy.core.live.Gear
import de.dressler.twizy.core.live.LiveData
import de.dressler.twizy.core.live.LiveMonitor
import de.dressler.twizy.core.live.VehicleGuard
import de.dressler.twizy.core.live.VehicleNotSafeException
import de.dressler.twizy.core.params.SevconCatalog
import de.dressler.twizy.core.sevcon.ControllerType
import de.dressler.twizy.core.sevcon.NmtState
import de.dressler.twizy.core.write.PreOpGuard
import de.dressler.twizy.core.write.SafeWriter
import de.dressler.twizy.core.write.WriteRequest
import de.dressler.twizy.core.write.WriteResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTest {

    private fun frame(hex: String) = hex.replace(" ", "").hexToBytes()

    // Sample frames from the "BMS-Replay" sheet of the Twizy CANBUS Objektverzeichnis.

    @Test
    fun `0x155 battery frame from the replay sheet`() {
        val b = Frames.battery(frame("07 97 CA 54 52 30 00 6C"))!!
        assertEquals(52.6, b.socPercent!!, 1e-9)        // 5230h / 400
        assertEquals(1.5, b.currentAmps!!, 1e-9)        // 7CAh: 1.5 A drawn from the battery
    }

    @Test
    fun `0x155 is invalid during the init phase`() {
        assertNull(Frames.battery(frame("07 97 CA 94 52 30 00 6C")))
    }

    @Test
    fun `0x424 BMS limits from the replay sheet`() {
        val l = Frames.bmsLimits(frame("11 40 10 20 39 63 00 3A"))!!
        assertEquals(8_000, l.maxRecupWatts)
        assertEquals(16_000, l.maxDriveWatts)
        assertEquals(99, l.healthPercent)
    }

    @Test
    fun `0x55F pack voltage from the replay sheet`() {
        assertEquals(54.3, Frames.packVoltage(frame("FF FF 73 00 00 21 F2 1F"))!!, 1e-9)
    }

    @Test
    fun `0x556 cell voltages and 0x554 temperatures from the replay sheet`() {
        val cells = Frames.cellVoltages(frame("30 93 09 30 93 09 30 9A"), 5)!!
        assertEquals(listOf(3.885, 3.885, 3.885, 3.885, 3.885), cells)
        assertEquals(List(7) { 18 }, Frames.moduleTempsC(frame("3A 3A 3A 3A 3A 3A 3A 00")))
    }

    @Test
    fun `simulated vehicle frames decode to its state`() {
        val v = de.dressler.twizy.core.sim.SimulatedVehicle().apply {
            speedKph = 42.37; gear = Gear.DRIVE; go = true; currentAmps = -12.25; socPercent = 81.5
        }
        var data = LiveData()
        for (id in Frames.PERIOD_MILLIS.keys) data = data.with(id, v.frame(id)!!)
        assertEquals(42.37, data.dashboard!!.speedKph!!, 1e-9)
        assertEquals(Gear.DRIVE, data.driveState!!.gear)
        assertTrue(data.driveState!!.go)
        assertEquals(-12.25, data.battery!!.currentAmps!!, 1e-9)
        assertEquals(81.5, data.battery!!.socPercent!!, 1e-9)
        assertEquals(v.cellVoltages, data.cellVoltages)
        assertEquals(v.moduleTempsC, data.moduleTempsC)
        assertEquals(55.4, data.packVoltage!!, 1e-9)
        assertEquals(12_345.67, data.odometerKm!!, 1e-9)
        assertEquals("1234567", data.vinSuffix)
        assertEquals(22, data.motorTempC)
    }

    @Test
    fun `monitoring switches the filter and SDO still works afterwards`() = runTest {
        val stack = SimStack().init()
        val live = LiveMonitor(stack.elm)
        val data = live.refresh(LiveData(), cycle = 0)
        assertEquals(76.5, data.battery!!.socPercent!!, 1e-9)
        assertEquals(Gear.NEUTRAL, data.driveState!!.gear)
        assertEquals(ControllerType.TWIZY80, stack.sevcon.identify()) // filter back on 0x581
        assertTrue(stack.transport.received.contains("ATCRA155"))
        assertEquals("ATCRA581", stack.transport.received.last { it.startsWith("ATCRA") })
    }

    @Test
    fun `missing frames time out without breaking the adapter`() = runTest {
        val stack = SimStack().init()
        stack.vehicle.silent += Frames.DRIVE_STATE
        assertNull(LiveMonitor(stack.elm).fetch(Frames.DRIVE_STATE))
        assertEquals(ControllerType.TWIZY80, stack.sevcon.identify())
    }

    private suspend fun guardResult(configure: SimStack.() -> Unit): Pair<WriteResult, SimStack> {
        val stack = SimStack().init()
        stack.configure()
        val guard = VehicleGuard(LiveMonitor(stack.elm), PreOpGuard { true })
        val writer = SafeWriter(stack.sevcon, guard)
        val result = writer.execute(writer.prepare(listOf(WriteRequest(SevconCatalog[0x2920, 0x05], 8_000))))
        return result to stack
    }

    @Test
    fun `guard allows configuration mode when stopped in N without GO`() = runTest {
        val (result, stack) = guardResult { }
        assertTrue(result.toString(), result is WriteResult.Success)
        assertEquals(8_000L, stack.sim.value(SevconCatalog[0x2920, 0x05].address))
    }

    @Test
    fun `guard refuses when moving, in gear, with GO or without data`() = runTest {
        val cases = mapOf<String, SimStack.() -> Unit>(
            "moving" to { vehicle.speedKph = 3.2 },
            "not N" to { vehicle.gear = Gear.DRIVE },
            "GO is active" to { vehicle.go = true },
            "could not read gear" to { vehicle.silent += Frames.DRIVE_STATE },
            "could not read the speed" to { vehicle.silent += Frames.DASHBOARD },
        )
        for ((reason, setup) in cases) {
            val (result, stack) = guardResult(setup)
            result as WriteResult.Failed
            val cause = result.cause as VehicleNotSafeException
            assertTrue("$reason: ${cause.reasons}", cause.reasons.any { reason in it })
            assertEquals(7_250L, stack.sim.value(SevconCatalog[0x2920, 0x05].address))
            assertEquals(NmtState.OPERATIONAL.code, stack.sim.state)
        }
    }

    @Test
    fun `operational writes need no vehicle check`() = runTest {
        val (result, _) = guardResult { vehicle.speedKph = 50.0 }
        assertTrue(result is WriteResult.Failed) // speed object needs pre-op
        val stack = SimStack().init().apply { vehicle.speedKph = 50.0 }
        val writer = SafeWriter(stack.sevcon, VehicleGuard(LiveMonitor(stack.elm), PreOpGuard { true }))
        val r = writer.execute(writer.prepare(listOf(WriteRequest(SevconCatalog[0x2920, 0x03], 300))))
        assertTrue(r is WriteResult.Success)
    }
}
