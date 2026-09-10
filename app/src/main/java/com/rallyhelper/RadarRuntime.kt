package com.rallyhelper

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import radar.vision.RuntimeMode
import radar.vision.ScreenState
import radar.vision.RefreshMode

enum class RuntimeLifecycle { STOPPED, RUNNING, AUTOMATION_PAUSED, NEEDS_CALIBRATION }

data class RadarStatus(
    val running: Boolean = false,
    val lifecycle: RuntimeLifecycle = RuntimeLifecycle.STOPPED,
    val mode: RuntimeMode = RuntimeMode.RADAR,
    val refreshMode: RefreshMode = RefreshMode.OFF,
    val screen: ScreenState = ScreenState.UNKNOWN,
    val framesAnalyzed: Long = 0,
    val framesDropped: Long = 0,
    val framesThrottled: Long = 0,
    val ralliesSeen: Long = 0,
    val lastLatencyMs: Long? = null,
    val sessionStartedAtEpochMs: Long? = null,
    val eligible: Long = 0,
    val nonTarget: Long = 0,
    val full: Long = 0,
    val unknown: Long = 0,
    val alertsEmitted: Long = 0,
    val shadowSelections: Long = 0,
    val policySkipped: Long = 0,
    val shadowPhase: String = "IDLE",
    val shadowDelayRemainingSeconds: Int? = null,
    val shadowWouldAttempts: Long = 0,
    val oneTapOpenAttempts: Long = 0,
    val oneTapOpenSuccesses: Long = 0,
    val oneTapOpenFailures: Long = 0,
    val joinAttempts: Long = 0,
    val joinSuccesses: Long = 0,
    val joinFailures: Long = 0,
    val fullBeforeJoin: Long = 0,
    val noSquad: Long = 0,
    val tooLate: Long = 0,
    val visionRejects: Long = 0,
    val safetyAborts: Long = 0,
    val safetyRejects: Long = 0,
    val refreshDetected: Long = 0,
    val refreshRequests: Long = 0,
    val refreshGestureAccepted: Long = 0,
    val refreshGestureCompleted: Long = 0,
    val refreshVerifiedSuccesses: Long = 0,
    val refreshVerifiedFailures: Long = 0,
    val refreshSafetyRejects: Long = 0,
    val refreshAlerts: Long = 0,
    val refreshStuck: Long = 0,
    val averageLatencyMs: Long? = null,
    val p50LatencyMs: Long? = null,
    val p95LatencyMs: Long? = null,
    val message: String = "Остановлен",
)

object RadarRuntime {
    private val mutable = MutableStateFlow(RadarStatus())
    val status = mutable.asStateFlow()

    fun update(block: (RadarStatus) -> RadarStatus) {
        mutable.value = block(mutable.value)
    }

    fun resetForSession(
        mode: RuntimeMode,
        refreshMode: RefreshMode = RefreshMode.OFF,
        startedAtEpochMs: Long = System.currentTimeMillis(),
    ) {
        mutable.value = RadarStatus(
            running = true,
            lifecycle = RuntimeLifecycle.RUNNING,
            mode = mode,
            refreshMode = refreshMode,
            sessionStartedAtEpochMs = startedAtEpochMs,
            message = "Запуск локального анализа…",
        )
    }
}
