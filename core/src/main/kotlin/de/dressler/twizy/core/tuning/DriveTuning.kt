package de.dressler.twizy.core.tuning

import de.dressler.twizy.core.params.SevconCatalog
import de.dressler.twizy.core.sevcon.ControllerType
import de.dressler.twizy.core.sevcon.Sevcon
import de.dressler.twizy.core.write.WriteRequest
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * Speed and power settings. Percentages are relative to the factory values of the model.
 * Motor currents stay at the factory default (OVMS "limited" mode).
 */
@Serializable
data class DriveTuning(
    val maxKph: Int,
    /** Speed at which the overspeed warning (STOP lamp) switches on. */
    val warnKph: Int,
    val torquePercent: Int,
    /** Power in the low speed range, i.e. how long full torque is available. */
    val powerLowPercent: Int,
    /** Power at top speed. */
    val powerHighPercent: Int,
) {
    companion object {
        val KPH_RANGE = 6..111
        val TORQUE_RANGE = 10..130
        val POWER_LOW_RANGE = 10..139
        val POWER_HIGH_RANGE = 10..130

        /** Highest max speed whose overspeed braking points stay below 10900 rpm. */
        fun maxKphFor(type: ControllerType): Int =
            KPH_RANGE.reversed().first { DriveTuningPlanner.rpmFor(type, it) + TuningDefaults.of(type).deltaBrakeEnd <= MAX_TRIGGER_RPM }

        const val MAX_TRIGGER_RPM = 10_900L

        fun factory(type: ControllerType): DriveTuning {
            val d = TuningDefaults.of(type)
            return DriveTuning(d.kphMax, d.kphWarn, 100, 100, 100)
        }
    }
}

class TuningException(val problems: List<String>) : Exception(problems.joinToString("; "))

/**
 * Turns a [DriveTuning] into register writes, using the formulas of OVMS
 * vehicle_twizy_cfg_speed(), _cfg_power() and _cfg_makepowermap().
 */
object DriveTuningPlanner {

    private val FIBONACCI = longArrayOf(1, 2, 3, 5, 8, 13, 21)

    fun plan(type: ControllerType, tuning: DriveTuning): List<WriteRequest> {
        val d = TuningDefaults.of(type)
        val problems = mutableListOf<String>()
        fun check(name: String, value: Int, range: IntRange) {
            if (value !in range) problems += "$name must be between ${range.first} and ${range.last}, not $value"
        }
        check("Max speed (km/h)", tuning.maxKph, DriveTuning.KPH_RANGE)
        check("Warning speed (km/h)", tuning.warnKph, DriveTuning.KPH_RANGE)
        check("Torque (%)", tuning.torquePercent, DriveTuning.TORQUE_RANGE)
        check("Power low speed (%)", tuning.powerLowPercent, DriveTuning.POWER_LOW_RANGE)
        check("Power high speed (%)", tuning.powerHighPercent, DriveTuning.POWER_HIGH_RANGE)
        if (tuning.warnKph < tuning.maxKph) problems += "The warning speed must not be below the max speed"
        val maxKph = DriveTuning.maxKphFor(type)
        if (tuning.maxKph in DriveTuning.KPH_RANGE && tuning.maxKph > maxKph) problems += "The max speed of a ${type.label} is limited to $maxKph km/h"
        if (problems.isNotEmpty()) throw TuningException(problems)

        val requests = mutableListOf<WriteRequest>()
        fun write(index: Int, sub: Int, value: Long) {
            requests += WriteRequest(SevconCatalog[index, sub], value)
        }

        // --- speed (cfg_speed) ---
        val rpm = rpmFor(type, tuning.maxKph)
        val warnRpm = scale(d.rpmWarn.toLong(), d.kphWarn.toLong(), tuning.warnKph.toLong(), 400, 10_900)
        write(0x3813, 0x34, warnRpm)                                    // warning on
        write(0x3813, 0x3C, warnRpm - d.deltaWarnOff)                   // warning off
        write(0x2920, 0x05, rpm)                                        // max forward
        write(0x2920, 0x06, minOf(rpm, d.rpmRev.toLong()))              // max reverse
        write(0x3813, 0x33, rpm + d.deltaBrakeStart)                    // neutral braking start
        write(0x3813, 0x35, rpm + d.deltaBrakeEnd)                      // neutral braking end
        write(0x3813, 0x3B, minOf(rpm + d.deltaBrakeDown, DriveTuning.MAX_TRIGGER_RPM)) // drive brakedown

        // --- power (cfg_power, current at 100 %) ---
        val torque = scale(d.torque, 100, tuning.torquePercent.toLong(), 10_000, d.torqueLimit)
        val powerLow = scale(d.powerLow, 100, tuning.powerLowPercent.toLong(), 500, d.powerLowLimit)
        val powerHigh = scale(d.powerHigh, 100, tuning.powerHighPercent.toLong(), 500, d.powerHighLimit)
        write(0x4641, 0x02, d.statorCurrentMax)
        write(0x6075, 0x00, d.currentLimit)
        write(0x6076, 0x00, torque + d.deltaMapTorque)

        // --- torque/speed map (cfg_makepowermap) ---
        val isDefault = rpm == d.rpmMax.toLong() && torque == d.torque &&
            powerLow == d.powerLow && powerHigh == d.powerHigh
        if (isDefault) {
            d.powerMap.forEachIndexed { i, v -> write(0x4611, 0x01 + i, v) }
            d.fluxMap.forEachIndexed { i, v -> write(0x4610, 0x0F + i, v) }
        } else {
            val map = powerMap(d, rpm, torque, powerLow, powerHigh, problems)
            if (problems.isNotEmpty()) throw TuningException(problems)
            map.forEachIndexed { i, v -> write(0x4611, 0x01 + i, v) }
            val flux = if (map[0] > d.fluxMap[2]) d.extendedFluxMap else d.fluxMap
            flux.forEachIndexed { i, v -> write(0x4610, 0x0F + i, v) }
        }
        return requests
    }

