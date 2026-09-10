package radar.vision

enum class GesturePurpose { REFRESH, JOIN_PLUS, SEND }

data class GestureRequest(
    val requestId: String,
    val purpose: GesturePurpose,
    val normalizedPoint: NormalizedPoint,
    val sourceFrameId: Long,
    val rallyId: RallyId? = null,
    val createdAtMonotonicMs: Long,
    val expiresAtMonotonicMs: Long,
    val expectedPackage: String,
    val expectedScreen: ScreenState,
    val expectedForegroundGeneration: Long = 0,
    val projectionSessionGeneration: Long = 0,
)

enum class GestureRejectReason {
    NONE,
    SERVICE_DISCONNECTED,
    WRONG_PURPOSE,
    INVALID_TTL,
    EXPIRED,
    UNVERIFIED_EXPECTED_PACKAGE,
    WRONG_FOREGROUND_PACKAGE,
    FOREGROUND_CHANGED_AFTER_VALIDATION,
    STALE_FOREGROUND_EVENT,
    PROJECTION_SESSION_CHANGED,
    WRONG_SOURCE_SCREEN,
    CANCELLED,
    GESTURE_IN_FLIGHT,
    DISPATCH_REJECTED,
}

data class GestureGateDecision(
    val allowed: Boolean,
    val reason: GestureRejectReason,
)

object GestureSafetyGate {
    fun evaluate(
        request: GestureRequest,
        verifiedPackage: String,
        lastForegroundPackage: String?,
        lastForegroundEventMonotonicMs: Long?,
        nowMonotonicMs: Long,
        serviceConnected: Boolean,
        cancelled: Boolean,
        gestureInFlight: Boolean,
        currentForegroundGeneration: Long = request.expectedForegroundGeneration,
        currentProjectionSessionGeneration: Long = request.projectionSessionGeneration,
        maxForegroundAgeMs: Long = 6 * 60 * 60 * 1_000,
    ): GestureGateDecision {
        val reason = when {
            !serviceConnected -> GestureRejectReason.SERVICE_DISCONNECTED
            request.purpose == GesturePurpose.SEND -> GestureRejectReason.WRONG_PURPOSE
            request.purpose == GesturePurpose.JOIN_PLUS && request.rallyId == null ->
                GestureRejectReason.WRONG_PURPOSE
            request.expiresAtMonotonicMs - request.createdAtMonotonicMs !in 1..750 ->
                GestureRejectReason.INVALID_TTL
            request.createdAtMonotonicMs > nowMonotonicMs -> GestureRejectReason.INVALID_TTL
            nowMonotonicMs > request.expiresAtMonotonicMs -> GestureRejectReason.EXPIRED
            verifiedPackage.isBlank() || request.expectedPackage != verifiedPackage ->
                GestureRejectReason.UNVERIFIED_EXPECTED_PACKAGE
            lastForegroundPackage != request.expectedPackage -> GestureRejectReason.WRONG_FOREGROUND_PACKAGE
            currentForegroundGeneration != request.expectedForegroundGeneration ->
                GestureRejectReason.FOREGROUND_CHANGED_AFTER_VALIDATION
            lastForegroundEventMonotonicMs == null ||
                nowMonotonicMs - lastForegroundEventMonotonicMs !in 0..maxForegroundAgeMs ->
                GestureRejectReason.STALE_FOREGROUND_EVENT
            currentProjectionSessionGeneration != request.projectionSessionGeneration ->
                GestureRejectReason.PROJECTION_SESSION_CHANGED
            request.expectedScreen != ScreenState.EVENT_LIST -> GestureRejectReason.WRONG_SOURCE_SCREEN
            cancelled -> GestureRejectReason.CANCELLED
            gestureInFlight -> GestureRejectReason.GESTURE_IN_FLIGHT
            else -> GestureRejectReason.NONE
        }
        return GestureGateDecision(reason == GestureRejectReason.NONE, reason)
    }
}
