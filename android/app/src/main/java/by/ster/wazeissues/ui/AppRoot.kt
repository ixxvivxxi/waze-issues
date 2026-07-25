package by.ster.wazeissues.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            when {
                state.showSettings -> SettingsScreen(state, vm)
                state.editingId != null -> EditNoteScreen(state, vm)
                else -> MainScreen(state, vm)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MainScreen(state: UiState, vm: MainViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Waze Issues",
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = { vm.openSettings(true) }) {
                Text("Settings")
            }
        }
        Text(
            state.statusMessage.ifBlank { "Tap to report · nick: ${state.nick.ifBlank { "—" }}" },
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            FilledTonalButton(
                onClick = { vm.reportBump(true) },
                enabled = !state.busy,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            ) { Text("Bump +", fontSize = 16.sp) }
            FilledTonalButton(
                onClick = { vm.reportBump(false) },
                enabled = !state.busy,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            ) { Text("Bump −", fontSize = 16.sp) }
            listOf(40, 60, 70, 90, 100, 110, 120).forEach { kmh ->
                Button(
                    onClick = { vm.reportSpeed(kmh) },
                    enabled = !state.busy,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                ) { Text("$kmh", fontSize = 16.sp) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Recent", style = MaterialTheme.typography.titleSmall)
        LazyColumn(Modifier.weight(1f)) {
            items(state.recent, key = { it.id }) { item ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { vm.openEdit(item) }
                        .padding(vertical = 6.dp),
                ) {
                    Text(item.label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        item.description?.takeIf { it.isNotBlank() } ?: "Tap to add note",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(state: UiState, vm: MainViewModel) {
    var nick by remember(state.nick) { mutableStateOf(state.nick) }
    var apiKey by remember(state.apiKey) { mutableStateOf(state.apiKey) }
    var apiBase by remember(state.apiBase) { mutableStateOf(state.apiBase) }
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = nick,
            onValueChange = { nick = it },
            label = { Text("Nick (shown to editors)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiBase,
            onValueChange = { apiBase = it },
            label = { Text("API base URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.saveSettings(nick, apiKey, apiBase) }) { Text("Save") }
            TextButton(onClick = { vm.openSettings(false) }) { Text("Back") }
        }
    }
}

@Composable
private fun EditNoteScreen(state: UiState, vm: MainViewModel) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Add note", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = state.editingText,
            onValueChange = vm::setEditingText,
            label = { Text("Description") },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(140.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::saveDescription, enabled = !state.busy) { Text("Save") }
            TextButton(onClick = vm::closeEdit) { Text("Cancel") }
        }
    }
}
