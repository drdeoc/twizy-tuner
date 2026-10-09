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
import de.dressler.twizy.core.tuning.DrivingFeel

@Composable
fun FeelScreen(state: UiState, vm: MainViewModel) {
    val type = state.info?.type ?: return
    val current = state.feel
    val idle = state.busy == null

    Text("Driving feel", style = MaterialTheme.typography.titleLarge)
    if (current == null) {
        if (idle) OutlinedButton(onClick = vm::openFeel) { Text("Read settings") }
        return
    }
    val factory = remember(type) { DrivingFeel.factory(type) }
    var feel by remember(current) { mutableStateOf(current) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("These settings take effect immediately, without configuration mode.", style = MaterialTheme.typography.bodySmall)

        Section("Power and recuperation")
        Setting("Drive power level", feel.drivePercent, "%", DrivingFeel.DRIVE_RANGE, factory.drivePercent) { feel = feel.copy(drivePercent = it) }
        Setting("Recuperation, pedal released", feel.neutralRecupPercent, "%", DrivingFeel.RECUP_RANGE, factory.neutralRecupPercent) {
            feel = feel.copy(neutralRecupPercent = it)
        }
        Setting("Recuperation, foot brake", feel.brakeRecupPercent, "%", DrivingFeel.RECUP_RANGE, factory.brakeRecupPercent) {
            feel = feel.copy(brakeRecupPercent = it)
        }
        Text(
            "Strong recuperation brakes the rear wheels only. Be careful on wet or icy roads.",
            style = MaterialTheme.typography.bodySmall,
        )

        Section("Ramps (how fast torque changes)")
        Setting("Starting off", feel.rampStartPermille, "‰", DrivingFeel.RAMP_START_RANGE, factory.rampStartPermille) {
            feel = feel.copy(rampStartPermille = it)
        }
        Setting("Accelerating", feel.rampAccelPercent, "%", DrivingFeel.RAMP_ACCEL_RANGE, factory.rampAccelPercent) {
            feel = feel.copy(rampAccelPercent = it)
        }
        Setting("Releasing the pedal", feel.rampDecelPercent, "%", DrivingFeel.RAMP_RANGE, factory.rampDecelPercent) {
            feel = feel.copy(rampDecelPercent = it)
        }
        Setting("Into neutral recuperation", feel.rampNeutralPercent, "%", DrivingFeel.RAMP_RANGE, factory.rampNeutralPercent) {
            feel = feel.copy(rampNeutralPercent = it)
        }
        Setting("Into brake recuperation", feel.rampBrakePercent, "%", DrivingFeel.RAMP_RANGE, factory.rampBrakePercent) {
            feel = feel.copy(rampBrakePercent = it)
        }
        Setting("Speed-up limit", feel.rampLimitAccelPercent, "%", DrivingFeel.RAMP_LIMIT_ACCEL_RANGE, factory.rampLimitAccelPercent) {
            feel = feel.copy(rampLimitAccelPercent = it)
        }
        Setting("Slow-down limit", feel.rampLimitDecelPercent, "%", DrivingFeel.RAMP_RANGE, factory.rampLimitDecelPercent) {
            feel = feel.copy(rampLimitDecelPercent = it)
        }

        Section("Pedal")
        Setting("Smoothing", feel.smoothingPercent, "%", DrivingFeel.SMOOTHING_RANGE, factory.smoothingPercent) {
            feel = feel.copy(smoothingPercent = it)
        }

        state.problems.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.previewFeel(feel) }, enabled = idle) { Text("Preview changes") }
            OutlinedButton(onClick = { feel = factory }, enabled = idle) { Text("Factory") }
            OutlinedButton(onClick = { vm.show(Screen.HOME) }, enabled = idle) { Text("Back") }
        }
    }
}

@Composable
fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
}
