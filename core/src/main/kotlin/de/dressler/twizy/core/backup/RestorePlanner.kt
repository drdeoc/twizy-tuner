package de.dressler.twizy.core.backup

import de.dressler.twizy.core.canopen.ObjectAddress
import de.dressler.twizy.core.params.SafetyLimits
import de.dressler.twizy.core.params.SevconCatalog
import de.dressler.twizy.core.sevcon.ControllerType
import de.dressler.twizy.core.write.WriteRequest

data class SkippedEntry(val address: ObjectAddress, val name: String, val reason: String)

/**
 * What a restore would write. Pass [requests] to SafeWriter.prepare(), which drops
 * values that are already equal and shows old -> new for the rest.
 */
data class RestoreSelection(
    val requests: List<WriteRequest>,
    /** Writable objects that cannot be restored (missing, unreadable or outside the safe range). */
    val skipped: List<SkippedEntry>,
    /** Number of backed up objects that are read-only and therefore never restored. */
    val readOnlyCount: Int,
)

/** Only objects on the SafetyLimits whitelist are restored, in catalog order. */
object RestorePlanner {

    fun fromBackup(backup: Backup, controller: ControllerType): RestoreSelection {
        require(backup.controller == controller) {
            "Backup is from a ${backup.controller.label}, but the connected controller is a ${controller.label}"
        }
        return select(controller, backup.entries.size) { address -> backup.entry(address).toValue() }
    }

    fun factoryDefaults(controller: ControllerType): RestoreSelection =
        select(controller, SevconCatalog.all.size) { address ->
            SevconCatalog[address].defaultFor(controller)?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException("no factory default known"))
        }

    private fun select(
        controller: ControllerType,
        total: Int,
        valueOf: (ObjectAddress) -> Result<Long>,
    ): RestoreSelection {
        val requests = mutableListOf<WriteRequest>()
        val skipped = mutableListOf<SkippedEntry>()
        val writable = SevconCatalog.all.filter { SafetyLimits.isWritable(it.address) }
        for (p in writable) {
            valueOf(p.address)
                .onSuccess { value ->
                    val range = SafetyLimits.rangeFor(p.address, controller)!!
                    if (value in range) {
                        requests += WriteRequest(p, value)
                    } else {
                        skipped += SkippedEntry(p.address, p.name, "$value is outside the safe range ${range.first}..${range.last}")
                    }
                }
                .onFailure { skipped += SkippedEntry(p.address, p.name, it.message ?: "no value") }
        }
        return RestoreSelection(requests, skipped, readOnlyCount = total - writable.size)
    }

    private fun BackupEntry?.toValue(): Result<Long> = when {
        this == null -> Result.failure(IllegalStateException("not in backup"))
        error != null -> Result.failure(IllegalStateException("could not be read: $error"))
        value == null -> Result.failure(IllegalStateException("no numeric value"))
        else -> Result.success(value)
    }
}
