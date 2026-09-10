package radar.vision

enum class GesturePurpose { REFRESH, UNSUPPORTED }

data class GestureRequest(
    val requestId: String,
    val purpose: GesturePurpose,
    val point: NormalizedPoint,
    val sourceFrameId: Long,
    val sourceObservedAtMonotonicMs: Long,
    val expiresAtMonotonicMs: Long,
    val expectedPackage: String,
    val expectedScreen: ScreenState,
)

enum class GestureRejectReason {
    NONE,
    SERVICE_DISCONNECTED,
    WRONG_PURPOSE,
    EXPIRED,
    UNVERIFIED_EXPECTED_PACKAGE,
    WRONG_FOREGROUND_PACKAGE,
    STALE_FOREGROUND_EVENT,
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
        maxForegroundAgeMs: Long = 30_000,
    ): GestureGateDecision {
        val reason = when {
            !serviceConnected -> GestureRejectReason.SERVICE_DISCONNECTED
            request.purpose != GesturePurpose.REFRESH -> GestureRejectReason.WRONG_PURPOSE
            nowMonotonicMs > request.expiresAtMonotonicMs -> GestureRejectReason.EXPIRED
            verifiedPackage.isBlank() || request.expectedPackage != verifiedPackage ->
                GestureRejectReason.UNVERIFIED_EXPECTED_PACKAGE
            lastForegroundPackage != request.expectedPackage -> GestureRejectReason.WRONG_FOREGROUND_PACKAGE
            lastForegroundEventMonotonicMs == null ||
                nowMonotonicMs - lastForegroundEventMonotonicMs !in 0..maxForegroundAgeMs ->
                GestureRejectReason.STALE_FOREGROUND_EVENT
            request.expectedScreen != ScreenState.EVENT_LIST -> GestureRejectReason.WRONG_SOURCE_SCREEN
            cancelled -> GestureRejectReason.CANCELLED
            gestureInFlight -> GestureRejectReason.GESTURE_IN_FLIGHT
            else -> GestureRejectReason.NONE
        }
        return GestureGateDecision(reason == GestureRejectReason.NONE, reason)
    }
}
