package radar.vision

enum class ShadowAutoPhase { IDLE, SKIPPED, WAITING_DELAY, REVALIDATING, WOULD_START_JOIN_FLOW, INVALIDATED, PAUSED }

data class ShadowAutoUpdate(
    val phase: ShadowAutoPhase,
    val rallyId: RallyId? = null,
    val delaySeconds: Int? = null,
    val dueAtMonotonicMs: Long? = null,
    val virtualAttempts: Int = 0,
    val policySkips: Int = 0,
    val reason: String? = null,
)

/**
 * Coordinates the real timing and revalidation semantics of Shadow Auto. This class owns no input
 * API and therefore cannot tap or gesture. A WOULD_START_JOIN_FLOW result is a simulated attempt.
 */
class ShadowAutoCoordinator(
    config: AutoPolicyConfig = AutoPolicyConfig(),
    private val policy: AutoPolicy = AutoPolicy(config),
    private val selector: TargetSelector = TargetSelector(),
) {
    private data class Pending(
        val rallyId: RallyId,
        val scheduledFrameId: Long,
        val delaySeconds: Int,
        val dueAtMonotonicMs: Long,
    )

    private var pending: Pending? = null
    private val processedWhileVisible = mutableSetOf<RallyId>()
    private var paused = false
    private var resumeAfterFrameId: Long? = null
    private var virtualAttempts = 0
    private var policySkips = 0

    @Synchronized
    fun updateConfig(config: AutoPolicyConfig) {
        cancelPending()
        policy.updateConfig(config)
    }

    @Synchronized
    fun pause() {
        cancelPending()
        paused = true
        resumeAfterFrameId = null
    }

    @Synchronized
    fun resume(currentFrameId: Long?) {
        paused = false
        resumeAfterFrameId = currentFrameId
    }

    @Synchronized
    fun reset() {
        cancelPending()
        processedWhileVisible.clear()
        paused = false
        resumeAfterFrameId = null
        virtualAttempts = 0
        policySkips = 0
    }

    @Synchronized
    fun onFrame(
        frame: FrameAnalysis,
        tracks: TrackingUpdate,
        actionDecisions: List<DetectorDecision>,
    ): ShadowAutoUpdate {
        val visibleIds = tracks.active.asSequence()
            .filter { it.presentInCurrentFrame && it.lastSeenFrameId == frame.frameId }
            .map { it.id }
            .toSet()
        processedWhileVisible.retainAll(visibleIds)

        if (paused) return snapshot(ShadowAutoPhase.PAUSED, reason = "automation paused")
        resumeAfterFrameId?.let { resumeFrame ->
            if (frame.frameId <= resumeFrame) return snapshot(ShadowAutoPhase.IDLE, reason = "waiting for fresh frame after resume")
            resumeAfterFrameId = null
        }

        val eligibleIds = actionDecisions.asSequence()
            .filter { it.kind == DecisionKind.WOULD_SELECT }
            .mapNotNull { it.rallyId }
            .toSet()
        pending?.let { scheduled ->
            if (scheduled.rallyId !in eligibleIds || scheduled.rallyId !in visibleIds) {
                finishPending(scheduled.rallyId)
                processedWhileVisible += scheduled.rallyId
                return snapshot(ShadowAutoPhase.INVALIDATED, scheduled.rallyId, reason = "target failed fresh-frame revalidation")
            }
            if (frame.observedAtMonotonicMs < scheduled.dueAtMonotonicMs) {
                return snapshot(ShadowAutoPhase.WAITING_DELAY, scheduled.rallyId, scheduled.delaySeconds, scheduled.dueAtMonotonicMs)
            }
            if (frame.frameId <= scheduled.scheduledFrameId) {
                return snapshot(
                    ShadowAutoPhase.REVALIDATING,
                    scheduled.rallyId,
                    scheduled.delaySeconds,
                    scheduled.dueAtMonotonicMs,
                    "waiting for a newer analyzed frame",
                )
            }
            finishPending(scheduled.rallyId)
            processedWhileVisible += scheduled.rallyId
            virtualAttempts++
            return snapshot(
                ShadowAutoPhase.WOULD_START_JOIN_FLOW,
                scheduled.rallyId,
                scheduled.delaySeconds,
                scheduled.dueAtMonotonicMs,
                "simulation only; no input emitted",
            )
        }

        val selected = selector.select(frame.frameId, tracks, actionDecisions, processedWhileVisible)
            ?: return snapshot(ShadowAutoPhase.IDLE)
        return when (val decision = policy.onEligible(selected.id)) {
            is AutoPolicyDecision.Skip -> {
                processedWhileVisible += selected.id
                policySkips++
                snapshot(ShadowAutoPhase.SKIPPED, selected.id, reason = "eligible target skipped by policy")
            }
            is AutoPolicyDecision.Wait -> {
                val dueAt = frame.observedAtMonotonicMs + decision.delaySeconds * 1_000L
                pending = Pending(selected.id, frame.frameId, decision.delaySeconds, dueAt)
                snapshot(ShadowAutoPhase.WAITING_DELAY, selected.id, decision.delaySeconds, dueAt)
            }
        }
    }

    private fun cancelPending() {
        pending?.let { policy.onAttemptFinished(it.rallyId) }
        pending = null
    }

    private fun finishPending(rallyId: RallyId) {
        policy.onAttemptFinished(rallyId)
        pending = null
    }

    private fun snapshot(
        phase: ShadowAutoPhase,
        rallyId: RallyId? = null,
        delaySeconds: Int? = null,
        dueAtMonotonicMs: Long? = null,
        reason: String? = null,
    ) = ShadowAutoUpdate(phase, rallyId, delaySeconds, dueAtMonotonicMs, virtualAttempts, policySkips, reason)
}
