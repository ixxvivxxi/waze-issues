package by.ster.wazeissues.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import by.ster.wazeissues.WazeIssuesApp
import by.ster.wazeissues.bubble.BubbleExpandDirection
import kotlinx.coroutines.flow.StateFlow

/** Thin Activity-facing wrapper around the process-wide [ReportController]. */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val reports = WazeIssuesApp.get(app).reports

    val state: StateFlow<UiState> = reports.state

    init {
        reports.acquire()
    }

    override fun onCleared() {
        reports.release()
        super.onCleared()
    }

    fun dismissUpdate() = reports.dismissUpdate()

    fun downloadAndInstallUpdate() = reports.downloadAndInstallUpdate()

    fun openSettings(open: Boolean) = reports.openSettings(open)

    fun setLanguage(tag: String) = reports.setLanguage(tag)

    fun setBubbleExpand(direction: BubbleExpandDirection) =
        reports.setBubbleExpand(direction)

    fun setBubbleStartByDefault(enabled: Boolean) =
        reports.setBubbleStartByDefault(enabled)

    fun setBubbleLaunchWaze(enabled: Boolean) =
        reports.setBubbleLaunchWaze(enabled)

    fun saveSettings(nick: String, apiBase: String) = reports.saveSettings(nick, apiBase)

    fun openEdit(item: RecentItem) = reports.openEdit(item)

    fun setEditingText(text: String) = reports.setEditingText(text)

    fun setEditingLengthM(meters: Int) = reports.setEditingLengthM(meters)

    fun closeEdit() = reports.closeEdit()

    fun saveDescription() = reports.saveDescription()

    fun deleteEditingReport() = reports.deleteEditingReport()

    fun reportBump(add: Boolean) = reports.reportBump(add)

    fun reportSpeed(kmh: Int) = reports.reportSpeed(kmh)

    fun beginLengthGesture(kmh: Int): Boolean = reports.beginLengthGesture(kmh)

    fun updateLengthGesture(meters: Int) = reports.updateLengthGesture(meters)

    fun finishLengthGesture() = reports.finishLengthGesture()

    fun cancelLengthGesture() = reports.cancelLengthGesture()

    fun reportGeneral() = reports.reportGeneral()

    companion object {
        const val LENGTH_MIN_M = ReportController.LENGTH_MIN_M
        const val LENGTH_MAX_M = ReportController.LENGTH_MAX_M
        const val LENGTH_STEP_M = ReportController.LENGTH_STEP_M
    }
}
