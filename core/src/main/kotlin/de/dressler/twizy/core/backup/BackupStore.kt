package de.dressler.twizy.core.backup

import de.dressler.twizy.core.sevcon.ControllerType
import java.io.File

data class StoredBackup(val file: File, val backup: Backup)

/** Backups as JSON files in one directory, newest first. */
class BackupStore(private val directory: File) {

    fun list(): List<StoredBackup> =
        directory.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }.orEmpty()
            .mapNotNull { f -> runCatching { StoredBackup(f, Backup.fromJson(f.readText())) }.getOrNull() }
            .sortedByDescending { it.backup.createdAt }

    fun hasBackupFor(controller: ControllerType): Boolean = list().any { it.backup.controller == controller }

    fun save(backup: Backup): File {
        directory.mkdirs()
        val stamp = backup.createdAt.replace(Regex("[^0-9A-Za-z]"), "-")
        var file = File(directory, "${backup.controller.name.lowercase()}_$stamp$SUFFIX")
        var n = 2
        while (file.exists()) file = File(directory, "${backup.controller.name.lowercase()}_${stamp}_${n++}$SUFFIX")
        // Write to a temp file first so a crash never leaves a truncated backup behind.
        val tmp = File(directory, file.name + ".tmp")
        tmp.writeText(backup.toJson())
        check(tmp.renameTo(file)) { "Could not store backup as $file" }
        return file
    }

    fun delete(file: File): Boolean = file.parentFile == directory && file.delete()

    private companion object {
        const val SUFFIX = ".json"
    }
}
