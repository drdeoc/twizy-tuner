package de.dressler.twizy.app.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import de.dressler.twizy.app.MainViewModel

@Composable
fun ConnectScreen(vm: MainViewModel) {
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
