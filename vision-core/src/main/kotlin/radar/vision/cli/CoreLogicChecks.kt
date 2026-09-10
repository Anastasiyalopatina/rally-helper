package radar.vision.cli

import radar.vision.AutomationEvent
import radar.vision.AutomationState
import radar.vision.AutomationStateMachine
import radar.vision.AutoPolicy
import radar.vision.AutoPolicyConfig
import radar.vision.AutoPolicyDecision
import radar.vision.BossType
import radar.vision.CardCandidateSource
import radar.vision.FrameAnalysis
import radar.vision.JoinedState
import radar.vision.NormalizedRect
import radar.vision.RallyCandidate
import radar.vision.RallyConfidences
import radar.vision.RallyId
import radar.vision.RallyTracker
import radar.vision.RefreshCoordinator
import radar.vision.Recognition
import radar.vision.OneTapRequest
import radar.vision.OneTapRequestGuard
import radar.vision.ScoredCardCandidate
import radar.vision.ScreenState
import radar.vision.DecisionKind
import radar.vision.RuntimeMode
import radar.vision.RadarAlertPolicy
import radar.vision.SafetyController
import radar.vision.SafetyPolicy
import radar.vision.ShadowAutoCoordinator
import radar.vision.ShadowAutoPhase
import radar.vision.TargetSelector
import radar.vision.TransitionResult
import radar.vision.mergeCardCandidates
import kotlin.random.Random

