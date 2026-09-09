package com.rallyhelper.data

import android.content.Context
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
    val overlayEnabled: Boolean = false,
    val mode: RuntimeMode = RuntimeMode.RADAR,
    val debugMode: DebugCaptureMode = DebugCaptureMode.FAILURES,
    val retentionDays: Int = 3,
    val delayMinSeconds: Int = 0,
    val delayMaxSeconds: Int = 0,
    val skipMin: Int = 0,
    val skipMax: Int = 0,
    val minimumFreeSlots: Int = 1,
    val safetyMarginSeconds: Int = 3,
    val calibrationProfile: String = "reference-1280x2800-v2",
    val captureLabArmed: Boolean = false,
)

class RadarSettingsStore(private val context: Context) {
    val settings: Flow<RadarSettings> = context.radarDataStore.data.map { values ->
        RadarSettings(
            selectedLevels = values[Keys.LEVELS]?.split(',')?.mapNotNull(String::toIntOrNull)?.toSet()
                ?: setOf(5, 10),
            soundEnabled = values[Keys.SOUND] ?: true,
            vibrationEnabled = values[Keys.VIBRATION] ?: true,
            overlayEnabled = values[Keys.OVERLAY] ?: false,
            mode = values[Keys.MODE]?.let { runCatching { RuntimeMode.valueOf(it) }.getOrNull() }
                ?: RuntimeMode.RADAR,
            debugMode = values[Keys.DEBUG]?.let { runCatching { DebugCaptureMode.valueOf(it) }.getOrNull() }
                ?: DebugCaptureMode.FAILURES,
            retentionDays = (values[Keys.RETENTION] ?: 3).coerceIn(1, 7),
            delayMinSeconds = (values[Keys.DELAY_MIN] ?: 0).coerceIn(0, MAX_DELAY_SECONDS),
            delayMaxSeconds = (values[Keys.DELAY_MAX] ?: 0).coerceIn(0, MAX_DELAY_SECONDS),
            skipMin = (values[Keys.SKIP_MIN] ?: 0).coerceIn(0, MAX_SKIP),
            skipMax = (values[Keys.SKIP_MAX] ?: 0).coerceIn(0, MAX_SKIP),
            minimumFreeSlots = (values[Keys.MINIMUM_FREE_SLOTS] ?: 1).coerceAtLeast(1),
            safetyMarginSeconds = (values[Keys.SAFETY_MARGIN] ?: 3).coerceIn(0, MAX_SAFETY_MARGIN_SECONDS),
            calibrationProfile = values[Keys.CALIBRATION] ?: "reference-1280x2800-v2",
            captureLabArmed = values[Keys.CAPTURE_LAB_ARMED] ?: false,
        ).normalized()
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

    suspend fun setOverlayEnabled(enabled: Boolean) {
        context.radarDataStore.edit { it[Keys.OVERLAY] = enabled }
    }

    suspend fun setDelayRange(minSeconds: Int, maxSeconds: Int) {
        val min = minSeconds.coerceIn(0, MAX_DELAY_SECONDS)
        val max = maxSeconds.coerceIn(min, MAX_DELAY_SECONDS)
        context.radarDataStore.edit {
            it[Keys.DELAY_MIN] = min
            it[Keys.DELAY_MAX] = max
        }
    }

    suspend fun setSkipRange(min: Int, max: Int) {
        val safeMin = min.coerceIn(0, MAX_SKIP)
        val safeMax = max.coerceIn(safeMin, MAX_SKIP)
        context.radarDataStore.edit {
            it[Keys.SKIP_MIN] = safeMin
            it[Keys.SKIP_MAX] = safeMax
        }
    }

    suspend fun setMinimumFreeSlots(value: Int) {
        context.radarDataStore.edit { it[Keys.MINIMUM_FREE_SLOTS] = value.coerceAtLeast(1) }
    }

    suspend fun setSafetyMarginSeconds(value: Int) {
        context.radarDataStore.edit { it[Keys.SAFETY_MARGIN] = value.coerceIn(0, MAX_SAFETY_MARGIN_SECONDS) }
    }

    suspend fun setCaptureLabArmed(armed: Boolean) {
        context.radarDataStore.edit { it[Keys.CAPTURE_LAB_ARMED] = armed }
    }

    private object Keys {
        val LEVELS = stringPreferencesKey("selected_levels")
        val SOUND = booleanPreferencesKey("sound_enabled")
        val VIBRATION = booleanPreferencesKey("vibration_enabled")
        val OVERLAY = booleanPreferencesKey("overlay_enabled")
        val MODE = stringPreferencesKey("runtime_mode")
        val DEBUG = stringPreferencesKey("debug_capture_mode")
        val RETENTION = intPreferencesKey("debug_retention_days")
        val DELAY_MIN = intPreferencesKey("auto_delay_min_seconds")
        val DELAY_MAX = intPreferencesKey("auto_delay_max_seconds")
        val SKIP_MIN = intPreferencesKey("auto_skip_min")
        val SKIP_MAX = intPreferencesKey("auto_skip_max")
        val MINIMUM_FREE_SLOTS = intPreferencesKey("minimum_free_slots")
        val SAFETY_MARGIN = intPreferencesKey("safety_margin_seconds")
        val CALIBRATION = stringPreferencesKey("calibration_profile")
        val CAPTURE_LAB_ARMED = booleanPreferencesKey("capture_lab_armed")
    }

    companion object {
        const val MAX_DELAY_SECONDS = 120
        const val MAX_SKIP = 20
        const val MAX_SAFETY_MARGIN_SECONDS = 60
    }
}

private fun RadarSettings.normalized(): RadarSettings {
    val minDelay = delayMinSeconds.coerceIn(0, RadarSettingsStore.MAX_DELAY_SECONDS)
    val minSkip = skipMin.coerceIn(0, RadarSettingsStore.MAX_SKIP)
    return copy(
        selectedLevels = selectedLevels.filterTo(sortedSetOf()) { it > 0 },
        retentionDays = retentionDays.coerceIn(1, 7),
        delayMinSeconds = minDelay,
        delayMaxSeconds = delayMaxSeconds.coerceIn(minDelay, RadarSettingsStore.MAX_DELAY_SECONDS),
        skipMin = minSkip,
        skipMax = skipMax.coerceIn(minSkip, RadarSettingsStore.MAX_SKIP),
        minimumFreeSlots = minimumFreeSlots.coerceAtLeast(1),
        safetyMarginSeconds = safetyMarginSeconds.coerceIn(0, RadarSettingsStore.MAX_SAFETY_MARGIN_SECONDS),
    )
}
