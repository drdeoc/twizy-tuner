package de.dressler.twizy.app.ui

import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.dressler.twizy.app.MainViewModel
import de.dressler.twizy.app.Screen
import de.dressler.twizy.app.UiState
import de.dressler.twizy.core.tuning.MapKind
import de.dressler.twizy.core.tuning.TorqueSpeedMaps
import de.dressler.twizy.core.tuning.TuningDefaults
import kotlin.math.roundToInt

@Composable
fun MapsScreen(state: UiState, vm: MainViewModel) {
    val type = state.info?.type ?: return
    val current = state.maps
    val idle = state.busy == null

    // The chart needs the width; the screen switches back when it is left.
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { if (previous != null) activity.requestedOrientation = previous }
    }

    if (current == null) {
        Text("Torque/speed maps", style = MaterialTheme.typography.titleLarge)
        if (idle) OutlinedButton(onClick = vm::openMaps) { Text("Read settings") }
        return
    }
    val defaults = TuningDefaults.of(type)
    val kphPerRpm = defaults.kphMax / defaults.rpmMax.toDouble()
    val factory = remember(type) { TorqueSpeedMaps.factory(type) }
    var maps by remember(current) { mutableStateOf(current) }
    var tab by remember { mutableIntStateOf(0) }
    var active by remember { mutableStateOf<Int?>(null) }
    val kind = MapKind.entries[tab]

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Torque/speed maps", style = MaterialTheme.typography.titleLarge)
            SecondaryTabRow(selectedTabIndex = tab, modifier = Modifier.weight(1f)) {
                MapKind.entries.forEachIndexed { i, k ->
                    Tab(
                        selected = i == tab,
                        onClick = { tab = i; active = null },
                        text = { Text(k.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TorqueSpeedChart(
                points = maps[kind],
                factory = factory[kind],
                kphPerRpm = kphPerRpm,
                active = active,
                enabled = idle,
                onActiveChange = { active = it },
                onMove = { i, point -> maps = maps.with(kind, maps[kind].toMutableList().also { it[i] = point }) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            Column(
                Modifier.width(200.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    when (kind) {
                        MapKind.DRIVE -> "Maximum drive torque by speed."
                        MapKind.NEUTRAL -> "Recuperation by speed, pedal released."
                        MapKind.BRAKE -> "Recuperation by speed, foot brake pressed."
                    } + " Drag the points; dashed grey is factory.",
                    style = MaterialTheme.typography.bodySmall,
                )
                maps[kind].forEachIndexed { i, p ->
                    Text(
                        "P${i + 1}: ${p.displayPercent()} % at ${(p.rpm * kphPerRpm).roundToInt()} km/h (${p.rpm} rpm)",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (i == active) FontWeight.Bold else null,
                        color = if (i == active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                state.problems.forEach { Text("• $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Button(onClick = { vm.previewMaps(maps) }, enabled = idle, modifier = Modifier.fillMaxWidth()) { Text("Preview changes") }
                OutlinedButton(onClick = { maps = maps.with(kind, factory[kind]) }, enabled = idle, modifier = Modifier.fillMaxWidth()) {
                    Text("Factory")
                }
                OutlinedButton(onClick = { vm.show(Screen.HOME) }, enabled = idle, modifier = Modifier.fillMaxWidth()) { Text("Back") }
            }
        }
    }
}
