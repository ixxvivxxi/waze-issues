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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import by.ster.wazeissues.AppLocales
import by.ster.wazeissues.R
import by.ster.wazeissues.location.LiveLocation

private val SpeedSignRed = Color(0xFFE30613)
private val SpeedSignBg = Color(0xFFFFFFF8)
private val SpeedSignText = Color(0xFF1A1A1A)
private val SpeedEndGrey = Color(0xFF7A7A7A)
private val BumpAddBg = Color(0xFF1B7A3D)
private val BumpRemoveBg = Color(0xFFB3261E)
private val SyncOk = Color(0xFF2E7D32)
private val SyncFail = Color(0xFFC62828)
private val SyncPending = Color(0xFF9E9E9E)

@Composable
private fun gpsStatusText(state: UiState): String {
    val acc = state.gpsAccuracyM
    return when {
        acc == null -> stringResource(R.string.gps_searching)
        !state.hasFreshGps -> stringResource(R.string.gps_stale, acc.toInt())
        else -> stringResource(R.string.gps_accuracy, acc.toInt())
    }
}

@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            when {
                !state.settingsLoaded -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.loading))
                    }
                }
                !state.settingsReady || state.showSettings -> SettingsScreen(state, vm)
                state.editingId != null -> EditNoteScreen(state, vm)
                else -> MainScreen(state, vm)
            }
        }
    }
}

