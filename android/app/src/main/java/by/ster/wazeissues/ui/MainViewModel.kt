package by.ster.wazeissues.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import by.ster.wazeissues.AppLocales
import by.ster.wazeissues.BuildConfig
import by.ster.wazeissues.R
import by.ster.wazeissues.data.ApiClient
import by.ster.wazeissues.data.LonLat
import by.ster.wazeissues.data.SettingsStore
import by.ster.wazeissues.location.LiveLocation
import by.ster.wazeissues.location.LocationTrailService
import by.ster.wazeissues.location.TrailBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/** Payload kept until the report is accepted by the server (for offline retry). */
data class OutboundPayload(
    val issueType: String,
    val lon: Double,
    val lat: Double,
    val valueKmh: Int?,
    val accuracyM: Float?,
    val fixTimeMs: Long,
    val seedPoints: List<LonLat>,
)

data class RecentItem(
    val id: String,
    val label: String,
    val description: String?,
    val createdAt: String,
    val syncStatus: SyncStatus = SyncStatus.Synced,
    val outbound: OutboundPayload? = null,
)

data class UpdateInfo(
    val versionName: String,
    val apkUrl: String,
)

data class UiState(
    val nick: String = "",
    val apiBase: String = "",
    val settingsReady: Boolean = false,
    val settingsLoaded: Boolean = false,
    val busy: Boolean = false,
    val statusMessage: String = "",
    val recent: List<RecentItem> = emptyList(),
    val showSettings: Boolean = false,
    val editingId: String? = null,
    val editingText: String = "",
    /** Current GPS horizontal accuracy in meters; null if no fix yet. */
    val gpsAccuracyM: Float? = null,
    val gpsAgeMs: Long? = null,
    val hasFreshGps: Boolean = false,
    val language: String = AppLocales.EN,
    /** Set when a newer APK is published on the server. */
    val updateAvailable: UpdateInfo? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = SettingsStore(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val flushMutex = Mutex()

    private val api =
        ApiClient(
            baseUrlProvider = { _state.value.apiBase },
        )

    private fun str(resId: Int): String = getApplication<Application>().getString(resId)

    private fun str(resId: Int, vararg args: Any): String =
        getApplication<Application>().getString(resId, *args)

    init {
        LiveLocation.start(app)
        _state.update { it.copy(language = AppLocales.currentTag()) }
        viewModelScope.launch {
            combine(settings.nick, settings.apiBase) { nick, base ->
                nick to base
            }.collect { (nick, base) ->
                val ready = nick.isNotBlank()
                _state.update { s ->
                    s.copy(
                        nick = nick,
                        apiBase = base,
                        settingsReady = ready,
                        settingsLoaded = true,
                        showSettings = if (!ready) true else s.showSettings,
                        language = AppLocales.currentTag(),
                    )
                }
            }
        }
        viewModelScope.launch {
            LiveLocation.latest.collect { sample ->
                val now = SystemClock.elapsedRealtime()
                _state.update {
                    it.copy(
                        gpsAccuracyM = sample?.accuracyM,
                        gpsAgeMs = sample?.let { s -> now - s.timeMs },
                        hasFreshGps =
                            sample != null && now - sample.timeMs <= LiveLocation.MAX_FIX_AGE_MS,
                    )
                }
            }
        }
        TrailBus.onFinished = { id, points ->
            viewModelScope.launch { uploadTrajectory(id, points) }
        }
        viewModelScope.launch {
            checkForUpdate()
        }
        // Keep retrying failed uploads while the reporter UI is open.
        viewModelScope.launch {
            while (true) {
                delay(RETRY_INTERVAL_MS)
                if (_state.value.recent.any { it.syncStatus == SyncStatus.Failed }) {
                    flushFailedReports()
                }
            }
        }
    }

    companion object {
        private const val RETRY_INTERVAL_MS = 15_000L
    }

    private suspend fun checkForUpdate() {
        val latest = withContext(Dispatchers.IO) { api.fetchLatestVersion() } ?: return
        if (latest.versionCode > BuildConfig.VERSION_CODE) {
            _state.update {
                it.copy(updateAvailable = UpdateInfo(latest.versionName, latest.apkUrl))
            }
        }
    }

    fun dismissUpdate() {
        _state.update { it.copy(updateAvailable = null) }
    }

    override fun onCleared() {
        TrailBus.onFinished = null
        LiveLocation.stop()
        super.onCleared()
    }

    fun openSettings(open: Boolean) {
        if (!open && !_state.value.settingsReady) return
        _state.update { it.copy(showSettings = open, language = AppLocales.currentTag()) }
    }

    fun setLanguage(tag: String) {
        if (tag !in AppLocales.supported) return
        AppLocales.apply(tag)
        _state.update { it.copy(language = tag) }
    }

    fun saveSettings(nick: String, apiBase: String) {
        val n = nick.trim()
        if (n.isBlank()) {
            _state.update { it.copy(statusMessage = str(R.string.need_nick), showSettings = true) }
            return
        }
        viewModelScope.launch {
            settings.setNick(n)
            settings.setApiBase(apiBase)
            _state.update {
                it.copy(
                    showSettings = false,
                    settingsReady = true,
                    statusMessage = str(R.string.settings_saved),
                    nick = n,
                    apiBase = apiBase.trim().trimEnd('/'),
                )
            }
        }
    }

    fun openEdit(item: RecentItem) {
        when (item.syncStatus) {
            SyncStatus.Failed -> {
                viewModelScope.launch { flushFailedReports(preferId = item.id) }
            }
            SyncStatus.Pending -> {
                _state.update { it.copy(statusMessage = str(R.string.wait_until_synced)) }
            }
            SyncStatus.Synced -> {
                _state.update {
                    it.copy(editingId = item.id, editingText = item.description.orEmpty())
                }
            }
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
        if (!s.settingsReady) {
            _state.update {
                it.copy(statusMessage = str(R.string.need_nick), showSettings = true)
            }
            return
        }

        // Capture GPS at tap time — do not wait for network.
        val fix =
            LiveLocation.snapshotForReport()
                ?: run {
                    _state.update { it.copy(statusMessage = str(R.string.no_gps)) }
                    return
                }
        val seedBefore = LiveLocation.recentPoints(12_000L)

        val localId = UUID.randomUUID().toString()
        val createdAt = Instant.now().toString()
        val outbound =
            OutboundPayload(
                issueType = issueType,
                lon = fix.lon,
                lat = fix.lat,
                valueKmh = valueKmh,
                accuracyM = fix.accuracyM,
                fixTimeMs = fix.timeMs,
                seedPoints = seedBefore,
            )
        val pending =
            RecentItem(
                id = localId,
                label = label,
                description = null,
                createdAt = createdAt,
                syncStatus = SyncStatus.Pending,
                outbound = outbound,
            )
        val accHint =
            fix.accuracyM?.let { str(R.string.queued_with_accuracy, label, it.toInt()) }
                ?: str(R.string.queued, label)
        _state.update {
            it.copy(
                recent = (listOf(pending) + it.recent).take(30),
                statusMessage = accHint,
            )
        }
        vibrate()

        viewModelScope.launch {
            val ok = uploadOne(localId, label, outbound, s.nick)
            if (ok) {
                // Network is up — push any earlier failures that piled up offline.
                flushFailedReports()
            }
        }
    }

    /**
     * Retries Failed reports (oldest first). Stops on the first failure so we don't
     * hammer the API while still offline. [preferId] is tried first when set (manual tap).
     */
    private suspend fun flushFailedReports(preferId: String? = null) {
        if (!_state.value.settingsReady) return
        flushMutex.withLock {
            val nick = _state.value.nick
            val failed =
                _state.value.recent
                    .filter { it.syncStatus == SyncStatus.Failed && it.outbound != null }
                    .sortedBy { it.createdAt }
                    .toMutableList()
            if (preferId != null) {
                val idx = failed.indexOfFirst { it.id == preferId }
                if (idx > 0) {
                    val preferred = failed.removeAt(idx)
                    failed.add(0, preferred)
                }
            }
            for (item in failed) {
                val out = item.outbound ?: continue
                // Still present and failed? (may have been dropped from the list)
                val current =
                    _state.value.recent.firstOrNull { it.id == item.id } ?: continue
                if (current.syncStatus != SyncStatus.Failed) continue

                _state.update { st ->
                    st.copy(
                        statusMessage = str(R.string.retrying, item.label),
                        recent =
                            st.recent.map {
                                if (it.id == item.id) it.copy(syncStatus = SyncStatus.Pending) else it
                            },
                    )
                }
                val ok = uploadOne(item.id, item.label, out, nick)
                if (!ok) break
            }
        }
    }

    /** Returns true if the server accepted the report. */
    private suspend fun uploadOne(
        localId: String,
        label: String,
        outbound: OutboundPayload,
        nick: String,
    ): Boolean {
        return try {
            val created =
                withContext(Dispatchers.IO) {
                    api.createReport(
                        issueType = outbound.issueType,
                        lon = outbound.lon,
                        lat = outbound.lat,
                        reporterNick = nick,
                        valueKmh = outbound.valueKmh,
                        clientEventId = localId,
                        accuracyM = outbound.accuracyM,
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
                                    outbound = null,
                                )
                            } else {
                                item
                            }
                        },
                )
            }
            val after = LiveLocation.pointsSince(outbound.fixTimeMs)
            val seed =
                dedupePoints(
                    outbound.seedPoints +
                        LonLat(outbound.lon, outbound.lat) +
                        after,
                )
            TrailBus.seed(created.id, seed)
            startTrailService(created.id)
            true
        } catch (e: Exception) {
            _state.update { st ->
                st.copy(
                    statusMessage = e.message ?: str(R.string.send_failed),
                    recent =
                        st.recent.map { item ->
                            if (item.id == localId) {
                                item.copy(syncStatus = SyncStatus.Failed, outbound = outbound)
                            } else {
                                item
                            }
                        },
                )
            }
            false
        }
    }

    private fun dedupePoints(points: List<LonLat>): List<LonLat> {
        if (points.isEmpty()) return points
        val out = ArrayList<LonLat>(points.size)
        var prev: LonLat? = null
        for (p in points) {
            if (prev == null || prev.lon != p.lon || prev.lat != p.lat) {
                out += p
                prev = p
            }
        }
        return out
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
