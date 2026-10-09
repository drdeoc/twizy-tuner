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
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.dressler.twizy.app.MainViewModel
import de.dressler.twizy.app.Screen
import de.dressler.twizy.app.UiState
import de.dressler.twizy.core.tuning.MapKind
import de.dressler.twizy.core.tuning.MapPoint
import de.dressler.twizy.core.tuning.TorqueSpeedMaps
import de.dressler.twizy.core.tuning.TuningDefaults
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val RPM_STEP = 50

@Composable
fun MapsScreen(state: UiState, vm: MainViewModel) {
    val type = state.info?.type ?: return
    val current = state.maps
    val idle = state.busy == null

    Text("Torque/speed maps", style = MaterialTheme.typography.titleLarge)
    if (current == null) {
        if (idle) OutlinedButton(onClick = vm::openMaps) { Text("Read settings") }
        return
    }
    val defaults = TuningDefaults.of(type)
    val factory = remember(type) { TorqueSpeedMaps.factory(type) }
    var maps by remember(current) { mutableStateOf(current) }
    var tab by remember { mutableIntStateOf(0) }
    val kind = MapKind.entries[tab]

    SecondaryTabRow(selectedTabIndex = tab) {
        MapKind.entries.forEachIndexed { i, k ->
            Tab(selected = i == tab, onClick = { tab = i }, text = { Text(k.name.lowercase().replaceFirstChar { it.uppercase() }) })
        }
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            when (kind) {
                MapKind.DRIVE -> "Maximum drive torque depending on speed."
                MapKind.NEUTRAL -> "Recuperation strength depending on speed when the pedal is released."
                MapKind.BRAKE -> "Recuperation strength depending on speed when the foot brake is pressed."
            } + " Between the points the controller interpolates. Changing these needs configuration mode.",
            style = MaterialTheme.typography.bodySmall,
        )
        maps[kind].forEachIndexed { i, point ->
            val kph = (point.rpm * defaults.kphMax / defaults.rpmMax.toDouble()).roundToInt()
            val factoryPoint = factory[kind][i]
            Section("Point ${i + 1}")
            Text(
                "Speed: ${point.rpm} rpm ≈ $kph km/h" + if (point.rpm != factoryPoint.rpm) "  (factory ${factoryPoint.rpm})" else "",
            )
            Slider(
                value = point.rpm.toFloat(),
                onValueChange = { v ->
                    val rpm = (v / RPM_STEP).roundToLong() * RPM_STEP
                    maps = maps.with(kind, maps[kind].toMutableList().also { it[i] = point.copy(rpm = rpm) })
                },
                valueRange = 0f..MapPoint.MAX_RPM.toFloat(),
                steps = (MapPoint.MAX_RPM / RPM_STEP - 1).toInt(),
            )
            Setting("Level", point.percent, "%", 0..100, factoryPoint.percent) { percent ->
                maps = maps.with(kind, maps[kind].toMutableList().also { it[i] = MapPoint.ofPercent(percent, point.rpm) })
            }
        }

        state.problems.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.previewMaps(maps) }, enabled = idle) { Text("Preview changes") }
            OutlinedButton(onClick = { maps = maps.with(kind, factory[kind]) }, enabled = idle) { Text("Factory") }
            OutlinedButton(onClick = { vm.show(Screen.HOME) }, enabled = idle) { Text("Back") }
        }
    }
}
