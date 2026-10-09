package de.dressler.twizy.core.backup

import de.dressler.twizy.core.ControllerInfo
import de.dressler.twizy.core.canopen.SdoException
import de.dressler.twizy.core.elm.ElmException
import de.dressler.twizy.core.params.Parameter
import de.dressler.twizy.core.params.SevconCatalog
import de.dressler.twizy.core.sevcon.Sevcon

/** Reads every catalog object. Read-only; works in operational mode without login. */
class BackupService(
    private val sevcon: Sevcon,
    private val catalog: List<Parameter> = SevconCatalog.all,
) {
    /**
     * Objects that cannot be read are recorded with their error. Anything else
     * (e.g. a lost Bluetooth connection) aborts the backup.
     */
    suspend fun create(
        info: ControllerInfo,
        createdAt: String,
        label: String = "",
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Backup {
        val entries = catalog.mapIndexed { i, p ->
            progress(i, catalog.size)
            read(p)
        }
        progress(catalog.size, catalog.size)
        return Backup(
            createdAt = createdAt,
            label = label,
            controller = info.type,
            productName = info.productName,
            softwareVersion = info.softwareVersion,
            hardwareVersion = info.hardwareVersion,
            entries = entries,
        )
    }

    private suspend fun read(p: Parameter): BackupEntry {
        val address = p.address.toString()
        return try {
            if (p.type.isNumeric) {
                BackupEntry(address, p.name, value = sevcon.read(p))
            } else {
                BackupEntry(address, p.name, text = sevcon.readString(p.address))
            }
        } catch (e: SdoException) {
            BackupEntry(address, p.name, error = e.message ?: e.toString())
        } catch (e: ElmException) {
            BackupEntry(address, p.name, error = e.message ?: e.toString())
        }
    }
}
