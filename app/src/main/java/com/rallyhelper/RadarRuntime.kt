package com.rallyhelper

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import radar.vision.RuntimeMode
import radar.vision.ScreenState

data class RadarStatus(
    val running: Boolean = false,
    val mode: RuntimeMode = RuntimeMode.RADAR,
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
    val attempts: Long = 0,
    val successes: Long = 0,
    val failures: Long = 0,
    val fullBeforeJoin: Long = 0,
    val noSquad: Long = 0,
    val tooLate: Long = 0,
    val visionRejects: Long = 0,
    val safetyAborts: Long = 0,
    val safetyRejects: Long = 0,
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
}
