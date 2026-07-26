package by.ster.wazeissues.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import by.ster.wazeissues.data.LonLat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

data class GpsSample(
    val lon: Double,
    val lat: Double,
    /** Horizontal accuracy in meters; null if unknown. */
    val accuracyM: Float?,
    val timeMs: Long,
    val bearingDeg: Float? = null,
) {
    fun toLonLat(): LonLat = LonLat(lon, lat)
}

/**
 * Continuous high-accuracy GPS while the reporter UI is active.
 * Snapshots at tap time avoid the delay of getCurrentLocation() (which caused
 * reports to land tens of meters past the real spot).
 */
object LiveLocation {
    private const val MAX_SAMPLES = 90
    /** Prefer fixes newer than this for reporting. */
    const val MAX_FIX_AGE_MS = 2_500L
    /** Soft warning threshold shown in UI. */
    const val GOOD_ACCURACY_M = 15f

    private val _latest = MutableStateFlow<GpsSample?>(null)
    val latest: StateFlow<GpsSample?> = _latest.asStateFlow()

    private val ring = ArrayDeque<GpsSample>(MAX_SAMPLES)
    private var started = false
    private var fused: com.google.android.gms.location.FusedLocationProviderClient? = null

    private val callback =
        object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) {
                    accept(loc)
                }
                result.lastLocation?.let { accept(it) }
            }
        }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(context: Context) {
        if (started) return
        val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)
        fused = client
        val req =
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 500L)
                .setMinUpdateIntervalMillis(200L)
                .setMinUpdateDistanceMeters(0f)
                .setWaitForAccurateLocation(false)
                .setMaxUpdates(Int.MAX_VALUE)
                .build()
        client.requestLocationUpdates(req, callback, Looper.getMainLooper())
        started = true
        // Warm with last known if available (marked stale via age).
        client.lastLocation.addOnSuccessListener { loc ->
            if (loc != null && _latest.value == null) accept(loc)
        }
    }

    @Synchronized
    fun stop() {
        if (!started) return
        fused?.removeLocationUpdates(callback)
        fused = null
        started = false
    }

    @Synchronized
    private fun accept(loc: Location) {
        val sample =
            GpsSample(
                lon = loc.longitude,
                lat = loc.latitude,
                accuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
                timeMs = loc.elapsedRealtimeNanos / 1_000_000L,
                bearingDeg = if (loc.hasBearing()) loc.bearing else null,
            )
        // Prefer more accurate / fresher samples; ignore much worse accuracy if we have a good one.
        val cur = _latest.value
        if (cur != null) {
            val ageCur = sample.timeMs - cur.timeMs
            val accNew = sample.accuracyM ?: 999f
            val accCur = cur.accuracyM ?: 999f
            if (ageCur < 800 && accNew > accCur * 1.8f && accNew > 25f) {
                // skip noisy jump
                return
            }
        }
        _latest.value = sample
        ring.addLast(sample)
        while (ring.size > MAX_SAMPLES) ring.removeFirst()
    }

    /** Best fix for a report tap: must be reasonably fresh. */
    @Synchronized
    fun snapshotForReport(nowElapsedMs: Long = android.os.SystemClock.elapsedRealtime()): GpsSample? {
        val s = _latest.value ?: return null
        if (nowElapsedMs - s.timeMs > MAX_FIX_AGE_MS) return null
        return s
    }

    /** Points from the last [maxAgeMs], oldest first (for heading seed). */
    @Synchronized
    fun recentPoints(maxAgeMs: Long = 12_000L): List<LonLat> {
        val latest = _latest.value ?: return emptyList()
        val cutoff = latest.timeMs - maxAgeMs
        return ring.filter { it.timeMs >= cutoff }.map { it.toLonLat() }
    }

    @Synchronized
    fun pointsSince(fromElapsedMs: Long): List<LonLat> {
        return ring.filter { it.timeMs >= fromElapsedMs }.map { it.toLonLat() }
    }
}
