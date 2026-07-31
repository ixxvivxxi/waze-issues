package by.ster.wazeissues.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.content.FileProvider
import by.ster.wazeissues.AppLocales
import by.ster.wazeissues.BuildConfig
import by.ster.wazeissues.R
import by.ster.wazeissues.bubble.BubbleExpandDirection
import by.ster.wazeissues.data.ApiClient
import by.ster.wazeissues.data.LonLat
import by.ster.wazeissues.data.SettingsStore
import by.ster.wazeissues.location.GpsSample
import by.ster.wazeissues.location.LiveLocation
import by.ster.wazeissues.location.LocationTrailService
import by.ster.wazeissues.location.TrailBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import java.io.File
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
    val lengthM: Int?,
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
    val issueType: String? = null,
    val valueKmh: Int? = null,
    /** Applicability length in meters; 0 = until signs. Null if N/A. */
    val lengthM: Int? = null,
)

data class UpdateInfo(
    val versionName: String,
    val apkUrl: String,
)

/** In-progress long-press length selection on a speed sign (no edit screen). */
data class LengthGesture(
    val reportId: String,
    val kmh: Int,
    val lengthM: Int,
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
    val editingIssueType: String? = null,
    val editingValueKmh: Int? = null,
    val editingLengthM: Int = 0,
    val editingLabel: String = "",
    val lengthGesture: LengthGesture? = null,
    /** Current GPS horizontal accuracy in meters; null if no fix yet. */
    val gpsAccuracyM: Float? = null,
    val gpsAgeMs: Long? = null,
    val hasFreshGps: Boolean = false,
    val language: String = AppLocales.EN,
    /** Bubble menu expand direction (relative to hub). */
    val bubbleExpand: BubbleExpandDirection = BubbleExpandDirection.Up,
    /** Set when a newer APK is published on the server. */
    val updateAvailable: UpdateInfo? = null,
    val updateDownloading: Boolean = false,
)

/**
 * Process-wide reporter used by the full Activity UI and the floating bubble overlay.
 */
