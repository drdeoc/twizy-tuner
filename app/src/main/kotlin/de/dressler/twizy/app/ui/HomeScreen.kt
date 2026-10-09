package de.dressler.twizy.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.dressler.twizy.app.MainViewModel
import de.dressler.twizy.app.Screen
import de.dressler.twizy.app.UiState

@Composable
fun HomeScreen(state: UiState, vm: MainViewModel) {
    val info = state.info!!
    val idle = state.busy == null
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(info.type.label, style = MaterialTheme.typography.titleMedium)
            Text("Product: ${info.productName}")
            Text("Software ${info.softwareVersion}, hardware ${info.hardwareVersion}")
            Text("Adapter: ${info.adapterVersion}")
            Text("Access level: ${state.accessLevel ?: "?"}   State: ${state.nmtState ?: "?"}")
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.show(Screen.PARAMETERS) }, enabled = idle, modifier = Modifier.fillMaxWidth()) { Text("Parameters") }
        Button(onClick = vm::openTuning, enabled = idle, modifier = Modifier.fillMaxWidth()) { Text("Speed & power") }
        Button(onClick = { vm.show(Screen.BACKUPS) }, enabled = idle, modifier = Modifier.fillMaxWidth()) { Text("Backup & restore") }
        OutlinedButton(onClick = vm::refreshStatus, enabled = idle, modifier = Modifier.fillMaxWidth()) { Text("Refresh status") }
        OutlinedButton(onClick = vm::disconnect, enabled = idle, modifier = Modifier.fillMaxWidth()) { Text("Disconnect") }
    }
}
