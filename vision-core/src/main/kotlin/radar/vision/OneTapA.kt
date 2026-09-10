package radar.vision

data class UserJoinRequest(
    val rallyId: RallyId,
    val displayedFrameId: Long,
    val requestedAtMonotonicMs: Long,
)

enum class OneTapRejectReason {
    NONE,
    NOT_FRESH,
    WRONG_SCREEN,
    SCREEN_CONFIDENCE,
    TARGET_DISAPPEARED,
    TARGET_NOT_STABLE,
    NON_TARGET,
    BOSS_CONFIDENCE,
    WRONG_LEVEL,
    LEVEL_CONFIDENCE,
    FULL,
    NO_PLUS,
    PLUS_CONFIDENCE,
    INVALID_PLUS_GEOMETRY,
}

data class OneTapOpenDecision(
    val allowed: Boolean,
    val reason: OneTapRejectReason,
    val rally: TrackedRally? = null,
    val plusBounds: NormalizedRect? = null,
)

/** Strict gate for opening the march screen. Participant OCR is intentionally not required. */
class OneTapOpenPolicy(
    private val targetLevels: Set<Int>,
    private val minScreenConfidence: Float = 0.78f,
    private val minBossConfidence: Float = 0.78f,
    private val minLevelConfidence: Float = 0.70f,
    private val minPlusConfidence: Float = 0.62f,
) {
    fun evaluate(
        request: UserJoinRequest,
        frame: FrameAnalysis,
        tracking: TrackingUpdate,
    ): OneTapOpenDecision {
        if (frame.frameId <= request.displayedFrameId || frame.observedAtMonotonicMs < request.requestedAtMonotonicMs) {
            return reject(OneTapRejectReason.NOT_FRESH)
        }
        if (frame.screen != ScreenState.EVENT_LIST) return reject(OneTapRejectReason.WRONG_SCREEN)
        if (frame.screenConfidence < minScreenConfidence) return reject(OneTapRejectReason.SCREEN_CONFIDENCE)
        val rally = OneTapRequestGuard.resolve(
            OneTapRequest(request.rallyId, request.displayedFrameId),
            frame.frameId,
            tracking,
        )
            ?: return reject(OneTapRejectReason.TARGET_DISAPPEARED)
        val candidate = rally.candidate
        if (candidate.bossType != BossType.TARGET) return reject(OneTapRejectReason.NON_TARGET)
        if (candidate.confidences.boss < minBossConfidence) return reject(OneTapRejectReason.BOSS_CONFIDENCE)
        if (candidate.level == null || candidate.level !in targetLevels) return reject(OneTapRejectReason.WRONG_LEVEL)
        if (candidate.confidences.level < minLevelConfidence) return reject(OneTapRejectReason.LEVEL_CONFIDENCE)
        if (candidate.full == true || candidate.joinedState == JoinedState.FULL) return reject(OneTapRejectReason.FULL)
        if (candidate.joinPlusBounds.isEmpty()) return reject(OneTapRejectReason.NO_PLUS)
        if (candidate.confidences.plus < minPlusConfidence) return reject(OneTapRejectReason.PLUS_CONFIDENCE)
        val plus = candidate.joinPlusBounds
            .filter { it.isSaneJoinPlusInside(candidate.cardBounds) }
            .minWithOrNull(compareBy<NormalizedRect> { it.left }.thenBy { it.top })
            ?: return reject(OneTapRejectReason.INVALID_PLUS_GEOMETRY)
        return OneTapOpenDecision(true, OneTapRejectReason.NONE, rally, plus)
    }

    private fun reject(reason: OneTapRejectReason) = OneTapOpenDecision(false, reason)
}

private fun NormalizedRect.isSaneJoinPlusInside(card: NormalizedRect): Boolean {
    val centerInside = center.x in card.left..card.right && center.y in card.top..card.bottom
    val fullyInside = left >= card.left && top >= card.top && right <= card.right && bottom <= card.bottom
    return centerInside && fullyInside && width in 0.008..0.20 && height in 0.006..0.16
}

sealed interface OneTapFlowState {
    data object Idle : OneTapFlowState
    data class AwaitingFresh(val request: UserJoinRequest) : OneTapFlowState
    data class Dispatching(val request: UserJoinRequest, val gesture: GestureRequest) : OneTapFlowState
    data class AwaitingMarch(
        val request: UserJoinRequest,
        val gestureRequestId: String,
        val deadlineMonotonicMs: Long,
    ) : OneTapFlowState
}

sealed interface OneTapFlowUpdate {
    data object Ignored : OneTapFlowUpdate
    data class Rejected(val reason: OneTapRejectReason) : OneTapFlowUpdate
    data class Dispatch(val request: GestureRequest) : OneTapFlowUpdate
    data object AwaitingMarch : OneTapFlowUpdate
    data object Success : OneTapFlowUpdate
    data object Failure : OneTapFlowUpdate
}

