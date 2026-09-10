package radar.vision

data class OneTapCompleteConfig(
    val squadPriority: List<Int> = listOf(1, 2, 3),
    val allowReturning: Boolean = true,
    val sendWhenTravelUnknown: Boolean = false,
    val safetyMarginSeconds: Int = 3,
)

data class OneTapRallyContext(
    val flowId: String,
    val rallyId: RallyId,
    val rallyLevel: Int,
    val rallyRemainingSecondsAtOpen: Int?,
    val rallyRemainingObservedAtMonotonicMs: Long,
    val joinPlusGestureId: String,
    val marchOpenedAtMonotonicMs: Long? = null,
)

enum class OneTapCompleteStage {
    IDLE,
    AWAITING_FRESH_RALLY,
    DISPATCHING_JOIN_PLUS,
    AWAITING_MARCH,
    ANALYZING_SQUADS,
    SELECTING_SQUAD,
    VERIFYING_SQUAD,
    CHECKING_SEND,
    DISPATCHING_SEND,
    VERIFYING_SEND,
    SUCCESS,
    FAILED,
    MANUAL_FALLBACK,
}

sealed interface OneTapCompleteUpdate {
    data object Ignored : OneTapCompleteUpdate
    data class Rejected(val reason: OneTapRejectReason) : OneTapCompleteUpdate
    data class Progress(val stage: OneTapCompleteStage, val detail: String) : OneTapCompleteUpdate
    data class Dispatch(
        val request: GestureRequest,
        val openVerified: Boolean = false,
        val selectionVerified: Boolean = false,
    ) : OneTapCompleteUpdate
    data class ManualFallback(
        val reason: String,
        val openVerified: Boolean,
        val squadSelected: Boolean,
    ) : OneTapCompleteUpdate
    data class Success(val flowId: String, val squadSlot: Int) : OneTapCompleteUpdate
    data class Failure(val reason: String) : OneTapCompleteUpdate
}

private sealed interface CompleteState {
    data object Idle : CompleteState
    data class AwaitingFresh(val request: UserJoinRequest, val flowId: String) : CompleteState
    data class DispatchingJoin(val request: UserJoinRequest, val context: OneTapRallyContext, val gesture: GestureRequest) : CompleteState
    data class AwaitingMarch(val context: OneTapRallyContext, val deadline: Long) : CompleteState
    data class DispatchingSelect(val context: OneTapRallyContext, val squad: MarchSquadInfo, val gesture: GestureRequest) : CompleteState
    data class VerifyingSquad(val context: OneTapRallyContext, val squad: MarchSquadInfo, val deadline: Long, val afterFrameId: Long) : CompleteState
    data class DispatchingSend(val context: OneTapRallyContext, val squad: MarchSquadInfo, val gesture: GestureRequest) : CompleteState
    data class VerifyingSend(val context: OneTapRallyContext, val squad: MarchSquadInfo, val deadline: Long) : CompleteState
}

