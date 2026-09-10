package com.rallyhelper.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.Image
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.rallyhelper.MainActivity
import com.rallyhelper.RadarRuntime
import com.rallyhelper.RuntimeLifecycle
import com.rallyhelper.data.DebugCaptureMode
import com.rallyhelper.data.RadarRepository
import com.rallyhelper.data.RadarSettings
import com.rallyhelper.data.RadarSettingsStore
import com.rallyhelper.debug.DebugCaptureStore
import com.rallyhelper.debug.CaptureLabLabel
import com.rallyhelper.debug.CaptureLabStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import radar.vision.BossType
import radar.vision.AutoPolicyConfig
import radar.vision.CalibrationProfile
import radar.vision.DecisionKind
import radar.vision.JoinedState
import radar.vision.RallyTracker
import radar.vision.RadarAlertPolicy
import radar.vision.RuntimeMode
import radar.vision.SafetyPolicy
import radar.vision.SafetyController
import radar.vision.ShadowAutoCoordinator
import radar.vision.ShadowAutoPhase
import radar.vision.ShadowAutoUpdate
import radar.vision.ScreenState
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class RadarForegroundService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val pending = AtomicReference<Image?>()
    private val draining = AtomicBoolean(false)
    private val capturedContentVisible = AtomicBoolean(true)
    private val frameIds = AtomicLong()
    private val lastOfferedForAnalysisNs = AtomicLong()
    private val tracker = RallyTracker()
    private val calibration = CalibrationProfile()
    private val detector by lazy { RadarDetectorFactory.create(applicationContext) }
    private val debugStore by lazy { DebugCaptureStore(applicationContext) }
    private val captureLabStore by lazy { CaptureLabStore(applicationContext) }
    private val settingsStore by lazy { RadarSettingsStore(applicationContext) }
    private val repository by lazy { RadarRepository.create(applicationContext) }
    private val overlay by lazy {
        RallyOverlayController(
            applicationContext,
            onJoinRequested = ::handleJoinRequested,
            onPauseRequested = ::toggleAutomationPause,
        )
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings = AtomicReference(RadarSettings())
    private val shadowCoordinator = ShadowAutoCoordinator()
    private var appliedAutoConfig = AutoPolicyConfig()
    private val frameBuffer = ScreenCaptureController.Companion.ReusableFrameBuffer()
    private val alertSound by lazy { LocalAlertSound(applicationContext) }
    private lateinit var captureThread: HandlerThread
    private var capture: ScreenCaptureController? = null
    private var projection: MediaProjection? = null
    private var lastDebugCaptureMs = 0L
    private val seenRallies = mutableSetOf<String>()
    private val alertedRallies = mutableSetOf<String>()
    private val observationState = mutableMapOf<String, String>()
    private val lastCandidateById = mutableMapOf<String, radar.vision.RallyCandidate>()
    private val lastDecisionState = mutableMapOf<String, String>()
    private val eligibleRallies = mutableSetOf<String>()
    private val nonTargetRallies = mutableSetOf<String>()
    private val fullRallies = mutableSetOf<String>()
    private val unknownRallies = mutableSetOf<String>()
    private val policySkippedRallies = mutableSetOf<String>()
    private val selectedDelayByRally = mutableMapOf<String, Int>()
    private val skipDecisionByRally = mutableMapOf<String, Boolean>()
    private val debuggedTargets = mutableSetOf<String>()
    private val recentLatencies = ArrayDeque<Long>()
    private var latencyTotalMs = 0L
    private var latencySamples = 0L
    private var safetyRejectCount = 0L
    private var visionRejectCount = 0L
    private var safetyAbortCount = 0L
    private var sessionId: Long? = null
    private var captureWidth = 0
    private var captureHeight = 0
    private var lastAbortReason: String? = null
    @Volatile private var automationPaused = false
    @Volatile private var lastAnalyzedFrameId: Long? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        captureThread = HandlerThread("radar-capture").also { it.start() }
        serviceScope.launch {
            settingsStore.settings.distinctUntilChanged().collect { updated ->
                applySettings(updated)
                debugStore.purgeExpired(updated.retentionDays)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PAUSE) {
            setAutomationPaused(true)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESUME) {
            setAutomationPaused(false)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CAPTURE_LAB) {
            val label = intent.getStringExtra(EXTRA_CAPTURE_LABEL)
                ?.let { runCatching { CaptureLabLabel.valueOf(it) }.getOrNull() }
                ?: CaptureLabLabel.UNKNOWN_UI
            val optionalIntValue = intent.getIntExtra(EXTRA_CAPTURE_VALUE, Int.MIN_VALUE)
                .takeUnless { it == Int.MIN_VALUE }
            val archive = captureLabStore.save(label, optionalIntValue)
            RadarRuntime.update {
                it.copy(message = archive?.let { file -> "Capture Lab сохранён локально: ${file.name}" }
                    ?: "Capture Lab: буфер пока пуст")
            }
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || capture != null) return START_NOT_STICKY
        val initialSettings = runBlocking(Dispatchers.IO) { settingsStore.settings.first() }
        applySettings(initialSettings)
        resetSessionState(initialSettings)
        startForegroundCompat(notification("Запуск захвата…"))
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else @Suppress("DEPRECATION") (intent.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            RadarRuntime.update { it.copy(message = "Нет действительного разрешения MediaProjection") }
            stopSelf()
            return START_NOT_STICKY
        }
        startProjection(resultCode, resultData)
        return START_NOT_STICKY
    }

    private fun applySettings(updated: RadarSettings) {
        val previous = settings.get()
        settings.set(updated)
        val policyConfig = AutoPolicyConfig(
            delayMinSeconds = updated.delayMinSeconds,
            delayMaxSeconds = updated.delayMaxSeconds,
            skipMin = updated.skipMin,
            skipMax = updated.skipMax,
        )
        if (policyConfig != appliedAutoConfig) {
            shadowCoordinator.updateConfig(policyConfig)
            appliedAutoConfig = policyConfig
        }
        captureLabStore.setArmed(updated.captureLabArmed)
        if (previous.mode.isAutoLoop() && !updated.mode.isAutoLoop()) {
            shadowCoordinator.pause()
            automationPaused = false
            RadarRuntime.update {
                it.copy(
                    lifecycle = if (it.running) RuntimeLifecycle.RUNNING else it.lifecycle,
                    shadowPhase = ShadowAutoPhase.IDLE.name,
                    shadowDelayRemainingSeconds = null,
                    message = "Auto policy отменена при смене режима; ожидается новый цикл",
                )
            }
            val current = RadarRuntime.status.value
            overlay.update(
                updated.mode,
                null,
                OverlayCounters(current.successes, current.failures, current.policySkipped, current.shadowSelections),
            )
        } else if (!previous.mode.isAutoLoop() && updated.mode.isAutoLoop()) {
            shadowCoordinator.resume(lastAnalyzedFrameId)
        }
        overlay.setEnabled(updated.overlayEnabled)
        RadarRuntime.update { it.copy(mode = updated.mode) }
    }

    private fun resetSessionState(currentSettings: RadarSettings) {
        pending.getAndSet(null)?.close()
        tracker.reset()
        shadowCoordinator.reset()
        frameIds.set(0)
        lastOfferedForAnalysisNs.set(0)
        lastAnalyzedFrameId = null
        automationPaused = false
        seenRallies.clear()
        alertedRallies.clear()
        observationState.clear()
        lastCandidateById.clear()
        lastDecisionState.clear()
        eligibleRallies.clear()
        nonTargetRallies.clear()
        fullRallies.clear()
        unknownRallies.clear()
        policySkippedRallies.clear()
        selectedDelayByRally.clear()
        skipDecisionByRally.clear()
        debuggedTargets.clear()
        recentLatencies.clear()
        latencyTotalMs = 0
        latencySamples = 0
        safetyRejectCount = 0
        visionRejectCount = 0
        safetyAbortCount = 0
        lastAbortReason = null
        RadarRuntime.resetForSession(currentSettings.mode)
    }

    private fun toggleAutomationPause() = setAutomationPaused(!automationPaused)

    private fun setAutomationPaused(value: Boolean) {
        automationPaused = value
        if (value) shadowCoordinator.pause() else shadowCoordinator.resume(lastAnalyzedFrameId)
        RadarRuntime.update {
            it.copy(
                lifecycle = if (value) RuntimeLifecycle.AUTOMATION_PAUSED else RuntimeLifecycle.RUNNING,
                shadowPhase = if (value) ShadowAutoPhase.PAUSED.name else ShadowAutoPhase.IDLE.name,
                shadowDelayRemainingSeconds = null,
                message = if (value) {
                    "Автоматизация на паузе; Radar и уведомления продолжают работать"
                } else {
                    "Автоматизация возобновлена; ожидается новый кадр"
                },
            )
        }
        val current = RadarRuntime.status.value
        overlay.update(
            current.mode,
            null,
            OverlayCounters(current.successes, current.failures, current.policySkipped, current.shadowSelections),
            OverlayAutomationState(value, current.shadowPhase, null),
        )
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            notification(if (value) "Автоматизация приостановлена · Radar активен" else "Автоматизация возобновлена"),
        )
    }

    private fun recordShadowUpdate(update: ShadowAutoUpdate) {
        val id = update.rallyId?.value ?: return
        when (update.phase) {
            ShadowAutoPhase.SKIPPED -> {
                policySkippedRallies += id
                skipDecisionByRally[id] = true
            }
            ShadowAutoPhase.WAITING_DELAY -> {
                skipDecisionByRally[id] = false
                update.delaySeconds?.let { selectedDelayByRally[id] = it }
            }
            else -> Unit
        }
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        val metrics = displayMetrics()
        val manager = getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = manager.getMediaProjection(resultCode, data)
        projection = mediaProjection
        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Handler(Looper.getMainLooper()).post {
                    RadarRuntime.update { it.copy(running = false, message = "MediaProjection остановлен системой") }
                    stopSelf()
                }
            }

            override fun onCapturedContentResize(width: Int, height: Int) {
                Handler(Looper.getMainLooper()).post { handleCapturedContentResize(width, height) }
            }

            override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                capturedContentVisible.set(isVisible)
                if (!isVisible) {
                    recordAbortOnce("captured-content-not-visible")
                    RadarRuntime.update { it.copy(message = "Radar paused: shared content is not visible") }
                }
            }
        }, Handler(Looper.getMainLooper()))
        if (!calibration.isCompatible(metrics.widthPixels, metrics.heightPixels)) {
            RadarRuntime.update {
                it.copy(
                    running = true,
                    lifecycle = RuntimeLifecycle.NEEDS_CALIBRATION,
                    message = "RADAR_PAUSED_NEEDS_CALIBRATION: ${calibration.incompatibilityReason(metrics.widthPixels, metrics.heightPixels)}",
                )
            }
            return
        }
        captureWidth = metrics.widthPixels
        captureHeight = metrics.heightPixels
        sessionId = runBlocking(Dispatchers.IO) {
            repository.beginSession(settings.get().calibrationProfile, settings.get().mode)
        }
        capture = ScreenCaptureController(
            mediaProjection,
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            Handler(captureThread.looper),
        ).also { source -> source.setListener(::offerLatest) }
        RadarRuntime.update {
            it.copy(
                running = true,
                lifecycle = RuntimeLifecycle.RUNNING,
                mode = settings.get().mode,
                sessionStartedAtEpochMs = System.currentTimeMillis(),
                message = "Radar работает локально · ${metrics.widthPixels}×${metrics.heightPixels}",
            )
        }
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            notification("Radar работает · ${metrics.widthPixels}×${metrics.heightPixels}"),
        )
    }

    private fun handleCapturedContentResize(width: Int, height: Int) {
        if (!calibration.isCompatible(width, height)) {
            pauseForGeometry("captured content resized to ${width}×$height")
            return
        }
        captureWidth = width
        captureHeight = height
        capture?.resize(width, height, resources.displayMetrics.densityDpi)
        RadarRuntime.update { it.copy(message = "Capture resized · ${width}×$height") }
    }

    private fun offerLatest(image: Image) {
        if (!capturedContentVisible.get()) {
            image.close()
            RadarRuntime.update { it.copy(framesDropped = it.framesDropped + 1) }
            return
        }
        val now = System.nanoTime()
        val intervalNs = if (RadarRuntime.status.value.screen == ScreenState.EVENT_LIST) {
            250_000_000L
        } else {
            500_000_000L
        }
        while (true) {
            val previousOfferNs = lastOfferedForAnalysisNs.get()
            if (now - previousOfferNs < intervalNs) {
                image.close()
                RadarRuntime.update { it.copy(framesThrottled = it.framesThrottled + 1) }
                return
            }
            if (lastOfferedForAnalysisNs.compareAndSet(previousOfferNs, now)) break
        }
        val previous = pending.getAndSet(image)
        if (previous != null) {
            previous.close()
            RadarRuntime.update { it.copy(framesDropped = it.framesDropped + 1) }
        }
        if (draining.compareAndSet(false, true)) executor.execute(::drainLatest)
    }

    private fun drainLatest() {
        try {
            while (true) {
                val image = pending.getAndSet(null) ?: break
                val started = System.nanoTime()
                try {
                    if (image.width != captureWidth || image.height != captureHeight) {
                        pauseForGeometry("image dimensions ${image.width}×${image.height}, expected ${captureWidth}×$captureHeight")
                        continue
                    }
                    val currentSettings = settings.get()
                    val argbImage = frameBuffer.copyFrom(image)
                    overlay.boundsNormalized(image.width, image.height)?.let(argbImage::mask)
                    val observedMs = android.os.SystemClock.elapsedRealtime()
                    val analysis = detector.analyze(argbImage, frameIds.incrementAndGet(), observedMs)
                    lastAnalyzedFrameId = analysis.frameId
                    if (analysis.screen == ScreenState.UNKNOWN) recordAbortOnce("screen:UNKNOWN") else lastAbortReason = null
                    val tracking = tracker.update(analysis)
                    val activeNow = tracking.active.filter { it.stable && it.presentInCurrentFrame && it.lastSeenFrameId == analysis.frameId }
                    activeNow.forEach { seenRallies += it.id.value }
                    val safety = SafetyController(
                        SafetyPolicy(
                            targetLevels = currentSettings.selectedLevels,
                            minimumFreeSlots = currentSettings.minimumFreeSlots,
                            safetyMarginSeconds = currentSettings.safetyMarginSeconds,
                        ),
                    )
                    val actionDecisions = safety.decide(currentSettings.mode, analysis, tracking)
                    val alertDecisions = RadarAlertPolicy(
                        SafetyPolicy(
                            targetLevels = currentSettings.selectedLevels,
                            minimumFreeSlots = currentSettings.minimumFreeSlots,
                            safetyMarginSeconds = currentSettings.safetyMarginSeconds,
                        ),
                    ).decide(analysis, tracking)
                    val decisions = if (currentSettings.mode == RuntimeMode.RADAR) alertDecisions else actionDecisions
                    val activeNowIds = activeNow.mapTo(hashSetOf()) { it.id }
                    val currentRadarDecisions = alertDecisions.filter { it.rallyId == null || it.rallyId in activeNowIds }
                    val currentActionDecisions = actionDecisions.filter { it.rallyId == null || it.rallyId in activeNowIds }
                    val decisionById = decisions.mapNotNull { decision -> decision.rallyId?.value?.let { it to decision } }.toMap()
                    activeNow.forEach { track ->
                        val id = track.id.value
                        when {
                            track.candidate.level != null && track.candidate.level !in currentSettings.selectedLevels ->
                                nonTargetRallies += id
                            track.candidate.bossType == BossType.NON_TARGET -> nonTargetRallies += id
                            track.candidate.bossType == BossType.UNKNOWN || track.candidate.level == null -> unknownRallies += id
                            track.candidate.joinedState == JoinedState.FULL -> fullRallies += id
                        }
                        if (decisionById[id]?.kind == DecisionKind.WOULD_SELECT) eligibleRallies += id
                    }
                    val newlyAlerted = if (currentSettings.mode != RuntimeMode.SHADOW_AUTO) {
                        decisions.filter { decision ->
                            decision.kind == DecisionKind.WOULD_SELECT &&
                                decision.rallyId?.value?.let(alertedRallies::add) == true
                        }
                    } else emptyList()
                    newlyAlerted.forEach { decision ->
                        val level = tracking.active.firstOrNull { it.id == decision.rallyId }?.candidate?.level
                        emitAlert(level, currentSettings)
                    }
                    val shadowUpdate = if (currentSettings.mode == RuntimeMode.SHADOW_AUTO || currentSettings.mode == RuntimeMode.AUTO) {
                        shadowCoordinator.onFrame(analysis, tracking, actionDecisions).also(::recordShadowUpdate)
                    } else ShadowAutoUpdate(ShadowAutoPhase.IDLE)
                    if (currentSettings.captureLabArmed) {
                        frameBuffer.snapshotDownscaled().also { small ->
                            captureLabStore.add(
                                bitmap = small,
                                analysis = analysis,
                                mode = currentSettings.mode,
                                radarDecisions = currentRadarDecisions,
                                actionDecisions = currentActionDecisions,
                                currentTracks = activeNow,
                                shadowUpdate = shadowUpdate,
                                actualAlertEmitted = newlyAlerted.isNotEmpty(),
                            )
                            small.recycle()
                        }
                    }
                    val overlayRallyId = shadowUpdate.rallyId
                        ?: decisions.firstOrNull { it.kind == DecisionKind.WOULD_SELECT }?.rallyId
                    val overlayRally = overlayRallyId?.let { id ->
                        activeNow.firstOrNull { it.id == id }?.candidate
                    }
                    val delayRemaining = shadowUpdate.dueAtMonotonicMs?.let { due ->
                        ((due - observedMs).coerceAtLeast(0L) + 999L) / 1_000L
                    }?.toInt()
                    overlay.update(
                        currentSettings.mode,
                        overlayRally,
                        OverlayCounters(
                            realSuccess = RadarRuntime.status.value.successes,
                            realFailed = RadarRuntime.status.value.failures,
                            skipped = shadowUpdate.policySkips.toLong(),
                            shadowWouldAttempt = shadowUpdate.virtualAttempts.toLong(),
                        ),
                        OverlayAutomationState(automationPaused, shadowUpdate.phase.name, delayRemaining),
                    )
                    persistMeaningfulEvents(activeNow, tracking.expired, decisions, analysis.observedAtMonotonicMs)
                    val nowEpochMs = System.currentTimeMillis()
                    val diagnosticFailure = analysis.screen == radar.vision.ScreenState.UNKNOWN ||
                        analysis.rallies.any { it.bossType == BossType.UNKNOWN || it.level == null || it.full == true && it.joinPlusBounds.isNotEmpty() }
                    val newTargetForDebug = activeNow.firstOrNull { track ->
                        track.candidate.bossType == BossType.TARGET && debuggedTargets.add(track.id.value)
                    }
                    val debugReason = when (currentSettings.debugMode) {
                        DebugCaptureMode.OFF -> null
                        DebugCaptureMode.FAILURES -> "low-confidence".takeIf { diagnosticFailure && nowEpochMs - lastDebugCaptureMs >= 60_000 }
                        DebugCaptureMode.ALL_TARGETS -> when {
                            newTargetForDebug != null -> "new-target"
                            diagnosticFailure && nowEpochMs - lastDebugCaptureMs >= 60_000 -> "low-confidence"
                            else -> null
                        }
                    }
                    if (debugReason != null) {
                        val snapshot = frameBuffer.snapshotBitmap()
                        debugStore.saveAnnotated(snapshot, analysis, debugReason)
                        snapshot.recycle()
                        lastDebugCaptureMs = nowEpochMs
                    }
                    val latencyMs = (System.nanoTime() - started) / 1_000_000
                    recordLatency(latencyMs)
                    val sortedLatencies = recentLatencies.sorted()
                    RadarRuntime.update { status ->
                        status.copy(
                            running = true,
                            screen = analysis.screen,
                            framesAnalyzed = status.framesAnalyzed + 1,
                            ralliesSeen = seenRallies.size.toLong(),
                            lastLatencyMs = latencyMs,
                            eligible = eligibleRallies.size.toLong(),
                            nonTarget = nonTargetRallies.size.toLong(),
                            full = fullRallies.size.toLong(),
                            unknown = unknownRallies.size.toLong(),
                            alertsEmitted = alertedRallies.size.toLong(),
                            shadowSelections = shadowUpdate.virtualAttempts.toLong(),
                            policySkipped = shadowUpdate.policySkips.toLong(),
                            shadowPhase = shadowUpdate.phase.name,
                            shadowDelayRemainingSeconds = delayRemaining,
                            attempts = shadowUpdate.virtualAttempts.toLong(),
                            safetyRejects = safetyRejectCount,
                            visionRejects = visionRejectCount,
                            safetyAborts = safetyAbortCount,
                            averageLatencyMs = if (latencySamples == 0L) null else latencyTotalMs / latencySamples,
                            p50LatencyMs = sortedLatencies.percentile(0.50),
                            p95LatencyMs = sortedLatencies.percentile(0.95),
                            mode = currentSettings.mode,
                            message = when {
                                automationPaused -> "Автоматизация на паузе; Radar и уведомления продолжают работать"
                                currentSettings.mode == RuntimeMode.SHADOW_AUTO ->
                                    "Shadow ${shadowUpdate.phase}: ${shadowUpdate.reason ?: "наблюдение"}"
                                currentSettings.mode == RuntimeMode.ONE_TAP || currentSettings.mode == RuntimeMode.AUTO ->
                                    "${currentSettings.mode}: действия заблокированы до device validation"
                                newlyAlerted.isNotEmpty() -> "Новая подходящая карточка"
                                else -> "Radar работает"
                            },
                        )
                    }
                } finally {
                    image.close()
                }
            }
        } catch (error: Throwable) {
            RadarRuntime.update { it.copy(message = "Radar paused: ${error.javaClass.simpleName}") }
            recordAbortOnce("runtime:${error.javaClass.simpleName}")
        } finally {
            draining.set(false)
            if (pending.get() != null && draining.compareAndSet(false, true)) executor.execute(::drainLatest)
        }
    }

    private fun persistMeaningfulEvents(
        activeNow: List<radar.vision.TrackedRally>,
        expired: List<radar.vision.RallyId>,
        decisions: List<radar.vision.DetectorDecision>,
        observedAtMonotonicMs: Long,
    ) {
        val activeSession = sessionId ?: return
        val decisionsById = decisions.mapNotNull { it.rallyId?.value?.let { id -> id to it } }.toMap()
        activeNow.forEach { track ->
            val id = track.id.value
            val candidate = track.candidate
            val actionable = decisionsById[id]?.kind == DecisionKind.WOULD_SELECT
            val countdownBucket = candidate.remainingSeconds?.div(5)
            val fingerprint = listOf(
                candidate.bossType,
                candidate.level,
                candidate.participantCount,
                candidate.capacity,
                candidate.joinedState,
                candidate.joinable,
                countdownBucket,
                actionable,
                selectedDelayByRally[id],
                skipDecisionByRally[id],
            ).joinToString("|")
            val previous = observationState.put(id, fingerprint)
            lastCandidateById[id] = candidate
            if (previous != fingerprint) {
                serviceScope.launch {
                    repository.recordObservation(
                        activeSession,
                        id,
                        candidate,
                        observedAtMonotonicMs,
                        actionable,
                        selectedDelayByRally[id],
                        skipDecisionByRally[id],
                        eventType = if (previous == null) "FIRST_SEEN" else "STATE_CHANGED",
                    )
                }
            }
        }
        expired.forEach { rallyId ->
            val id = rallyId.value
            val candidate = lastCandidateById.remove(id)
            observationState.remove(id)
            selectedDelayByRally.remove(id)
            skipDecisionByRally.remove(id)
            if (candidate != null) serviceScope.launch {
                repository.recordObservation(
                    activeSession,
                    id,
                    candidate,
                    observedAtMonotonicMs,
                    actionable = false,
                    selectedDelaySeconds = null,
                    skipDecision = null,
                    eventType = "DISAPPEARED",
                )
            }
        }
        decisions.forEach { decision ->
            val key = decision.rallyId?.value ?: "frame"
            val state = "${decision.kind}:${decision.reason}"
            if (lastDecisionState.put(key, state) != state) {
                if (decision.kind == DecisionKind.REJECT) {
                    safetyRejectCount++
                    if (
                        decision.reason.contains("unknown") ||
                        decision.reason.contains("confidence") ||
                        decision.reason.contains("screen") ||
                        decision.reason.contains("absent")
                    ) visionRejectCount++
                }
                serviceScope.launch { repository.recordDecision(activeSession, decision) }
            }
        }
    }

    private fun emitAlert(level: Int?, currentSettings: RadarSettings) {
        if (currentSettings.soundEnabled) alertSound.play()
        if (currentSettings.vibrationEnabled) {
            val vibrator = getSystemService(Vibrator::class.java)
            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(120, 90))
            else @Suppress("DEPRECATION") vibrator.vibrate(120)
        }
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            notification("Новая подходящая карточка${level?.let { " · L$it" }.orEmpty()}"),
        )
    }

    private fun handleJoinRequested() {
        recordAbortOnce("join-flow-locked:device-validation-required")
        RadarRuntime.update {
            it.copy(message = "JoinFlow заблокирован: сначала завершите RADAR/SHADOW device validation")
        }
    }

    private fun recordLatency(value: Long) {
        latencyTotalMs += value
        latencySamples++
        recentLatencies.addLast(value)
        if (recentLatencies.size > 600) recentLatencies.removeFirst()
    }

    private fun recordAbortOnce(reason: String) {
        if (lastAbortReason == reason) return
        lastAbortReason = reason
        safetyAbortCount++
        val activeSession = sessionId ?: return
        serviceScope.launch { repository.recordAbort(activeSession, reason) }
    }

    private fun pauseForGeometry(reason: String) {
        pending.getAndSet(null)?.close()
        capture?.close()
        capture = null
        shadowCoordinator.pause()
        automationPaused = true
        recordAbortOnce("geometry:$reason")
        val message = "RADAR_PAUSED_NEEDS_CALIBRATION: $reason"
        RadarRuntime.update {
            it.copy(
                running = true,
                lifecycle = RuntimeLifecycle.NEEDS_CALIBRATION,
                shadowPhase = ShadowAutoPhase.PAUSED.name,
                shadowDelayRemainingSeconds = null,
                message = message,
            )
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }

    override fun onDestroy() {
        pending.getAndSet(null)?.close()
        capture?.close(); capture = null
        projection?.stop(); projection = null
        executor.shutdownNow()
        captureThread.quitSafely()
        frameBuffer.close()
        overlay.close()
        serviceScope.cancel()
        val summary = RadarRuntime.status.value
        runBlocking(Dispatchers.IO) { sessionId?.let { repository.endSession(it, summary) } }
        repository.close()
        alertSound.close()
        RadarRuntime.update {
            it.copy(running = false, lifecycle = RuntimeLifecycle.STOPPED, message = "Остановлен")
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 2, stopIntent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Rally Helper")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
        if (settings.get().mode.isAutoLoop()) {
            val automationAction = PendingIntent.getService(
                this,
                3,
                Intent(this, RadarForegroundService::class.java).setAction(
                    if (automationPaused) ACTION_RESUME else ACTION_PAUSE,
                ),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, if (automationPaused) "Возобновить" else "Пауза авто", automationAction)
        }
        return builder.addAction(0, "Остановить", stop).build()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Radar", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun displayMetrics(): DisplayMetrics {
        val metrics = resources.displayMetrics
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            metrics.widthPixels = bounds.width(); metrics.heightPixels = bounds.height()
        } else @Suppress("DEPRECATION") getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    companion object {
        private const val ACTION_START = "com.rallyhelper.START_RADAR"
        private const val ACTION_STOP = "com.rallyhelper.STOP_RADAR"
        private const val ACTION_PAUSE = "com.rallyhelper.PAUSE_AUTOMATION"
        private const val ACTION_RESUME = "com.rallyhelper.RESUME_AUTOMATION"
        private const val ACTION_CAPTURE_LAB = "com.rallyhelper.SAVE_CAPTURE_LAB"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_CAPTURE_LABEL = "capture_label"
        private const val EXTRA_CAPTURE_VALUE = "capture_value"
        private const val CHANNEL_ID = "radar"
        private const val NOTIFICATION_ID = 42

        fun startIntent(context: Context, resultCode: Int, data: Intent) =
            Intent(context, RadarForegroundService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode).putExtra(EXTRA_RESULT_DATA, data)

        fun stopIntent(context: Context) = Intent(context, RadarForegroundService::class.java).setAction(ACTION_STOP)

        fun captureLabIntent(context: Context, label: CaptureLabLabel, optionalIntValue: Int?) =
            Intent(context, RadarForegroundService::class.java).setAction(ACTION_CAPTURE_LAB)
                .putExtra(EXTRA_CAPTURE_LABEL, label.name)
                .apply { optionalIntValue?.let { putExtra(EXTRA_CAPTURE_VALUE, it) } }
    }
}

private fun RuntimeMode.isAutoLoop(): Boolean = this == RuntimeMode.AUTO || this == RuntimeMode.SHADOW_AUTO

private fun List<Long>.percentile(fraction: Double): Long? {
    if (isEmpty()) return null
    val index = kotlin.math.ceil((size - 1) * fraction).toInt().coerceIn(indices)
    return this[index]
}
