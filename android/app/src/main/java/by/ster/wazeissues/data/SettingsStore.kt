package by.ster.wazeissues.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import by.ster.wazeissues.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private val nickKey = stringPreferencesKey("nick")
    private val apiKeyKey = stringPreferencesKey("api_key")
    private val apiBaseKey = stringPreferencesKey("api_base")

    val nick: Flow<String> = context.dataStore.data.map { it[nickKey].orEmpty() }
    val apiKey: Flow<String> = context.dataStore.data.map { it[apiKeyKey].orEmpty() }
    val apiBase: Flow<String> =
        context.dataStore.data.map {
            it[apiBaseKey]?.takeIf { v -> v.isNotBlank() } ?: BuildConfig.DEFAULT_API_BASE
        }

    suspend fun setNick(value: String) {
        context.dataStore.edit { it[nickKey] = value.trim() }
    }

    suspend fun setApiKey(value: String) {
        context.dataStore.edit { it[apiKeyKey] = value.trim() }
    }

    suspend fun setApiBase(value: String) {
        context.dataStore.edit { it[apiBaseKey] = value.trim().trimEnd('/') }
    }
}
