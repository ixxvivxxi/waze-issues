package by.ster.wazeissues.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import by.ster.wazeissues.BuildConfig
import by.ster.wazeissues.bubble.BubbleExpandDirection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private val nickKey = stringPreferencesKey("nick")
    private val apiBaseKey = stringPreferencesKey("api_base")
    private val bubbleExpandKey = stringPreferencesKey("bubble_expand")
    private val bubbleXDpKey = floatPreferencesKey("bubble_x_dp")
    private val bubbleYDpKey = floatPreferencesKey("bubble_y_dp")
    private val bubbleStartByDefaultKey = booleanPreferencesKey("bubble_start_by_default")
    private val bubbleLaunchWazeKey = booleanPreferencesKey("bubble_launch_waze")

    val nick: Flow<String> = context.dataStore.data.map { it[nickKey].orEmpty() }
    val apiBase: Flow<String> =
        context.dataStore.data.map {
            it[apiBaseKey]?.takeIf { v -> v.isNotBlank() } ?: BuildConfig.DEFAULT_API_BASE
        }
    val bubbleExpand: Flow<BubbleExpandDirection> =
        context.dataStore.data.map { BubbleExpandDirection.fromStored(it[bubbleExpandKey]) }
    val bubbleXDp: Flow<Float?> = context.dataStore.data.map { it[bubbleXDpKey] }
    val bubbleYDp: Flow<Float?> = context.dataStore.data.map { it[bubbleYDpKey] }
    val bubbleStartByDefault: Flow<Boolean> =
        context.dataStore.data.map { it[bubbleStartByDefaultKey] ?: false }
    val bubbleLaunchWaze: Flow<Boolean> =
        context.dataStore.data.map { it[bubbleLaunchWazeKey] ?: false }

    suspend fun setNick(value: String) {
        context.dataStore.edit { it[nickKey] = value.trim() }
    }

    suspend fun setApiBase(value: String) {
        context.dataStore.edit { it[apiBaseKey] = value.trim().trimEnd('/') }
    }

    suspend fun setBubbleExpand(direction: BubbleExpandDirection) {
        context.dataStore.edit { it[bubbleExpandKey] = direction.name }
    }

    suspend fun setBubblePosition(xDp: Float, yDp: Float) {
        context.dataStore.edit {
            it[bubbleXDpKey] = xDp
            it[bubbleYDpKey] = yDp
        }
    }

    suspend fun setBubbleStartByDefault(enabled: Boolean) {
        context.dataStore.edit { it[bubbleStartByDefaultKey] = enabled }
    }

    suspend fun setBubbleLaunchWaze(enabled: Boolean) {
        context.dataStore.edit { it[bubbleLaunchWazeKey] = enabled }
    }
}
