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
import by.ster.wazeissues.data.ApiClient
import by.ster.wazeissues.data.LonLat
import by.ster.wazeissues.data.ReportRemote
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class RecentItem(
    val id: String,
    val label: String,
    val description: String?,
    val createdAt: String,
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
                    statusMessage = "Settings saved",
                    nick = nick.trim(),
                    apiKey = apiKey.trim(),
                    apiBase = apiBase.trim().trimEnd('/'),
                )
            }
        }
    }

    fun openEdit(item: RecentItem) {
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
                        statusMessage = "Note saved",
                        recent =
                            s.recent.map {
                                if (it.id == id) it.copy(description = text) else it
                            },
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(busy = false, statusMessage = e.message ?: "Failed to save note")
                }
            }
        }
    }

    fun reportBump(add: Boolean) {
        sendReport(
            issueType = if (add) "speed_bump_add" else "speed_bump_remove",
            label = if (add) "Bump +" else "Bump −",
            valueKmh = null,
        )
    }

    fun reportSpeed(kmh: Int) {
        sendReport(issueType = "speed_limit", label = "$kmh km/h", valueKmh = kmh)
    }

    fun reportGeneral() {
        sendReport(issueType = "general", label = "General issue", valueKmh = null)
    }

    private fun sendReport(issueType: String, label: String, valueKmh: Int?) {
        val s = _state.value
        if (s.nick.isBlank()) {
            _state.update {
                it.copy(statusMessage = "Set your nick in Settings first", showSettings = true)
            }
            return
        }
        if (s.apiKey.isBlank()) {
            _state.update {
                it.copy(statusMessage = "Set API key in Settings first", showSettings = true)
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                val fix = LocationFix.current(getApplication())
                vibrate()
                val created =
                    withContext(Dispatchers.IO) {
                        api.createReport(
                            issueType = issueType,
                            lon = fix.lon,
                            lat = fix.lat,
                            reporterNick = s.nick,
                            valueKmh = valueKmh,
                        )
                    }
                prependRecent(created, label)
                _state.update { it.copy(busy = false, statusMessage = "Sent: $label") }
                startTrailService(created.id)
            } catch (e: Exception) {
                _state.update {
                    it.copy(busy = false, statusMessage = e.message ?: "Send failed")
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
            _state.update { it.copy(statusMessage = "Trail too short for heading") }
            return
        }
        try {
            val heading = bearing(points.first(), points.last())
            withContext(Dispatchers.IO) { api.patchTrajectory(reportId, points, heading) }
            _state.update { it.copy(statusMessage = "Direction saved (${heading.toInt()}°)") }
        } catch (e: Exception) {
            _state.update { it.copy(statusMessage = "Trail upload failed: ${e.message}") }
        }
    }

    private fun prependRecent(report: ReportRemote, label: String) {
        val item =
            RecentItem(
                id = report.id,
                label = label,
                description = report.description,
                createdAt = report.createdAt,
            )
        _state.update { it.copy(recent = (listOf(item) + it.recent).take(30)) }
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
