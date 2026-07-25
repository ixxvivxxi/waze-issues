package by.ster.wazeissues.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

private val SpeedSignRed = Color(0xFFE30613)
private val SpeedSignBg = Color(0xFFFFFFF8)
private val SpeedSignText = Color(0xFF1A1A1A)
private val BumpAddBg = Color(0xFF1B7A3D)
private val BumpRemoveBg = Color(0xFFB3261E)

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
            verticalAlignment = Alignment.CenterVertically,
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
            modifier = Modifier.padding(bottom = 8.dp),
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BumpActionButton(
                add = true,
                enabled = !state.busy,
                onClick = { vm.reportBump(true) },
                modifier = Modifier.weight(1f),
            )
            BumpActionButton(
                add = false,
                enabled = !state.busy,
                onClick = { vm.reportBump(false) },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Speed limit",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        val speeds = listOf(40, 60, 70, 90, 100, 110, 120)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            speeds.chunked(4).forEach { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { kmh ->
                        SpeedLimitButton(
                            kmh = kmh,
                            enabled = !state.busy,
                            onClick = { vm.reportSpeed(kmh) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(4 - row.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
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
private fun BumpActionButton(
    add: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (add) BumpAddBg else BumpRemoveBg
    val label = if (add) "Add" else "Remove"
    Box(
        modifier =
            modifier
                .height(104.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (enabled) bg else bg.copy(alpha = 0.45f))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            SpeedBumpIcon(
                add = add,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    label,
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "speed bump",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun SpeedBumpIcon(
    add: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val bump =
            Path().apply {
                moveTo(w * 0.08f, h * 0.72f)
                quadraticTo(w * 0.5f, h * 0.18f, w * 0.92f, h * 0.72f)
                lineTo(w * 0.08f, h * 0.72f)
                close()
            }
        drawPath(path = bump, color = Color.White)
        drawLine(
            color = Color.White.copy(alpha = 0.85f),
            start = Offset(w * 0.05f, h * 0.78f),
            end = Offset(w * 0.95f, h * 0.78f),
            strokeWidth = h * 0.06f,
            cap = StrokeCap.Round,
        )
        val markY = h * 0.88f
        val markColor = if (add) Color(0xFFB8F5C8) else Color(0xFFFFC9C4)
        drawCircle(
            color = markColor,
            radius = h * 0.14f,
            center = Offset(w * 0.82f, markY),
        )
        val cx = w * 0.82f
        val cy = markY
        val r = h * 0.08f
        drawLine(
            color = if (add) BumpAddBg else BumpRemoveBg,
            start = Offset(cx - r, cy),
            end = Offset(cx + r, cy),
            strokeWidth = h * 0.05f,
            cap = StrokeCap.Round,
        )
        if (add) {
            drawLine(
                color = BumpAddBg,
                start = Offset(cx, cy - r),
                end = Offset(cx, cy + r),
                strokeWidth = h * 0.05f,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun SpeedLimitButton(
    kmh: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .aspectRatio(1f)
                .clip(CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(if (enabled) SpeedSignBg else SpeedSignBg.copy(alpha = 0.5f))
                    .border(
                        width = 5.dp,
                        color = if (enabled) SpeedSignRed else SpeedSignRed.copy(alpha = 0.45f),
                        shape = CircleShape,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$kmh",
                color = if (enabled) SpeedSignText else SpeedSignText.copy(alpha = 0.45f),
                fontSize = if (kmh >= 100) 20.sp else 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                lineHeight = 24.sp,
            )
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
