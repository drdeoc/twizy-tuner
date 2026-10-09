package de.dressler.twizy.core.tuning

import de.dressler.twizy.core.sevcon.ControllerType

/**
 * Factory reference values per model, from OVMS vehicle_twizy.c (twizy_cfg_params[]).
 * Torques in mNm, power in W (mechanical), map torques in 1/16 Nm.
 */
data class TuningDefaults(
    val kphMax: Int,
    val rpmMax: Int,
    val rpmRev: Int,
    /** Neutral braking starts / ends and drive brakedown triggers this far above max rpm. */
    val deltaBrakeStart: Int,
    val deltaBrakeEnd: Int,
    val deltaBrakeDown: Int,
    val kphWarn: Int,
    val rpmWarn: Int,
    /** Overspeed warning switches off this far below the warning rpm. */
    val deltaWarnOff: Int,
    val torque: Long,
    val torqueLimit: Long,
    /** Added to the peak torque object 0x6076 on top of the map torque. */
    val deltaMapTorque: Long,
    val currentLimit: Long,
    val statorCurrentMax: Long,
    /** Last two flux map points (0x4610:0F..12), default and extended for higher torque. */
    val fluxMap: List<Long>,
    val extendedFluxMap: List<Long>,
    val powerLow: Long,
    val powerLowLimit: Long,
    val powerHigh: Long,
    val powerHighLimit: Long,
    /** Factory torque/speed curve 0x4611:01..12 as (torque, rpm) pairs. */
    val powerMap: List<Long>,
) {
    companion object {
        val TWIZY80 = TuningDefaults(
            kphMax = 80, rpmMax = 7250, rpmRev = 900,
            deltaBrakeStart = 400, deltaBrakeEnd = 800, deltaBrakeDown = 1250,
            kphWarn = 89, rpmWarn = 8050, deltaWarnOff = 550,
            torque = 55_000, torqueLimit = 70_125, deltaMapTorque = 0,
            currentLimit = 450_000, statorCurrentMax = 450,
            fluxMap = listOf(964, 9728, 1122, 9984),
            extendedFluxMap = listOf(1122, 10089, 2240, 11901),
            powerLow = 12_182, powerLowLimit = 17_000,
            powerHigh = 13_000, powerHighLimit = 17_000,
            powerMap = listOf(
                880, 0, 880, 2115, 659, 2700, 608, 3000, 516, 3500,
                421, 4500, 360, 5500, 307, 6500, 273, 7250,
            ),
        )

        val TWIZY45 = TuningDefaults(
            kphMax = 45, rpmMax = 5814, rpmRev = 1307,
            deltaBrakeStart = 686, deltaBrakeEnd = 1386, deltaBrakeDown = 2686,
            kphWarn = 56, rpmWarn = 7200, deltaWarnOff = 900,
            torque = 32_500, torqueLimit = 36_000, deltaMapTorque = 500,
            currentLimit = 270_000, statorCurrentMax = 290,
            fluxMap = listOf(480, 8192, 576, 8960),
            extendedFluxMap = listOf(656, 9600, 1328, 11901),
            powerLow = 7_050, powerLowLimit = 10_000,
            powerHigh = 7_650, powerHighLimit = 10_000,
            powerMap = listOf(
                520, 0, 520, 2050, 437, 2500, 363, 3000, 314, 3500,
                279, 4000, 247, 4500, 226, 5000, 195, 6000,
            ),
        )

        fun of(type: ControllerType) = when (type) {
            ControllerType.TWIZY80 -> TWIZY80
            ControllerType.TWIZY45 -> TWIZY45
        }
    }
}