class ReportController(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings = SettingsStore(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val flushMutex = Mutex()
    private var clients = 0
    private var settingsJob: Job? = null
    private var gpsJob: Job? = null
    private var retryJob: Job? = null
    private var updateJob: Job? = null

    /** GPS fix frozen when the bubble menu opens (tap time). */
    private var frozenFix: GpsSample? = null

    private val api =
        ApiClient(
            baseUrlProvider = { _state.value.apiBase },
        )

    /** Local ids discarded by the user before/while upload — DELETE after sync. */
    private val discardAfterUpload = mutableSetOf<String>()

    /** Server/local ids whose trail must not be uploaded (deleted reports). */
    private val suppressTrailIds = mutableSetOf<String>()

    private fun str(resId: Int): String = app.getString(resId)

    private fun str(resId: Int, vararg args: Any): String =
        app.getString(resId, *args)

    init {
        _state.update { it.copy(language = AppLocales.currentTag()) }
        TrailBus.onFinished = { id, points ->
            scope.launch { uploadTrajectory(id, points) }
        }
        settingsJob =
            scope.launch {
                combine(settings.nick, settings.apiBase, settings.bubbleExpand) { nick, base, expand ->
                    Triple(nick, base, expand)
                }.collect { (nick, base, expand) ->
                    val ready = nick.isNotBlank()
                    _state.update { s ->
                        s.copy(
                            nick = nick,
                            apiBase = base,
                            bubbleExpand = expand,
                            settingsReady = ready,
                            settingsLoaded = true,
                            showSettings = if (!ready) true else s.showSettings,
                            language = AppLocales.currentTag(),
                        )
                    }
                }
            }
        updateJob = scope.launch { checkForUpdate() }
        retryJob =
            scope.launch {
                while (true) {
                    delay(RETRY_INTERVAL_MS)
                    if (_state.value.recent.any { it.syncStatus == SyncStatus.Failed }) {
                        flushFailedReports()
                    }
                }
            }
    }

    /** Activity / bubble clients keep LiveLocation alive while at least one is attached. */
    @Synchronized
    fun acquire() {
        if (clients == 0) {
            LiveLocation.start(app)
            gpsJob =
                scope.launch {
                    LiveLocation.latest.collect { sample ->
                        val now = SystemClock.elapsedRealtime()
                        _state.update {
                            it.copy(
                                gpsAccuracyM = sample?.accuracyM,
                                gpsAgeMs = sample?.let { s -> now - s.timeMs },
                                hasFreshGps =
                                    sample != null &&
                                        now - sample.timeMs <= LiveLocation.MAX_FIX_AGE_MS,
                            )
                        }
                    }
                }
        }
        clients++
    }

    @Synchronized
    fun release() {
        if (clients == 0) return
        clients--
        if (clients == 0) {
            gpsJob?.cancel()
            gpsJob = null
            LiveLocation.stop()
            clearFrozenFix()
        }
    }

    /** Snapshot GPS for bubble actions until the menu collapses. */
    fun captureFrozenFix(): Boolean {
        val fix = LiveLocation.snapshotForReport()
        frozenFix = fix
        if (fix == null) {
            _state.update { it.copy(statusMessage = str(R.string.no_gps)) }
            return false
        }
        return true
    }

    fun clearFrozenFix() {
        frozenFix = null
    }

    companion object {
        private const val RETRY_INTERVAL_MS = 15_000L
        const val LENGTH_MIN_M = 0
        const val LENGTH_MAX_M = 1000
        const val LENGTH_STEP_M = 50
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

    /** Download APK in-process (follows GitHub redirects) and open the installer. */
    fun downloadAndInstallUpdate() {
        val info = _state.value.updateAvailable ?: return
        if (_state.value.updateDownloading) return
        val app = app
        scope.launch {
            _state.update {
                it.copy(
                    updateDownloading = true,
                    statusMessage = str(R.string.update_downloading),
                )
            }
            try {
                if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
                    _state.update {
                        it.copy(
                            updateDownloading = false,
                            statusMessage = str(R.string.update_allow_unknown),
                        )
                    }
                    val settings =
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${app.packageName}"),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    app.startActivity(settings)
                    return@launch
                }
                val dest = File(app.cacheDir, "waze-issues-update.apk")
                if (dest.exists()) dest.delete()
                withContext(Dispatchers.IO) { api.downloadApk(info.apkUrl, dest) }
                val uri =
                    FileProvider.getUriForFile(
                        app,
                        "${app.packageName}.fileprovider",
                        dest,
                    )
                val install =
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                app.startActivity(install)
                _state.update {
                    it.copy(
                        updateDownloading = false,
                        statusMessage = str(R.string.update_installing),
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        updateDownloading = false,
                        statusMessage = e.message ?: str(R.string.update_download_failed),
                    )
                }
            }
        }
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

    fun setBubbleExpand(direction: BubbleExpandDirection) {
        scope.launch {
            settings.setBubbleExpand(direction)
            _state.update { it.copy(bubbleExpand = direction) }
        }
    }

    fun saveSettings(nick: String, apiBase: String) {
        val n = nick.trim()
        if (n.isBlank()) {
            _state.update { it.copy(statusMessage = str(R.string.need_nick), showSettings = true) }
            return
        }
        scope.launch {
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
                scope.launch { flushFailedReports(preferId = item.id) }
            }
            SyncStatus.Pending -> {
                _state.update { it.copy(statusMessage = str(R.string.wait_until_synced)) }
            }
            SyncStatus.Synced -> openEditorFor(item)
        }
    }

    private fun openEditorFor(item: RecentItem) {
        val showLength =
            item.issueType == "speed_limit" && (item.valueKmh ?: 0) != 0
        _state.update {
            it.copy(
                editingId = item.id,
                editingText = item.description.orEmpty(),
                editingIssueType = item.issueType,
                editingValueKmh = item.valueKmh,
                editingLengthM = if (showLength) (item.lengthM ?: 0) else 0,
                editingLabel = item.label,
            )
        }
    }

    fun setEditingText(text: String) {
        _state.update { s ->
            val id = s.editingId
            s.copy(
                editingText = text,
                recent =
                    if (id == null) {
                        s.recent
                    } else {
                        s.recent.map {
                            if (it.id == id) it.copy(description = text) else it
                        }
                    },
            )
        }
    }

    fun setEditingLengthM(meters: Int) {
        val snapped =
            (meters.coerceIn(LENGTH_MIN_M, LENGTH_MAX_M) / LENGTH_STEP_M) * LENGTH_STEP_M
        _state.update { s ->
            val id = s.editingId
            s.copy(
                editingLengthM = snapped,
                recent =
                    if (id == null) {
                        s.recent
                    } else {
                        s.recent.map { item ->
                            if (item.id != id) {
                                item
                            } else {
                                item.copy(
                                    lengthM = snapped,
                                    outbound = item.outbound?.copy(lengthM = snapped),
                                )
                            }
                        }
                    },
            )
        }
    }

    fun closeEdit() {
        _state.update {
            it.copy(
                editingId = null,
                editingText = "",
                editingIssueType = null,
                editingValueKmh = null,
                editingLengthM = 0,
                editingLabel = "",
            )
        }
    }

    fun saveDescription() {
        val id = _state.value.editingId ?: return
        val text = _state.value.editingText
        val issueType = _state.value.editingIssueType
        val valueKmh = _state.value.editingValueKmh
        val lengthM = _state.value.editingLengthM
        val patchLength = issueType == "speed_limit" && (valueKmh ?: 0) != 0
        val item = _state.value.recent.firstOrNull { it.id == id }

        // Still uploading — keep values locally; uploadOne / a later save will PATCH.
        if (item != null && item.syncStatus != SyncStatus.Synced) {
            _state.update { s ->
                s.copy(
                    editingId = null,
                    editingText = "",
                    editingIssueType = null,
                    editingValueKmh = null,
                    editingLengthM = 0,
                    editingLabel = "",
                    statusMessage = str(R.string.note_saved_local),
                    recent =
                        s.recent.map {
                            if (it.id != id) {
                                it
                            } else {
                                it.copy(
                                    description = text,
                                    lengthM = if (patchLength) lengthM else it.lengthM,
                                    outbound =
                                        it.outbound?.copy(
                                            lengthM = if (patchLength) lengthM else it.outbound.lengthM,
                                        ),
                                )
                            }
                        },
                )
            }
            return
        }

        scope.launch {
            _state.update { it.copy(busy = true) }
            try {
                withContext(Dispatchers.IO) {
                    api.patchReport(
                        id = id,
                        description = text,
                        lengthM = if (patchLength) lengthM else null,
                    )
                }
                _state.update { s ->
                    s.copy(
                        busy = false,
                        editingId = null,
                        editingText = "",
                        editingIssueType = null,
                        editingValueKmh = null,
                        editingLengthM = 0,
                        editingLabel = "",
                        statusMessage = str(R.string.note_saved),
                        recent =
                            s.recent.map {
                                if (it.id == id) {
                                    it.copy(
                                        description = text,
                                        lengthM = if (patchLength) lengthM else it.lengthM,
                                    )
                                } else {
                                    it
                                }
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

    fun deleteEditingReport() {
        val id = _state.value.editingId ?: return
        val item = _state.value.recent.firstOrNull { it.id == id }
        scope.launch {
            _state.update { it.copy(busy = true) }
            try {
                // Drop any in-flight GPS trail for this report (avoids 404 status spam).
                suppressTrailIds += id
                if (TrailBus.activeReportId == id) {
                    stopTrailService()
                }
                when {
                    item == null -> Unit
                    item.syncStatus == SyncStatus.Synced -> {
                        withContext(Dispatchers.IO) { api.deleteReport(id) }
                    }
                    else -> {
                        // Still uploading — drop locally and delete once the server accepts it.
                        discardAfterUpload += id
                    }
                }
                _state.update { s ->
                    s.copy(
                        busy = false,
                        editingId = null,
                        editingText = "",
                        editingIssueType = null,
                        editingValueKmh = null,
                        editingLengthM = 0,
                        editingLabel = "",
                        statusMessage = str(R.string.report_deleted),
                        recent = s.recent.filter { it.id != id },
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        busy = false,
                        statusMessage = e.message ?: str(R.string.report_delete_failed),
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
            lengthM = null,
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
            lengthM = if (kmh == 0) null else 0,
        )
    }

    /**
     * Long-press on a speed sign: queue the report and start an in-place length gesture.
     * Returns false if GPS/settings blocked the report (caller should abort the gesture).
     */
    fun beginLengthGesture(kmh: Int): Boolean {
        if (kmh == 0) return false
        if (_state.value.lengthGesture != null) return false
        val id =
            sendReport(
                issueType = "speed_limit",
                label = str(R.string.label_speed_kmh, kmh),
                valueKmh = kmh,
                lengthM = 0,
                returnLocalId = true,
            ) ?: return false
        _state.update {
            it.copy(lengthGesture = LengthGesture(reportId = id, kmh = kmh, lengthM = 0))
        }
        return true
    }

    fun updateLengthGesture(meters: Int) {
        val g = _state.value.lengthGesture ?: return
        val snapped =
            (meters.coerceIn(LENGTH_MIN_M, LENGTH_MAX_M) / LENGTH_STEP_M) * LENGTH_STEP_M
        if (snapped == g.lengthM) return
        _state.update { s ->
            s.copy(
                lengthGesture = g.copy(lengthM = snapped),
                recent =
                    s.recent.map { item ->
                        if (item.id != g.reportId) {
                            item
                        } else {
                            item.copy(
                                lengthM = snapped,
                                outbound = item.outbound?.copy(lengthM = snapped),
                            )
                        }
                    },
            )
        }
    }

    fun finishLengthGesture() {
        val g = _state.value.lengthGesture ?: return
        val item = _state.value.recent.firstOrNull { it.id == g.reportId }
        _state.update {
            it.copy(
                lengthGesture = null,
                statusMessage =
                    if (g.lengthM == 0) {
                        str(R.string.length_saved_unlimited, g.kmh)
                    } else {
                        str(R.string.length_saved_meters, g.kmh, g.lengthM)
                    },
            )
        }
        vibrate()
        if (item?.syncStatus == SyncStatus.Synced) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        api.patchReport(id = g.reportId, lengthM = g.lengthM)
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    fun cancelLengthGesture() {
        _state.update { it.copy(lengthGesture = null) }
    }

    fun reportGeneral() {
        sendReport(
            issueType = "general",
            label = str(R.string.label_general),
            valueKmh = null,
            lengthM = null,
        )
    }

    /**
     * @param returnLocalId when true, returns the queued local id (or null on failure)
     *   instead of Unit-style fire-and-forget.
     */
    private fun sendReport(
        issueType: String,
        label: String,
        valueKmh: Int?,
        lengthM: Int?,
        returnLocalId: Boolean = false,
    ): String? {
        val s = _state.value
        if (!s.settingsReady) {
            _state.update {
                it.copy(statusMessage = str(R.string.need_nick), showSettings = true)
            }
            return null
        }

        // Prefer bubble freeze (menu-open time); else live snapshot at tap.
        val fix =
            frozenFix
                ?: LiveLocation.snapshotForReport()
                ?: run {
                    _state.update { it.copy(statusMessage = str(R.string.no_gps)) }
                    return null
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
                lengthM = lengthM,
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
                issueType = issueType,
                valueKmh = valueKmh,
                lengthM = lengthM,
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

        scope.launch {
            val ok = uploadOne(localId, label, outbound, s.nick)
            if (ok) {
                // Network is up — push any earlier failures that piled up offline.
                flushFailedReports()
            }
        }
        return if (returnLocalId) localId else null
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
        // Prefer latest outbound from state (user may have adjusted length while pending).
        val latestOutbound =
            _state.value.recent.firstOrNull { it.id == localId }?.outbound ?: outbound
        return try {
            val created =
                withContext(Dispatchers.IO) {
                    api.createReport(
                        issueType = latestOutbound.issueType,
                        lon = latestOutbound.lon,
                        lat = latestOutbound.lat,
                        reporterNick = nick,
                        valueKmh = latestOutbound.valueKmh,
                        lengthM = latestOutbound.lengthM,
                        clientEventId = localId,
                        accuracyM = latestOutbound.accuracyM,
                    )
                }
            if (localId in discardAfterUpload) {
                discardAfterUpload.remove(localId)
                try {
                    withContext(Dispatchers.IO) { api.deleteReport(created.id) }
                } catch (_: Exception) {
                }
                _state.update { st ->
                    st.copy(
                        recent = st.recent.filter { it.id != localId && it.id != created.id },
                        editingId =
                            if (st.editingId == localId || st.editingId == created.id) {
                                null
                            } else {
                                st.editingId
                            },
                        editingText = if (st.editingId == localId) "" else st.editingText,
                        editingIssueType =
                            if (st.editingId == localId) null else st.editingIssueType,
                        editingValueKmh =
                            if (st.editingId == localId) null else st.editingValueKmh,
                        editingLengthM = if (st.editingId == localId) 0 else st.editingLengthM,
                        editingLabel = if (st.editingId == localId) "" else st.editingLabel,
                    )
                }
                return true
            }

            val localItem = _state.value.recent.firstOrNull { it.id == localId }
            val editingHere = _state.value.editingId == localId
            val gestureHere = _state.value.lengthGesture?.takeIf { it.reportId == localId }
            val finalDesc =
                when {
                    editingHere && _state.value.editingText.isNotBlank() ->
                        _state.value.editingText
                    !localItem?.description.isNullOrBlank() -> localItem?.description
                    else -> created.description
                }
            val finalLength =
                when {
                    latestOutbound.issueType != "speed_limit" ||
                        (latestOutbound.valueKmh ?: 0) == 0 -> null
                    gestureHere != null -> gestureHere.lengthM
                    editingHere -> _state.value.editingLengthM
                    else -> localItem?.lengthM ?: latestOutbound.lengthM ?: 0
                }

            // CREATE already sent lengthM; PATCH if description set or length diverged after POST started.
            val needDescPatch = !finalDesc.isNullOrBlank()
            val needLengthPatch =
                finalLength != null && finalLength != (latestOutbound.lengthM ?: 0)
            if (needDescPatch || needLengthPatch) {
                try {
                    withContext(Dispatchers.IO) {
                        api.patchReport(
                            id = created.id,
                            description = if (needDescPatch) finalDesc else null,
                            lengthM = if (needLengthPatch) finalLength else null,
                        )
                    }
                } catch (_: Exception) {
                }
            }

            _state.update { st ->
                st.copy(
                    statusMessage = str(R.string.sent, label),
                    editingId = if (st.editingId == localId) created.id else st.editingId,
                    lengthGesture =
                        st.lengthGesture?.let { g ->
                            if (g.reportId == localId) g.copy(reportId = created.id) else g
                        },
                    recent =
                        st.recent.map { item ->
                            if (item.id == localId) {
                                item.copy(
                                    id = created.id,
                                    description = finalDesc,
                                    createdAt = created.createdAt,
                                    syncStatus = SyncStatus.Synced,
                                    outbound = null,
                                    issueType = latestOutbound.issueType,
                                    valueKmh = latestOutbound.valueKmh,
                                    lengthM = finalLength,
                                )
                            } else {
                                item
                            }
                        },
                )
            }
            val after = LiveLocation.pointsSince(latestOutbound.fixTimeMs)
            val seed =
                dedupePoints(
                    latestOutbound.seedPoints +
                        LonLat(latestOutbound.lon, latestOutbound.lat) +
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
                                item.copy(syncStatus = SyncStatus.Failed, outbound = latestOutbound)
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
        if (reportId in suppressTrailIds) return
        val ctx = app
        val intent =
            Intent(ctx, LocationTrailService::class.java).apply {
                putExtra(LocationTrailService.EXTRA_REPORT_ID, reportId)
            }
        ctx.startForegroundService(intent)
    }

    private fun stopTrailService() {
        val intent =
            Intent(app, LocationTrailService::class.java).apply {
                action = LocationTrailService.ACTION_STOP
            }
        app.startService(intent)
    }

    private suspend fun uploadTrajectory(reportId: String, points: List<LonLat>) {
        if (reportId in suppressTrailIds) {
            suppressTrailIds.remove(reportId)
            return
        }
        if (points.size < 2) {
            _state.update { it.copy(statusMessage = str(R.string.trail_too_short)) }
            return
        }
        try {
            val heading = bearing(points.first(), points.last())
            withContext(Dispatchers.IO) { api.patchTrajectory(reportId, points, heading) }
            if (reportId in suppressTrailIds) {
                suppressTrailIds.remove(reportId)
                return
            }
            _state.update {
                it.copy(statusMessage = str(R.string.direction_saved, heading.toInt()))
            }
        } catch (e: Exception) {
            // Report already deleted (or never existed) — don't overwrite "deleted" status.
            if (reportId in suppressTrailIds || isReportGone(e)) {
                suppressTrailIds.remove(reportId)
                return
            }
            _state.update {
                it.copy(statusMessage = str(R.string.trail_upload_failed, e.message ?: ""))
            }
        }
    }

    private fun isReportGone(e: Exception): Boolean {
        val msg = e.message.orEmpty()
        return msg.contains("HTTP 404") || msg.contains("Report not found", ignoreCase = true)
    }

    private fun vibrate() {
        val ctx = app
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
