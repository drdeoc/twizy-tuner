package de.dressler.twizy.app

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Scaffold { padding ->
                    App(Modifier.padding(padding).padding(16.dp))
                }
            }
        }
    }
}

@Composable
private fun App(modifier: Modifier, vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.busy?.let {
            Text(it)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (state.info == null) {
            if (state.busy == null) ConnectScreen(vm)
        } else {
            ControllerScreen(state, vm)
        }
    }
}

@Composable
private fun ConnectScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val adapter = remember { context.getSystemService(BluetoothManager::class.java)?.adapter }
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else null
    var granted by remember {
        mutableStateOf(permission == null || ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    Text("Select your ELM327 adapter", style = MaterialTheme.typography.titleLarge)
    Text("Pair the adapter in the Android Bluetooth settings first. Switch the ignition on, but do not press GO.")
    when {
        adapter == null -> Text("This device has no Bluetooth.")
        !granted && permission != null -> Button(onClick = { launcher.launch(permission) }) { Text("Allow Bluetooth access") }
        !adapter.isEnabled -> Text("Bluetooth is switched off.")
        else -> {
            val devices = remember(granted) { vm.pairedAdapters(adapter) }
            if (devices.isEmpty()) Text("No paired devices.")
            LazyColumn(Modifier.fillMaxWidth()) {
                items(devices) { d ->
                    Column(Modifier.fillMaxWidth().clickable { vm.connect(adapter, d) }.padding(vertical = 12.dp)) {
                        Text(d.name, style = MaterialTheme.typography.bodyLarge)
                        Text(d.address, style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = vm::connectSimulator) { Text("Use simulator (no car)") }
}

@Composable
private fun ControllerScreen(state: UiState, vm: MainViewModel) {
    val info = state.info!!
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(info.type.label, style = MaterialTheme.typography.titleMedium)
            Text("Product: ${info.productName}")
            Text("Software ${info.softwareVersion}, hardware ${info.hardwareVersion}")
            Text("Adapter: ${info.adapterVersion}")
            Text("Access level: ${state.accessLevel ?: "?"}   State: ${state.nmtState ?: "?"}")
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = vm::readAll, enabled = state.busy == null) { Text("Read all parameters") }
        OutlinedButton(onClick = vm::refreshStatus, enabled = state.busy == null) { Text("Status") }
        OutlinedButton(onClick = vm::disconnect) { Text("Disconnect") }
    }
    LazyColumn(Modifier.fillMaxWidth()) {
        items(state.rows, key = { it.parameter.address.toString() }) { row ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text("${row.parameter.address}  ${row.parameter.name}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    row.value ?: "error: ${row.error}",
                    fontFamily = FontFamily.Monospace,
                    color = if (row.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
            HorizontalDivider()
        }
    }
}
