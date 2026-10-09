package de.dressler.twizy.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
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
import de.dressler.twizy.core.live.LiveData

@Composable
fun DashboardScreen(state: UiState, vm: MainViewModel) {
    val d = state.live
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Live dashboard", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { vm.show(Screen.HOME) }) { Text("Back") }
    }
    if (d == null) {
        Text("Waiting for data… The ignition must be on.")
        return
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Group("Driving", drivingRows(d))
        Group("Battery", batteryRows(d))
        Group("Temperatures", temperatureRows(d))
        Group("Cells", cellRows(d))
        Text(
            "Values are read one frame at a time, so they refresh every second or two.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun drivingRows(d: LiveData) = listOf(
    "Speed" to d.dashboard?.speedKph?.let { "%.1f km/h".format(it) },
    "Gear" to d.driveState?.gear?.name?.let { it + if (d.driveState?.go == true) " · GO" else "" },
    "Pedal" to d.driveState?.pedalPercent?.let { "$it %" },
    "Foot brake" to d.driveState?.footbrake?.let { if (it) "pressed" else "released" },
    "Range" to d.dashboard?.rangeKm?.let { "$it km" },
    "Odometer" to (d.odometerKm?.let { "%.2f km".format(it) } ?: d.dashboard?.odometerKm?.let { "$it km" }),
    "VIN (last 7)" to d.vinSuffix,
)

private fun batteryRows(d: LiveData) = listOf(
    "State of charge" to d.battery?.socPercent?.let { "%.1f %%".format(it) },
    "Current" to d.battery?.currentAmps?.let { "%.2f A".format(it) + if (it < 0) " (charging)" else "" },
    "Power" to d.batteryWatts?.let { "%.0f W".format(it) },
    "Voltage" to d.packVoltage?.let { "%.1f V".format(it) },
    "BMS max drive" to d.bmsLimits?.maxDriveWatts?.let { "$it W" },
    "BMS max recuperation" to d.bmsLimits?.maxRecupWatts?.let { "$it W" },
    "Battery health" to d.bmsLimits?.healthPercent?.let { "$it %" },
    "Charger" to d.charger?.let { c ->
        listOfNotNull("plugged in".takeIf { c.pluggedIn }, "charging".takeIf { c.charging }, "key on".takeIf { c.keyOn })
            .joinToString(", ").ifEmpty { "–" }
    },
)

private fun temperatureRows(d: LiveData) = listOf(
    "Motor" to d.motorTempC?.let { "$it °C" },
    "Controller" to d.controllerTempC?.let { "$it °C" },
    "Charger" to d.charger?.chargerTempC?.let { "$it °C" },
    "Battery modules" to d.moduleTempsC?.joinToString(" ") { "$it°" },
)

private fun cellRows(d: LiveData): List<Pair<String, String?>> {
    val known = d.cellVoltages.filterNotNull()
    val summary = if (known.isEmpty()) null else
        "min %.3f V · max %.3f V · spread %.0f mV".format(known.min(), known.max(), (known.max() - known.min()) * 1000)
    return listOf("Summary" to summary) + d.cellVoltages.mapIndexed { i, v -> "Cell ${i + 1}" to v?.let { "%.3f V".format(it) } }
}

@Composable
private fun Group(title: String, rows: List<Pair<String, String?>>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            rows.forEach { (label, value) ->
                Row {
                    Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(value ?: "–", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
