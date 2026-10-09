package de.dressler.twizy.core.live

/**
 * Decoders for the Twizy's broadcast CAN frames. Sources: "Twizy CANBUS Objektverzeichnis"
 * and the OVMS module (vehicle_twizy.c, poll0/poll1), whose validity checks are kept.
 * Every decoder returns null for frames that carry no valid data (init phase, 0xFF fillers).
 */
enum class Gear { DRIVE, NEUTRAL, REVERSE, UNKNOWN }

/** 0x155, every 10 ms. */
data class BatteryFrame(
    val socPercent: Double?,
    /** Positive = discharging, negative = charging/recuperating. */
    val currentAmps: Double?,
)

/** 0x424, every 100 ms. Limits the BMS currently allows. */
data class BmsLimitsFrame(val maxRecupWatts: Int?, val maxDriveWatts: Int?, val healthPercent: Int?)

/** 0x597, every 100 ms. */
data class ChargerFrame(
    val pluggedIn: Boolean,
    val keyOn: Boolean,
    val charging: Boolean,
    /** Switch-on/-off phase; the other flags are not reliable then. */
    val offline: Boolean,
    val chargerTempC: Int?,
)

/** 0x599, every 100 ms. */
data class DashboardFrame(val odometerKm: Long, val rangeKm: Int?, val speedKph: Double?)

/** 0x59B, every 100 ms. */
data class DriveStateFrame(val gear: Gear, val footbrake: Boolean, val go: Boolean, val pedalPercent: Int)

object Frames {
    const val BATTERY = 0x155
    const val MOTOR = 0x196
    const val BMS_LIMITS = 0x424
    const val MODULE_TEMPS = 0x554
    const val CELLS_1 = 0x556
    const val CELLS_2 = 0x557
    const val CELLS_3 = 0x55E
    const val PACK = 0x55F
    const val CHARGER = 0x597
    const val DASHBOARD = 0x599
    const val DRIVE_STATE = 0x59B
    const val CONTROLLER = 0x59E
    const val ODOMETER = 0x5D7
    const val VIN = 0x69F

    /** Nominal transmission period in ms, used for monitoring timeouts. */
    val PERIOD_MILLIS = mapOf(
        BATTERY to 10, MOTOR to 10, BMS_LIMITS to 100, MODULE_TEMPS to 1000, CELLS_1 to 100,
        CELLS_2 to 1000, CELLS_3 to 1000, PACK to 1000, CHARGER to 100, DASHBOARD to 100,
        DRIVE_STATE to 100, CONTROLLER to 100, ODOMETER to 100, VIN to 1000,
    )

    fun battery(d: ByteArray): BatteryFrame? {
        if (d.size < 6 || d.u(3) != 0x54) return null // 0x94 = init/exit phase
        val soc = (d.u(4) shl 8) + d.u(5)
        val current = ((d.u(1) and 0x0F) shl 8) + d.u(2)
        return BatteryFrame(
            socPercent = if (soc in 1..40_000) soc / 400.0 else null,
            currentAmps = if (current in 1 until 0xF00) (2000 - current) / 4.0 else null,
        )
    }

    fun motorTempC(d: ByteArray): Int? = d.temp(5)

    fun bmsLimits(d: ByteArray): BmsLimitsFrame? {
        if (d.size < 6) return null
        return BmsLimitsFrame(
            maxRecupWatts = d.u(2).takeIf { it != 0xFF }?.times(500),
            maxDriveWatts = d.u(3).takeIf { it != 0xFF }?.times(500),
            healthPercent = d.u(5).takeIf { it in 1..100 },
        )
    }

    /** 7 cell module temperatures in °C. */
    fun moduleTempsC(d: ByteArray): List<Int>? {
        if (d.size < 7 || d.u(0) == 0xFF) return null
        return (0 until 7).map { d.u(it) - 40 }
    }

    /** Cell voltages in V: 0x556 and 0x557 carry 5 each, 0x55E carries 4 (14 modules). */
    fun cellVoltages(d: ByteArray, count: Int): List<Double>? {
        if (d.size < 8 || d.u(0) == 0xFF) return null
        return (0 until count).map { twelveBit(d, it) / 200.0 }
    }

    /** Pack voltage in V (average of the two measurements in 0x55F). */
    fun packVoltage(d: ByteArray): Double? {
        if (d.size < 8 || d.u(5) == 0xFF) return null
        val v1 = (d.u(5) shl 4) or (d.u(6) shr 4)
        val v2 = ((d.u(6) and 0x0F) shl 8) or d.u(7)
        return ((v1 + v2 + 1) shr 1) / 10.0
    }

    fun charger(d: ByteArray): ChargerFrame? {
        if (d.size < 8) return null
        val flags = d.u(1)
        return ChargerFrame(
            pluggedIn = d.u(0) and 0x20 != 0,
            keyOn = flags and 0x10 != 0,
            charging = flags and 0x20 != 0,
            offline = flags and 0x40 != 0,
            chargerTempC = d.temp(7),
        )
    }

    fun dashboard(d: ByteArray): DashboardFrame? {
        if (d.size < 8) return null
        val speed = (d.u(6) shl 8) + d.u(7)
        return DashboardFrame(
            odometerKm = (d.u(0).toLong() shl 24) or (d.u(1).toLong() shl 16) or (d.u(2).toLong() shl 8) or d.u(3).toLong(),
            rangeKm = d.u(5).takeIf { it in 1 until 0xFF },
            speedKph = if (speed != 0xFFFF) speed / 100.0 else null,
        )
    }

    fun driveState(d: ByteArray): DriveStateFrame? {
        if (d.size < 4) return null
        return DriveStateFrame(
            gear = when (d.u(0)) {
                0x80 -> Gear.DRIVE
                0x20 -> Gear.NEUTRAL
                0x08 -> Gear.REVERSE
                else -> Gear.UNKNOWN
            },
            footbrake = d.u(1) and 0x01 != 0,
            go = d.u(1) and 0x08 != 0,
            pedalPercent = (d.u(3) * 100 + 126) / 253,
        )
    }

    fun controllerTempC(d: ByteArray): Int? = d.temp(5)

    /** Precise odometer from 0x5D7, in km. */
    fun odometerKm(d: ByteArray): Double? {
        if (d.size < 6) return null
        val raw = (d.u(5).toLong() shr 4) or (d.u(4).toLong() shl 4) or (d.u(3).toLong() shl 12) or (d.u(2).toLong() shl 20)
        return raw / 100.0
    }

    /** Last 7 digits of the VIN (0x69F, nibbles in reverse order). */
    fun vinSuffix(d: ByteArray): String? {
        if (d.size < 4) return null
        val nibbles = (0 until 8).map { i -> (d.u(i / 2) shr (if (i % 2 == 0) 4 else 0)) and 0x0F }
        return (7 downTo 1).joinToString("") { nibbles[it].toString(16) }.uppercase()
    }

    private fun twelveBit(d: ByteArray, i: Int): Int {
        val bit = i * 12
        val byte = bit / 8
        return if (bit % 8 == 0) (d.u(byte) shl 4) or (d.u(byte + 1) shr 4)
        else ((d.u(byte) and 0x0F) shl 8) or d.u(byte + 1)
    }

    private fun ByteArray.u(i: Int) = this[i].toInt() and 0xFF

    /** Temperature byte with 40 °C offset; 0 and >= 0xF0 mean "no data" (as in OVMS). */
    private fun ByteArray.temp(i: Int): Int? = if (size > i && u(i) in 1 until 0xF0) u(i) - 40 else null
}
