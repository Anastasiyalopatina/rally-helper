package radar.vision

/** Selects exactly one currently eligible rally using a stable, auditable priority order. */
class TargetSelector {
    fun select(
        frameId: Long,
        tracks: TrackingUpdate,
        decisions: List<DetectorDecision>,
        excluded: Set<RallyId> = emptySet(),
    ): TrackedRally? {
        val eligible = decisions.asSequence()
            .filter { it.kind == DecisionKind.WOULD_SELECT }
            .mapNotNull { it.rallyId }
            .toSet()
        return tracks.active.asSequence()
            .filter {
                it.id in eligible && it.id !in excluded && it.stable &&
                    it.presentInCurrentFrame && it.lastSeenFrameId == frameId
            }
            .sortedWith(
                compareByDescending<TrackedRally> { it.candidate.level ?: Int.MIN_VALUE }
                    .thenBy { it.candidate.remainingSeconds ?: Int.MAX_VALUE }
                    .thenBy { it.firstSeenMonotonicMs }
                    .thenBy { it.id.value },
            )
            .firstOrNull()
    }
}
