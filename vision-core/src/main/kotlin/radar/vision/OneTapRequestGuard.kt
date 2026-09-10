package radar.vision

data class OneTapRequest(val rallyId: RallyId, val displayedFrameId: Long)

/** A user request is bound to the displayed identity; geometry is never used to retarget it. */
object OneTapRequestGuard {
    fun resolve(request: OneTapRequest, currentFrameId: Long, tracking: TrackingUpdate): TrackedRally? {
        if (currentFrameId < request.displayedFrameId) return null
        return tracking.active.singleOrNull { track ->
            track.id == request.rallyId &&
                track.stable &&
                track.presentInCurrentFrame &&
                track.lastSeenFrameId == currentFrameId
        }
    }
}
