package radar.vision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OneTapCompleteTest {
    private val rallyId = RallyId("rally")
    private val card = NormalizedRect(.08, .15, .92, .72)
    private val plus = NormalizedRect(.43, .49, .57, .59)
    private val policy = OneTapOpenPolicy(setOf(10))

    @Test fun `M1 returning allowed selects squad one and proceeds to send`() {
        val coordinator = openedCoordinator()
        val select = assertIs<OneTapCompleteUpdate.Dispatch>(
            coordinator.onFrame(
                marchFrame(
                    squads = listOf(
                        squad(1, MarchSquadState.RETURNING, redirect = true),
                        squad(2, MarchSquadState.FREE),
                        squad(3, MarchSquadState.FREE),
                    ),
                ), tracking(), policy, config(), PKG, 4, 7,
            ),
        )
        assertEquals(1, select.request.squadSlotIndex)
        coordinator.onGestureCompleted(select.request.requestId, true, 2_250)
        val send = coordinator.onFrame(
            marchFrame(
                id = 4,
                at = 2_400,
                squads = listOf(squad(1, MarchSquadState.RETURNING, selected = true, redirect = true)),
            ), tracking(), policy, config(), PKG, 4, 7,
        )
        assertEquals(GesturePurpose.SEND, assertIs<OneTapCompleteUpdate.Dispatch>(send).request.purpose)
    }

    @Test fun `M3 busy squad one selects free squad two`() {
        val coordinator = openedCoordinator()
        val update = coordinator.onFrame(
            marchFrame(squads = listOf(squad(1, MarchSquadState.BUSY), squad(2, MarchSquadState.FREE))),
            tracking(), policy, config(), PKG, 4, 7,
        )
        assertEquals(2, assertIs<OneTapCompleteUpdate.Dispatch>(update).request.squadSlotIndex)
    }

    @Test fun `M4 all busy produces no send`() {
        assertEquals(
            "NO_ELIGIBLE_SQUAD",
            assertIs<OneTapCompleteUpdate.ManualFallback>(
                openedCoordinator().onFrame(
                    marchFrame(squads = (1..3).map { squad(it, MarchSquadState.BUSY) }),
                    tracking(), policy, config(), PKG, 4, 7,
                ),
            ).reason,
        )
    }

    @Test fun `M5 all unknown produces no send`() {
        assertEquals(
            "NO_ELIGIBLE_SQUAD",
            assertIs<OneTapCompleteUpdate.ManualFallback>(
                openedCoordinator().onFrame(
                    marchFrame(squads = (1..3).map { squad(it, MarchSquadState.UNKNOWN) }),
                    tracking(), policy, config(), PKG, 4, 7,
                ),
            ).reason,
        )
    }

    @Test fun `free first squad is selected then verified before send`() {
        val coordinator = openedCoordinator()
        val select = assertIs<OneTapCompleteUpdate.Dispatch>(
            coordinator.onFrame(marchFrame(squads = listOf(squad(1, MarchSquadState.FREE))), tracking(), policy, config(), PKG, 4, 7),
        )
        assertEquals(GesturePurpose.SELECT_SQUAD, select.request.purpose)
        coordinator.onGestureCompleted(select.request.requestId, true, 2_250)
        val send = assertIs<OneTapCompleteUpdate.Dispatch>(
            coordinator.onFrame(marchFrame(id = 4, at = 2_400, squads = listOf(squad(1, MarchSquadState.FREE, selected = true))), tracking(), policy, config(), PKG, 4, 7),
        )
        assertEquals(GesturePurpose.SEND, send.request.purpose)
        assertTrue(send.selectionVerified)
    }

    @Test fun `M6 best squad already selected avoids redundant selection`() {
        val coordinator = openedCoordinator()
        val send = assertIs<OneTapCompleteUpdate.Dispatch>(
            coordinator.onFrame(marchFrame(squads = listOf(squad(1, MarchSquadState.FREE, selected = true))), tracking(), policy, config(), PKG, 4, 7),
        )
        assertEquals(GesturePurpose.SEND, send.request.purpose)
    }

    @Test fun `returning squad is eligible only with confirmed redirect`() {
        val selection = MarchSquadSelector.select(
            listOf(squad(1, MarchSquadState.RETURNING, redirect = true)),
            MarchSquadSelectionConfig(allowReturning = true),
        )
        assertEquals(1, selection.squad?.slotIndex)
        assertEquals(null, MarchSquadSelector.select(
            listOf(squad(1, MarchSquadState.RETURNING, redirect = false)),
            MarchSquadSelectionConfig(allowReturning = true),
        ).squad)
    }

    @Test fun `M2 returning disabled falls through to squad two and can send`() {
        val selection = MarchSquadSelector.select(
            listOf(squad(1, MarchSquadState.RETURNING, redirect = true), squad(2, MarchSquadState.FREE)),
            MarchSquadSelectionConfig(allowReturning = false),
        )
        assertEquals(2, selection.squad?.slotIndex)
    }

    @Test fun `busy and unknown squads fail closed`() {
        val coordinator = openedCoordinator()
        val update = assertIs<OneTapCompleteUpdate.ManualFallback>(
            coordinator.onFrame(
                marchFrame(squads = listOf(squad(1, MarchSquadState.BUSY), squad(2, MarchSquadState.UNKNOWN))),
                tracking(), policy, config(), PKG, 4, 7,
            ),
        )
        assertEquals("NO_ELIGIBLE_SQUAD", update.reason)
    }

    @Test fun `configured priority is deterministic`() {
        val selection = MarchSquadSelector.select(
            listOf(squad(1, MarchSquadState.FREE), squad(2, MarchSquadState.FREE), squad(3, MarchSquadState.FREE)),
            MarchSquadSelectionConfig(priority = listOf(3, 1, 2)),
        )
        assertEquals(3, selection.squad?.slotIndex)
    }

    @Test fun `slot outside one through three is never authorized`() {
        val request = marchGesture(GesturePurpose.SELECT_SQUAD).copy(squadSlotIndex = 4, squadEligible = true)
        assertEquals(GestureRejectReason.SQUAD_NOT_ELIGIBLE, gate(request).reason)
    }

    @Test fun `M7 selected squad change is not accepted after tap`() {
        val coordinator = openedCoordinator()
        val select = assertIs<OneTapCompleteUpdate.Dispatch>(
            coordinator.onFrame(marchFrame(squads = listOf(squad(1, MarchSquadState.FREE))), tracking(), policy, config(), PKG, 4, 7),
        )
        coordinator.onGestureCompleted(select.request.requestId, true, 2_250)
        val update = assertIs<OneTapCompleteUpdate.ManualFallback>(
            coordinator.onFrame(marchFrame(id = 4, at = 4_300, squads = listOf(squad(1, MarchSquadState.FREE))), tracking(), policy, config(), PKG, 4, 7),
        )
        assertEquals("SELECTED_SQUAD_CHANGED_OR_UNVERIFIED", update.reason)
    }

    @Test fun `empty troops block send`() {
        val update = marchToSend(troops = TroopSanity(false, 1f))
        assertEquals("TROOPS_NOT_VERIFIED", assertIs<OneTapCompleteUpdate.ManualFallback>(update).reason)
    }

    @Test fun `missing send button blocks send`() {
        val update = marchToSend(send = null)
        assertEquals("SEND_BUTTON_NOT_VERIFIED", assertIs<OneTapCompleteUpdate.ManualFallback>(update).reason)
    }

    @Test fun `M9 unknown travel blocks send by default`() {
        val update = marchToSend(travel = Recognition.unknown("ocr"))
        assertEquals("TRAVEL_TIME_UNKNOWN", assertIs<OneTapCompleteUpdate.ManualFallback>(update).reason)
    }

    @Test fun `M10 explicit unknown travel fallback permits send`() {
        val coordinator = openedCoordinator()
        val update = coordinator.onFrame(
            marchFrame(
                squads = listOf(squad(1, MarchSquadState.FREE, selected = true)),
                travel = Recognition.unknown("ocr"),
            ), tracking(), policy, config(sendUnknown = true), PKG, 4, 7,
        )
        assertEquals(GesturePurpose.SEND, assertIs<OneTapCompleteUpdate.Dispatch>(update).request.purpose)
    }

    @Test fun `M8 insufficient remaining time blocks send`() {
        val update = marchToSend(at = 39_000, travel = accepted(8))
        assertEquals("TOO_LATE", assertIs<OneTapCompleteUpdate.ManualFallback>(update).reason)
    }

    @Test fun `send requires selected squad and verified button at safety gate`() {
        assertEquals(
            GestureRejectReason.SELECTED_SQUAD_NOT_VERIFIED,
            gate(marchGesture(GesturePurpose.SEND).copy(selectedSquadVerified = false, sendButtonVerified = true)).reason,
        )
        assertEquals(
            GestureRejectReason.SEND_BUTTON_NOT_VERIFIED,
            gate(marchGesture(GesturePurpose.SEND).copy(selectedSquadVerified = true, sendButtonVerified = false)).reason,
        )
    }

    @Test fun `M11 foreground switch before squad selection rejects tap`() {
        val request = marchGesture(GesturePurpose.SELECT_SQUAD)
        val decision = GestureSafetyGate.evaluate(
            request, PKG, "other.package", 2_050, 2_100, true, false, false,
            currentForegroundGeneration = 4,
            currentProjectionSessionGeneration = 7,
            activeOneTapFlowId = "flow",
        )
        assertEquals(GestureRejectReason.WRONG_FOREGROUND_PACKAGE, decision.reason)
    }

    @Test fun `M12 foreground switch before send rejects tap`() {
        val request = marchGesture(GesturePurpose.SEND)
        val decision = GestureSafetyGate.evaluate(
            request, PKG, "other.package", 2_050, 2_100, true, false, false,
            currentForegroundGeneration = 4,
            currentProjectionSessionGeneration = 7,
            activeOneTapFlowId = "flow",
        )
        assertEquals(GestureRejectReason.WRONG_FOREGROUND_PACKAGE, decision.reason)
    }

    @Test fun `M13 cancelled projection flow emits zero further gestures`() {
        val coordinator = openedCoordinator()
        assertIs<OneTapCompleteUpdate.Failure>(coordinator.cancel("PROJECTION_LOST"))
        assertIs<OneTapCompleteUpdate.Ignored>(
            coordinator.onFrame(
                marchFrame(squads = listOf(squad(1, MarchSquadState.FREE))),
                tracking(), policy, config(), PKG, 4, 7,
            ),
        )
    }

    @Test fun `M16 send to world map is verified success`() {
        val coordinator = openedCoordinator()
        val dispatch = assertIs<OneTapCompleteUpdate.Dispatch>(
            coordinator.onFrame(marchFrame(squads = listOf(squad(1, MarchSquadState.FREE, selected = true))), tracking(), policy, config(), PKG, 4, 7),
        )
        coordinator.onGestureCompleted(dispatch.request.requestId, true, 2_300)
        assertIs<OneTapCompleteUpdate.Progress>(
            coordinator.onFrame(marchFrame(id = 4, at = 2_500), tracking(), policy, config(), PKG, 4, 7),
        )
        assertIs<OneTapCompleteUpdate.Success>(
            coordinator.onFrame(frame(5, 2_700, ScreenState.WORLD_MAP), tracking(), policy, config(), PKG, 4, 7),
        )
    }

    @Test fun `M15 completed send without screen change is never success`() {
        val coordinator = openedCoordinator()
        val dispatch = assertIs<OneTapCompleteUpdate.Dispatch>(
            coordinator.onFrame(marchFrame(squads = listOf(squad(1, MarchSquadState.FREE, selected = true))), tracking(), policy, config(), PKG, 4, 7),
        )
        coordinator.onGestureCompleted(dispatch.request.requestId, true, 2_300)
        assertIs<OneTapCompleteUpdate.Progress>(
            coordinator.onFrame(marchFrame(id = 4, at = 2_500), tracking(), policy, config(), PKG, 4, 7),
        )
        assertEquals(
            "SEND_UNVERIFIED",
            assertIs<OneTapCompleteUpdate.ManualFallback>(
                coordinator.onFrame(marchFrame(id = 5, at = 4_301), tracking(), policy, config(), PKG, 4, 7),
            ).reason,
        )
    }

    @Test fun `unknown post-send frame fails closed`() {
        val coordinator = openedCoordinator()
        val dispatch = assertIs<OneTapCompleteUpdate.Dispatch>(
            coordinator.onFrame(marchFrame(squads = listOf(squad(1, MarchSquadState.FREE, selected = true))), tracking(), policy, config(), PKG, 4, 7),
        )
        coordinator.onGestureCompleted(dispatch.request.requestId, true, 2_300)
        val update = coordinator.onFrame(frame(5, 2_500, ScreenState.UNKNOWN), tracking(), policy, config(), PKG, 4, 7)
        assertEquals("SEND_UNVERIFIED", assertIs<OneTapCompleteUpdate.ManualFallback>(update).reason)
    }

    @Test fun `M14 double user join still owns one complete flow`() {
        val coordinator = OneTapCompleteCoordinator()
        assertTrue(coordinator.begin(userRequest()))
        assertFalse(coordinator.begin(userRequest().copy(requestedAtMonotonicMs = 1_501)))
    }

    @Test fun `wrong flow cannot select or send`() {
        val request = marchGesture(GesturePurpose.SELECT_SQUAD).copy(flowId = "other", squadSlotIndex = 1, squadEligible = true)
        assertEquals(GestureRejectReason.WRONG_FLOW, gate(request, activeFlow = "flow").reason)
    }

    private fun openedCoordinator(): OneTapCompleteCoordinator {
        val coordinator = OneTapCompleteCoordinator()
        assertTrue(coordinator.begin(userRequest()))
        val join = assertIs<OneTapCompleteUpdate.Dispatch>(
            coordinator.onFrame(eventFrame(), tracking(track()), policy, config(), PKG, 4, 7),
        )
        assertEquals(GesturePurpose.JOIN_PLUS, join.request.purpose)
        coordinator.onGestureCompleted(join.request.requestId, true, 2_100)
        return coordinator
    }

    private fun marchToSend(
        at: Long = 2_200,
        travel: Recognition<Int> = accepted(5),
        send: SendButtonCandidate? = SendButtonCandidate(NormalizedRect(.25, .82, .75, .94), true, 1f),
        troops: TroopSanity = TroopSanity(true, 1f),
    ): OneTapCompleteUpdate {
        val coordinator = openedCoordinator()
        return coordinator.onFrame(
            marchFrame(at = at, squads = listOf(squad(1, MarchSquadState.FREE, selected = true)), travel = travel, send = send, troops = troops),
            tracking(), policy, config(), PKG, 4, 7,
        )
    }

    private fun userRequest() = UserJoinRequest(rallyId, 1, 1_500)

    private fun eventFrame() = frame(2, 2_000, ScreenState.EVENT_LIST).copy(rallies = listOf(candidate()))

    private fun marchFrame(
        id: Long = 3,
        at: Long = 2_200,
        squads: List<MarchSquadInfo> = emptyList(),
        travel: Recognition<Int> = accepted(5),
        send: SendButtonCandidate? = SendButtonCandidate(NormalizedRect(.25, .82, .75, .94), true, 1f),
        troops: TroopSanity = TroopSanity(true, 1f),
    ) = frame(id, at, ScreenState.MARCH_SCREEN).copy(
        travelTime = travel,
        sendButton = send,
        marchSquads = squads,
        troopSanity = troops,
    )

    private fun frame(id: Long, at: Long, screen: ScreenState) = FrameAnalysis(id, at, screen, 1f)

    private fun candidate() = RallyCandidate(
        rallyId, BossType.TARGET, 10, 1, 5, 45, 1_000, card, listOf(plus), true, false,
        JoinedState.JOINABLE, RallyConfidences(1f, 1f, 1f, 1f, 1f, 1f),
    )

    private fun track() = TrackedRally(rallyId, candidate(), 1_000, 2_000, 2, true, 2, true)
    private fun tracking(vararg active: TrackedRally) = TrackingUpdate(active.toList(), emptyList())

    private fun squad(
        slot: Int,
        state: MarchSquadState,
        selected: Boolean = false,
        redirect: Boolean = false,
    ) = MarchSquadInfo(
        slot, NormalizedRect(.1 * slot, .70, .1 * slot + .08, .80), state, selected,
        stateConfidence = 1f, selectedConfidence = if (selected) 1f else 0f, redirectConfirmed = redirect,
    )

    private fun config(sendUnknown: Boolean = false) = OneTapCompleteConfig(sendWhenTravelUnknown = sendUnknown)
    private fun accepted(value: Int) = Recognition(value, 1f, accepted = true)

    private fun marchGesture(purpose: GesturePurpose) = GestureRequest(
        "g", purpose, NormalizedPoint(.5, .5), 3, rallyId, "flow", 1, true,
        selectedSquadVerified = true, sendButtonVerified = true, userAuthorizedOneTap = true,
        createdAtMonotonicMs = 2_000, expiresAtMonotonicMs = 2_750,
        expectedPackage = PKG, expectedScreen = ScreenState.MARCH_SCREEN,
        expectedForegroundGeneration = 4, projectionSessionGeneration = 7,
    )

    private fun gate(request: GestureRequest, activeFlow: String = "flow") = GestureSafetyGate.evaluate(
        request, PKG, PKG, 2_050, 2_100, true, false, false,
        currentForegroundGeneration = 4,
        currentProjectionSessionGeneration = 7,
        activeOneTapFlowId = activeFlow,
        currentSourceFrameId = request.sourceFrameId,
    )

    private companion object { const val PKG = "target.package" }
}
