package radar.vision

data class RefreshRequest(
    val frameId: Long,
    val observedAtMonotonicMs: Long,
    val targetBounds: NormalizedRect,
)

/**
 * Produces at most one refresh request per visible button appearance. It rearms only after
 * two confirmed absent frames, or after a bounded retry timeout when a dispatched tap did
 * not change the UI. This class owns timing only and has no input API.
 */
class RefreshCoordinator(
    private val minimumIntervalMs: Long = 750,
    private val retryAfterMs: Long = 2_000,
    private val absentFramesToRearm: Int = 2,
) {
    private var armed = true
    private var absentFrames = 0
    private var lastRequestAtMs = Long.MIN_VALUE

    @Synchronized
    fun reset() {
        armed = true
        absentFrames = 0
        lastRequestAtMs = Long.MIN_VALUE
    }

    @Synchronized
    fun onFrame(frame: FrameAnalysis, minimumScreenConfidence: Float = 0.70f): RefreshRequest? {
        if (frame.screen != ScreenState.EVENT_LIST || frame.screenConfidence < minimumScreenConfidence) {
            absentFrames = 0
            armed = true
            return null
        }
        val bounds = frame.refreshButton.value
        if (!frame.refreshButton.accepted || bounds == null) {
            absentFrames++
            if (absentFrames >= absentFramesToRearm) armed = true
            return null
        }
        absentFrames = 0
        val elapsed = if (lastRequestAtMs == Long.MIN_VALUE) Long.MAX_VALUE
        else (frame.observedAtMonotonicMs - lastRequestAtMs).coerceAtLeast(0)
        if (!armed && elapsed >= retryAfterMs) armed = true
        if (!armed || elapsed < minimumIntervalMs) return null
        armed = false
        lastRequestAtMs = frame.observedAtMonotonicMs
        return RefreshRequest(frame.frameId, frame.observedAtMonotonicMs, bounds)
    }

    @Synchronized
    fun onDispatchFailed(frameId: Long) {
        // A failure is tied to the most recent request by the caller. Rearming is safe because
        // a new CV frame is still required before another request can be produced.
        if (frameId >= 0) armed = true
    }
}
