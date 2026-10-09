package de.dressler.twizy.core.live

import de.dressler.twizy.core.elm.Elm327

/** Latest decoded values; null means not (yet) received or invalid. */
data class LiveData(
    val battery: BatteryFrame? = null,
    val bmsLimits: BmsLimitsFrame? = null,
    val packVoltage: Double? = null,
    val cellVoltages: List<Double?> = List(14) { null },
    val moduleTempsC: List<Int>? = null,
    val charger: ChargerFrame? = null,
    val dashboard: DashboardFrame? = null,
    val driveState: DriveStateFrame? = null,
    val motorTempC: Int? = null,
    val controllerTempC: Int? = null,
    val odometerKm: Double? = null,
    val vinSuffix: String? = null,
) {
    /** Battery power in W, from current and pack voltage. */
    val batteryWatts: Double? get() = battery?.currentAmps?.let { a -> packVoltage?.let { v -> a * v } }

    fun with(id: Int, data: ByteArray): LiveData = when (id) {
        Frames.BATTERY -> Frames.battery(data)?.let { copy(battery = it) }
        Frames.BMS_LIMITS -> Frames.bmsLimits(data)?.let { copy(bmsLimits = it) }
        Frames.PACK -> Frames.packVoltage(data)?.let { copy(packVoltage = it) }
        Frames.CELLS_1 -> Frames.cellVoltages(data, 5)?.let { copy(cellVoltages = cellVoltages.replace(0, it)) }
        Frames.CELLS_2 -> Frames.cellVoltages(data, 5)?.let { copy(cellVoltages = cellVoltages.replace(5, it)) }
        Frames.CELLS_3 -> Frames.cellVoltages(data, 4)?.let { copy(cellVoltages = cellVoltages.replace(10, it)) }
        Frames.MODULE_TEMPS -> Frames.moduleTempsC(data)?.let { copy(moduleTempsC = it) }
        Frames.CHARGER -> Frames.charger(data)?.let { copy(charger = it) }
        Frames.DASHBOARD -> Frames.dashboard(data)?.let { copy(dashboard = it) }
        Frames.DRIVE_STATE -> Frames.driveState(data)?.let { copy(driveState = it) }
        Frames.MOTOR -> Frames.motorTempC(data)?.let { copy(motorTempC = it) }
        Frames.CONTROLLER -> Frames.controllerTempC(data)?.let { copy(controllerTempC = it) }
        Frames.ODOMETER -> Frames.odometerKm(data)?.let { copy(odometerKm = it) }
        Frames.VIN -> Frames.vinSuffix(data)?.let { copy(vinSuffix = it) }
        else -> null
    } ?: this

    private fun List<Double?>.replace(from: Int, values: List<Double>) =
        mapIndexed { i, v -> if (i in from until from + values.size) values[i - from] else v }
}

/** Polls broadcast frames one at a time through the adapter. */
class LiveMonitor(private val elm: Elm327) {

    /** Fetches one frame of [id] (or null if none arrived within a few periods). */
    suspend fun fetch(id: Int): ByteArray? {
        val period = Frames.PERIOD_MILLIS[id] ?: 1000
        return elm.monitor(id, timeout = maxOf(300L, period * 3L))
    }

    /** One refresh: all fast frames plus one of the slow ones ([cycle] picks which). */
    suspend fun refresh(current: LiveData, cycle: Int): LiveData {
        var data = current
        for (id in FAST + SLOW[cycle % SLOW.size]) {
            fetch(id)?.let { data = data.with(id, it) }
        }
        return data
    }

    companion object {
        val FAST = listOf(Frames.BATTERY, Frames.DASHBOARD, Frames.DRIVE_STATE, Frames.BMS_LIMITS, Frames.CHARGER)
        val SLOW = listOf(
            Frames.PACK, Frames.CELLS_1, Frames.MOTOR, Frames.CONTROLLER, Frames.CELLS_2,
            Frames.MODULE_TEMPS, Frames.CELLS_3, Frames.ODOMETER, Frames.VIN,
        )
    }
}
