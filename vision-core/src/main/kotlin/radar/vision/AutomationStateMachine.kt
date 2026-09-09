package radar.vision

enum class AutomationState {
    IDLE, TARGET_DETECTED, WAITING_DELAY, REVALIDATING_RALLY, WAITING_MARCH_SCREEN,
    ANALYZING_MARCHES, CHECKING_TRAVEL_TIME, VERIFYING_SEND, RECOVERING_TO_EVENT_LIST,
    SUCCESS, FAILURE, PAUSED,
}

enum class FailureReason {
    FULL_BEFORE_JOIN, RALLY_DISAPPEARED, NO_SQUAD, TRAVEL_TOO_LONG, MARCH_SCREEN_NOT_FOUND,
    SEND_DISABLED, SEND_FAILED, NAVIGATION_FAILED, VISION_LOW_CONFIDENCE,
    APP_NOT_FOREGROUND, USER_CANCELLED, MEDIA_PROJECTION_STOPPED, SCREEN_LOST,
    TARGET_INVALIDATED, WATCHDOG_TIMEOUT, INVALID_TRANSITION, UNKNOWN,
}

data class FlowContext(
    val flowId: Long,
    val rallyId: RallyId?,
    val startedAtMonotonicMs: Long,
    val deadlineMonotonicMs: Long,
    val candidateSnapshot: RallyCandidate? = null,
    val expectedScreen: ScreenState = ScreenState.EVENT_LIST,
    val actionCount: Int = 0,
    val lastValidatedFrameId: Long? = null,
    val failureReason: FailureReason? = null,
)

sealed interface AutomationEvent {
    val flowId: Long?

    data class EligibleTarget(val id: RallyId, override val flowId: Long) : AutomationEvent
    data class DelayElapsed(override val flowId: Long) : AutomationEvent
    data class RallyValidated(override val flowId: Long) : AutomationEvent
    data class MarchScreenFound(override val flowId: Long) : AutomationEvent
    data class MarchesAnalyzed(override val flowId: Long) : AutomationEvent
    data class TravelTimeAccepted(override val flowId: Long) : AutomationEvent
    data class SendObserved(override val flowId: Long) : AutomationEvent
    data class SendVerified(override val flowId: Long) : AutomationEvent
    data class EventListRecovered(override val flowId: Long) : AutomationEvent
    data class Abort(val reason: FailureReason, override val flowId: Long? = null) : AutomationEvent
    data class Cancel(override val flowId: Long) : AutomationEvent
    data class Timeout(override val flowId: Long) : AutomationEvent
    data object ProjectionStopped : AutomationEvent { override val flowId: Long? = null }
    data class ScreenLost(override val flowId: Long) : AutomationEvent
    data class TargetInvalidated(override val flowId: Long) : AutomationEvent
    data object Pause : AutomationEvent { override val flowId: Long? = null }
    data object Resume : AutomationEvent { override val flowId: Long? = null }
    data object Reset : AutomationEvent { override val flowId: Long? = null }
}

data class Transition(
    val state: AutomationState,
    val context: FlowContext? = null,
    val failureReason: FailureReason? = null,
)

sealed interface TransitionResult {
    val current: Transition
    data class Accepted(val previous: Transition, override val current: Transition) : TransitionResult
    data class Rejected(val event: AutomationEvent, override val current: Transition, val reason: String) : TransitionResult
}

class AutomationStateMachine(
    initial: AutomationState = AutomationState.IDLE,
    private val watchdogMs: Long = 20_000,
) {
    var current: Transition = Transition(initial)
        private set
    @Synchronized
    fun dispatch(event: AutomationEvent, nowMonotonicMs: Long): TransitionResult {
        val previous = current
        if (event is AutomationEvent.Reset) {
            current = Transition(AutomationState.IDLE)
            return TransitionResult.Accepted(previous, current)
        }
        if (event is AutomationEvent.Pause && current.state != AutomationState.PAUSED) {
            current = Transition(AutomationState.PAUSED)
            return TransitionResult.Accepted(previous, current)
        }
        if (event is AutomationEvent.Resume && current.state == AutomationState.PAUSED) {
            current = Transition(AutomationState.IDLE)
            return TransitionResult.Accepted(previous, current)
        }
        if (event is AutomationEvent.Abort) {
            if (event.flowId != null && event.flowId != current.context?.flowId) return reject(event, "stale flow id")
            current = Transition(AutomationState.FAILURE, current.context, event.reason)
            return TransitionResult.Accepted(previous, current)
        }
        val terminalReason = when (event) {
            is AutomationEvent.Cancel -> FailureReason.USER_CANCELLED
            is AutomationEvent.Timeout -> FailureReason.WATCHDOG_TIMEOUT
            is AutomationEvent.ProjectionStopped -> FailureReason.MEDIA_PROJECTION_STOPPED
            is AutomationEvent.ScreenLost -> FailureReason.SCREEN_LOST
            is AutomationEvent.TargetInvalidated -> FailureReason.TARGET_INVALIDATED
            else -> null
        }
        if (terminalReason != null) {
            if (event.flowId != null && event.flowId != current.context?.flowId) return reject(event, "stale flow id")
            current = Transition(AutomationState.FAILURE, current.context?.copy(failureReason = terminalReason), terminalReason)
            return TransitionResult.Accepted(previous, current)
        }
        if (current.state == AutomationState.PAUSED) return reject(event, "paused")
        if (current.context != null && nowMonotonicMs > current.context!!.deadlineMonotonicMs) {
            current = Transition(AutomationState.FAILURE, current.context, FailureReason.WATCHDOG_TIMEOUT)
            return TransitionResult.Accepted(previous, current)
        }
        if (current.context != null && event.flowId != current.context!!.flowId) return reject(event, "stale flow id")

        val next = when {
            current.state == AutomationState.IDLE && event is AutomationEvent.EligibleTarget -> Transition(
                AutomationState.TARGET_DETECTED,
                FlowContext(event.flowId, event.id, nowMonotonicMs, nowMonotonicMs + watchdogMs),
            )
            current.state == AutomationState.TARGET_DETECTED && event is AutomationEvent.DelayElapsed ->
                current.copy(state = AutomationState.REVALIDATING_RALLY)
            current.state == AutomationState.REVALIDATING_RALLY && event is AutomationEvent.RallyValidated ->
                current.copy(state = AutomationState.WAITING_MARCH_SCREEN)
            current.state == AutomationState.WAITING_MARCH_SCREEN && event is AutomationEvent.MarchScreenFound ->
                current.copy(state = AutomationState.ANALYZING_MARCHES)
            current.state == AutomationState.ANALYZING_MARCHES && event is AutomationEvent.MarchesAnalyzed ->
                current.copy(state = AutomationState.CHECKING_TRAVEL_TIME)
            current.state == AutomationState.CHECKING_TRAVEL_TIME && event is AutomationEvent.TravelTimeAccepted ->
                current.copy(state = AutomationState.VERIFYING_SEND)
            current.state == AutomationState.VERIFYING_SEND && event is AutomationEvent.SendVerified ->
                current.copy(state = AutomationState.RECOVERING_TO_EVENT_LIST)
            current.state == AutomationState.RECOVERING_TO_EVENT_LIST && event is AutomationEvent.EventListRecovered ->
                current.copy(state = AutomationState.SUCCESS)
            else -> return reject(event, "${current.state} does not accept ${event::class.simpleName}")
        }
        current = next
        return TransitionResult.Accepted(previous, next)
    }

    private fun reject(event: AutomationEvent, reason: String): TransitionResult.Rejected =
        TransitionResult.Rejected(event, current, reason)
}
