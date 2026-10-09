package de.dressler.twizy.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import de.dressler.twizy.app.MainViewModel
import de.dressler.twizy.app.UiState

@Composable
fun WritePreviewScreen(state: UiState, vm: MainViewModel) {
    val preview = state.preview ?: return
    val plan = preview.plan
    val idle = state.busy == null

    Text(preview.title, style = MaterialTheme.typography.titleLarge)
    if (plan.isEmpty) {
        Text("The controller already has these values. Nothing to write.")
    } else {
        Text("${plan.changes.size} values will change.")
        if (plan.requiresPreOp) {
            Text(
                "This needs configuration mode: the Twizy must stand still, in N, without GO. You will be asked to confirm.",
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text("A backup of the current values is made first, so this can be undone.", style = MaterialTheme.typography.bodySmall)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = vm::executePreview, enabled = idle && !plan.isEmpty) { Text("Write ${plan.changes.size} values") }
        OutlinedButton(onClick = vm::cancelPreview, enabled = idle) { Text("Cancel") }
    }

    LazyColumn(Modifier.fillMaxWidth()) {
        items(plan.changes, key = { it.parameter.address.toString() }) { change ->
            val p = change.parameter
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text("${p.address}  ${p.name}", style = MaterialTheme.typography.bodyMedium)
                Text("${p.format(change.oldValue)}  →  ${p.format(change.newValue)}", fontFamily = FontFamily.Monospace)
            }
            HorizontalDivider()
        }
        if (preview.skipped.isNotEmpty()) {
            item {
                Text(
                    "Not restored (${preview.skipped.size})",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            items(preview.skipped, key = { "skip-${it.address}" }) { s ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${s.address}  ${s.name}", style = MaterialTheme.typography.bodyMedium)
                    Text(s.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        preview.readOnlyCount?.let { count ->
            item {
                Text(
                    "$count read-only objects are never written.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}