@Composable
private fun MainScreen(state: UiState, vm: MainViewModel) {
    val nickShown =
        state.nick.ifBlank { stringResource(R.string.nick_placeholder) }
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
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = { vm.openSettings(true) }) {
                Text(stringResource(R.string.settings))
            }
        }
        state.updateAvailable?.let { update ->
            UpdateBanner(
                versionName = update.versionName,
                apkUrl = update.apkUrl,
                onDismiss = { vm.dismissUpdate() },
            )
        }
        Text(
            state.statusMessage.ifBlank {
                stringResource(R.string.status_idle, nickShown)
            },
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        Text(
            gpsStatusText(state),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color =
                when {
                    !state.hasFreshGps -> MaterialTheme.colorScheme.error
                    (state.gpsAccuracyM ?: 999f) > LiveLocation.GOOD_ACCURACY_M ->
                        Color(0xFFB26A00)
                    else -> Color(0xFF2E7D32)
                },
            modifier = Modifier.padding(bottom = 6.dp),
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BumpActionButton(
                add = true,
                onClick = { vm.reportBump(true) },
                modifier = Modifier.weight(1f),
            )
            BumpActionButton(
                add = false,
                onClick = { vm.reportBump(false) },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(8.dp))
        GeneralIssueButton(
            onClick = { vm.reportGeneral() },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.speed_limit_section),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        // 0 = end of speed limit
        val speeds = listOf(20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 0)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            speeds.chunked(6).forEach { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    row.forEach { kmh ->
                        if (kmh == 0) {
                            SpeedLimitEndButton(
                                onClick = { vm.reportSpeed(0) },
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            SpeedLimitButton(
                                kmh = kmh,
                                onClick = { vm.reportSpeed(kmh) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    repeat(6 - row.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.recent), style = MaterialTheme.typography.titleSmall)
        LazyColumn(Modifier.weight(1f)) {
            items(state.recent, key = { it.id }) { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { vm.openEdit(item) }
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SyncIndicator(item.syncStatus)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            when {
                                item.description?.isNotBlank() == true -> item.description
                                item.syncStatus == SyncStatus.Failed ->
                                    stringResource(R.string.tap_to_retry)
                                item.syncStatus == SyncStatus.Pending ->
                                    stringResource(R.string.sync_pending_hint)
                                else -> stringResource(R.string.tap_to_add_note)
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncIndicator(status: SyncStatus) {
    val (symbol, color) =
        when (status) {
            SyncStatus.Synced -> "✓" to SyncOk
            SyncStatus.Pending -> "…" to SyncPending
            SyncStatus.Failed -> "!" to SyncFail
        }
    Box(
        modifier =
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .border(1.5.dp, color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            symbol,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun GeneralIssueButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = Color(0xFF37474F)
    Box(
        modifier =
            modifier
                .height(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(bg)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFFC107)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "!",
                    color = Color(0xFF212121),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    stringResource(R.string.general_issue),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.general_issue_hint),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun BumpActionButton(
    add: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (add) BumpAddBg else BumpRemoveBg
    val label = if (add) stringResource(R.string.bump_add) else stringResource(R.string.bump_remove)
    Box(
        modifier =
            modifier
                .height(64.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(bg)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            SpeedBumpIcon(
                add = add,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    label,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.speed_bump),
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 11.sp,
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .aspectRatio(1f)
                .clip(CircleShape)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(1.dp)
                    .clip(CircleShape)
                    .background(SpeedSignBg)
                    .border(
                        width = 3.dp,
                        color = SpeedSignRed,
                        shape = CircleShape,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$kmh",
                color = SpeedSignText,
                fontSize = if (kmh >= 100) 12.sp else 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                lineHeight = 14.sp,
            )
        }
    }
}

/** European-style end of all restrictions / end of speed limit. */
@Composable
private fun SpeedLimitEndButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .aspectRatio(1f)
                .clip(CircleShape)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(1.dp)
                    .clip(CircleShape)
                    .background(SpeedSignBg)
                    .border(
                        width = 2.5.dp,
                        color = SpeedEndGrey,
                        shape = CircleShape,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize().padding(6.dp)) {
                drawLine(
                    color = SpeedEndGrey,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height),
                    strokeWidth = size.minDimension * 0.18f,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun UpdateBanner(
    versionName: String,
    apkUrl: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.update_available, versionName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl)),
                )
            }) {
                Text(stringResource(R.string.update_download))
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.update_later))
            }
        }
    }
}

@Composable
private fun SettingsScreen(state: UiState, vm: MainViewModel) {
    var nick by remember(state.nick) { mutableStateOf(state.nick) }
    var apiBase by remember(state.apiBase) { mutableStateOf(state.apiBase) }
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.language_label),
            style = MaterialTheme.typography.titleSmall,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LanguageOption(
                label = "English",
                selected = state.language == AppLocales.EN,
                onClick = { vm.setLanguage(AppLocales.EN) },
                modifier = Modifier.weight(1f),
            )
            LanguageOption(
                label = "Русский",
                selected = state.language == AppLocales.RU,
                onClick = { vm.setLanguage(AppLocales.RU) },
                modifier = Modifier.weight(1f),
            )
            LanguageOption(
                label = "Беларуская",
                selected = state.language == AppLocales.BE,
                onClick = { vm.setLanguage(AppLocales.BE) },
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = nick,
            onValueChange = { nick = it },
            label = { Text(stringResource(R.string.nick_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiBase,
            onValueChange = { apiBase = it },
            label = { Text(stringResource(R.string.api_base_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.saveSettings(nick, apiBase) }) {
                Text(stringResource(R.string.save))
            }
            if (state.settingsReady) {
                TextButton(onClick = { vm.openSettings(false) }) {
                    Text(stringResource(R.string.back))
                }
            }
        }
        if (!state.settingsReady) {
            Text(
                stringResource(R.string.settings_required_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LanguageOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier,
            contentPadding = ButtonDefaults.ContentPadding,
        ) {
            Text(label, fontSize = 12.sp, maxLines = 1)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            contentPadding = ButtonDefaults.ContentPadding,
        ) {
            Text(label, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
private fun EditNoteScreen(state: UiState, vm: MainViewModel) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.add_note), style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = state.editingText,
            onValueChange = vm::setEditingText,
            label = { Text(stringResource(R.string.description)) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(140.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::saveDescription, enabled = !state.busy) {
                Text(stringResource(R.string.save))
            }
            TextButton(onClick = vm::closeEdit) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}
