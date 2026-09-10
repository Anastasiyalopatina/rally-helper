package radar.vision

enum class ScreenState { EVENT_LIST, MARCH_SCREEN, WORLD_MAP, UNKNOWN }

enum class BossType { TARGET, NON_TARGET, UNKNOWN }

enum class JoinedState { JOINABLE, ALREADY_JOINED, FULL, NOT_TARGET, UNKNOWN }

enum class RefreshMode { OFF, ALERT_ONLY, AUTO_REFRESH }

data class RefreshControlCandidate(
    val bounds: NormalizedRect,
    val orangeShapeConfidence: Float,
    val positionConfidence: Float,
    val badgeConfidence: Float?,
    val stableFrames: Int,
    val overallConfidence: Float,
)

data class NormalizedPoint(val x: Double, val y: Double) {
    init {
        require(x in 0.0..1.0 && y in 0.0..1.0)
    }
}

data class NormalizedRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    init {
        require(left in 0.0..1.0 && top in 0.0..1.0)
        require(right in 0.0..1.0 && bottom in 0.0..1.0)
        require(left < right && top < bottom)
    }

    val width: Double get() = right - left
    val height: Double get() = bottom - top
    val center: NormalizedPoint get() = NormalizedPoint((left + right) / 2.0, (top + bottom) / 2.0)

    fun local(child: NormalizedRect): NormalizedRect = NormalizedRect(
        left + child.left * width,
        top + child.top * height,
        left + child.right * width,
        top + child.bottom * height,
    )

    fun intersectionOverUnion(other: NormalizedRect): Double {
        val x1 = maxOf(left, other.left)
        val y1 = maxOf(top, other.top)
        val x2 = minOf(right, other.right)
        val y2 = minOf(bottom, other.bottom)
        val intersection = maxOf(0.0, x2 - x1) * maxOf(0.0, y2 - y1)
        val union = width * height + other.width * other.height - intersection
        return if (union <= 0.0) 0.0 else intersection / union
    }
}

data class Recognition<T>(
    val value: T?,
    val confidence: Float,
    val runnerUpConfidence: Float = 0f,
    val accepted: Boolean = false,
    val rejectionReason: String? = null,
) {
    companion object {
        fun <T> unknown(reason: String, confidence: Float = 0f) = Recognition<T>(
            value = null,
            confidence = confidence,
            accepted = false,
            rejectionReason = reason,
        )
    }
}

data class VisualComponent(
    val pixelCount: Int,
    val bounds: NormalizedRect,
    val centroid: NormalizedPoint,
    val confidence: Float,
)

data class RallyConfidences(
    val card: Float,
    val boss: Float,
    val level: Float,
    val participant: Float,
    val plus: Float,
    val timer: Float,
)

@JvmInline
value class RallyId(val value: String)

data class RallyIdentityFingerprint(
    val targetTitleHash: Long,
    val coordinatesHash: Long,
)

data class RallyCandidate(
    val ephemeralId: RallyId?,
    val bossType: BossType,
    val level: Int?,
    val participantCount: Int?,
    val capacity: Int?,
    val remainingSeconds: Int?,
    val firstSeenMonotonicMs: Long,
    val cardBounds: NormalizedRect,
    val joinPlusBounds: List<NormalizedRect>,
    val joinable: Boolean,
    val full: Boolean?,
    val joinedState: JoinedState,
    val confidences: RallyConfidences,
    val identityFingerprint: RallyIdentityFingerprint? = null,
)

enum class SquadState { FREE, MOVING, RETURNING, GATHERING, OCCUPIED_OTHER, LOCKED, UNKNOWN }

data class SquadInfo(
    val slotIndex: Int,
    val state: SquadState,
    val remainingSeconds: Int?,
    val redirectPossible: Boolean?,
    val confidence: Float,
    val bounds: NormalizedRect,
)

data class FrameAnalysis(
    val frameId: Long,
    val observedAtMonotonicMs: Long,
    val screen: ScreenState,
    val screenConfidence: Float,
    val rallies: List<RallyCandidate> = emptyList(),
    val travelTime: Recognition<Int> = Recognition.unknown("not a march screen"),
    val sendButtonFound: Boolean = false,
    val refreshButton: Recognition<RefreshControlCandidate> = Recognition.unknown("not an event-list screen"),
    val squads: List<SquadInfo> = emptyList(),
    val diagnostics: Map<String, Double> = emptyMap(),
)

interface ArgbImage {
    val width: Int
    val height: Int
    fun argb(x: Int, y: Int): Int
}
