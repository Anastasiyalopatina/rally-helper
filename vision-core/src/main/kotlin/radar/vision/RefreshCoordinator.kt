package radar.vision

data class RefreshMetrics(
    val detected: Long = 0,
    val requests: Long = 0,
    val gestureAccepted: Long = 0,
    val gestureCompleted: Long = 0,
    val verifiedSuccess: Long = 0,
    val verifiedFailure: Long = 0,
    val rejectedBySafety: Long = 0,
    val alerts: Long = 0,
    val stuck: Long = 0,
)

data class RefreshEvaluation(
    val request: GestureRequest? = null,
    val shouldAlert: Boolean = false,
    val verifiedSuccess: Boolean = false,
    val verifiedFailure: Boolean = false,
    val autoRefreshPaused: Boolean = false,
    val reason: String? = null,
    val metrics: RefreshMetrics,
)

/** Purpose-scoped refresh state machine. It never dispatches input itself. */
class RefreshCoordinator(
    private val autoConfidenceThreshold: Float = 0.86f,
    private val alertConfidenceThreshold: Float = 0.72f,
    private val minimumStableFrames: Int = 2,
    private val requestTtlMs: Long = 750,
    private val verificationWindowMs: Long = 1_500,
    private val absentFramesToRearm: Int = 2,
    private val maxRetriesPerAppearance: Int = 1,
) {
    private data class AwaitingVerification(
        val requestId: String,
        val completedAtMonotonicMs: Long,
        val sourceFingerprint: Long,
    )

    private var appearanceSequence = 0L
    private var appearanceActive = false
    private var absentFrames = 0
    private var attemptsThisAppearance = 0
    private var alertedThisAppearance = false
    private var requestInFlight: GestureRequest? = null
    private var requestSourceFingerprint: Long? = null
    private var awaitingVerification: AwaitingVerification? = null
    private var retryReady = false
    private var pausedForStuckAppearance = false
    private var metrics = RefreshMetrics()
    private var currentFrameFingerprint = 0L

    @Synchronized
    fun reset() {
        appearanceSequence = 0
        appearanceActive = false
        absentFrames = 0
        attemptsThisAppearance = 0
        alertedThisAppearance = false
        requestInFlight = null
        requestSourceFingerprint = null
        awaitingVerification = null
        retryReady = false
        pausedForStuckAppearance = false
        metrics = RefreshMetrics()
        currentFrameFingerprint = 0L
    }

    @Synchronized
    fun onFrame(frame: FrameAnalysis, mode: RefreshMode, expectedPackage: String): RefreshEvaluation {
        val candidate = frame.refreshButton.value
        val confirmedScreen = frame.screen == ScreenState.EVENT_LIST && frame.screenConfidence >= 0.70f
        val visible = confirmedScreen && frame.refreshButton.accepted && candidate != null
        currentFrameFingerprint = eventListFingerprint(frame)

        awaitingVerification?.let { pending ->
            val changed = !visible || currentFrameFingerprint != pending.sourceFingerprint
            if (changed) {
                awaitingVerification = null
                metrics = metrics.copy(verifiedSuccess = metrics.verifiedSuccess + 1)
                return evaluateAppearance(frame, mode, expectedPackage, visible, candidate, verifiedSuccess = true)
            }
            if (frame.observedAtMonotonicMs - pending.completedAtMonotonicMs >= verificationWindowMs) {
                awaitingVerification = null
                metrics = metrics.copy(verifiedFailure = metrics.verifiedFailure + 1)
                if (attemptsThisAppearance > maxRetriesPerAppearance) {
                    pausedForStuckAppearance = true
                    metrics = metrics.copy(stuck = metrics.stuck + 1)
                    return snapshot(true, autoRefreshPaused = true, reason = "REFRESH_STUCK")
                }
                val retry = buildRequest(frame, candidate, expectedPackage, visible)
                return snapshot(true, request = retry, reason = "refresh unverified; bounded retry")
            }
        }

        return evaluateAppearance(frame, mode, expectedPackage, visible, candidate)
    }

    private fun evaluateAppearance(
        frame: FrameAnalysis,
        mode: RefreshMode,
        expectedPackage: String,
        visible: Boolean,
        candidate: RefreshControlCandidate?,
        verifiedSuccess: Boolean = false,
    ): RefreshEvaluation {
        if (!visible) {
            absentFrames++
            if (absentFrames >= absentFramesToRearm) {
                appearanceActive = false
                attemptsThisAppearance = 0
                alertedThisAppearance = false
                pausedForStuckAppearance = false
                requestInFlight = null
                requestSourceFingerprint = null
                retryReady = false
            }
            return snapshot(verifiedSuccess = verifiedSuccess)
        }

        absentFrames = 0
        if (!appearanceActive) {
            appearanceActive = true
            appearanceSequence++
            attemptsThisAppearance = 0
            alertedThisAppearance = false
            pausedForStuckAppearance = false
            metrics = metrics.copy(detected = metrics.detected + 1)
        }
        if (mode == RefreshMode.OFF || pausedForStuckAppearance) {
            return snapshot(verifiedSuccess = verifiedSuccess, autoRefreshPaused = pausedForStuckAppearance)
        }
        if (candidate == null || candidate.overallConfidence < alertConfidenceThreshold) {
            return snapshot(verifiedSuccess = verifiedSuccess, reason = "refresh confidence below alert threshold")
        }
        if (mode == RefreshMode.ALERT_ONLY) {
            val shouldAlert = !alertedThisAppearance
            if (shouldAlert) {
                alertedThisAppearance = true
                metrics = metrics.copy(alerts = metrics.alerts + 1)
            }
            return snapshot(shouldAlert = shouldAlert, verifiedSuccess = verifiedSuccess)
        }
        if (
            candidate.stableFrames < minimumStableFrames ||
            candidate.overallConfidence < autoConfidenceThreshold ||
            requestInFlight != null || awaitingVerification != null
        ) return snapshot(verifiedSuccess = verifiedSuccess)

        if (retryReady) {
            retryReady = false
            return snapshot(
                request = buildRequest(frame, candidate, expectedPackage, visible),
                verifiedSuccess = verifiedSuccess,
                reason = "bounded retry",
            )
        }
        if (attemptsThisAppearance > 0) return snapshot(verifiedSuccess = verifiedSuccess)

        return snapshot(
            request = buildRequest(frame, candidate, expectedPackage, visible),
            verifiedSuccess = verifiedSuccess,
        )
    }

    private fun buildRequest(
        frame: FrameAnalysis,
        candidate: RefreshControlCandidate?,
        expectedPackage: String,
        visible: Boolean,
    ): GestureRequest? {
        if (!visible || candidate == null || pausedForStuckAppearance || requestInFlight != null) return null
        if (attemptsThisAppearance > maxRetriesPerAppearance) return null
        val request = GestureRequest(
            requestId = "refresh-$appearanceSequence-${attemptsThisAppearance + 1}-${frame.frameId}",
            purpose = GesturePurpose.REFRESH,
            normalizedPoint = candidate.bounds.center,
            sourceFrameId = frame.frameId,
            createdAtMonotonicMs = frame.observedAtMonotonicMs,
            expiresAtMonotonicMs = frame.observedAtMonotonicMs + requestTtlMs,
            expectedPackage = expectedPackage,
            expectedScreen = frame.screen,
        )
        attemptsThisAppearance++
        requestInFlight = request
        requestSourceFingerprint = currentFrameFingerprint
        metrics = metrics.copy(requests = metrics.requests + 1)
        return request
    }

    @Synchronized
    fun onGestureAccepted(requestId: String) {
        if (requestInFlight?.requestId == requestId) {
            metrics = metrics.copy(gestureAccepted = metrics.gestureAccepted + 1)
        }
    }

    @Synchronized
    fun onGestureCompleted(requestId: String, completedAtMonotonicMs: Long, completed: Boolean) {
        val request = requestInFlight?.takeIf { it.requestId == requestId } ?: return
        val sourceFingerprint = requestSourceFingerprint ?: currentFrameFingerprint
        requestInFlight = null
        requestSourceFingerprint = null
        if (!completed) {
            metrics = metrics.copy(verifiedFailure = metrics.verifiedFailure + 1)
            if (attemptsThisAppearance > maxRetriesPerAppearance) {
                pausedForStuckAppearance = true
                metrics = metrics.copy(stuck = metrics.stuck + 1)
            } else retryReady = true
            return
        }
        metrics = metrics.copy(gestureCompleted = metrics.gestureCompleted + 1)
        awaitingVerification = AwaitingVerification(requestId, completedAtMonotonicMs, sourceFingerprint)
    }

    @Synchronized
    fun onGestureRejected(requestId: String) {
        if (requestInFlight?.requestId != requestId) return
        requestInFlight = null
        requestSourceFingerprint = null
        metrics = metrics.copy(rejectedBySafety = metrics.rejectedBySafety + 1)
        pausedForStuckAppearance = true
    }

    @Synchronized
    fun cancelPending() {
        requestInFlight = null
        requestSourceFingerprint = null
        awaitingVerification = null
        retryReady = false
    }

    @Synchronized
    fun metrics(): RefreshMetrics = metrics

    private fun eventListFingerprint(frame: FrameAnalysis): Long {
        var hash = 1125899906842597L
        frame.rallies.sortedBy { it.cardBounds.top }.forEach { rally ->
            hash = hash * 31 + (rally.identityFingerprint?.targetTitleHash ?: 0L)
            hash = hash * 31 + (rally.identityFingerprint?.coordinatesHash ?: 0L)
            hash = hash * 31 + (rally.level ?: -1)
            hash = hash * 31 + rally.joinPlusBounds.size
        }
        return hash
    }

    private fun snapshot(
        verifiedFailure: Boolean = false,
        request: GestureRequest? = null,
        shouldAlert: Boolean = false,
        verifiedSuccess: Boolean = false,
        autoRefreshPaused: Boolean = false,
        reason: String? = null,
    ) = RefreshEvaluation(
        request = request,
        shouldAlert = shouldAlert,
        verifiedSuccess = verifiedSuccess,
        verifiedFailure = verifiedFailure,
        autoRefreshPaused = autoRefreshPaused,
        reason = reason,
        metrics = metrics,
    )
}
