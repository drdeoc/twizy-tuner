package de.dressler.twizy.app

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.dressler.twizy.core.ControllerInfo
import de.dressler.twizy.core.TwizyConnection
import de.dressler.twizy.core.backup.Backup
import de.dressler.twizy.core.backup.BackupService
import de.dressler.twizy.core.backup.BackupStore
import de.dressler.twizy.core.backup.RestorePlanner
import de.dressler.twizy.core.backup.RestoreSelection
import de.dressler.twizy.core.backup.SkippedEntry
import de.dressler.twizy.core.backup.StoredBackup
import de.dressler.twizy.core.params.Parameter
import de.dressler.twizy.core.params.SevconCatalog
import de.dressler.twizy.core.sevcon.NmtState
import de.dressler.twizy.core.sim.SimulatedElm327Transport
import de.dressler.twizy.core.sim.SimulatedSevcon
import de.dressler.twizy.core.transport.Transport
import de.dressler.twizy.core.tuning.DriveTuning
import de.dressler.twizy.core.tuning.DriveTuningPlanner
import de.dressler.twizy.core.tuning.DriveTuningReader
import de.dressler.twizy.core.tuning.TuningException
import de.dressler.twizy.core.write.WriteRequest
import de.dressler.twizy.core.write.PreOpGuard
import de.dressler.twizy.core.write.WritePlan
import de.dressler.twizy.core.write.WriteResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

data class Adapter(val name: String, val address: String)

data class ParameterRow(val parameter: Parameter, val value: String?, val error: String?)

enum class Screen { HOME, PARAMETERS, BACKUPS, TUNING, PREVIEW }

/** Changes waiting for the driver's OK, shown on the preview screen. */
data class WritePreview(
    val title: String,
    val plan: WritePlan,
    /** Screen to return to after writing or cancelling. */
    val origin: Screen,
    val skipped: List<SkippedEntry> = emptyList(),
    val readOnlyCount: Int? = null,
)

