package de.dressler.twizy.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.dressler.twizy.app.MainViewModel
import de.dressler.twizy.app.Screen

@Composable
fun App(modifier: Modifier, vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

    BackHandler(enabled = state.info != null && state.screen != Screen.HOME && state.busy == null) {
        if (state.screen == Screen.RESTORE) vm.cancelRestore() else vm.show(Screen.HOME)
    }

    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.busy?.let {
            Text(it)
            val progress = state.progress
            if (progress != null) LinearProgressIndicator({ progress }, Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

        when {
            state.info == null -> if (state.busy == null) ConnectScreen(vm)
            state.screen == Screen.HOME -> HomeScreen(state, vm)
            state.screen == Screen.PARAMETERS -> ParametersScreen(state, vm)
            state.screen == Screen.BACKUPS -> BackupsScreen(state, vm)
            state.screen == Screen.RESTORE -> RestoreScreen(state, vm)
        }
    }

    if (state.askPreOp) PreOpDialog(vm)
}

@Composable
private fun PreOpDialog(vm: MainViewModel) {
    AlertDialog(
        onDismissRequest = { vm.answerPreOp(false) },
        title = { Text("Switch motor controller to configuration mode?") },
        text = {
            Text(
                "Some values can only be written while the controller is in pre-operational mode. " +
                    "The motor is disabled during that time.\n\n" +
                    "Only continue if the Twizy is standing still, in N, and GO is not active."
            )
        },
        confirmButton = { TextButton(onClick = { vm.answerPreOp(true) }) { Text("Car is safe, continue") } },
        dismissButton = { TextButton(onClick = { vm.answerPreOp(false) }) { Text("Cancel") } },
    )
}
