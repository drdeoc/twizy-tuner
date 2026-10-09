package de.dressler.twizy.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.dressler.twizy.core.ControllerInfo
import de.dressler.twizy.core.TwizyConnection
import de.dressler.twizy.core.params.Parameter
import de.dressler.twizy.core.params.SevconCatalog
import de.dressler.twizy.core.sevcon.NmtState
import de.dressler.twizy.core.sim.SimulatedElm327Transport
import de.dressler.twizy.core.sim.SimulatedSevcon
import de.dressler.twizy.core.transport.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class Adapter(val name: String, val address: String)

data class ParameterRow(val parameter: Parameter, val value: String?, val error: String?)

data class UiState(
    val busy: String? = null,
    val error: String? = null,
    val info: ControllerInfo? = null,
    val accessLevel: Long? = null,
    val nmtState: NmtState? = null,
    val rows: List<ParameterRow> = emptyList(),
)

class MainViewModel : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var connection: TwizyConnection? = null
    private var job: Job? = null

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
        connection?.close()
        connection = null
        _state.value = UiState()
    }

    /** Reads every catalog entry. Read-only, works in operational mode without login. */
    fun readAll() = run("Reading parameters…") { c ->
        val rows = SevconCatalog.all.map { p ->
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

    private fun open(message: String, transport: suspend () -> Transport) {
        disconnect()
        job = viewModelScope.launch(Dispatchers.IO) {
            _state.value = UiState(busy = message)
            try {
                val c = TwizyConnection.open(transport())
                connection = c
                _state.value = UiState(
                    info = c.info,
                    accessLevel = c.sevcon.accessLevel(),
                    nmtState = c.sevcon.nmtState(),
                )
            } catch (e: Exception) {
                _state.value = UiState(error = "Connection failed: ${e.message}")
            }
        }
    }

    private fun run(message: String, block: suspend (TwizyConnection) -> Unit) {
        val c = connection ?: return
        if (job?.isActive == true) return
        job = viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(busy = message, error = null) }
            try {
                block(c)
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    override fun onCleared() {
        connection?.close()
    }
}
