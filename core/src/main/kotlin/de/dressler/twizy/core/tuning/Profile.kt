package de.dressler.twizy.core.tuning

import de.dressler.twizy.core.sevcon.ControllerType
import de.dressler.twizy.core.sevcon.Sevcon
import de.dressler.twizy.core.write.WriteRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** A complete set of tuning settings for one model, like an OVMS CFG profile. */
@Serializable
data class Profile(
    val format: Int = FORMAT,
    val name: String,
    val controller: ControllerType,
    val drive: DriveTuning,
    val feel: DrivingFeel,
    val maps: TorqueSpeedMaps,
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        const val FORMAT = 1

        private val json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        fun fromJson(text: String): Profile {
            val profile = json.decodeFromString(serializer(), text)
            require(profile.format == FORMAT) { "Unsupported profile format ${profile.format}" }
            return profile
        }

        fun factory(type: ControllerType) = Profile(
            name = "Factory",
            controller = type,
            drive = DriveTuning.factory(type),
            feel = DrivingFeel.factory(type),
            maps = TorqueSpeedMaps.factory(type),
        )

        /** The settings currently in the controller (speed and power are estimates). */
        suspend fun read(sevcon: Sevcon, type: ControllerType, name: String) = Profile(
            name = name,
            controller = type,
            drive = DriveTuningReader.read(sevcon, type),
            feel = DrivingFeelReader.read(sevcon, type),
            maps = TorqueSpeedMaps.read(sevcon),
        )
    }
}

object ProfilePlanner {

    /**
     * All writes for [profile]. Operational-mode settings come first (SafeWriter writes them
     * before entering pre-op), then speed/power and the torque/speed maps in a safe order.
     */
    suspend fun plan(sevcon: Sevcon, type: ControllerType, profile: Profile): List<WriteRequest> {
        require(profile.controller == type) {
            "Profile '${profile.name}' is for a ${profile.controller.label}, but the connected controller is a ${type.label}"
        }
        val problems = mutableListOf<String>()
        fun <T> collect(block: () -> List<T>): List<T> = try {
            block()
        } catch (e: TuningException) {
            problems += e.problems
            emptyList()
        }
        val feel = collect { DrivingFeelPlanner.plan(type, profile.feel) }
        val drive = collect { DriveTuningPlanner.plan(type, profile.drive) }
        val currentMaps = TorqueSpeedMaps.read(sevcon)
        val maps = collect { TorqueSpeedMapPlanner.plan(currentMaps, profile.maps) }
        if (problems.isNotEmpty()) throw TuningException(problems)
        return feel + drive + maps
    }
}

/** Profiles as JSON files, one per name. */
class ProfileStore(private val directory: File) {

    fun list(): List<Profile> =
        directory.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { runCatching { Profile.fromJson(it.readText()) }.getOrNull() }
            .sortedBy { it.name.lowercase() }

    fun save(profile: Profile) {
        require(profile.name.isNotBlank()) { "A profile needs a name" }
        directory.mkdirs()
        val tmp = File(directory, fileName(profile) + ".tmp")
        tmp.writeText(profile.toJson())
        val target = File(directory, fileName(profile))
        target.delete()
        check(tmp.renameTo(target)) { "Could not store profile ${profile.name}" }
    }

    fun delete(profile: Profile): Boolean = File(directory, fileName(profile)).delete()

    private fun fileName(profile: Profile): String {
        val safe = profile.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "profile" }
        return "${profile.controller.name.lowercase()}_${safe}_${profile.name.hashCode().toUInt().toString(16)}.json"
    }
}
