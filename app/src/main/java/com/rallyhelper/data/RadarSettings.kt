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
import radar.vision.RefreshMode

private val Context.radarDataStore by preferencesDataStore("radar_settings")

enum class DebugCaptureMode { OFF, FAILURES, ALL_TARGETS }
enum class AlertSoundMode { SYSTEM, MEDIA }

data class RadarSettings(
    val selectedLevels: Set<Int> = setOf(5, 10),
    val soundEnabled: Boolean = true,
    val alertSoundMode: AlertSoundMode = AlertSoundMode.SYSTEM,
    val vibrationEnabled: Boolean = true,
    val squadPriority: List<Int> = listOf(1, 2, 3),
    val allowReturningSquads: Boolean = true,
    val sendWhenTravelUnknown: Boolean = false,
    val overlayEnabled: Boolean = false,
    val mode: RuntimeMode = RuntimeMode.RADAR,
    val refreshMode: RefreshMode = RefreshMode.OFF,
    val debugMode: DebugCaptureMode = DebugCaptureMode.FAILURES,
    val retentionDays: Int = 3,
    val delayMinSeconds: Int = 0,
    val delayMaxSeconds: Int = 0,
    val skipMin: Int = 0,
    val skipMax: Int = 0,
    val safetyMarginSeconds: Int = 3,
    val calibrationProfile: String = "reference-1280x2800-v2",
    val captureLabArmed: Boolean = false,
    val evidenceCollectorEnabled: Boolean = false,
)

