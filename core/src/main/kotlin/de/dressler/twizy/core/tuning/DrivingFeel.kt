package de.dressler.twizy.core.tuning

import de.dressler.twizy.core.params.SevconCatalog
import de.dressler.twizy.core.sevcon.ControllerType
import de.dressler.twizy.core.sevcon.Sevcon
import de.dressler.twizy.core.write.WriteRequest
import kotlinx.serialization.Serializable

/**
 * Drive and recuperation levels, ramps and smoothing. All of these can be changed
 * in operational mode. Formulas from OVMS cfg_drive, cfg_recup, cfg_ramps, cfg_rampl
 * and cfg_smoothing.
 */
@Serializable
data class DrivingFeel(
    /** Maximum drive power level. */
    val drivePercent: Int,
    /** Recuperation when releasing the pedal (neutral). Factory: 18 % (Twizy 45: 21 %). */
    val neutralRecupPercent: Int,
    /** Recuperation when pressing the foot brake. */
    val brakeRecupPercent: Int,
    /** Torque ramp when starting off, in per mille. */
    val rampStartPermille: Int,
    val rampAccelPercent: Int,
    val rampDecelPercent: Int,
    val rampNeutralPercent: Int,
    val rampBrakePercent: Int,
    /** Speed change limits (rpm/s). */
    val rampLimitAccelPercent: Int,
    val rampLimitDecelPercent: Int,
    /** Pedal response smoothing. */
    val smoothingPercent: Int,
) {
    companion object {
        val DRIVE_RANGE = 10..100
        val RECUP_RANGE = 0..100
        val RAMP_START_RANGE = 1..250
        val RAMP_ACCEL_RANGE = 1..100
        val RAMP_RANGE = 0..100
        val RAMP_LIMIT_ACCEL_RANGE = 1..100
        val SMOOTHING_RANGE = 0..100

        fun factory(type: ControllerType): DrivingFeel {
            val d = TuningDefaults.of(type)
            return DrivingFeel(
                drivePercent = 100,
                neutralRecupPercent = d.recupPercent,
                brakeRecupPercent = d.recupPercent,
                rampStartPermille = d.rampStartPermille,
                rampAccelPercent = d.rampAccelPercent,
                rampDecelPercent = 20,
                rampNeutralPercent = 40,
                rampBrakePercent = 40,
                rampLimitAccelPercent = 30,
                rampLimitDecelPercent = 30,
                smoothingPercent = 70,
            )
        }
    }
}

object DrivingFeelPlanner {

    fun plan(type: ControllerType, feel: DrivingFeel): List<WriteRequest> {
        val d = TuningDefaults.of(type)
        val problems = mutableListOf<String>()
        fun check(name: String, value: Int, range: IntRange) {
            if (value !in range) problems += "$name must be between ${range.first} and ${range.last}, not $value"
        }
        with(feel) {
            check("Drive level (%)", drivePercent, DrivingFeel.DRIVE_RANGE)
            check("Neutral recuperation (%)", neutralRecupPercent, DrivingFeel.RECUP_RANGE)
            check("Brake recuperation (%)", brakeRecupPercent, DrivingFeel.RECUP_RANGE)
            check("Start ramp (‰)", rampStartPermille, DrivingFeel.RAMP_START_RANGE)
            check("Acceleration ramp (%)", rampAccelPercent, DrivingFeel.RAMP_ACCEL_RANGE)
            check("Deceleration ramp (%)", rampDecelPercent, DrivingFeel.RAMP_RANGE)
            check("Neutral ramp (%)", rampNeutralPercent, DrivingFeel.RAMP_RANGE)
            check("Brake ramp (%)", rampBrakePercent, DrivingFeel.RAMP_RANGE)
            check("Acceleration limit (%)", rampLimitAccelPercent, DrivingFeel.RAMP_LIMIT_ACCEL_RANGE)
            check("Deceleration limit (%)", rampLimitDecelPercent, DrivingFeel.RAMP_RANGE)
            check("Smoothing (%)", smoothingPercent, DrivingFeel.SMOOTHING_RANGE)
        }
        if (problems.isNotEmpty()) throw TuningException(problems)

        fun req(index: Int, sub: Int, value: Long) = WriteRequest(SevconCatalog[index, sub], value)
        return with(feel) {
            listOf(
                req(0x2920, 0x01, drivePercent * 10L),
                req(0x2920, 0x03, scale(d.recup, d.recupPercent.toLong(), neutralRecupPercent.toLong(), 0, 1000)),
                req(0x2920, 0x04, scale(d.recup, d.recupPercent.toLong(), brakeRecupPercent.toLong(), 0, 1000)),
                req(0x291C, 0x02, scale(d.rampStart, d.rampStartPermille.toLong(), rampStartPermille.toLong(), 10, 10_000)),
                req(0x2920, 0x07, scale(d.rampAccel, d.rampAccelPercent.toLong(), rampAccelPercent.toLong(), 10, 10_000)),
                req(0x2920, 0x0B, scale(2000, 20, rampDecelPercent.toLong(), 10, 10_000)),
                req(0x2920, 0x0D, scale(4000, 40, rampNeutralPercent.toLong(), 10, 10_000)),
                req(0x2920, 0x0E, scale(4000, 40, rampBrakePercent.toLong(), 10, 10_000)),
                req(0x2920, 0x0F, scale(6000, 30, rampLimitAccelPercent.toLong(), 0, 20_000)),
                req(0x2920, 0x10, scale(6000, 30, rampLimitDecelPercent.toLong(), 0, 20_000)),
                req(0x290A, 0x01, 1L + smoothingPercent / 10),
                req(0x290A, 0x03, scale(800, 70, smoothingPercent.toLong(), 0, 1000)),
            )
        }
    }
}

object DrivingFeelReader {

    suspend fun read(sevcon: Sevcon, type: ControllerType): DrivingFeel {
        val d = TuningDefaults.of(type)
        suspend fun value(index: Int, sub: Int) = sevcon.read(SevconCatalog[index, sub])
        return DrivingFeel(
            drivePercent = ((value(0x2920, 0x01) + 5) / 10).toInt(),
            neutralRecupPercent = unscale(value(0x2920, 0x03), d.recup, d.recupPercent),
            brakeRecupPercent = unscale(value(0x2920, 0x04), d.recup, d.recupPercent),
            rampStartPermille = unscale(value(0x291C, 0x02), d.rampStart, d.rampStartPermille),
            rampAccelPercent = unscale(value(0x2920, 0x07), d.rampAccel, d.rampAccelPercent),
            rampDecelPercent = unscale(value(0x2920, 0x0B), 2000, 20),
            rampNeutralPercent = unscale(value(0x2920, 0x0D), 4000, 40),
            rampBrakePercent = unscale(value(0x2920, 0x0E), 4000, 40),
            rampLimitAccelPercent = unscale(value(0x2920, 0x0F), 6000, 30),
            rampLimitDecelPercent = unscale(value(0x2920, 0x10), 6000, 30),
            smoothingPercent = unscale(value(0x290A, 0x03), 800, 70),
        )
    }
}
