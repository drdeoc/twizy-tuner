package de.dressler.twizy.core.tuning

import de.dressler.twizy.core.params.SevconCatalog
import de.dressler.twizy.core.sevcon.ControllerType
import de.dressler.twizy.core.sevcon.Sevcon
import de.dressler.twizy.core.write.WriteRequest
import kotlinx.serialization.Serializable

/**
 * The three torque/speed maps in 0x3813 (OVMS cfg_tsmap). Each has four points
 * (level, rpm); level 32767 = 100 %. Between and beyond the points the controller
 * interpolates / holds the level.
 */
enum class MapKind(val label: String, val baseSubIndex: Int) {
    /** Drive torque limit by speed. */
    DRIVE("Drive", 0x24),
    /** Recuperation when releasing the pedal. */
    NEUTRAL("Neutral recuperation", 0x1B),
    /** Recuperation when pressing the foot brake. */
    BRAKE("Brake recuperation", 0x07);

    fun levelSubIndex(point: Int) = baseSubIndex + 2 * point
    fun rpmSubIndex(point: Int) = baseSubIndex + 2 * point + 1
}

@Serializable
data class MapPoint(val level: Long, val rpm: Long) {
    val percent: Int get() = (level * 100 / FULL_LEVEL).toInt()

    companion object {
        const val FULL_LEVEL = 32_767L
        const val MAX_RPM = 11_000L

        fun ofPercent(percent: Int, rpm: Long) = MapPoint(scale(FULL_LEVEL, 100, percent.toLong(), 0, FULL_LEVEL), rpm)
    }
}

@Serializable
data class TorqueSpeedMaps(
    val drive: List<MapPoint>,
    val neutral: List<MapPoint>,
    val brake: List<MapPoint>,
) {
    operator fun get(kind: MapKind): List<MapPoint> = when (kind) {
        MapKind.DRIVE -> drive
        MapKind.NEUTRAL -> neutral
        MapKind.BRAKE -> brake
    }

    fun with(kind: MapKind, points: List<MapPoint>): TorqueSpeedMaps = when (kind) {
        MapKind.DRIVE -> copy(drive = points)
        MapKind.NEUTRAL -> copy(neutral = points)
        MapKind.BRAKE -> copy(brake = points)
    }

    companion object {
        /** Factory maps from the catalog defaults. */
        fun factory(type: ControllerType): TorqueSpeedMaps {
            fun map(kind: MapKind) = (0..3).map { i ->
                MapPoint(
                    level = SevconCatalog[0x3813, kind.levelSubIndex(i)].defaultFor(type)!!,
                    rpm = SevconCatalog[0x3813, kind.rpmSubIndex(i)].defaultFor(type)!!,
                )
            }
            return TorqueSpeedMaps(map(MapKind.DRIVE), map(MapKind.NEUTRAL), map(MapKind.BRAKE))
        }

        suspend fun read(sevcon: Sevcon): TorqueSpeedMaps {
            suspend fun map(kind: MapKind) = (0..3).map { i ->
                MapPoint(
                    level = sevcon.read(SevconCatalog[0x3813, kind.levelSubIndex(i)]),
                    rpm = sevcon.read(SevconCatalog[0x3813, kind.rpmSubIndex(i)]),
                )
            }
            return TorqueSpeedMaps(map(MapKind.DRIVE), map(MapKind.NEUTRAL), map(MapKind.BRAKE))
        }
    }
}

object TorqueSpeedMapPlanner {

    /**
     * Writes that turn [current] into [target]. The controller raises a "Param dyn range"
     * alert if a point's rpm passes a neighbour, so (like OVMS) a point is only moved
     * once its new rpm fits between the neighbours' rpm at that moment.
     */
    fun plan(current: TorqueSpeedMaps, target: TorqueSpeedMaps): List<WriteRequest> {
        val problems = mutableListOf<String>()
        for (kind in MapKind.entries) {
            val points = target[kind]
            if (points.size != 4) problems += "${kind.label} map needs 4 points"
            points.forEachIndexed { i, p ->
                if (p.level !in 0..MapPoint.FULL_LEVEL) problems += "${kind.label} point ${i + 1}: level out of range"
                if (p.rpm !in 0..MapPoint.MAX_RPM) problems += "${kind.label} point ${i + 1}: speed out of range"
            }
            if (points.zipWithNext().any { (a, b) -> b.rpm < a.rpm }) {
                problems += "${kind.label} map: speeds must not decrease from point to point"
            }
        }
        if (problems.isNotEmpty()) throw TuningException(problems)

        val requests = mutableListOf<WriteRequest>()
        for (kind in MapKind.entries) requests += planMap(kind, current[kind], target[kind])
        return requests
    }

    private fun planMap(kind: MapKind, current: List<MapPoint>, target: List<MapPoint>): List<WriteRequest> {
        fun req(sub: Int, value: Long) = WriteRequest(SevconCatalog[0x3813, sub], value)
        val rpm = current.map { it.rpm }.toMutableList()
        val pending = (0..3).toMutableList()
        val requests = mutableListOf<WriteRequest>()
        while (pending.isNotEmpty()) {
            val movable = pending.firstOrNull { i ->
                val low = if (i == 0) 0 else rpm[i - 1]
                val high = if (i == 3) Long.MAX_VALUE else rpm[i + 1]
                target[i].rpm in low..high
            } ?: throw TuningException(listOf("${kind.label} map: no valid order to move the points"))
            requests += req(kind.rpmSubIndex(movable), target[movable].rpm)
            requests += req(kind.levelSubIndex(movable), target[movable].level)
            rpm[movable] = target[movable].rpm
            pending.remove(movable)
        }
        return requests
    }
}
