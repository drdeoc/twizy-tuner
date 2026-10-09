package de.dressler.twizy.core.live

import de.dressler.twizy.core.write.PreOpGuard

class VehicleNotSafeException(val reasons: List<String>) :
    Exception("Configuration mode refused: " + reasons.joinToString("; "))

/**
 * Pre-operational safety check from the live CAN frames, like the OVMS "triple check":
 * the vehicle must not move, must be in N and GO must be off. Only then the driver is
 * asked to confirm ([confirm]). If the frames cannot be read, configuration mode is refused.
 */
class VehicleGuard(
    private val monitor: LiveMonitor,
    private val confirm: PreOpGuard,
) : PreOpGuard {

    override suspend fun confirmVehicleSafe(): Boolean {
        val reasons = check()
        if (reasons.isNotEmpty()) throw VehicleNotSafeException(reasons)
        return confirm.confirmVehicleSafe()
    }

    suspend fun check(): List<String> {
        val reasons = mutableListOf<String>()
        val state = monitor.fetch(Frames.DRIVE_STATE)?.let(Frames::driveState)
        val dashboard = monitor.fetch(Frames.DASHBOARD)?.let(Frames::dashboard)
        if (state == null) {
            reasons += "could not read gear and GO state (CAN 0x59B)"
        } else {
            if (state.go) reasons += "GO is active"
            if (state.gear != Gear.NEUTRAL) reasons += "gear is ${state.gear}, not N"
        }
        val speed = dashboard?.speedKph
        if (speed == null) reasons += "could not read the speed (CAN 0x599)"
        else if (speed > 0.0) reasons += "vehicle is moving (%.1f km/h)".format(speed)
        return reasons
    }
}
