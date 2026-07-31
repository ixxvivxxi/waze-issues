package by.ster.wazeissues.bubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import by.ster.wazeissues.MainActivity
import by.ster.wazeissues.R
import by.ster.wazeissues.WazeIssuesApp
import by.ster.wazeissues.ui.ReportController

/**
 * Hub lives in its own overlay window and never moves on expand — only the label
 * flips WI ↔ ×. Action icons / speed panel are separate windows beside the hub.
 */
class BubbleOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private lateinit var reports: ReportController

    private var hubView: ComposeView? = null
    private var hubParams: WindowManager.LayoutParams? = null
    private var actionsView: ComposeView? = null
    private var actionsParams: WindowManager.LayoutParams? = null
    private var speedView: ComposeView? = null
    private var speedParams: WindowManager.LayoutParams? = null

    private var phase by mutableStateOf(BubblePhase.Collapsed)
    private var acquired = false

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        reports = WazeIssuesApp.get(application).reports
        reports.acquire()
        acquired = true
        startAsForeground()
        attachHub()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        detachAll()
        reports.clearFrozenFix()
        if (acquired) {
            reports.release()
            acquired = false
        }
        super.onDestroy()
    }

    private fun startAsForeground() {
        val channelId = "waze_issues_bubble"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                channelId,
                getString(R.string.bubble_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val openApp =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, BubbleOverlayService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification: Notification =
            NotificationCompat.Builder(this, channelId)
                .setContentTitle(getString(R.string.bubble_notification_title))
                .setContentText(getString(R.string.bubble_notification_text))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(openApp)
                .addAction(0, getString(R.string.bubble_stop), stop)
                .setOngoing(true)
                .build()
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun baseParams(widthPx: Int, heightPx: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            widthPx,
            heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

    private fun <T : android.view.View> T.installComposeTree() {
        setViewTreeLifecycleOwner(this@BubbleOverlayService)
        setViewTreeSavedStateRegistryOwner(this@BubbleOverlayService)
    }

    private fun attachHub() {
        val metrics = currentMetrics()
        val density = resources.displayMetrics.density
        val hubWindowDp = metrics.hubDp + 2 * metrics.shadowPadDp
        val hubPx = (hubWindowDp * density).toInt()
        val params =
            baseParams(hubPx, hubPx).apply {
                x = (16 * density).toInt()
                y = (120 * density).toInt()
            }
        hubParams = params
        val hubDp = metrics.hubDp
        val view =
            ComposeView(this).apply {
                installComposeTree()
                setContent {
                    BubbleHubOverlay(
                        reports = reports,
                        phase = phase,
                        hubDp = hubDp,
                        onOpen = {
                            if (reports.captureFrozenFix()) {
                                applyPhase(BubblePhase.ArcMenu)
                            }
                        },
                        onCollapse = { applyPhase(BubblePhase.Collapsed) },
                        onDrag = { dx, dy -> dragHub(dx, dy) },
                    )
                }
            }
        hubView = view
        windowManager.addView(view, params)
    }

    private fun detachAll() {
        removeActions()
        removeSpeed()
        hubView?.let { windowManager.removeView(it) }
        hubView = null
        hubParams = null
    }

    private fun currentMetrics(): BubbleMetrics =
        BubbleMetrics.from(resources, reports.state.value.bubbleExpand)

    private fun applyPhase(next: BubblePhase) {
        if (next == BubblePhase.Collapsed) {
            reports.clearFrozenFix()
            if (reports.state.value.lengthGesture != null) {
                reports.cancelLengthGesture()
            }
        }
        phase = next
        // Hub window size/position never changes here — only Compose label updates via [phase].
        when (next) {
            BubblePhase.Collapsed -> {
                removeActions()
                removeSpeed()
            }
            BubblePhase.ArcMenu -> {
                removeSpeed()
                showActions()
            }
            BubblePhase.SpeedList -> {
                removeActions()
                showSpeed()
            }
        }
    }

    private fun showActions() {
        val hub = hubParams ?: return
        val direction = reports.state.value.bubbleExpand
        val metrics = currentMetrics()
        val density = resources.displayMetrics.density
        val w = (metrics.actionsWidthDp * density).toInt().coerceAtLeast(1)
        val h = (metrics.actionsHeightDp * density).toInt().coerceAtLeast(1)
        val gap = (metrics.gapDp * density).toInt()
        val (ax, ay) = actionsOrigin(hub, direction, w, h, gap)
        val existing = actionsParams
        if (existing != null && actionsView != null) {
            existing.width = w
            existing.height = h
            existing.x = ax
            existing.y = ay
            clampToScreen(existing)
            windowManager.updateViewLayout(actionsView, existing)
            return
        }
        val params =
            baseParams(w, h).apply {
                x = ax
                y = ay
            }
        clampToScreen(params)
        actionsParams = params
        val view =
            ComposeView(this).apply {
                installComposeTree()
                setContent {
                    BubbleActionsOverlay(
                        reports = reports,
                        direction = reports.state.value.bubbleExpand,
                        metrics = currentMetrics(),
                        onGeneral = {
                            reports.reportGeneral()
                            applyPhase(BubblePhase.Collapsed)
                        },
                        onBumpAdd = {
                            reports.reportBump(true)
                            applyPhase(BubblePhase.Collapsed)
                        },
                        onBumpRemove = {
                            reports.reportBump(false)
                            applyPhase(BubblePhase.Collapsed)
                        },
                        onSpeed = { applyPhase(BubblePhase.SpeedList) },
                    )
                }
            }
        actionsView = view
        windowManager.addView(view, params)
    }

    private fun removeActions() {
        actionsView?.let { runCatching { windowManager.removeView(it) } }
        actionsView = null
        actionsParams = null
    }

    private fun showSpeed() {
        val hub = hubParams ?: return
        val metrics = currentMetrics()
        val density = resources.displayMetrics.density
        val w = (metrics.speedWidthDp * density).toInt().coerceAtLeast(1)
        val h = (metrics.speedHeightDp * density).toInt().coerceAtLeast(1)
        val params =
            baseParams(w, h).apply {
                x = hub.x + hub.width / 2 - w / 2
                y = hub.y + hub.height / 2 - h / 2
            }
        clampToScreen(params)
        val existing = speedParams
        if (existing != null && speedView != null) {
            existing.width = w
            existing.height = h
            existing.x = params.x
            existing.y = params.y
            windowManager.updateViewLayout(speedView, existing)
            return
        }
        speedParams = params
        val view =
            ComposeView(this).apply {
                installComposeTree()
                setContent {
                    BubbleSpeedOverlay(
                        reports = reports,
                        onBack = { applyPhase(BubblePhase.ArcMenu) },
                        onCollapse = { applyPhase(BubblePhase.Collapsed) },
                        onOpenApp = { openFullApp() },
                    )
                }
            }
        speedView = view
        windowManager.addView(view, params)
    }

    private fun removeSpeed() {
        speedView?.let { runCatching { windowManager.removeView(it) } }
        speedView = null
        speedParams = null
    }

    /** Visual hub bounds (window includes shadow pad around the circle). */
    private fun visualHub(hub: WindowManager.LayoutParams): IntArray {
        val pad = (currentMetrics().shadowPadDp * resources.displayMetrics.density).toInt()
        return intArrayOf(
            hub.x + pad,
            hub.y + pad,
            hub.width - 2 * pad,
            hub.height - 2 * pad,
        )
    }

    private fun actionsOrigin(
        hub: WindowManager.LayoutParams,
        direction: BubbleExpandDirection,
        actionsW: Int,
        actionsH: Int,
        gap: Int,
    ): Pair<Int, Int> {
        val bounds = visualHub(hub)
        val x = bounds[0]
        val y = bounds[1]
        val w = bounds[2]
        val h = bounds[3]
        val pad = (currentMetrics().shadowPadDp * resources.displayMetrics.density).toInt()
        // Position so the visual circles (inset by shadow pad) sit `gap` from the hub circle.
        return when (direction) {
            BubbleExpandDirection.Up ->
                x + (w - actionsW) / 2 to y - gap - actionsH + pad
            BubbleExpandDirection.Down ->
                x + (w - actionsW) / 2 to y + h + gap - pad
            BubbleExpandDirection.Left ->
                x - gap - actionsW + pad to y + (h - actionsH) / 2
            BubbleExpandDirection.Right ->
                x + w + gap - pad to y + (h - actionsH) / 2
        }
    }

    private fun dragHub(dx: Float, dy: Float) {
        val params = hubParams ?: return
        val view = hubView ?: return
        params.x += dx.toInt()
        params.y += dy.toInt()
        clampToScreen(params)
        windowManager.updateViewLayout(view, params)
        // Keep satellite windows glued to the hub.
        when (phase) {
            BubblePhase.ArcMenu -> showActions()
            BubblePhase.SpeedList -> showSpeed()
            BubblePhase.Collapsed -> Unit
        }
    }

    private fun clampToScreen(params: WindowManager.LayoutParams) {
        val dm = resources.displayMetrics
        val maxX = (dm.widthPixels - params.width).coerceAtLeast(0)
        val maxY = (dm.heightPixels - params.height).coerceAtLeast(0)
        params.x = params.x.coerceIn(0, maxX)
        params.y = params.y.coerceIn(0, maxY)
    }

    private fun openFullApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
        stopSelf()
    }

    companion object {
        const val ACTION_STOP = "by.ster.wazeissues.STOP_BUBBLE"
        private const val NOTIF_ID = 43
    }
}

enum class BubblePhase {
    Collapsed,
    ArcMenu,
    SpeedList,
}