/** One user authorization owns one bounded state-driven JOIN_PLUS -> SELECT_SQUAD -> SEND flow. */
class OneTapCompleteCoordinator(
    private val transitionWindowMs: Long = 2_000,
    private val requestTtlMs: Long = 750,
    private val minMarchConfidence: Float = .70f,
) {
    private var state: CompleteState = CompleteState.Idle
    private var sequence = 0L

    @Synchronized
    fun begin(request: UserJoinRequest): Boolean {
        if (state !is CompleteState.Idle) return false
        sequence++
        state = CompleteState.AwaitingFresh(request, "flow-${request.rallyId.value}-${request.requestedAtMonotonicMs}-$sequence")
        return true
    }

    @Synchronized
    fun onFrame(
        frame: FrameAnalysis,
        tracking: TrackingUpdate,
        openPolicy: OneTapOpenPolicy,
        config: OneTapCompleteConfig,
        expectedPackage: String,
        foregroundGeneration: Long,
        projectionGeneration: Long,
    ): OneTapCompleteUpdate = when (val current = state) {
        CompleteState.Idle -> OneTapCompleteUpdate.Ignored
        is CompleteState.AwaitingFresh -> openFromFresh(
            current, frame, tracking, openPolicy, expectedPackage, foregroundGeneration, projectionGeneration,
        )
        is CompleteState.AwaitingMarch -> analyzeMarch(
            current, frame, config, expectedPackage, foregroundGeneration, projectionGeneration,
        )
        is CompleteState.VerifyingSquad -> verifySquad(
            current, frame, config, expectedPackage, foregroundGeneration, projectionGeneration,
        )
        is CompleteState.VerifyingSend -> verifySend(current, frame)
        is CompleteState.DispatchingJoin -> OneTapCompleteUpdate.Progress(
            OneTapCompleteStage.DISPATCHING_JOIN_PLUS, "Открываю штурм…",
        )
        is CompleteState.DispatchingSelect -> OneTapCompleteUpdate.Progress(
            OneTapCompleteStage.SELECTING_SQUAD, "Выбираю отряд ${current.squad.slotIndex}…",
        )
        is CompleteState.DispatchingSend -> OneTapCompleteUpdate.Progress(
            OneTapCompleteStage.DISPATCHING_SEND, "Отправляю отряд…",
        )
    }

    @Synchronized
    fun onGestureCompleted(requestId: String, completed: Boolean, completedAtMonotonicMs: Long): OneTapCompleteUpdate {
        return when (val current = state) {
            is CompleteState.DispatchingJoin -> {
                if (current.gesture.requestId != requestId) return OneTapCompleteUpdate.Ignored
                if (!completed) return fail("JOIN_PLUS_GESTURE_FAILED")
                state = CompleteState.AwaitingMarch(current.context, completedAtMonotonicMs + transitionWindowMs)
                OneTapCompleteUpdate.Progress(OneTapCompleteStage.AWAITING_MARCH, "Проверяю экран отряда…")
            }
            is CompleteState.DispatchingSelect -> {
                if (current.gesture.requestId != requestId) return OneTapCompleteUpdate.Ignored
                if (!completed) return manual("SELECT_SQUAD_GESTURE_FAILED", true, false)
                state = CompleteState.VerifyingSquad(
                    current.context, current.squad, completedAtMonotonicMs + transitionWindowMs, current.gesture.sourceFrameId,
                )
                OneTapCompleteUpdate.Progress(OneTapCompleteStage.VERIFYING_SQUAD, "Проверяю выбранный отряд…")
            }
            is CompleteState.DispatchingSend -> {
                if (current.gesture.requestId != requestId) return OneTapCompleteUpdate.Ignored
                if (!completed) return manual("SEND_GESTURE_FAILED", true, true)
                state = CompleteState.VerifyingSend(current.context, current.squad, completedAtMonotonicMs + transitionWindowMs)
                OneTapCompleteUpdate.Progress(OneTapCompleteStage.VERIFYING_SEND, "Проверяю отправку…")
            }
            else -> OneTapCompleteUpdate.Ignored
        }
    }

    @Synchronized
    fun cancel(reason: String = "CANCELLED"): OneTapCompleteUpdate {
        if (state is CompleteState.Idle) return OneTapCompleteUpdate.Ignored
        state = CompleteState.Idle
        return OneTapCompleteUpdate.Failure(reason)
    }

    @Synchronized fun isActive() = state !is CompleteState.Idle
    @Synchronized fun activeFlowId(): String? = when (val s = state) {
        is CompleteState.AwaitingFresh -> s.flowId
        is CompleteState.DispatchingJoin -> s.context.flowId
        is CompleteState.AwaitingMarch -> s.context.flowId
        is CompleteState.DispatchingSelect -> s.context.flowId
        is CompleteState.VerifyingSquad -> s.context.flowId
        is CompleteState.DispatchingSend -> s.context.flowId
        is CompleteState.VerifyingSend -> s.context.flowId
        CompleteState.Idle -> null
    }
    @Synchronized fun currentRallyId(): RallyId? = when (val s = state) {
        is CompleteState.AwaitingFresh -> s.request.rallyId
        is CompleteState.DispatchingJoin -> s.context.rallyId
        is CompleteState.AwaitingMarch -> s.context.rallyId
        is CompleteState.DispatchingSelect -> s.context.rallyId
        is CompleteState.VerifyingSquad -> s.context.rallyId
        is CompleteState.DispatchingSend -> s.context.rallyId
        is CompleteState.VerifyingSend -> s.context.rallyId
        CompleteState.Idle -> null
    }

    private fun openFromFresh(
        current: CompleteState.AwaitingFresh,
        frame: FrameAnalysis,
        tracking: TrackingUpdate,
        policy: OneTapOpenPolicy,
        expectedPackage: String,
        foregroundGeneration: Long,
        projectionGeneration: Long,
    ): OneTapCompleteUpdate {
        val decision = policy.evaluate(current.request, frame, tracking)
        if (!decision.allowed) {
            state = CompleteState.Idle
            return OneTapCompleteUpdate.Rejected(decision.reason)
        }
        val rally = requireNotNull(decision.rally).candidate
        val gesture = GestureRequest(
            requestId = "join-${current.flowId}-${frame.frameId}",
            purpose = GesturePurpose.JOIN_PLUS,
            normalizedPoint = requireNotNull(decision.plusBounds).center,
            sourceFrameId = frame.frameId,
            rallyId = decision.rally.id,
            flowId = current.flowId,
            userAuthorizedOneTap = true,
            createdAtMonotonicMs = frame.observedAtMonotonicMs,
            expiresAtMonotonicMs = frame.observedAtMonotonicMs + requestTtlMs,
            expectedPackage = expectedPackage,
            expectedScreen = ScreenState.EVENT_LIST,
            expectedForegroundGeneration = foregroundGeneration,
            projectionSessionGeneration = projectionGeneration,
        )
        val context = OneTapRallyContext(
            flowId = current.flowId,
            rallyId = decision.rally.id,
            rallyLevel = requireNotNull(rally.level),
            rallyRemainingSecondsAtOpen = rally.remainingSeconds,
            rallyRemainingObservedAtMonotonicMs = frame.observedAtMonotonicMs,
            joinPlusGestureId = gesture.requestId,
        )
        state = CompleteState.DispatchingJoin(current.request, context, gesture)
        return OneTapCompleteUpdate.Dispatch(gesture)
    }

    private fun analyzeMarch(
        current: CompleteState.AwaitingMarch,
        frame: FrameAnalysis,
        config: OneTapCompleteConfig,
        expectedPackage: String,
        foregroundGeneration: Long,
        projectionGeneration: Long,
    ): OneTapCompleteUpdate {
        if (frame.screen != ScreenState.MARCH_SCREEN || frame.screenConfidence < minMarchConfidence) {
            if (frame.observedAtMonotonicMs < current.deadline) {
                return OneTapCompleteUpdate.Progress(OneTapCompleteStage.AWAITING_MARCH, "Открываю штурм…")
            }
            return manual("MARCH_SCREEN_NOT_VERIFIED", false, false)
        }
        val context = current.context.copy(marchOpenedAtMonotonicMs = frame.observedAtMonotonicMs)
        val selection = MarchSquadSelector.select(
            frame.marchSquads,
            MarchSquadSelectionConfig(config.squadPriority, config.allowReturning),
        )
        val squad = selection.squad ?: return manual(selection.reason, true, false)
        if (squad.selected && squad.selectedConfidence >= .68f) {
            return createSend(context, squad, frame, config, expectedPackage, foregroundGeneration, projectionGeneration, true)
        }
        val gesture = baseMarchGesture(
            "squad-${context.flowId}-${squad.slotIndex}-${frame.frameId}", GesturePurpose.SELECT_SQUAD,
            squad.bounds.center, context, frame, expectedPackage, foregroundGeneration, projectionGeneration,
        ).copy(squadSlotIndex = squad.slotIndex, squadEligible = true)
        state = CompleteState.DispatchingSelect(context, squad, gesture)
        return OneTapCompleteUpdate.Dispatch(gesture, openVerified = true)
    }

    private fun verifySquad(
        current: CompleteState.VerifyingSquad,
        frame: FrameAnalysis,
        config: OneTapCompleteConfig,
        expectedPackage: String,
        foregroundGeneration: Long,
        projectionGeneration: Long,
    ): OneTapCompleteUpdate {
        if (frame.frameId <= current.afterFrameId) return OneTapCompleteUpdate.Progress(
            OneTapCompleteStage.VERIFYING_SQUAD, "Жду свежий кадр выбранного отряда…",
        )
        if (frame.screen != ScreenState.MARCH_SCREEN || frame.screenConfidence < minMarchConfidence) {
            if (frame.observedAtMonotonicMs < current.deadline) return OneTapCompleteUpdate.Progress(
                OneTapCompleteStage.VERIFYING_SQUAD, "Проверяю отряд…",
            )
            return manual("SELECTED_SQUAD_NOT_VERIFIED", true, false)
        }
        val squad = frame.marchSquads.firstOrNull { it.slotIndex == current.squad.slotIndex }
        if (squad == null || !squad.selected || squad.selectedConfidence < .68f || !eligible(squad, config)) {
            if (frame.observedAtMonotonicMs < current.deadline) return OneTapCompleteUpdate.Progress(
                OneTapCompleteStage.VERIFYING_SQUAD, "Проверяю отряд ${current.squad.slotIndex}…",
            )
            return manual("SELECTED_SQUAD_CHANGED_OR_UNVERIFIED", true, false)
        }
        return createSend(
            current.context, squad, frame, config, expectedPackage, foregroundGeneration, projectionGeneration, true,
        )
    }

    private fun createSend(
        context: OneTapRallyContext,
        squad: MarchSquadInfo,
        frame: FrameAnalysis,
        config: OneTapCompleteConfig,
        expectedPackage: String,
        foregroundGeneration: Long,
        projectionGeneration: Long,
        selectionVerified: Boolean,
    ): OneTapCompleteUpdate {
        if (!frame.troopSanity.nonEmpty || frame.troopSanity.confidence < .70f) {
            return manual("TROOPS_NOT_VERIFIED", true, selectionVerified)
        }
        val send = frame.sendButton
        if (send == null || !send.enabled || send.confidence < .70f) {
            return manual("SEND_BUTTON_NOT_VERIFIED", true, selectionVerified)
        }
        val travel = frame.travelTime.value.takeIf { frame.travelTime.accepted }
        if (travel == null && !config.sendWhenTravelUnknown) {
            return manual("TRAVEL_TIME_UNKNOWN", true, selectionVerified)
        }
        if (travel != null) {
            val original = context.rallyRemainingSecondsAtOpen
                ?: return manual("RALLY_REMAINING_UNKNOWN", true, selectionVerified)
            val elapsed = ((frame.observedAtMonotonicMs - context.rallyRemainingObservedAtMonotonicMs).coerceAtLeast(0) / 1_000).toInt()
            val estimated = original - elapsed
            if (travel + config.safetyMarginSeconds >= estimated) {
                return manual("TOO_LATE", true, selectionVerified)
            }
        }
        val gesture = baseMarchGesture(
            "send-${context.flowId}-${frame.frameId}", GesturePurpose.SEND, send.bounds.center,
            context, frame, expectedPackage, foregroundGeneration, projectionGeneration,
        ).copy(
            squadSlotIndex = squad.slotIndex,
            squadEligible = true,
            selectedSquadVerified = true,
            sendButtonVerified = true,
        )
        state = CompleteState.DispatchingSend(context, squad, gesture)
        return OneTapCompleteUpdate.Dispatch(
            gesture,
            openVerified = true,
            selectionVerified = selectionVerified,
        )
    }

    private fun verifySend(current: CompleteState.VerifyingSend, frame: FrameAnalysis): OneTapCompleteUpdate {
        if (frame.screen == ScreenState.WORLD_MAP) {
            state = CompleteState.Idle
            return OneTapCompleteUpdate.Success(current.context.flowId, current.squad.slotIndex)
        }
        if (frame.screen == ScreenState.UNKNOWN || frame.observedAtMonotonicMs >= current.deadline) {
            return manual("SEND_UNVERIFIED", true, true)
        }
        return OneTapCompleteUpdate.Progress(OneTapCompleteStage.VERIFYING_SEND, "Проверяю отправку…")
    }

    private fun baseMarchGesture(
        id: String,
        purpose: GesturePurpose,
        point: NormalizedPoint,
        context: OneTapRallyContext,
        frame: FrameAnalysis,
        expectedPackage: String,
        foregroundGeneration: Long,
        projectionGeneration: Long,
    ) = GestureRequest(
        requestId = id,
        purpose = purpose,
        normalizedPoint = point,
        sourceFrameId = frame.frameId,
        rallyId = context.rallyId,
        flowId = context.flowId,
        userAuthorizedOneTap = true,
        createdAtMonotonicMs = frame.observedAtMonotonicMs,
        expiresAtMonotonicMs = frame.observedAtMonotonicMs + requestTtlMs,
        expectedPackage = expectedPackage,
        expectedScreen = ScreenState.MARCH_SCREEN,
        expectedForegroundGeneration = foregroundGeneration,
        projectionSessionGeneration = projectionGeneration,
    )

    private fun eligible(squad: MarchSquadInfo, config: OneTapCompleteConfig) = when (squad.state) {
        MarchSquadState.FREE -> squad.stateConfidence >= .78f
        MarchSquadState.RETURNING -> config.allowReturning && squad.redirectConfirmed && squad.stateConfidence >= .82f
        MarchSquadState.BUSY, MarchSquadState.UNKNOWN -> false
    }

    private fun manual(reason: String, open: Boolean, selected: Boolean): OneTapCompleteUpdate.ManualFallback {
        state = CompleteState.Idle
        return OneTapCompleteUpdate.ManualFallback(reason, open, selected)
    }

    private fun fail(reason: String): OneTapCompleteUpdate.Failure {
        state = CompleteState.Idle
        return OneTapCompleteUpdate.Failure(reason)
    }
}