fun main() {
    val upper = NormalizedRect(0.1, 0.1, 0.9, 0.3)
    val lower = NormalizedRect(0.1, 0.34, 0.9, 0.54)
    fun candidate(
        box: NormalizedRect,
        count: Int,
        timer: Int,
        bossType: BossType = BossType.TARGET,
        level: Int = 10,
        identity: radar.vision.RallyIdentityFingerprint? = null,
    ) = RallyCandidate(
        null, bossType, level, count, 5, timer, 0, box,
        listOf(NormalizedRect(0.6, box.top + 0.05, 0.65, box.top + 0.09)), true, false, JoinedState.JOINABLE,
        RallyConfidences(1f, 1f, 1f, 1f, 1f, 1f), identity,
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
    check(SafetyController().decide(RuntimeMode.RADAR, secondFrame, currentTracking).single().kind == DecisionKind.WOULD_SELECT) {
        "One free slot is sufficient"
    }
    val fullFrame = secondFrame.copy(rallies = listOf(candidate(upper, 5, 49)))
    val fullTrack = second.copy(candidate = candidate(upper, 5, 49), lastSeenFrameId = fullFrame.frameId)
    check(
        SafetyController().decide(RuntimeMode.RADAR, fullFrame, radar.vision.TrackingUpdate(listOf(fullTrack), emptyList()))
            .single().kind == DecisionKind.REJECT,
    ) { "A full rally must be rejected" }
    check(SafetyController(SafetyPolicy(safetyMarginSeconds = 3)).canSend(7, 11))
    check(!SafetyController(SafetyPolicy(safetyMarginSeconds = 3)).canSend(7, 10))
    check(SafetyController(SafetyPolicy(safetyMarginSeconds = 3)).canSend(75, 100)) {
        "Travel time must not have an arbitrary 60-second ceiling"
    }
    check(!SafetyController().canSend(null, 40))

    val cardHeight = 0.225
    fun scored(top: Double, score: Double, source: CardCandidateSource) = ScoredCardCandidate(
        NormalizedRect(0.02, top, 0.98, top + cardHeight), score, source,
    )
    val standard = mergeCardCandidates(
        listOf(scored(.15, .90, CardCandidateSource.LATTICE), scored(.151, .94, CardCandidateSource.FREE_SCAN)),
        cardHeight,
    )
    check(standard.size == 1 && kotlin.math.abs(standard.single().top - .15) < .001)
    val shifted = mergeCardCandidates(listOf(scored(.205, .88, CardCandidateSource.FREE_SCAN)), cardHeight)
    check(shifted.single().top == .205)
    val mixed = mergeCardCandidates(
        listOf(
            scored(.15, .90, CardCandidateSource.LATTICE),
            scored(.151, .93, CardCandidateSource.FREE_SCAN),
            scored(.455, .87, CardCandidateSource.FREE_SCAN),
        ),
        cardHeight,
    )
    check(mixed.size == 2 && mixed[1].top == .455) { "Lattice and shifted scan cards must be combined" }
    val partialBoundary = mergeCardCandidates(listOf(scored(.74, .83, CardCandidateSource.FREE_SCAN)), cardHeight)
    check(partialBoundary.single().bottom < 1.0) { "A partial-scroll candidate near the scan boundary must survive" }
    val scrolled = mergeCardCandidates(
        listOf(scored(.22, .82, CardCandidateSource.FREE_SCAN), scored(.48, .84, CardCandidateSource.FREE_SCAN)),
        cardHeight,
    )
    check(scrolled.map { it.top } == listOf(.22, .48))

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

    val afterTransientMiss = tracker.update(frame(4, listOf(candidate(upper, 3, 47)))).active.single { it.presentInCurrentFrame }
    check(afterTransientMiss.id == first.id) {
        "A rally must retain identity across one missed detector frame"
    }

    tracker.update(frame(8, emptyList()))
    val replacement = tracker.update(frame(9, listOf(candidate(upper, 1, 46)))).active.single { it.presentInCurrentFrame }
    check(replacement.id != first.id) { "A new card at the same Y must not reuse a disappeared track" }
    check(
        OneTapRequestGuard.resolve(OneTapRequest(first.id, 2), 9, radar.vision.TrackingUpdate(listOf(replacement), emptyList())) == null,
    ) { "A stale tap for A must not resolve to B at the same coordinates" }

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

    val visualIdentityA = radar.vision.RallyIdentityFingerprint(0x1111111111111111, 0x2222222222222222)
    val visualIdentityB = radar.vision.RallyIdentityFingerprint(0xeeeeeeeeeeeeeeeeUL.toLong(), 0xddddddddddddddddUL.toLong())
    val identityTracker = RallyTracker()
    val identityA = identityTracker.update(frame(40, listOf(candidate(upper, 1, 50, identity = visualIdentityA)))).active.single()
    val movedIdentityA = identityTracker.update(frame(41, listOf(candidate(lower, 3, 49, identity = visualIdentityA)))).active
        .single { it.presentInCurrentFrame }
    check(movedIdentityA.id == identityA.id) { "Stable title+coordinate identity must survive a card move" }
    val distinctIdentity = identityTracker.update(frame(42, listOf(candidate(lower, 1, 48, identity = visualIdentityB)))).active
        .single { it.presentInCurrentFrame }
    check(distinctIdentity.id != identityA.id) { "Different title+coordinate identity must create a different rally" }

    val refreshBounds = NormalizedRect(0.31, 0.93, 0.69, 0.98)
    fun refreshFrame(id: Long, visible: Boolean) = frame(id, emptyList()).copy(
        refreshButton = if (visible) Recognition(refreshBounds, 1f, accepted = true)
        else Recognition.unknown("not visible"),
    )
    val refresh = RefreshCoordinator(minimumIntervalMs = 750, retryAfterMs = 2_000)
    check(refresh.onFrame(refreshFrame(50, true))?.frameId == 50L)
    check(refresh.onFrame(refreshFrame(51, true)) == null) { "A visible refresh button must not be tapped every frame" }
    check(refresh.onFrame(refreshFrame(52, false)) == null)
    check(refresh.onFrame(refreshFrame(53, false)) == null)
    check(refresh.onFrame(refreshFrame(54, true))?.frameId == 54L) { "Two absent frames must rearm refresh" }
    check(refresh.onFrame(refreshFrame(55, true)) == null)
    check(refresh.onFrame(refreshFrame(57, true))?.frameId == 57L) { "A stuck button must retry after timeout" }

    refresh.reset()
    check(refresh.onFrame(refreshFrame(60, true).copy(observedAtMonotonicMs = 3_000))?.frameId == 60L)
    check(
        refresh.onFrame(
            refreshFrame(61, true).copy(
                screen = ScreenState.UNKNOWN,
                observedAtMonotonicMs = 3_100,
            ),
        ) == null,
    )
    check(refresh.onFrame(refreshFrame(62, true).copy(observedAtMonotonicMs = 3_200)) == null) {
        "A single uncertain screen frame must not rearm the same visible refresh button"
    }

    val machine = AutomationStateMachine()
    check(machine.dispatch(AutomationEvent.DelayElapsed(99), 0) is TransitionResult.Rejected)
    check(machine.dispatch(AutomationEvent.EligibleTarget(RallyId("r1"), 7), 100) is TransitionResult.Accepted)
    check(machine.current.state == AutomationState.TARGET_DETECTED)
    machine.dispatch(AutomationEvent.Pause, 110)
    check(machine.current.state == AutomationState.PAUSED)
    machine.dispatch(AutomationEvent.Resume, 120)
    check(machine.current.state == AutomationState.IDLE) { "Resume must start a fresh detection cycle" }
    check(machine.dispatch(AutomationEvent.DelayElapsed(7), 130) is TransitionResult.Rejected)

    val autoPolicy = AutoPolicy(AutoPolicyConfig(delayMinSeconds = 2, delayMaxSeconds = 4, skipMin = 1, skipMax = 1), Random(7))
    val skipped = autoPolicy.onEligible(RallyId("skip"))
    check(skipped is AutoPolicyDecision.Skip && skipped.remainingEligibleSkips == 0)
    autoPolicy.onRejected()
    val selected = autoPolicy.onEligible(RallyId("selected"))
    check(selected is AutoPolicyDecision.Wait && selected.delaySeconds in 2..4)
    check(autoPolicy.onEligible(RallyId("selected")) == selected) { "Delay must be sampled once per rally" }
    autoPolicy.onAttemptFinished(RallyId("selected"))
    check(autoPolicy.onEligible(RallyId("next")) is AutoPolicyDecision.Skip) { "A new skip K is required after an attempt" }
    autoPolicy.updateConfig(AutoPolicyConfig(skipMin = 2, skipMax = 2))
    val reconfigured = autoPolicy.onEligible(RallyId("reconfigured"))
    check(reconfigured is AutoPolicyDecision.Skip && reconfigured.remainingEligibleSkips == 1) {
        "A live range change must resample K inside the new range"
    }

    val priorityTracker = RallyTracker()
    priorityTracker.update(frame(30, listOf(candidate(upper, 1, 30, level = 5), candidate(lower, 1, 55, level = 10))))
    val priorityFrame = frame(31, listOf(candidate(upper, 2, 29, level = 5), candidate(lower, 2, 54, level = 10)))
    val priorityTracks = priorityTracker.update(priorityFrame)
    val priorityDecisions = SafetyController().decide(RuntimeMode.SHADOW_AUTO, priorityFrame, priorityTracks)
    check(TargetSelector().select(priorityFrame.frameId, priorityTracks, priorityDecisions)?.candidate?.level == 10) {
        "Higher configured target level must win deterministic selection"
    }

    val coordinator = ShadowAutoCoordinator(
        AutoPolicyConfig(delayMinSeconds = 2, delayMaxSeconds = 2, skipMin = 0, skipMax = 0),
        AutoPolicy(AutoPolicyConfig(2, 2, 0, 0), Random(1)),
    )
    val scheduled = coordinator.onFrame(priorityFrame, priorityTracks, priorityDecisions)
    check(scheduled.phase == ShadowAutoPhase.WAITING_DELAY && scheduled.delaySeconds == 2)
    val beforeDueFrame = priorityFrame.copy(frameId = 32, observedAtMonotonicMs = priorityFrame.observedAtMonotonicMs + 1_000)
    val beforeDueTracks = priorityTracks.copy(active = priorityTracks.active.map {
        it.copy(lastSeenFrameId = beforeDueFrame.frameId, lastSeenMonotonicMs = beforeDueFrame.observedAtMonotonicMs)
    })
    check(coordinator.onFrame(beforeDueFrame, beforeDueTracks, priorityDecisions).phase == ShadowAutoPhase.WAITING_DELAY)
    val dueFrame = beforeDueFrame.copy(frameId = 33, observedAtMonotonicMs = priorityFrame.observedAtMonotonicMs + 2_000)
    val dueTracks = beforeDueTracks.copy(active = beforeDueTracks.active.map {
        it.copy(lastSeenFrameId = dueFrame.frameId, lastSeenMonotonicMs = dueFrame.observedAtMonotonicMs)
    })
    val dueDecisions = SafetyController().decide(RuntimeMode.SHADOW_AUTO, dueFrame, dueTracks)
    check(coordinator.onFrame(dueFrame, dueTracks, dueDecisions).phase == ShadowAutoPhase.WOULD_START_JOIN_FLOW)
    check(coordinator.onFrame(dueFrame, dueTracks, dueDecisions).virtualAttempts == 1) { "Visible rally must be processed once" }

    coordinator.reset()
    coordinator.onFrame(priorityFrame, priorityTracks, priorityDecisions)
    coordinator.pause()
    check(coordinator.onFrame(dueFrame, dueTracks, dueDecisions).phase == ShadowAutoPhase.PAUSED)
    coordinator.resume(dueFrame.frameId)
    check(coordinator.onFrame(dueFrame, dueTracks, dueDecisions).phase == ShadowAutoPhase.IDLE) {
        "Resume must reject the pre-resume frame"
    }

    val cancellationPolicy = AutoPolicy(AutoPolicyConfig(2, 2, 1, 1), Random(3))
    check(cancellationPolicy.onEligible(RallyId("skip-a")) is AutoPolicyDecision.Skip)
    val pendingA = cancellationPolicy.onEligible(RallyId("pending-a")) as AutoPolicyDecision.Wait
    cancellationPolicy.onPendingCancelled(pendingA.rallyId)
    check(cancellationPolicy.onEligible(RallyId("pending-b")) is AutoPolicyDecision.Wait) {
        "Cancelling pending work must not resample skip K"
    }
    cancellationPolicy.resetSession()
    val sessionB = cancellationPolicy.onEligible(RallyId("session-b")) as AutoPolicyDecision.Skip
    check(sessionB.remainingEligibleSkips == 0) { "A new session must start an independent policy cycle" }

    val resetConfig = AutoPolicyConfig(0, 0, 2, 2)
    val resetCoordinator = ShadowAutoCoordinator(resetConfig, AutoPolicy(resetConfig, Random(9)))
    val sessionAFirst = resetCoordinator.onFrame(priorityFrame, priorityTracks, priorityDecisions)
    check(sessionAFirst.phase == ShadowAutoPhase.SKIPPED && sessionAFirst.policySkips == 1)
    resetCoordinator.reset()
    val sessionBFirst = resetCoordinator.onFrame(priorityFrame, priorityTracks, priorityDecisions)
    check(sessionBFirst.phase == ShadowAutoPhase.SKIPPED && sessionBFirst.policySkips == 1) {
        "Coordinator reset must restore a fresh skip K instead of leaking partially consumed session state"
    }

    val relaxedCandidate = candidate(upper, 1, 30).copy(
        participantCount = null,
        capacity = null,
        joinedState = JoinedState.UNKNOWN,
        confidences = RallyConfidences(1f, .95f, .9f, .1f, .85f, 1f),
    )
    val relaxedTrack = RallyTracker()
    relaxedTrack.update(frame(40, listOf(relaxedCandidate)))
    val relaxedFrame = frame(41, listOf(relaxedCandidate))
    val relaxedTracks = relaxedTrack.update(relaxedFrame)
    check(RadarAlertPolicy().decide(relaxedFrame, relaxedTracks).single().kind == DecisionKind.WOULD_SELECT)
    check(SafetyController().decide(RuntimeMode.SHADOW_AUTO, relaxedFrame, relaxedTracks).single().kind == DecisionKind.REJECT) {
        "Relaxed radar evidence must never authorize an action"
    }
    println(
        "PASS stale-track action guard; PASS duplicate/reorder identity; " +
            "PASS alert/action policy split; PASS deterministic single-flight shadow coordinator; " +
            "PASS combined card NMS/shift/partial/scroll; PASS out-of-policy/free-slot/travel guards; " +
            "PASS explicit transitions/pause/stale-rally-id guard",
    )
}
