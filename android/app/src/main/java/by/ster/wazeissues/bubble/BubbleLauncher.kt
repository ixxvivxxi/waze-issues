package by.ster.wazeissues.bubble

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import by.ster.wazeissues.R
import by.ster.wazeissues.WazeIssuesApp

object BubbleLauncher {
    fun canDrawOverlays(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    /** Opens the special overlay toggle (may show “App was denied access” until restricted settings are allowed). */
    fun openOverlaySettings(context: Context) {
        val intent =
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Opens App info — on Android 13+ sideloaded APKs the user must tap ⋮ →
     * “Allow restricted settings” before overlay can be enabled.
     */
    fun openAppInfo(context: Context) {
        val intent =
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * @return true if the bubble service was started.
     * If overlay permission is missing, returns false so the UI can show instructions
     * (does not auto-jump to Settings).
     */
    fun start(context: Context): Boolean {
        val appCtx = context.applicationContext
        if (!canDrawOverlays(appCtx)) {
            return false
        }
        val reports = WazeIssuesApp.get(appCtx as Application).reports
        if (!reports.state.value.settingsReady) {
            Toast.makeText(appCtx, R.string.need_nick, Toast.LENGTH_LONG).show()
            return false
        }
        context.startForegroundService(Intent(context, BubbleOverlayService::class.java))
        return true
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, BubbleOverlayService::class.java))
    }
}
