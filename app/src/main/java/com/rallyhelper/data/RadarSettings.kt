package com.rallyhelper.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import radar.vision.RuntimeMode

private val Context.radarDataStore by preferencesDataStore("radar_settings")

enum class DebugCaptureMode { OFF, FAILURES, ALL_TARGETS }

data class RadarSettings(
    val selectedLevels: Set<Int> = setOf(5, 10),
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val mode: RuntimeMode = RuntimeMode.RADAR,
    val debugMode: DebugCaptureMode = DebugCaptureMode.FAILURES,
    val retentionDays: Int = 3,
    val calibrationProfile: String = "reference-1280x2800-v2",
)

class RadarSettingsStore(private val context: Context) {
    val settings: Flow<RadarSettings> = context.radarDataStore.data.map { values ->
        RadarSettings(
            selectedLevels = values[Keys.LEVELS]?.split(',')?.mapNotNull(String::toIntOrNull)?.toSet()
                ?: setOf(5, 10),
            soundEnabled = values[Keys.SOUND] ?: true,
            vibrationEnabled = values[Keys.VIBRATION] ?: true,
            mode = values[Keys.MODE]?.let { runCatching { RuntimeMode.valueOf(it) }.getOrNull() }
                ?: RuntimeMode.RADAR,
            debugMode = values[Keys.DEBUG]?.let(DebugCaptureMode::valueOf) ?: DebugCaptureMode.FAILURES,
            retentionDays = values[Keys.RETENTION] ?: 3,
            calibrationProfile = values[Keys.CALIBRATION] ?: "reference-1280x2800-v2",
        )
    }

    suspend fun setDebugPolicy(mode: DebugCaptureMode, retentionDays: Int) {
        context.radarDataStore.edit { values ->
            values[Keys.DEBUG] = mode.name
            values[Keys.RETENTION] = retentionDays.coerceIn(1, 7)
        }
    }

    suspend fun setSelectedLevels(levels: Set<Int>) {
        context.radarDataStore.edit { it[Keys.LEVELS] = levels.sorted().joinToString(",") }
    }

    suspend fun setMode(mode: RuntimeMode) {
        context.radarDataStore.edit { it[Keys.MODE] = mode.name }
    }

    suspend fun setSoundEnabled(enabled: Boolean) {
        context.radarDataStore.edit { it[Keys.SOUND] = enabled }
    }

    suspend fun setVibrationEnabled(enabled: Boolean) {
        context.radarDataStore.edit { it[Keys.VIBRATION] = enabled }
    }

    private object Keys {
        val LEVELS = stringPreferencesKey("selected_levels")
        val SOUND = booleanPreferencesKey("sound_enabled")
        val VIBRATION = booleanPreferencesKey("vibration_enabled")
        val MODE = stringPreferencesKey("runtime_mode")
        val DEBUG = stringPreferencesKey("debug_capture_mode")
        val RETENTION = intPreferencesKey("debug_retention_days")
        val CALIBRATION = stringPreferencesKey("calibration_profile")
    }
}
