package com.rallyhelper.debug

import android.content.Context

enum class GuidedValidationStatus {
    NOT_COLLECTED,
    RUNNING,
    AWAITING_CONFIRMATION,
    COLLECTED,
    REPLAY_PASS,
    REPLAY_FAIL,
    NOT_OBSERVED,
}

data class GuidedValidationCase(
    val id: String,
    val title: String,
    val captureLabel: CaptureLabLabel,
)

data class ActiveGuidedValidation(
    val case: GuidedValidationCase,
    val startedAtMonotonicMs: Long,
)

class GuidedValidationStore(context: Context) {
    private val preferences = context.getSharedPreferences("guided_validation", Context.MODE_PRIVATE)

    fun status(case: GuidedValidationCase): GuidedValidationStatus = preferences
        .getString("status.${case.id}", null)
        ?.let { runCatching { GuidedValidationStatus.valueOf(it) }.getOrNull() }
        ?: GuidedValidationStatus.NOT_COLLECTED

    fun start(case: GuidedValidationCase, nowMonotonicMs: Long) {
        preferences.edit()
            .putString(ACTIVE_CASE, case.id)
            .putLong(ACTIVE_STARTED_AT, nowMonotonicMs)
            .putString("status.${case.id}", GuidedValidationStatus.RUNNING.name)
            .apply()
    }

    fun active(): ActiveGuidedValidation? {
        val id = preferences.getString(ACTIVE_CASE, null) ?: return null
        val case = CASES.firstOrNull { it.id == id } ?: return null
        return ActiveGuidedValidation(case, preferences.getLong(ACTIVE_STARTED_AT, 0L))
    }

    fun finish(case: GuidedValidationCase, status: GuidedValidationStatus) {
        preferences.edit()
            .remove(ACTIVE_CASE)
            .remove(ACTIVE_STARTED_AT)
            .putString("status.${case.id}", status.name)
            .apply()
    }

    fun setStatus(case: GuidedValidationCase, status: GuidedValidationStatus) {
        preferences.edit().putString("status.${case.id}", status.name).apply()
    }

    companion object {
        const val WINDOW_MS = 45_000L
        private const val ACTIVE_CASE = "active.case"
        private const val ACTIVE_STARTED_AT = "active.started_at"

        val CASES = listOf(
            GuidedValidationCase("R1", "L5 joinable", CaptureLabLabel.TARGET_LEVEL_5_JOINABLE),
            GuidedValidationCase("R2", "L10 joinable", CaptureLabLabel.TARGET_LEVEL_10_JOINABLE),
            GuidedValidationCase("R3", "Non-target + plus", CaptureLabLabel.NON_TARGET),
            GuidedValidationCase("R4", "Full target", CaptureLabLabel.TARGET_FULL),
            GuidedValidationCase("R5", "No plus / joined", CaptureLabLabel.TARGET_ALREADY_JOINED),
            GuidedValidationCase("R6", "Multiple rallies", CaptureLabLabel.MULTIPLE_TARGETS),
            GuidedValidationCase("R7", "Refresh / reorder", CaptureLabLabel.REFRESH_REORDER),
            GuidedValidationCase("R8", "Target disappears", CaptureLabLabel.TARGET_DISAPPEARS),
            GuidedValidationCase("M1", "March screen", CaptureLabLabel.MARCH_SCREEN),
            GuidedValidationCase("S1", "Free squad", CaptureLabLabel.SQUAD_FREE),
            GuidedValidationCase("S2", "Busy squad", CaptureLabLabel.SQUAD_MOVING),
            GuidedValidationCase("S3", "Returning squad", CaptureLabLabel.SQUAD_RETURNING),
            GuidedValidationCase("T1", "Distinct travel time 1", CaptureLabLabel.TRAVEL_TIME),
            GuidedValidationCase("T2", "Distinct travel time 2", CaptureLabLabel.TRAVEL_TIME),
            GuidedValidationCase("T3", "Distinct travel time 3", CaptureLabLabel.TRAVEL_TIME),
            GuidedValidationCase("T4", "Distinct travel time 4", CaptureLabLabel.TRAVEL_TIME),
            GuidedValidationCase("T5", "Distinct travel time 5", CaptureLabLabel.TRAVEL_TIME),
        )
    }
}
