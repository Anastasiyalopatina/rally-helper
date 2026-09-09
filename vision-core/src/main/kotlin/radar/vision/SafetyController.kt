package radar.vision

enum class RuntimeMode { RADAR, SHADOW_AUTO }

enum class DecisionKind { OBSERVE, WOULD_SELECT, WOULD_SEND, REJECT }

data class SafetyPolicy(
    val targetLevels: Set<Int> = setOf(5, 10),
    val minScreenConfidence: Float = 0.70f,
    val minBossConfidence: Float = 0.70f,
    val minLevelConfidence: Float = 0.62f,
    val minPlusConfidence: Float = 0.55f,
    val maxTravelSeconds: Int = 60,
)

data class DetectorDecision(
    val kind: DecisionKind,
    val rallyId: RallyId?,
    val reason: String,
    val targetBounds: NormalizedRect? = null,
)

class SafetyController(private val policy: SafetyPolicy = SafetyPolicy()) {
    fun decide(mode: RuntimeMode, frame: FrameAnalysis, tracks: TrackingUpdate): List<DetectorDecision> {
        if (mode == RuntimeMode.RADAR) return tracks.active.map {
            DetectorDecision(DecisionKind.OBSERVE, it.id, "radar-only")
        }
        if (frame.screen != ScreenState.EVENT_LIST || frame.screenConfidence < policy.minScreenConfidence) {
            return listOf(DetectorDecision(DecisionKind.REJECT, null, "screen not safely classified"))
        }
        return tracks.active.map { track ->
            val rally = track.candidate
            val reason = when {
                !track.presentInCurrentFrame || track.lastSeenFrameId != frame.frameId -> "rally absent from current frame"
                !track.stable -> "needs temporal confirmation"
                rally.level != null && rally.level !in policy.targetLevels -> "level outside policy"
                rally.bossType != BossType.TARGET -> "not a confirmed target"
                rally.confidences.boss < policy.minBossConfidence -> "boss confidence too low"
                rally.level == null || rally.confidences.level < policy.minLevelConfidence -> "level unknown"
                rally.joinedState != JoinedState.JOINABLE -> "rally not joinable"
                rally.joinPlusBounds.isEmpty() || rally.confidences.plus < policy.minPlusConfidence -> "plus unknown"
                else -> null
            }
            if (reason == null) DetectorDecision(
                DecisionKind.WOULD_SELECT, track.id, "shadow decision only", rally.joinPlusBounds.first(),
            ) else DetectorDecision(DecisionKind.REJECT, track.id, reason)
        }
    }
}
