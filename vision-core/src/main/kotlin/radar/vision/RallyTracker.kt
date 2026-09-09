package radar.vision

data class TrackedRally(
    val id: RallyId,
    val candidate: RallyCandidate,
    val firstSeenMonotonicMs: Long,
    val lastSeenMonotonicMs: Long,
    val observations: Int,
    val stable: Boolean,
    val lastSeenFrameId: Long,
    val presentInCurrentFrame: Boolean,
)

data class TrackingUpdate(
    val active: List<TrackedRally>,
    val expired: List<RallyId>,
)

class RallyTracker(
    private val confirmationFrames: Int = 2,
    private val expiryMs: Long = 3_000,
) {
    private val tracks = linkedMapOf<RallyId, TrackedRally>()
    private var nextId = 1L

    @Synchronized
    fun reset() {
        tracks.clear()
        nextId = 1L
    }

    @Synchronized
    fun update(frame: FrameAnalysis): TrackingUpdate {
        val now = frame.observedAtMonotonicMs
        val previousFrameTracks = tracks.values.filter { it.presentInCurrentFrame }
        tracks.replaceAll { _, track -> track.copy(presentInCurrentFrame = false) }
        val matched = mutableSetOf<RallyId>()
        frame.rallies.forEach { observation ->
            val prior = previousFrameTracks
                .filter { it.id !in matched }
                .maxByOrNull { score(it, observation, now) }
                ?.takeIf { score(it, observation, now) >= 0.54 }
            val id = prior?.id ?: RallyId("r${nextId++}")
            val firstSeen = prior?.firstSeenMonotonicMs ?: now
            val count = (prior?.observations ?: 0) + 1
            tracks[id] = TrackedRally(
                id = id,
                candidate = observation.copy(ephemeralId = id, firstSeenMonotonicMs = firstSeen),
                firstSeenMonotonicMs = firstSeen,
                lastSeenMonotonicMs = now,
                observations = count,
                stable = count >= confirmationFrames,
                lastSeenFrameId = frame.frameId,
                presentInCurrentFrame = true,
            )
            matched += id
        }
        val expired = tracks.values.filter { now - it.lastSeenMonotonicMs > expiryMs }.map { it.id }
        expired.forEach(tracks::remove)
        return TrackingUpdate(tracks.values.toList(), expired)
    }

    private fun score(track: TrackedRally, b: RallyCandidate, now: Long): Double {
        val a = track.candidate
        val elapsedSeconds = ((now - track.lastSeenMonotonicMs).coerceAtLeast(0) / 1_000.0)
        val geometry = a.cardBounds.intersectionOverUnion(b.cardBounds)
        val boss = if (a.bossType == b.bossType && a.bossType != BossType.UNKNOWN) 1.0 else 0.0
        val level = if (a.level != null && a.level == b.level) 1.0 else 0.0
        val capacity = if (a.capacity != null && a.capacity == b.capacity) 1.0 else 0.0
        val participants = when {
            a.participantCount == null || b.participantCount == null -> 0.25
            b.participantCount >= a.participantCount && b.participantCount - a.participantCount <= 2 -> 1.0
            else -> 0.0
        }
        val timer = when {
            a.remainingSeconds == null || b.remainingSeconds == null -> 0.2
            kotlin.math.abs((a.remainingSeconds - elapsedSeconds) - b.remainingSeconds) <= 3.0 -> 1.0
            b.remainingSeconds <= a.remainingSeconds -> 0.45
            else -> 0.0
        }
        val timing = (1.0 - elapsedSeconds / 4.0).coerceIn(0.0, 1.0)
        return geometry * 0.18 + boss * 0.10 + level * 0.08 + capacity * 0.08 +
            participants * 0.12 + timer * 0.38 + timing * 0.06
    }
}
