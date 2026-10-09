package de.dressler.twizy.core.params

import de.dressler.twizy.core.canopen.ObjectAddress
import de.dressler.twizy.core.sevcon.ControllerType
import de.dressler.twizy.core.sevcon.ControllerType.TWIZY45
import de.dressler.twizy.core.sevcon.ControllerType.TWIZY80

/**
 * Write whitelist with hard raw-value limits per controller type.
 *
 * Only objects listed here can be written at all. The ranges are the limits OVMS
 * (vehicle_twizy.c, cfg_* functions, "limited" mode) enforces, so they never raise
 * motor currents above the factory default. Everything else is read-only.
 */
object SafetyLimits {

    private val limits: Map<ObjectAddress, Map<ControllerType, LongRange>> = buildMap {
        fun both(index: Int, sub: Int, range: LongRange) = put(ObjectAddress(index, sub), mapOf(TWIZY80 to range, TWIZY45 to range))
        fun each(index: Int, sub: Int, t80: LongRange, t45: LongRange) = put(ObjectAddress(index, sub), mapOf(TWIZY80 to t80, TWIZY45 to t45))

        // Speed (cfg_speed): max 111 kph, overspeed triggers capped at 10900 rpm
        both(0x2920, 0x05, 400L..10_000L)               // profile max fwd rpm
        each(0x2920, 0x06, 0L..900L, 0L..1_307L)         // profile max rev rpm, never above default
        both(0x3813, 0x33, 400L..10_900L)               // overspeed start (neutral braking)
        both(0x3813, 0x34, 400L..10_900L)               // overspeed warning on
        both(0x3813, 0x35, 400L..10_900L)               // overspeed max rpm (neutral braking end)
        both(0x3813, 0x3B, 400L..10_900L)               // severe overspeed brakedown trigger
        both(0x3813, 0x3C, 0L..10_900L)                 // overspeed warning off

        // Power (cfg_power): currents only 10..100 % of default, torque up to the flux map limit
        each(0x4641, 0x02, 45L..450L, 29L..290L)                      // motor max stator current (A)
        each(0x6075, 0x00, 45_000L..450_000L, 27_000L..270_000L)      // current limit (mA)
        each(0x6076, 0x00, 10_000L..70_125L, 10_500L..36_500L)        // peak torque (mNm)
        for (sub in 0x01..0x12) {
            if (sub % 2 == 1) each(0x4611, sub, 0L..1_122L, 0L..576L)  // power curve torque (1/16 Nm)
            else both(0x4611, sub, 0L..11_000L)                       // power curve rpm
        }
        // Flux map: only between the default and the OVMS "extended" map points
        each(0x4610, 0x0F, 964L..1_122L, 480L..656L)
        each(0x4610, 0x10, 9_728L..10_089L, 8_192L..9_600L)
        each(0x4610, 0x11, 1_122L..2_240L, 576L..1_328L)
        each(0x4610, 0x12, 9_984L..11_901L, 8_960L..11_901L)

        // Drive and recuperation levels (cfg_drive, cfg_recup), per mille
        both(0x2920, 0x01, 100L..1_000L)
        both(0x2920, 0x03, 0L..1_000L)
        both(0x2920, 0x04, 0L..1_000L)

        // Torque/speed maps (cfg_tsmap): brake 07..0E, neutral 1B..22, drive 24..2B
        // as (level, rpm) pairs; level 32767 = 100 %
        for (base in listOf(0x07, 0x1B, 0x24)) for (point in 0..3) {
            both(0x3813, base + 2 * point, 0L..32_767L)
            both(0x3813, base + 2 * point + 1, 0L..11_000L)
        }

        // Ramps (cfg_ramps, cfg_rampl) and smoothing (cfg_smoothing)
        both(0x291C, 0x02, 10L..10_000L)
        for (sub in listOf(0x07, 0x0B, 0x0D, 0x0E)) both(0x2920, sub, 10L..10_000L)
        both(0x2920, 0x0F, 0L..20_000L)
        both(0x2920, 0x10, 0L..20_000L)
        both(0x290A, 0x01, 1L..11L)
        both(0x290A, 0x03, 0L..1_000L)
    }

    /** The allowed raw range, or null if the object must not be written. */
    fun rangeFor(address: ObjectAddress, type: ControllerType): LongRange? = limits[address]?.get(type)

    fun isWritable(address: ObjectAddress): Boolean = address in limits

    val writableAddresses: Set<ObjectAddress> get() = limits.keys
}
