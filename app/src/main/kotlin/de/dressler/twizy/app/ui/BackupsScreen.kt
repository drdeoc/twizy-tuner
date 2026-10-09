package de.dressler.twizy.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.dressler.twizy.app.MainViewModel
import de.dressler.twizy.app.Screen
import de.dressler.twizy.app.UiState
import de.dressler.twizy.core.backup.StoredBackup

@Composable
fun BackupsScreen(state: UiState, vm: MainViewModel) {
    val idle = state.busy == null
    val connectedType = state.info?.type
    var exporting by remember { mutableStateOf<StoredBackup?>(null) }
    var deleting by remember { mutableStateOf<StoredBackup?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val backup = exporting
        if (uri != null && backup != null) vm.exportBackup(backup, uri)
        exporting = null
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importBackup(uri)
    }

    Text("Backup & restore", style = MaterialTheme.typography.titleLarge)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::backupNow, enabled = idle) { Text("Back up now") }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }, enabled = idle) { Text("Import") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::previewFactoryDefaults, enabled = idle) { Text("Factory defaults…") }
            OutlinedButton(onClick = { vm.show(Screen.HOME) }, enabled = idle) { Text("Back") }
        }
    }

    if (state.backups.isEmpty()) Text("No backups yet.")
    LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.backups, key = { it.file.name }) { stored ->
            val b = stored.backup
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(b.createdAt, style = MaterialTheme.typography.titleSmall)
                    if (b.label.isNotEmpty()) Text(b.label)
                    Text("${b.controller.label}, software ${b.softwareVersion}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "${b.entries.size} objects" + if (b.readErrors > 0) ", ${b.readErrors} unreadable" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { vm.previewRestore(stored) }, enabled = idle && b.controller == connectedType) { Text("Restore…") }
                        TextButton(onClick = { exporting = stored; exportLauncher.launch(stored.file.name) }, enabled = idle) { Text("Export") }
                        TextButton(onClick = { deleting = stored }, enabled = idle) { Text("Delete") }
                    }
                }
            }
        }
    }

    deleting?.let { stored ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete backup?") },
            text = { Text("${stored.backup.createdAt} ${stored.backup.label}\nThis cannot be undone.") },
            confirmButton = { TextButton(onClick = { vm.deleteBackup(stored); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
