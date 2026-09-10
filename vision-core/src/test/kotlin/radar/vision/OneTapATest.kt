package radar.vision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OneTapATest {
    private val card = NormalizedRect(.10, .10, .90, .40)
    private val plus = NormalizedRect(.65, .20, .72, .28)
    private val oldId = RallyId("old")
    private val request = UserJoinRequest(oldId, displayedFrameId = 1, requestedAtMonotonicMs = 1_500)
    private val policy = OneTapOpenPolicy(setOf(10))

    private fun candidate(
        boss: BossType = BossType.TARGET,
        level: Int? = 10,
        full: Boolean? = false,
        joined: JoinedState = JoinedState.JOINABLE,
        pluses: List<NormalizedRect> = listOf(plus),
    ) = RallyCandidate(
        ephemeralId = oldId,
        bossType = boss,
        level = level,
        participantCount = null,
        capacity = null,
        remainingSeconds = 40,
        firstSeenMonotonicMs = 1_000,
        cardBounds = card,
        joinPlusBounds = pluses,
        joinable = pluses.isNotEmpty() && full != true,
        full = full,
        joinedState = joined,
        confidences = RallyConfidences(1f, .96f, .94f, .1f, .90f, .8f),
    )

    private fun frame(
        id: Long = 2,
        observedAt: Long = 2_000,
        screen: ScreenState = ScreenState.EVENT_LIST,
        confidence: Float = .98f,
    ) = FrameAnalysis(id, observedAt, screen, confidence)

    private fun track(
        id: RallyId = oldId,
        candidate: RallyCandidate = candidate(),
        frameId: Long = 2,
    ) = TrackedRally(id, candidate, 1_000, 2_000, 2, true, frameId, true)

    private fun tracking(vararg tracks: TrackedRally) = TrackingUpdate(tracks.toList(), emptyList())

    @Test fun `valid target and current id creates join plus request`() {
        val coordinator = OneTapFlowCoordinator()
        assertTrue(coordinator.begin(request))
        val rightPlus = NormalizedRect(.76, .20, .83, .28)
        val update = assertIs<OneTapFlowUpdate.Dispatch>(
            coordinator.onFreshFrame(
                frame(),
                tracking(track(candidate = candidate(pluses = listOf(rightPlus, plus)))),
                policy,
                "target.package",
            ),
        )
        assertEquals(GesturePurpose.JOIN_PLUS, update.request.purpose)
        assertEquals(oldId, update.request.rallyId)
        assertEquals(plus.center, update.request.normalizedPoint)
    }

    @Test fun `disappeared target emits no gesture`() {
        val decision = policy.evaluate(request, frame(), tracking())
        assertFalse(decision.allowed)
        assertEquals(OneTapRejectReason.TARGET_DISAPPEARED, decision.reason)
    }

    @Test fun `replacement at same geometry cannot replace requested identity`() {
        val replacement = track(RallyId("new"), candidate().copy(ephemeralId = RallyId("new")))
        assertEquals(
            OneTapRejectReason.TARGET_DISAPPEARED,
            policy.evaluate(request, frame(), tracking(replacement)).reason,
        )
    }

    @Test fun `non target with plus emits no gesture`() {
        assertEquals(
            OneTapRejectReason.NON_TARGET,
            policy.evaluate(request, frame(), tracking(track(candidate = candidate(boss = BossType.NON_TARGET)))).reason,
        )
    }

    @Test fun `full target emits no gesture`() {
        val full = candidate(full = true, joined = JoinedState.FULL)
        assertEquals(OneTapRejectReason.FULL, policy.evaluate(request, frame(), tracking(track(candidate = full))).reason)
    }

    @Test fun `wrong level emits no gesture`() {
        assertEquals(
            OneTapRejectReason.WRONG_LEVEL,
            policy.evaluate(request, frame(), tracking(track(candidate = candidate(level = 5)))).reason,
        )
    }

    @Test fun `missing plus emits no gesture`() {
        assertEquals(
            OneTapRejectReason.NO_PLUS,
            policy.evaluate(request, frame(), tracking(track(candidate = candidate(pluses = emptyList())))).reason,
        )
    }

    @Test fun `wrong foreground rejects join gesture`() {
        val gesture = validGesture()
        val decision = GestureSafetyGate.evaluate(
            gesture, "target.package", "other.package", 2_100, 2_200, true, false, false,
        )
        assertEquals(GestureRejectReason.WRONG_FOREGROUND_PACKAGE, decision.reason)
    }

    @Test fun `expired join request is rejected`() {
        val gesture = validGesture()
        val decision = GestureSafetyGate.evaluate(
            gesture, "target.package", "target.package", 2_100, 2_751, true, false, false,
        )
        assertEquals(GestureRejectReason.EXPIRED, decision.reason)
    }

    @Test fun `double overlay tap starts exactly one flow`() {
        val coordinator = OneTapFlowCoordinator()
        assertTrue(coordinator.begin(request))
        assertFalse(coordinator.begin(request.copy(requestedAtMonotonicMs = 1_501)))
    }

    @Test fun `projection stop before fresh frame emits no gesture`() {
        val coordinator = OneTapFlowCoordinator()
        assertTrue(coordinator.begin(request))
        assertTrue(coordinator.cancel())
        assertIs<OneTapFlowUpdate.Ignored>(coordinator.onFreshFrame(frame(), tracking(track()), policy, "target.package"))
    }

    @Test fun `unknown screen emits no gesture`() {
        val result = policy.evaluate(request, frame(screen = ScreenState.UNKNOWN), tracking(track()))
        assertEquals(OneTapRejectReason.WRONG_SCREEN, result.reason)
    }

    @Test fun `gesture completion without march screen becomes failure`() {
        val coordinator = dispatchedCoordinator()
        assertIs<OneTapFlowUpdate.AwaitingMarch>(coordinator.onGestureCompleted("join-old-2", true, 2_050))
        assertIs<OneTapFlowUpdate.AwaitingMarch>(coordinator.onVerificationFrame(frame(id = 3, observedAt = 3_000)))
        assertIs<OneTapFlowUpdate.Failure>(coordinator.onVerificationFrame(frame(id = 4, observedAt = 4_050)))
    }

    @Test fun `confirmed march screen becomes success`() {
        val coordinator = dispatchedCoordinator()
        assertIs<OneTapFlowUpdate.AwaitingMarch>(coordinator.onGestureCompleted("join-old-2", true, 2_050))
        assertIs<OneTapFlowUpdate.Success>(
            coordinator.onVerificationFrame(frame(id = 3, observedAt = 2_400, screen = ScreenState.MARCH_SCREEN)),
        )
    }

    private fun dispatchedCoordinator() = OneTapFlowCoordinator().also { coordinator ->
        assertTrue(coordinator.begin(request))
        assertIs<OneTapFlowUpdate.Dispatch>(
            coordinator.onFreshFrame(frame(), tracking(track()), policy, "target.package"),
        )
    }

    private fun validGesture() = GestureRequest(
        requestId = "join-old-2",
        purpose = GesturePurpose.JOIN_PLUS,
        normalizedPoint = plus.center,
        sourceFrameId = 2,
        rallyId = oldId,
        createdAtMonotonicMs = 2_000,
        expiresAtMonotonicMs = 2_750,
        expectedPackage = "target.package",
        expectedScreen = ScreenState.EVENT_LIST,
    )
}
