package radar.vision

/**
 * Read-only notification policy. Unknown participant OCR may still alert when all visual anchors
 * are unusually strong. It never authorizes an input action; [SafetyController] remains strict.
 */
class RadarAlertPolicy(
    private val policy: SafetyPolicy = SafetyPolicy(),
    private val highBossConfidence: Float = 0.88f,
    private val highLevelConfidence: Float = 0.82f,
    private val highPlusConfidence: Float = 0.75f,
) {
    fun decide(frame: FrameAnalysis, tracks: TrackingUpdate): List<DetectorDecision> {
        if (frame.screen != ScreenState.EVENT_LIST || frame.screenConfidence < policy.minScreenConfidence) {
            return listOf(DetectorDecision(DecisionKind.REJECT, null, "screen not safely classified"))
        }
        return tracks.active.map { track ->
            val rally = track.candidate
            val participantKnown = rally.participantCount != null && rally.capacity != null
            val reason = when {
                !track.presentInCurrentFrame || track.lastSeenFrameId != frame.frameId -> "rally absent from current frame"
                !track.stable -> "needs temporal confirmation"
                rally.level != null && rally.level !in policy.targetLevels -> "level outside policy"
                rally.bossType != BossType.TARGET -> "not a confirmed target"
                rally.level == null -> "level unknown"
                rally.joinPlusBounds.isEmpty() -> "plus unknown"
                participantKnown && rally.capacity!! - rally.participantCount!! < policy.minimumFreeSlots -> "not enough free slots"
                participantKnown && rally.joinedState != JoinedState.JOINABLE -> "rally not joinable"
                participantKnown && rally.confidences.boss < policy.minBossConfidence -> "boss confidence too low"
                participantKnown && rally.confidences.level < policy.minLevelConfidence -> "level confidence too low"
                participantKnown && rally.confidences.plus < policy.minPlusConfidence -> "plus confidence too low"
                !participantKnown && (
                    rally.confidences.boss < highBossConfidence ||
                        rally.confidences.level < highLevelConfidence ||
                        rally.confidences.plus < highPlusConfidence
                    ) -> "participant capacity unknown without high-confidence anchors"
                else -> null
            }
            if (reason == null) {
                DetectorDecision(DecisionKind.WOULD_SELECT, track.id, "eligible radar alert", rally.joinPlusBounds.first())
            } else {
                DetectorDecision(DecisionKind.REJECT, track.id, reason)
            }
        }
    }
}
