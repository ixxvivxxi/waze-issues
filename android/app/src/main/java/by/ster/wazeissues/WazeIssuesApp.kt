package by.ster.wazeissues

import android.app.Application
import by.ster.wazeissues.ui.ReportController

class WazeIssuesApp : Application() {
    lateinit var reports: ReportController
        private set

    override fun onCreate() {
        super.onCreate()
        reports = ReportController(this)
    }

    companion object {
        fun get(app: Application): WazeIssuesApp = app as WazeIssuesApp
    }
}
