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
import de.dressler.twizy.app.Screen
import de.dressler.twizy.app.UiState

@Composable
fun ParametersScreen(state: UiState, vm: MainViewModel) {
    Text("Parameters", style = MaterialTheme.typography.titleLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = vm::readAll, enabled = state.busy == null) { Text("Read all") }
        OutlinedButton(onClick = { vm.show(Screen.HOME) }, enabled = state.busy == null) { Text("Back") }
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
