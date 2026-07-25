package by.ster.wazeissues.location

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import by.ster.wazeissues.R
import by.ster.wazeissues.data.LonLat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LocationTrailService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val points = mutableListOf<LonLat>()
    private var reportId: String? = null

    private val callback =
        object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                points += LonLat(loc.longitude, loc.latitude)
                TrailBus.publish(points.toList())
            }
        }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                reportId = intent?.getStringExtra(EXTRA_REPORT_ID)
                startForeground(NOTIF_ID, buildNotification())
                points.clear()
                val req =
                    LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                        .setMinUpdateIntervalMillis(500L)
                        .setWaitForAccurateLocation(false)
                        .build()
                fused.requestLocationUpdates(req, callback, mainLooper)
                scope.launch {
                    delay(TRAIL_MS)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        val id = reportId
        if (id != null && points.size >= 2) {
            TrailBus.finish(id, points.toList())
        } else if (id != null) {
            TrailBus.finish(id, points.toList())
        }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val channelId = "waze_issues_trail"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                channelId,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "by.ster.wazeissues.STOP_TRAIL"
        const val EXTRA_REPORT_ID = "report_id"
        const val TRAIL_MS = 20_000L
        private const val NOTIF_ID = 42
    }
}

object TrailBus {
    @Volatile
    var onFinished: ((reportId: String, points: List<LonLat>) -> Unit)? = null

    fun publish(@Suppress("UNUSED_PARAMETER") points: List<LonLat>) {}

    fun finish(reportId: String, points: List<LonLat>) {
        onFinished?.invoke(reportId, points)
    }
}
