package de.dressler.twizy.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import de.dressler.twizy.core.tuning.DriveTuning
import kotlin.math.roundToInt

@Composable
fun TuningScreen(state: UiState, vm: MainViewModel) {
    val type = state.info?.type ?: return
    val current = state.tuning
    val idle = state.busy == null

    Text("Speed & power", style = MaterialTheme.typography.titleLarge)
    if (current == null) {
        if (idle) OutlinedButton(onClick = vm::openTuning) { Text("Read settings") }
        return
    }

    val factory = remember(type) { DriveTuning.factory(type) }
    val maxKph = remember(type) { DriveTuning.maxKphFor(type) }
    var tuning by remember(current) { mutableStateOf(current) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Currently: ${current.maxKph} km/h, torque ${current.torquePercent} %, " +
                "power ${current.powerLowPercent} / ${current.powerHighPercent} % (estimated from the controller)",
            style = MaterialTheme.typography.bodySmall,
        )

        Setting("Max speed", tuning.maxKph, "km/h", DriveTuning.KPH_RANGE.first..maxKph, factory.maxKph) {
            tuning = tuning.copy(maxKph = it, warnKph = maxOf(tuning.warnKph, it))
        }
        Setting("Overspeed warning", tuning.warnKph, "km/h", tuning.maxKph..DriveTuning.KPH_RANGE.last, factory.warnKph) {
            tuning = tuning.copy(warnKph = it)
        }
        Setting("Torque", tuning.torquePercent, "%", DriveTuning.TORQUE_RANGE, 100) {
            tuning = tuning.copy(torquePercent = it)
        }
        Setting("Power at low speed", tuning.powerLowPercent, "%", DriveTuning.POWER_LOW_RANGE, 100) {
            tuning = tuning.copy(powerLowPercent = it)
        }
        Setting("Power at high speed", tuning.powerHighPercent, "%", DriveTuning.POWER_HIGH_RANGE, 100) {
            tuning = tuning.copy(powerHighPercent = it)
        }
        Text(
            "Torque is capped at the motor's flux map limit and motor currents always stay at the factory value. " +
                "Higher speeds than the factory setting are not allowed on public roads.",
            style = MaterialTheme.typography.bodySmall,
        )

        state.tuningProblems.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.previewTuning(tuning) }, enabled = idle) { Text("Preview changes") }
            OutlinedButton(onClick = { tuning = factory }, enabled = idle) { Text("Factory") }
            OutlinedButton(onClick = { vm.show(Screen.HOME) }, enabled = idle) { Text("Back") }
        }
    }
}

@Composable
private fun Setting(label: String, value: Int, unit: String, range: IntRange, factory: Int, onChange: (Int) -> Unit) {
    Text("$label: $value $unit" + if (value != factory) "  (factory $factory)" else "", style = MaterialTheme.typography.bodyLarge)
    Slider(
        value = value.coerceIn(range).toFloat(),
        onValueChange = { onChange(it.roundToInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = (range.last - range.first - 1).coerceAtLeast(0),
    )
}
