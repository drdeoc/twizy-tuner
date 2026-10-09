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
import androidx.compose.material3.OutlinedTextField
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
import de.dressler.twizy.core.tuning.Profile

@Composable
fun ProfilesScreen(state: UiState, vm: MainViewModel) {
    val type = state.info?.type ?: return
    val idle = state.busy == null
    var naming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Profile?>(null) }
    var exporting by remember { mutableStateOf<Profile?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val profile = exporting
        if (uri != null && profile != null) vm.exportProfile(profile, uri)
        exporting = null
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importProfile(uri)
    }

    Text("Profiles", style = MaterialTheme.typography.titleLarge)
    Text(
        "A profile holds speed & power, driving feel and the torque/speed maps. " +
            "Applying one shows all changes first.",
        style = MaterialTheme.typography.bodySmall,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { naming = true }, enabled = idle) { Text("Save current…") }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }, enabled = idle) { Text("Import") }
            OutlinedButton(onClick = { vm.show(Screen.HOME) }, enabled = idle) { Text("Back") }
        }
    }
    state.problems.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) }

    val profiles = listOf(Profile.factory(type)) + state.profiles.filter { it.controller == type }
    LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(profiles.indices.toList(), key = { i -> if (i == 0) "built-in" else "user-${profiles[i].name}" }) { i ->
            val profile = profiles[i]
            val builtIn = i == 0
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(profile.name + if (builtIn) " (built in)" else "", style = MaterialTheme.typography.titleSmall)
                    with(profile.drive) {
                        Text(
                            "$maxKph km/h, torque $torquePercent %, power $powerLowPercent / $powerHighPercent %",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    with(profile.feel) {
                        Text(
                            "Drive $drivePercent %, recuperation $neutralRecupPercent / $brakeRecupPercent %",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { vm.previewProfile(profile) }, enabled = idle) { Text("Apply…") }
                        if (!builtIn) {
                            TextButton(onClick = { exporting = profile; exportLauncher.launch("${profile.name}.json") }, enabled = idle) {
                                Text("Export")
                            }
                            TextButton(onClick = { deleting = profile }, enabled = idle) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }

    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Save current settings") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Profile name") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { vm.saveCurrentAsProfile(name); naming = false }, enabled = name.isNotBlank()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }
    deleting?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete profile '${profile.name}'?") },
            confirmButton = { TextButton(onClick = { vm.deleteProfile(profile); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