class RadarSettingsStore(private val context: Context) {
    val settings: Flow<RadarSettings> = context.radarDataStore.data.map { values ->
        RadarSettings(
            selectedLevels = values[Keys.LEVELS]?.split(',')?.mapNotNull(String::toIntOrNull)?.toSet()
                ?: setOf(5, 10),
            soundEnabled = values[Keys.SOUND] ?: true,
            alertSoundMode = values[Keys.SOUND_MODE]?.let { runCatching { AlertSoundMode.valueOf(it) }.getOrNull() }
                ?: AlertSoundMode.SYSTEM,
            vibrationEnabled = values[Keys.VIBRATION] ?: true,
            squadPriority = values[Keys.SQUAD_PRIORITY]?.split(',')?.mapNotNull(String::toIntOrNull)
                ?.takeIf { it.toSet() == setOf(1, 2, 3) } ?: listOf(1, 2, 3),
            allowReturningSquads = values[Keys.ALLOW_RETURNING] ?: true,
            sendWhenTravelUnknown = values[Keys.SEND_UNKNOWN_TRAVEL] ?: false,
            overlayEnabled = values[Keys.OVERLAY] ?: false,
            mode = values[Keys.MODE]?.let { runCatching { RuntimeMode.valueOf(it) }.getOrNull() }
                ?: RuntimeMode.RADAR,
            refreshMode = values[Keys.REFRESH_MODE]?.let { runCatching { RefreshMode.valueOf(it) }.getOrNull() }
                ?: RefreshMode.OFF,
            debugMode = values[Keys.DEBUG]?.let { runCatching { DebugCaptureMode.valueOf(it) }.getOrNull() }
                ?: DebugCaptureMode.FAILURES,
            retentionDays = (values[Keys.RETENTION] ?: 3).coerceIn(1, 7),
            delayMinSeconds = (values[Keys.DELAY_MIN] ?: 0).coerceIn(0, MAX_DELAY_SECONDS),
            delayMaxSeconds = (values[Keys.DELAY_MAX] ?: 0).coerceIn(0, MAX_DELAY_SECONDS),
            skipMin = (values[Keys.SKIP_MIN] ?: 0).coerceIn(0, MAX_SKIP),
            skipMax = (values[Keys.SKIP_MAX] ?: 0).coerceIn(0, MAX_SKIP),
            safetyMarginSeconds = (values[Keys.SAFETY_MARGIN] ?: 3).coerceIn(0, MAX_SAFETY_MARGIN_SECONDS),
            calibrationProfile = values[Keys.CALIBRATION] ?: "reference-1280x2800-v2",
            captureLabArmed = values[Keys.CAPTURE_LAB_ARMED] ?: false,
            evidenceCollectorEnabled = values[Keys.EVIDENCE_COLLECTOR] ?: false,
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

    suspend fun setRefreshMode(mode: RefreshMode) {
        context.radarDataStore.edit { it[Keys.REFRESH_MODE] = mode.name }
    }

    suspend fun setSoundEnabled(enabled: Boolean) {
        context.radarDataStore.edit { it[Keys.SOUND] = enabled }
    }

    suspend fun setAlertSoundMode(mode: AlertSoundMode) {
        context.radarDataStore.edit { it[Keys.SOUND_MODE] = mode.name }
    }

    suspend fun setSquadPriority(priority: List<Int>) {
        require(priority.toSet() == setOf(1, 2, 3))
        context.radarDataStore.edit { it[Keys.SQUAD_PRIORITY] = priority.joinToString(",") }
    }

    suspend fun setAllowReturningSquads(enabled: Boolean) {
        context.radarDataStore.edit { it[Keys.ALLOW_RETURNING] = enabled }
    }

    suspend fun setSendWhenTravelUnknown(enabled: Boolean) {
        context.radarDataStore.edit { it[Keys.SEND_UNKNOWN_TRAVEL] = enabled }
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

    suspend fun setSafetyMarginSeconds(value: Int) {
        context.radarDataStore.edit { it[Keys.SAFETY_MARGIN] = value.coerceIn(0, MAX_SAFETY_MARGIN_SECONDS) }
    }

    suspend fun setCaptureLabArmed(armed: Boolean) {
        context.radarDataStore.edit { it[Keys.CAPTURE_LAB_ARMED] = armed }
    }

    suspend fun setEvidenceCollectorEnabled(enabled: Boolean) {
        context.radarDataStore.edit { it[Keys.EVIDENCE_COLLECTOR] = enabled }
    }

    private object Keys {
        val LEVELS = stringPreferencesKey("selected_levels")
        val SOUND = booleanPreferencesKey("sound_enabled")
        val SOUND_MODE = stringPreferencesKey("alert_sound_mode")
        val VIBRATION = booleanPreferencesKey("vibration_enabled")
        val SQUAD_PRIORITY = stringPreferencesKey("squad_priority")
        val ALLOW_RETURNING = booleanPreferencesKey("allow_returning_squads")
        val SEND_UNKNOWN_TRAVEL = booleanPreferencesKey("send_when_travel_unknown")
        val OVERLAY = booleanPreferencesKey("overlay_enabled")
        val MODE = stringPreferencesKey("runtime_mode")
        val REFRESH_MODE = stringPreferencesKey("refresh_mode")
        val DEBUG = stringPreferencesKey("debug_capture_mode")
        val RETENTION = intPreferencesKey("debug_retention_days")
        val DELAY_MIN = intPreferencesKey("auto_delay_min_seconds")
        val DELAY_MAX = intPreferencesKey("auto_delay_max_seconds")
        val SKIP_MIN = intPreferencesKey("auto_skip_min")
        val SKIP_MAX = intPreferencesKey("auto_skip_max")
        val SAFETY_MARGIN = intPreferencesKey("safety_margin_seconds")
        val CALIBRATION = stringPreferencesKey("calibration_profile")
        val CAPTURE_LAB_ARMED = booleanPreferencesKey("capture_lab_armed")
        val EVIDENCE_COLLECTOR = booleanPreferencesKey("evidence_collector_enabled")
    }

    companion object {
        const val MAX_DELAY_SECONDS = 30
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
        safetyMarginSeconds = safetyMarginSeconds.coerceIn(0, RadarSettingsStore.MAX_SAFETY_MARGIN_SECONDS),
    )
}
