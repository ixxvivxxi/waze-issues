package by.ster.wazeissues.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import by.ster.wazeissues.R
import by.ster.wazeissues.data.ApiClient
import by.ster.wazeissues.data.LonLat
import by.ster.wazeissues.data.SettingsStore
import by.ster.wazeissues.location.LocationFix
import by.ster.wazeissues.location.LocationTrailService
import by.ster.wazeissues.location.TrailBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

enum class SyncStatus {
    Pending,
    Synced,
    Failed,
}

data class RecentItem(
    val id: String,
    val label: String,
    val description: String?,
    val createdAt: String,
    val syncStatus: SyncStatus = SyncStatus.Synced,
)

data class UiState(
    val nick: String = "",
    val apiKey: String = "",
    val apiBase: String = "",
    val busy: Boolean = false,
    val statusMessage: String = "",
    val recent: List<RecentItem> = emptyList(),
    val showSettings: Boolean = false,
    val editingId: String? = null,
    val editingText: String = "",
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = SettingsStore(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val api =
        ApiClient(
            baseUrlProvider = { _state.value.apiBase },
            apiKeyProvider = { _state.value.apiKey },
        )

    private fun str(resId: Int): String = getApplication<Application>().getString(resId)

    private fun str(resId: Int, vararg args: Any): String =
        getApplication<Application>().getString(resId, *args)

    init {
        viewModelScope.launch {
            settings.nick.collect { nick -> _state.update { it.copy(nick = nick) } }
        }
        viewModelScope.launch {
            settings.apiKey.collect { key -> _state.update { it.copy(apiKey = key) } }
        }
        viewModelScope.launch {
            settings.apiBase.collect { base -> _state.update { it.copy(apiBase = base) } }
        }
        TrailBus.onFinished = { id, points ->
            viewModelScope.launch { uploadTrajectory(id, points) }
        }
    }

    fun openSettings(open: Boolean) {
        _state.update { it.copy(showSettings = open) }
    }

    fun saveSettings(nick: String, apiKey: String, apiBase: String) {
        viewModelScope.launch {
            settings.setNick(nick)
            settings.setApiKey(apiKey)
            settings.setApiBase(apiBase)
            _state.update {
                it.copy(
                    showSettings = false,
                    statusMessage = str(R.string.settings_saved),
                    nick = nick.trim(),
                    apiKey = apiKey.trim(),
                    apiBase = apiBase.trim().trimEnd('/'),
                )
            }
        }
    }

    fun openEdit(item: RecentItem) {
        if (item.syncStatus != SyncStatus.Synced) {
            _state.update { it.copy(statusMessage = str(R.string.wait_until_synced)) }
            return
        }
        _state.update {
            it.copy(editingId = item.id, editingText = item.description.orEmpty())
        }
    }

    fun setEditingText(text: String) {
        _state.update { it.copy(editingText = text) }
    }

    fun closeEdit() {
        _state.update { it.copy(editingId = null, editingText = "") }
    }

    fun saveDescription() {
        val id = _state.value.editingId ?: return
        val text = _state.value.editingText
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                withContext(Dispatchers.IO) { api.patchDescription(id, text) }
                _state.update { s ->
                    s.copy(
                        busy = false,
                        editingId = null,
                        editingText = "",
                        statusMessage = str(R.string.note_saved),
                        recent =
                            s.recent.map {
                                if (it.id == id) it.copy(description = text) else it
                            },
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        busy = false,
                        statusMessage = e.message ?: str(R.string.note_save_failed),
                    )
                }
            }
        }
    }

    fun reportBump(add: Boolean) {
        sendReport(
            issueType = if (add) "speed_bump_add" else "speed_bump_remove",
            label =
                if (add) str(R.string.label_bump_add) else str(R.string.label_bump_remove),
            valueKmh = null,
        )
    }

    fun reportSpeed(kmh: Int) {
        sendReport(
            issueType = "speed_limit",
            label =
                if (kmh == 0) {
                    str(R.string.label_speed_end)
                } else {
                    str(R.string.label_speed_kmh, kmh)
                },
            valueKmh = kmh,
        )
    }

    fun reportGeneral() {
        sendReport(
            issueType = "general",
            label = str(R.string.label_general),
            valueKmh = null,
        )
    }

    private fun sendReport(issueType: String, label: String, valueKmh: Int?) {
        val s = _state.value
        if (s.nick.isBlank()) {
            _state.update {
                it.copy(statusMessage = str(R.string.need_nick), showSettings = true)
            }
            return
        }
        if (s.apiKey.isBlank()) {
            _state.update {
                it.copy(statusMessage = str(R.string.need_api_key), showSettings = true)
            }
            return
        }

        val localId = UUID.randomUUID().toString()
        val createdAt = Instant.now().toString()
        val pending =
            RecentItem(
                id = localId,
                label = label,
                description = null,
                createdAt = createdAt,
                syncStatus = SyncStatus.Pending,
            )
        _state.update {
            it.copy(
                recent = (listOf(pending) + it.recent).take(30),
                statusMessage = str(R.string.queued, label),
            )
        }
        vibrate()

        viewModelScope.launch {
            try {
                val fix = LocationFix.current(getApplication())
                val created =
                    withContext(Dispatchers.IO) {
                        api.createReport(
                            issueType = issueType,
                            lon = fix.lon,
                            lat = fix.lat,
                            reporterNick = s.nick,
                            valueKmh = valueKmh,
                            clientEventId = localId,
                        )
                    }
                _state.update { st ->
                    st.copy(
                        statusMessage = str(R.string.sent, label),
                        recent =
                            st.recent.map { item ->
                                if (item.id == localId) {
                                    item.copy(
                                        id = created.id,
                                        description = created.description,
                                        createdAt = created.createdAt,
                                        syncStatus = SyncStatus.Synced,
                                    )
                                } else {
                                    item
                                }
                            },
                    )
                }
                startTrailService(created.id)
            } catch (e: Exception) {
                _state.update { st ->
                    st.copy(
                        statusMessage = e.message ?: str(R.string.send_failed),
                        recent =
                            st.recent.map { item ->
                                if (item.id == localId) {
                                    item.copy(syncStatus = SyncStatus.Failed)
                                } else {
                                    item
                                }
                            },
                    )
                }
            }
        }
    }

    private fun startTrailService(reportId: String) {
        val ctx = getApplication<Application>()
        val intent =
            Intent(ctx, LocationTrailService::class.java).apply {
                putExtra(LocationTrailService.EXTRA_REPORT_ID, reportId)
            }
        ctx.startForegroundService(intent)
    }

    private suspend fun uploadTrajectory(reportId: String, points: List<LonLat>) {
        if (points.size < 2) {
            _state.update { it.copy(statusMessage = str(R.string.trail_too_short)) }
            return
        }
        try {
            val heading = bearing(points.first(), points.last())
            withContext(Dispatchers.IO) { api.patchTrajectory(reportId, points, heading) }
            _state.update {
                it.copy(statusMessage = str(R.string.direction_saved, heading.toInt()))
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(statusMessage = str(R.string.trail_upload_failed, e.message ?: ""))
            }
        }
    }

    private fun vibrate() {
        val ctx = getApplication<Application>()
        val vibrator =
            if (Build.VERSION.SDK_INT >= 31) {
                val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
        vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun bearing(a: LonLat, b: LonLat): Double {
        val φ1 = Math.toRadians(a.lat)
        val φ2 = Math.toRadians(b.lat)
        val Δλ = Math.toRadians(b.lon - a.lon)
        val y = sin(Δλ) * cos(φ2)
        val x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(Δλ)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }
}