/** Synchronized single-flight state machine. A completed Android gesture is never treated as success. */
class OneTapFlowCoordinator(
    private val verificationWindowMs: Long = 2_000,
    private val requestTtlMs: Long = 750,
    private val minMarchScreenConfidence: Float = 0.70f,
) {
    private var state: OneTapFlowState = OneTapFlowState.Idle

    @Synchronized
    fun begin(request: UserJoinRequest): Boolean {
        if (state !is OneTapFlowState.Idle) return false
        state = OneTapFlowState.AwaitingFresh(request)
        return true
    }

    @Synchronized
    fun onFreshFrame(
        frame: FrameAnalysis,
        tracking: TrackingUpdate,
        policy: OneTapOpenPolicy,
        expectedPackage: String,
        expectedForegroundGeneration: Long = 0,
        projectionSessionGeneration: Long = 0,
    ): OneTapFlowUpdate {
        val awaiting = state as? OneTapFlowState.AwaitingFresh ?: return OneTapFlowUpdate.Ignored
        val decision = policy.evaluate(awaiting.request, frame, tracking)
        if (!decision.allowed) {
            state = OneTapFlowState.Idle
            return OneTapFlowUpdate.Rejected(decision.reason)
        }
        val plus = requireNotNull(decision.plusBounds)
        val gesture = GestureRequest(
            requestId = "join-${awaiting.request.rallyId.value}-${frame.frameId}",
            purpose = GesturePurpose.JOIN_PLUS,
            normalizedPoint = plus.center,
            sourceFrameId = frame.frameId,
            rallyId = awaiting.request.rallyId,
            createdAtMonotonicMs = frame.observedAtMonotonicMs,
            expiresAtMonotonicMs = frame.observedAtMonotonicMs + requestTtlMs,
            expectedPackage = expectedPackage,
            expectedScreen = ScreenState.EVENT_LIST,
            expectedForegroundGeneration = expectedForegroundGeneration,
            projectionSessionGeneration = projectionSessionGeneration,
        )
        state = OneTapFlowState.Dispatching(awaiting.request, gesture)
        return OneTapFlowUpdate.Dispatch(gesture)
    }

    @Synchronized
    fun onGestureCompleted(requestId: String, completed: Boolean, completedAtMonotonicMs: Long): OneTapFlowUpdate {
        val dispatching = state as? OneTapFlowState.Dispatching ?: return OneTapFlowUpdate.Ignored
        if (dispatching.gesture.requestId != requestId) return OneTapFlowUpdate.Ignored
        if (!completed) {
            state = OneTapFlowState.Idle
            return OneTapFlowUpdate.Failure
        }
        state = OneTapFlowState.AwaitingMarch(
            dispatching.request,
            requestId,
            completedAtMonotonicMs + verificationWindowMs,
        )
        return OneTapFlowUpdate.AwaitingMarch
    }

    @Synchronized
    fun onVerificationFrame(frame: FrameAnalysis): OneTapFlowUpdate {
        val awaiting = state as? OneTapFlowState.AwaitingMarch ?: return OneTapFlowUpdate.Ignored
        if (frame.screen == ScreenState.MARCH_SCREEN && frame.screenConfidence >= minMarchScreenConfidence) {
            state = OneTapFlowState.Idle
            return OneTapFlowUpdate.Success
        }
        if (frame.observedAtMonotonicMs >= awaiting.deadlineMonotonicMs) {
            state = OneTapFlowState.Idle
            return OneTapFlowUpdate.Failure
        }
        return OneTapFlowUpdate.AwaitingMarch
    }

    @Synchronized
    fun cancel(): Boolean {
        val wasActive = state !is OneTapFlowState.Idle
        state = OneTapFlowState.Idle
        return wasActive
    }

    @Synchronized fun isActive(): Boolean = state !is OneTapFlowState.Idle
    @Synchronized fun currentRallyId(): RallyId? = when (val current = state) {
        OneTapFlowState.Idle -> null
        is OneTapFlowState.AwaitingFresh -> current.request.rallyId
        is OneTapFlowState.Dispatching -> current.request.rallyId
        is OneTapFlowState.AwaitingMarch -> current.request.rallyId
    }
}

/** One global lease prevents refresh and join gestures from dispatching concurrently. */
class GestureCoordinator {
    private var activeRequestId: String? = null
    private var activePurpose: GesturePurpose? = null

    @Synchronized
    fun tryAcquire(request: GestureRequest): Boolean {
        if (activeRequestId != null) return false
        activeRequestId = request.requestId
        activePurpose = request.purpose
        return true
    }

    @Synchronized
    fun release(requestId: String) {
        if (activeRequestId == requestId) {
            activeRequestId = null
            activePurpose = null
        }
    }

    @Synchronized fun isIdle(): Boolean = activeRequestId == null
    @Synchronized fun purpose(): GesturePurpose? = activePurpose

    @Synchronized
    fun cancelAll() {
        activeRequestId = null
        activePurpose = null
    }
}
