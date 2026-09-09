package radar.vision

import kotlin.random.Random

data class AutoPolicyConfig(
    val delayMinSeconds: Int = 0,
    val delayMaxSeconds: Int = 0,
    val skipMin: Int = 0,
    val skipMax: Int = 0,
) {
    init {
        require(delayMinSeconds >= 0 && delayMinSeconds <= delayMaxSeconds)
        require(skipMin >= 0 && skipMin <= skipMax)
    }
}

sealed interface AutoPolicyDecision {
    val rallyId: RallyId

    data class Skip(override val rallyId: RallyId, val remainingEligibleSkips: Int) : AutoPolicyDecision
    data class Wait(override val rallyId: RallyId, val delaySeconds: Int) : AutoPolicyDecision
}

/**
 * Functional selection policy only. It never performs input or owns gesture APIs.
 * A delay is sampled once per rally and a new skip count is sampled after each completed attempt.
 */
class AutoPolicy(
    config: AutoPolicyConfig = AutoPolicyConfig(),
    private val random: Random = Random.Default,
) {
    private var config = config
    private var eligibleSkipsRemaining = sample(config.skipMin, config.skipMax)
    private val delaysByRally = mutableMapOf<RallyId, Int>()

    @Synchronized
    fun updateConfig(updated: AutoPolicyConfig) {
        if (updated == config) return
        config = updated
        eligibleSkipsRemaining = sample(updated.skipMin, updated.skipMax)
        delaysByRally.clear()
    }

    @Synchronized
    fun onEligible(rallyId: RallyId): AutoPolicyDecision {
        if (eligibleSkipsRemaining > 0) {
            eligibleSkipsRemaining--
            return AutoPolicyDecision.Skip(rallyId, eligibleSkipsRemaining)
        }
        val delay = delaysByRally.getOrPut(rallyId) { sample(config.delayMinSeconds, config.delayMaxSeconds) }
        return AutoPolicyDecision.Wait(rallyId, delay)
    }

    /** Must be called after the attempt reaches a terminal success/failure state. */
    @Synchronized
    fun onAttemptFinished(rallyId: RallyId) {
        delaysByRally.remove(rallyId)
        eligibleSkipsRemaining = sample(config.skipMin, config.skipMax)
    }

    /** Cancels only a scheduled delay. It is not a completed attempt and does not resample skip K. */
    @Synchronized
    fun onPendingCancelled(rallyId: RallyId) {
        delaysByRally.remove(rallyId)
    }

    /** Starts an independent policy cycle without changing the current configuration. */
    @Synchronized
    fun resetSession() {
        delaysByRally.clear()
        eligibleSkipsRemaining = sample(config.skipMin, config.skipMax)
    }

    /** Non-eligible observations deliberately do not consume the counter. */
    fun onRejected() = Unit

    private fun sample(min: Int, max: Int): Int = if (min == max) min else random.nextInt(min, max + 1)
}