data class UiState(
    val screen: Screen = Screen.HOME,
    val busy: String? = null,
    val progress: Float? = null,
    val error: String? = null,
    val message: String? = null,
    val info: ControllerInfo? = null,
    val accessLevel: Long? = null,
    val nmtState: NmtState? = null,
    val rows: List<ParameterRow> = emptyList(),
    val backups: List<StoredBackup> = emptyList(),
    val preview: WritePreview? = null,
    /** Speed/power settings as currently configured in the controller. */
    val tuning: DriveTuning? = null,
    val tuningProblems: List<String> = emptyList(),
    /** True while the driver has to confirm that the car is safe for pre-operational mode. */
    val askPreOp: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val store = BackupStore(
        File(application.getExternalFilesDir(null) ?: application.filesDir, "backups")
    )

    private var connection: TwizyConnection? = null
    private var job: Job? = null
    private var preOpAnswer: CompletableDeferred<Boolean>? = null

    private val guard = PreOpGuard {
        val answer = CompletableDeferred<Boolean>()
        preOpAnswer = answer
        _state.update { it.copy(askPreOp = true) }
        try {
            answer.await()
        } finally {
            _state.update { it.copy(askPreOp = false) }
        }
    }

    init {
        refreshBackups()
    }

    // --- connection ---

    @SuppressLint("MissingPermission") // checked by the UI before this is called
    fun pairedAdapters(adapter: BluetoothAdapter?): List<Adapter> =
        adapter?.bondedDevices.orEmpty().map { Adapter(it.name ?: it.address, it.address) }.sortedBy { it.name }

    @SuppressLint("MissingPermission")
    fun connect(adapter: BluetoothAdapter, device: Adapter) = open("Connecting to ${device.name}…") {
        BluetoothSppTransport.connect(adapter.getRemoteDevice(device.address))
    }

    fun connectSimulator() = open("Starting simulator…") { SimulatedElm327Transport(SimulatedSevcon()) }

    fun disconnect() {
        job?.cancel()
        preOpAnswer?.complete(false)
        connection?.close()
        connection = null
        _state.value = UiState(backups = store.list())
    }

    private fun open(message: String, transport: suspend () -> Transport) {
        disconnect()
        job = viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(busy = message, error = null) }
            try {
                val c = TwizyConnection.open(transport())
                connection = c
                _state.update {
                    it.copy(info = c.info, accessLevel = c.sevcon.accessLevel(), nmtState = c.sevcon.nmtState())
                }
                if (!store.hasBackupFor(c.info.type)) {
                    createBackup(c, "Automatic (first connection)")
                    _state.update { it.copy(message = "Created a first backup of all controller parameters.") }
                }
            } catch (e: Exception) {
                connection?.close()
                connection = null
                _state.update { it.copy(info = null, error = "Connection failed: ${e.message}") }
            } finally {
                _state.update { it.copy(busy = null, progress = null) }
            }
        }
    }

    // --- navigation and messages ---

    fun show(screen: Screen) = _state.update { it.copy(screen = screen, error = null, message = null) }

    fun answerPreOp(confirmed: Boolean) {
        preOpAnswer?.complete(confirmed)
        preOpAnswer = null
    }

    // --- reading ---

    fun readAll() = run("Reading parameters…") { c ->
        val rows = SevconCatalog.all.mapIndexed { i, p ->
            _state.update { it.copy(progress = i / SevconCatalog.all.size.toFloat()) }
            try {
                val value = if (p.type.isNumeric) p.format(c.sevcon.read(p)) else c.sevcon.readString(p.address)
                ParameterRow(p, value, null)
            } catch (e: Exception) {
                ParameterRow(p, null, e.message)
            }
        }
        _state.update { it.copy(rows = rows) }
    }

    fun refreshStatus() = run("Reading status…") { c ->
        _state.update { it.copy(accessLevel = c.sevcon.accessLevel(), nmtState = c.sevcon.nmtState()) }
    }

    // --- backups ---

    fun backupNow() = run("Creating backup…") { c ->
        createBackup(c, "Manual")
        _state.update { it.copy(message = "Backup created.") }
    }

    fun deleteBackup(stored: StoredBackup) {
        store.delete(stored.file)
        refreshBackups()
    }

    fun exportBackup(stored: StoredBackup, target: Uri) = background {
        getApplication<Application>().contentResolver.openOutputStream(target, "wt")!!.use {
            it.write(stored.backup.toJson().toByteArray())
        }
        _state.update { it.copy(message = "Backup exported.") }
    }

    fun importBackup(source: Uri) = background {
        val text = getApplication<Application>().contentResolver.openInputStream(source)!!.use { it.readBytes().decodeToString() }
        store.save(Backup.fromJson(text))
        refreshBackups()
        _state.update { it.copy(message = "Backup imported.") }
    }

    private suspend fun createBackup(c: TwizyConnection, label: String) {
        val backup = BackupService(c.sevcon).create(c.info, now(), label) { done, total ->
            _state.update { it.copy(busy = "Backing up parameters ($done/$total)…", progress = done / total.toFloat()) }
        }
        store.save(backup)
        refreshBackups()
    }

    private fun refreshBackups() = _state.update { it.copy(backups = store.list()) }

    // --- restore ---

    fun previewRestore(stored: StoredBackup) = run("Comparing with the controller…") { c ->
        val selection = RestorePlanner.fromBackup(stored.backup, c.info.type)
        preview("Restore backup from ${stored.backup.createdAt}", c, selection)
    }

    fun previewFactoryDefaults() = run("Comparing with the factory defaults…") { c ->
        preview("Restore factory defaults (${c.info.type.label})", c, RestorePlanner.factoryDefaults(c.info.type))
    }

    private suspend fun preview(title: String, c: TwizyConnection, selection: RestoreSelection) =
        showPreview(c, title, selection.requests, Screen.BACKUPS, selection.skipped, selection.readOnlyCount)

    // --- speed and power ---

    fun openTuning() {
        show(Screen.TUNING)
        run("Reading speed and power settings…") { c ->
            _state.update { it.copy(tuning = DriveTuningReader.read(c.sevcon, c.info.type), tuningProblems = emptyList()) }
        }
    }

    fun previewTuning(tuning: DriveTuning) = run("Calculating…") { c ->
        val requests = try {
            DriveTuningPlanner.plan(c.info.type, tuning)
        } catch (e: TuningException) {
            _state.update { it.copy(tuningProblems = e.problems) }
            return@run
        }
        _state.update { it.copy(tuningProblems = emptyList()) }
        showPreview(c, "Speed ${tuning.maxKph} km/h, torque ${tuning.torquePercent} %, " +
            "power ${tuning.powerLowPercent} / ${tuning.powerHighPercent} %", requests, Screen.TUNING)
    }

    // --- writing ---

    private suspend fun showPreview(
        c: TwizyConnection,
        title: String,
        requests: List<WriteRequest>,
        origin: Screen,
        skipped: List<SkippedEntry> = emptyList(),
        readOnlyCount: Int? = null,
    ) {
        val plan = c.writer(guard).prepare(requests)
        _state.update {
            it.copy(preview = WritePreview(title, plan, origin, skipped, readOnlyCount), screen = Screen.PREVIEW)
        }
    }

    fun cancelPreview() = _state.update { it.copy(preview = null, screen = it.preview?.origin ?: Screen.HOME) }

    fun executePreview() = run("Writing…") { c ->
        val preview = _state.value.preview ?: return@run
        // A backup right before every write makes each change undoable.
        createBackup(c, "Before: ${preview.title}")
        _state.update { it.copy(busy = "Writing ${preview.plan.changes.size} values…", progress = null) }
        val result = c.writer(guard).execute(preview.plan)
        _state.update { it.copy(preview = null, screen = preview.origin) }
        when (result) {
            is WriteResult.Success -> _state.update { it.copy(message = "Wrote ${result.changes.size} values.") }
            is WriteResult.Failed -> _state.update { it.copy(error = describe(result)) }
        }
        _state.update { it.copy(accessLevel = c.sevcon.accessLevel(), nmtState = c.sevcon.nmtState()) }
        if (preview.origin == Screen.TUNING) {
            _state.update { it.copy(tuning = DriveTuningReader.read(c.sevcon, c.info.type)) }
        }
    }

    private fun describe(result: WriteResult.Failed): String = buildString {
        append("Writing failed: ${result.cause.message}")
        if (result.rolledBack.isNotEmpty()) append("\n${result.rolledBack.size} values were written and then reset to their previous value.")
        if (result.rollbackFailures.isNotEmpty()) {
            append("\nWARNING: these values could not be reset and need attention:")
            result.rollbackFailures.forEach { (change, e) -> append("\n• ${change.parameter}: ${e.message}") }
        }
        if (!result.operationalRestored) {
            append("\nWARNING: the controller is still in pre-operational mode. Switch the ignition off and on again.")
        }
    }

    // --- helpers ---

    private fun now() = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()

    private fun run(message: String, block: suspend (TwizyConnection) -> Unit) {
        val c = connection ?: return
        if (job?.isActive == true) return
        job = viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(busy = message, progress = null, error = null, message = null) }
            try {
                block(c)
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                _state.update { it.copy(busy = null, progress = null) }
            }
        }
    }

    private fun background(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.toString()) }
            }
        }
    }

    override fun onCleared() {
        connection?.close()
    }
}
