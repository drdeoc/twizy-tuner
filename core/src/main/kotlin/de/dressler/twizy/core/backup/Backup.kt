package de.dressler.twizy.core.backup

import de.dressler.twizy.core.canopen.ObjectAddress
import de.dressler.twizy.core.sevcon.ControllerType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A snapshot of every catalog object of one controller, stored as JSON. */
@Serializable
data class Backup(
    val format: Int = FORMAT,
    /** ISO-8601 instant, e.g. 2026-10-09T18:30:00Z */
    val createdAt: String,
    val label: String = "",
    val controller: ControllerType,
    val productName: String,
    val softwareVersion: String,
    val hardwareVersion: String,
    val entries: List<BackupEntry>,
) {
    val readErrors: Int get() = entries.count { it.error != null }

    fun entry(address: ObjectAddress): BackupEntry? = entries.firstOrNull { it.address == address.toString() }

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        const val FORMAT = 1

        private val json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        fun fromJson(text: String): Backup {
            val backup = json.decodeFromString(serializer(), text)
            require(backup.format == FORMAT) { "Unsupported backup format ${backup.format}" }
            return backup
        }
    }
}

/** One object. Exactly one of [value] (numeric, decoded), [text] (string) or [error] is set. */
@Serializable
data class BackupEntry(
    /** "4611:01" */
    val address: String,
    val name: String,
    val value: Long? = null,
    val text: String? = null,
    val error: String? = null,
)