    /** 9 (torque, rpm) points: constant torque up to rpm0, then rising power along a Fibonacci spacing. */
    private fun powerMap(
        d: TuningDefaults, rpmMax: Long, torque: Long, powerLow: Long, powerHigh: Long, problems: MutableList<String>,
    ): List<Long> {
        val rpm0 = (powerLow * 9549 + torque / 2) / torque
        val mapTorque = (torque * 16 + 500) / 1000
        if (rpm0 >= rpmMax) {
            problems += "Low speed power is too high for this torque: full torque would last up to $rpm0 rpm, " +
                "above the max speed ($rpmMax rpm). Lower the power or raise the torque."
            return emptyList()
        }
        val rpmStep = (rpmMax - rpm0 + FIBONACCI[6] / 2) / FIBONACCI[6]
        // C integer division truncates toward zero, like Kotlin's
        val powerStep = (powerHigh - powerLow + FIBONACCI[5] / 2) / FIBONACCI[5]

        val map = mutableListOf(mapTorque, 0L, mapTorque, rpm0)
        for (i in 0..6) {
            val r = if (i < 6) rpm0 + FIBONACCI[i] * rpmStep else rpmMax
            val p = if (i < 5) powerLow + FIBONACCI[i] * powerStep else powerHigh
            map += (((p * 9549 + r / 2) / r) * 16 + 500) / 1000
            map += r
        }

        val rpms = map.filterIndexed { i, _ -> i % 2 == 1 }
        if (rpms.zipWithNext().any { (a, b) -> b <= a }) {
            problems += "Max speed is too close to the end of the full torque range; the map cannot be built"
        }
        val torques = map.filterIndexed { i, _ -> i % 2 == 0 }
        if (torques.any { it > mapTorque }) {
            problems += "High speed power is too high compared to the low speed power for this torque"
        }
        return map
    }

    fun rpmFor(type: ControllerType, kph: Int): Long {
        val d = TuningDefaults.of(type)
        return scale(d.rpmMax.toLong(), d.kphMax.toLong(), kph.toLong(), 400, 10_000)
    }

}

/** Estimates the current [DriveTuning] from the controller (like OVMS cfg_readmaxpwr). */
object DriveTuningReader {

    suspend fun read(sevcon: Sevcon, type: ControllerType): DriveTuning {
        val d = TuningDefaults.of(type)
        suspend fun value(index: Int, sub: Int) = sevcon.read(SevconCatalog[index, sub])

        val rpm = value(0x2920, 0x05)
        val warnRpm = value(0x3813, 0x34)
        val torque = value(0x6076, 0x00) - d.deltaMapTorque
        val t2 = value(0x4611, 0x03)
        val rpm2 = value(0x4611, 0x04)
        val t9 = value(0x4611, 0x11)
        val rpm9 = value(0x4611, 0x12)

        val powerLow = if (t2 == d.powerMap[2] && rpm2 == d.powerMap[3]) d.powerLow else mechanicalPower(t2, rpm2)
        val powerHigh = if (t9 == d.powerMap[16] && rpm9 == d.powerMap[17]) d.powerHigh else mechanicalPower(t9, rpm9)

        return DriveTuning(
            maxKph = (rpm * d.kphMax / d.rpmMax.toDouble()).roundToInt(),
            warnKph = (warnRpm * d.kphWarn / d.rpmWarn.toDouble()).roundToInt(),
            torquePercent = (torque * 100.0 / d.torque).roundToInt(),
            powerLowPercent = (powerLow * 100.0 / d.powerLow).roundToInt(),
            powerHighPercent = (powerHigh * 100.0 / d.powerHigh).roundToInt(),
        )
    }

    /** Map torque (1/16 Nm) at [rpm] to mechanical power in W. */
    private fun mechanicalPower(mapTorque: Long, rpm: Long): Long = ((mapTorque * 1000 shr 4) * rpm + 9549 / 2) / 9549
}
