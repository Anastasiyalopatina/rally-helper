package radar.vision.cli

import radar.vision.AutomationEvent
import radar.vision.AutomationState
import radar.vision.AutomationStateMachine
import radar.vision.BossType
import radar.vision.FrameAnalysis
import radar.vision.JoinedState
import radar.vision.NormalizedRect
import radar.vision.RallyCandidate
import radar.vision.RallyConfidences
import radar.vision.RallyId
import radar.vision.RallyTracker
import radar.vision.ScreenState
import radar.vision.DecisionKind
import radar.vision.RuntimeMode
import radar.vision.SafetyController
import radar.vision.SafetyPolicy
import radar.vision.TransitionResult

fun main() {
    val upper = NormalizedRect(0.1, 0.1, 0.9, 0.3)
    val lower = NormalizedRect(0.1, 0.34, 0.9, 0.54)
    fun candidate(
        box: NormalizedRect,
        count: Int,
        timer: Int,
        bossType: BossType = BossType.TARGET,
        level: Int = 10,
    ) = RallyCandidate(
        null, bossType, level, count, 5, timer, 0, box,
        listOf(NormalizedRect(0.6, box.top + 0.05, 0.65, box.top + 0.09)), true, false, JoinedState.JOINABLE,
        RallyConfidences(1f, 1f, 1f, 1f, 1f, 1f),
    )
    fun frame(id: Long, rallies: List<RallyCandidate>) = FrameAnalysis(
        frameId = id,
        observedAtMonotonicMs = id * 1_000,
        screen = ScreenState.EVENT_LIST,
        screenConfidence = 1f,
        rallies = rallies,
    )
    val tracker = RallyTracker()
    val firstFrame = frame(1, listOf(candidate(upper, 1, 50)))
    val first = tracker.update(firstFrame).active.single()
    check(!first.stable)
    val secondFrame = frame(2, listOf(candidate(upper, 2, 49)))
    val second = tracker.update(secondFrame).active.single()
    check(second.stable && second.id == first.id && second.candidate.participantCount == 2)
    val currentTracking = radar.vision.TrackingUpdate(listOf(second), emptyList())
    check(
        SafetyController().decide(RuntimeMode.SHADOW_AUTO, secondFrame, currentTracking)
            .single().kind == DecisionKind.WOULD_SELECT,
    )
    check(
        SafetyController(SafetyPolicy(targetLevels = emptySet()))
            .decide(RuntimeMode.SHADOW_AUTO, secondFrame, currentTracking).single().kind == DecisionKind.REJECT,
    ) { "An empty target policy must select nothing" }

    val outOfPolicyTracker = RallyTracker()
    outOfPolicyTracker.update(frame(20, listOf(candidate(upper, 1, 30, BossType.UNKNOWN, 14))))
    val outOfPolicyFrame = frame(21, listOf(candidate(upper, 2, 29, BossType.UNKNOWN, 14)))
    val outOfPolicy = outOfPolicyTracker.update(outOfPolicyFrame)
    check(
        SafetyController().decide(RuntimeMode.SHADOW_AUTO, outOfPolicyFrame, outOfPolicy)
            .single().reason == "level outside policy",
    ) { "A recognized out-of-policy level must fail closed before boss classification" }

    val missingFrame = frame(3, emptyList())
    val missing = tracker.update(missingFrame)
    val stale = missing.active.single()
    check(!stale.presentInCurrentFrame && stale.lastSeenFrameId == 2L)
    check(
        SafetyController().decide(RuntimeMode.SHADOW_AUTO, missingFrame, missing)
            .none { it.kind == DecisionKind.WOULD_SELECT },
    )

    val replacement = tracker.update(frame(4, listOf(candidate(upper, 1, 46)))).active.single { it.presentInCurrentFrame }
    check(replacement.id != first.id) { "A new card at the same Y must not reuse a disappeared track" }

    val reorderTracker = RallyTracker()
    val initial = reorderTracker.update(
        frame(10, listOf(candidate(upper, 1, 50), candidate(lower, 1, 44))),
    ).active.filter { it.presentInCurrentFrame }
    val confirmed = reorderTracker.update(
        frame(11, listOf(candidate(upper, 2, 49), candidate(lower, 2, 43))),
    ).active.filter { it.presentInCurrentFrame }
    check(confirmed.size == 2 && confirmed.map { it.id }.toSet() == initial.map { it.id }.toSet())
    val lowerId = confirmed.single { it.candidate.remainingSeconds == 43 }.id
    val reordered = reorderTracker.update(frame(12, listOf(candidate(upper, 3, 42))))
    check(reordered.active.single { it.presentInCurrentFrame }.id == lowerId) {
        "Timer trajectory must preserve the lower rally identity after reorder"
    }

    val machine = AutomationStateMachine()
    check(machine.dispatch(AutomationEvent.DelayElapsed(99), 0) is TransitionResult.Rejected)
    check(machine.dispatch(AutomationEvent.EligibleTarget(RallyId("r1"), 7), 100) is TransitionResult.Accepted)
    check(machine.current.state == AutomationState.TARGET_DETECTED)
    machine.dispatch(AutomationEvent.Pause, 110)
    check(machine.current.state == AutomationState.PAUSED)
    machine.dispatch(AutomationEvent.Resume, 120)
    check(machine.current.state == AutomationState.TARGET_DETECTED)
    check(machine.dispatch(AutomationEvent.DelayElapsed(6), 130) is TransitionResult.Rejected)
    check(machine.dispatch(AutomationEvent.DelayElapsed(7), 130) is TransitionResult.Accepted)
    println(
        "PASS stale-track action guard; PASS duplicate/reorder identity; " +
            "PASS out-of-policy early reject; PASS explicit transitions/pause/flow-id guard",
    )
}
