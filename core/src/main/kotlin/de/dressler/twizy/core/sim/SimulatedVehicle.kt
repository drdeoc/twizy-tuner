package de.dressler.twizy.core.sim

import de.dressler.twizy.core.live.Frames
import de.dressler.twizy.core.live.Gear

/**
 * Produces the Twizy's broadcast frames from a simple vehicle state, encoded the way
 * [Frames] decodes them. Used by the simulator mode of the app and by tests.
 */
class SimulatedVehicle {
    var speedKph = 0.0
    var gear = Gear.NEUTRAL
    var go = false
    var footbrake = false
    var pedalPercent = 0
    var socPercent = 76.5
    var currentAmps = 1.0
    var packVoltage = 55.4
    var cellVoltages = List(14) { 3.95 + (it % 3) * 0.01 }
    var moduleTempsC = List(7) { 18 + it % 2 }
    var motorTempC = 22
    var controllerTempC = 24
    var chargerTempC = 20
    var keyOn = true
    var odometerKm = 12_345.67
    var rangeKm = 62
    var vinSuffix = "1234567"

    /** IDs that are currently not broadcast (e.g. to simulate a missing frame). */
    val silent = mutableSetOf<Int>()

    fun frame(id: Int): ByteArray? {
        if (id in silent) return null
        return when (id) {
            Frames.BATTERY -> {
                val current = (2000 - Math.round(currentAmps * 4)).toInt()
                val soc = Math.round(socPercent * 400).toInt()
                bytes(0x07, 0x90 or (current shr 8), current, 0x54, soc shr 8, soc, 0x00, 0x6C)
            }
            Frames.BMS_LIMITS -> bytes(0x11, 0x40, 0x10, 0x20, 0x39, 0x63, 0x00, 0x3A)
            Frames.PACK -> {
                val v = Math.round(packVoltage * 10).toInt()
                bytes(0xFF, 0xFF, 0x73, 0x00, 0x00, v shr 4, ((v and 0x0F) shl 4) or (v shr 8), v)
            }
            Frames.CELLS_1 -> cells(cellVoltages.subList(0, 5))
            Frames.CELLS_2 -> cells(cellVoltages.subList(5, 10))
            Frames.CELLS_3 -> cells(cellVoltages.subList(10, 14))
            Frames.MODULE_TEMPS -> bytes(*(moduleTempsC.map { it + 40 } + 0).toIntArray())
            Frames.CHARGER -> bytes(0x00, 0x80 or (if (keyOn) 0x10 else 0) or 0x04 or 0x01, 0x05, 0x41, 0x2F, 0, 1, chargerTempC + 40)
            Frames.DASHBOARD -> {
                val km = odometerKm.toLong()
                val speed = Math.round(speedKph * 100).toInt()
                bytes((km shr 24).toInt(), (km shr 16).toInt(), (km shr 8).toInt(), km.toInt(), 0xFF, rangeKm, speed shr 8, speed)
            }
            Frames.DRIVE_STATE -> bytes(
                when (gear) {
                    Gear.DRIVE -> 0x80
                    Gear.REVERSE -> 0x08
                    else -> 0x20
                },
                (if (footbrake) 0x01 else 0) or (if (go) 0x08 else 0) or 0x04,
                100, pedalPercent * 253 / 100, 0x44, 115, 0, 0x90,
            )
            Frames.MOTOR -> bytes(0, 0, 0, 0xFE, 0, motorTempC + 40, 0, 0)
            Frames.CONTROLLER -> bytes(0x11, 0xFD, 0, 0, 0, controllerTempC + 40, 0, 0)
            Frames.ODOMETER -> {
                val raw = Math.round(odometerKm * 100)
                bytes(0x1F, 0xB2, (raw shr 20).toInt(), (raw shr 12).toInt(), (raw shr 4).toInt(), ((raw and 0x0F) shl 4).toInt(), 0)
            }
            Frames.VIN -> {
                val digits = listOf(0xF) + vinSuffix.reversed().map { it.digitToInt(16) }
                ByteArray(4) { ((digits[it * 2] shl 4) or digits[it * 2 + 1]).toByte() }
            }
            else -> null
        }
    }

    private fun cells(volts: List<Double>): ByteArray {
        val raw = volts.map { Math.round(it * 200).toInt() }
        val out = ByteArray(8)
        raw.forEachIndexed { i, v ->
            val bit = i * 12
            val b = bit / 8
            if (bit % 8 == 0) {
                out[b] = (v shr 4).toByte()
                out[b + 1] = (out[b + 1].toInt() or ((v and 0x0F) shl 4)).toByte()
            } else {
                out[b] = (out[b].toInt() or (v shr 8)).toByte()
                out[b + 1] = v.toByte()
            }
        }
        return out
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}
