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
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.rallyhelper.MainActivity
import com.rallyhelper.BuildConfig
import com.rallyhelper.RadarRuntime
import com.rallyhelper.RadarStatus
import com.rallyhelper.RuntimeLifecycle
import com.rallyhelper.data.DebugCaptureMode
import com.rallyhelper.data.RadarRepository
import com.rallyhelper.data.RadarSettings
import com.rallyhelper.data.RadarSettingsStore
import com.rallyhelper.debug.DebugCaptureStore
import com.rallyhelper.input.GestureActionController
import com.rallyhelper.debug.CaptureLabLabel
import com.rallyhelper.debug.CaptureDatasetSplit
import com.rallyhelper.debug.CaptureLabMetadata
import com.rallyhelper.debug.CaptureLabStore
import com.rallyhelper.debug.GuidedValidationStatus
import com.rallyhelper.debug.GuidedValidationStore
import com.rallyhelper.debug.OneTapAttemptStore
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
import radar.vision.FrameAnalysis
import radar.vision.GestureCoordinator
import radar.vision.GesturePurpose
import radar.vision.JoinedState
import radar.vision.OneTapCompleteConfig
import radar.vision.OneTapCompleteCoordinator
import radar.vision.OneTapCompleteStage
import radar.vision.OneTapCompleteUpdate
import radar.vision.OneTapOpenPolicy
import radar.vision.OneTapRequest
import radar.vision.RallyTracker
import radar.vision.RefreshCoordinator
import radar.vision.RefreshMode
import radar.vision.RadarAlertPolicy
import radar.vision.RuntimeMode
import radar.vision.SafetyPolicy
import radar.vision.SafetyController
import radar.vision.ShadowAutoCoordinator
import radar.vision.ShadowAutoPhase
import radar.vision.ShadowAutoUpdate
import radar.vision.ScreenState
import radar.vision.SquadState
import radar.vision.TargetSelector
import radar.vision.UserJoinRequest
import radar.vision.effectiveOverlayEnabled
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class RadarForegroundService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val pending = AtomicReference<Image?>()
    private val draining = AtomicBoolean(false)
    private val capturedContentVisible = AtomicBoolean(true)
    private val actionInvalidationGeneration = AtomicLong()
    private val stopping = AtomicBoolean(false)
    private val frameIds = AtomicLong()
    private val lastOfferedForAnalysisNs = AtomicLong()
    private val tracker = RallyTracker()
    private val refreshCoordinator = RefreshCoordinator()
    private val gestureCoordinator = GestureCoordinator()
    private val oneTapCoordinator = OneTapCompleteCoordinator()
    private val targetSelector = TargetSelector()
    private val calibration = CalibrationProfile()
    private val detector by lazy { RadarDetectorFactory.create(applicationContext) }
    private val debugStore by lazy { DebugCaptureStore(applicationContext) }
    private val captureLabStore by lazy { CaptureLabStore(applicationContext) }
    private val guidedValidationStore by lazy { GuidedValidationStore(applicationContext) }
    private val oneTapAttemptStore by lazy { OneTapAttemptStore(applicationContext) }
    private val settingsStore by lazy { RadarSettingsStore(applicationContext) }
    private val repository by lazy { RadarRepository.create(applicationContext) }
    private val overlay by lazy {
        RallyOverlayController(
            applicationContext,
            onJoinRequested = ::handleJoinRequested,
            onActionsPermissionRequested = ::openAccessibilitySettings,
            onPauseRequested = ::toggleAutomationPause,
            onStopRequested = ::stopFromUi,
        )
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings = AtomicReference(RadarSettings())
    private val shadowCoordinator = ShadowAutoCoordinator()
    private var appliedAutoConfig = AutoPolicyConfig()
    private val frameBuffer = ScreenCaptureController.Companion.ReusableFrameBuffer()
    private val alertFeedback by lazy { LocalAlertFeedback(applicationContext) }
    private lateinit var captureThread: HandlerThread
    private var capture: ScreenCaptureController? = null
    private var projection: MediaProjection? = null
    private var projectionSessionGeneration: Long = 0
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
    private val evidenceKeys = mutableSetOf<String>()
    private val recentLatencies = ArrayDeque<Long>()
    private var latencyTotalMs = 0L
    private var latencySamples = 0L
    private var safetyRejectCount = 0L
    private var visionRejectCount = 0L
    private var safetyAbortCount = 0L
    private var sessionId: Long? = null
    private var captureWidth = 0
    private var captureHeight = 0
    private var captureDensityDpi = 0
    private var lastAbortReason: String? = null
    @Volatile private var automationPaused = false
    @Volatile private var lastAnalyzedFrameId: Long? = null
    @Volatile private var overlayJoinState = OverlayJoinState()
    @Volatile private var pendingOneTapRequestId: String? = null
    @Volatile private var oneTapOpenCounted = false
    @Volatile private var oneTapSelectionCounted = false
    @Volatile private var oneTapSelectionAttempted = false
    @Volatile private var oneTapSendAttempted = false

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
            stopFromUi()
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
            val split = intent.getStringExtra(EXTRA_CAPTURE_SPLIT)
                ?.let { runCatching { CaptureDatasetSplit.valueOf(it) }.getOrNull() }
                ?: CaptureDatasetSplit.TUNING
            val marked = captureLabStore.markScenario(label, optionalIntValue, split)
            RadarRuntime.update {
                it.copy(message = if (marked) {
                    "MARK SCENARIO: 3 сек до + 3 сек после · ${label.name} · ${split.name}"
                } else "Capture Lab: буфер пуст или уже идёт запись сценария")
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_GUIDED_VALIDATION) {
            val case = intent.getStringExtra(EXTRA_GUIDED_CASE)
                ?.let { id -> GuidedValidationStore.CASES.firstOrNull { it.id == id } }
            if (case != null) {
                guidedValidationStore.start(case, android.os.SystemClock.elapsedRealtime())
                captureLabStore.setArmed(true)
                RadarRuntime.update { it.copy(message = "${case.id}: bounded observation 45 sec") }
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
        if (stopping.get()) return
        val previous = settings.get()
        settings.set(updated)
        if (previous.refreshMode != updated.refreshMode) {
            refreshCoordinator.cancelPending()
            GestureActionController.cancelAll()
        }
        if (previous.mode == RuntimeMode.ONE_TAP && updated.mode != RuntimeMode.ONE_TAP) {
            oneTapCoordinator.cancel()
            gestureCoordinator.cancelAll()
            GestureActionController.cancelAll()
            overlayJoinState = OverlayJoinState()
        }
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
        captureLabStore.setArmed(
            updated.captureLabArmed || updated.evidenceCollectorEnabled ||
                guidedValidationStore.active() != null || captureLabStore.hasPendingScenario(),
        )
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
                null,
                null,
                current.toOverlayCounters(),
            )
        } else if (!previous.mode.isAutoLoop() && updated.mode.isAutoLoop()) {
            shadowCoordinator.resume(lastAnalyzedFrameId)
        }
        overlay.setEnabled(updated.mode.effectiveOverlayEnabled(updated.overlayEnabled))
        RadarRuntime.update { it.copy(mode = updated.mode, refreshMode = updated.refreshMode) }
    }

    private fun resetSessionState(currentSettings: RadarSettings) {
        pending.getAndSet(null)?.close()
        capturedContentVisible.set(true)
        actionInvalidationGeneration.incrementAndGet()
        tracker.reset()
        refreshCoordinator.reset()
        oneTapCoordinator.cancel()
        gestureCoordinator.cancelAll()
        overlayJoinState = OverlayJoinState()
        pendingOneTapRequestId = null
        oneTapOpenCounted = false
        oneTapSelectionCounted = false
        oneTapSelectionAttempted = false
        oneTapSendAttempted = false
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
        evidenceKeys.clear()
        recentLatencies.clear()
        latencyTotalMs = 0
        latencySamples = 0
        safetyRejectCount = 0
        visionRejectCount = 0
        safetyAbortCount = 0
        lastAbortReason = null
        RadarRuntime.resetForSession(currentSettings.mode, currentSettings.refreshMode)
    }

    private fun toggleAutomationPause() = setAutomationPaused(!automationPaused)

    private fun setAutomationPaused(value: Boolean) {
        automationPaused = value
        if (value) {
            refreshCoordinator.cancelPending()
            GestureActionController.cancelAll()
        }
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
            null,
            null,
            current.toOverlayCounters(),
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
        projectionSessionGeneration = GestureActionController.beginProjectionSession()
        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Handler(Looper.getMainLooper()).post {
                    cancelAllActions("Захват экрана остановлен")
                    GestureActionController.endProjectionSession(projectionSessionGeneration)
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
                    cancelAllActions("Экран игры больше не виден")
                    recordAbortOnce("captured-content-not-visible")
                    RadarRuntime.update { it.copy(message = "Radar paused: shared content is not visible") }
                } else {
                    RadarRuntime.update { it.copy(message = "Экран снова виден · ожидается новая цель") }
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
        captureDensityDpi = metrics.densityDpi
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
        captureDensityDpi = resources.displayMetrics.densityDpi
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
                    val overlayBounds = overlay.boundsNormalized(image.width, image.height)
                    // The user owns the overlay position. Mask its pixels from CV instead of
                    // snapping the window away mid-drag; obscured evidence then fails closed.
                    overlayBounds?.let(argbImage::mask)
                    val observedMs = android.os.SystemClock.elapsedRealtime()
                    val nextFrameId = frameIds.incrementAndGet()
                    if (BuildVariantHooks.requiresGeometryInvalidation(applicationContext)) {
                        pauseForGeometry("integration geometry invalidation")
                        continue
                    }
                    val analysis = BuildVariantHooks.analysisOverride(
                        applicationContext,
                        nextFrameId,
                        observedMs,
                    ) ?: detector.analyze(argbImage, nextFrameId, observedMs)
                    lastAnalyzedFrameId = analysis.frameId
                    if (analysis.screen == ScreenState.UNKNOWN) recordAbortOnce("screen:UNKNOWN") else lastAbortReason = null
                    val tracking = tracker.update(analysis)
                    val visibleNow = tracking.active.filter {
                        it.presentInCurrentFrame && it.lastSeenFrameId == analysis.frameId
                    }
                    val activeNow = tracking.active.filter { it.stable && it.presentInCurrentFrame && it.lastSeenFrameId == analysis.frameId }
                    activeNow.forEach { seenRallies += it.id.value }
                    val safety = SafetyController(
                        SafetyPolicy(
                            targetLevels = currentSettings.selectedLevels,
                            safetyMarginSeconds = currentSettings.safetyMarginSeconds,
                        ),
                    )
                    val actionDecisions = safety.decide(currentSettings.mode, analysis, tracking)
                    val alertDecisions = RadarAlertPolicy(
                        SafetyPolicy(
                            targetLevels = currentSettings.selectedLevels,
                            safetyMarginSeconds = currentSettings.safetyMarginSeconds,
                        ),
                    ).decide(analysis, tracking)
                    val oneTapPolicy = OneTapOpenPolicy(currentSettings.selectedLevels)
                    val oneTapDecisions = oneTapDecisions(analysis, tracking, oneTapPolicy)
                    val decisions = when (currentSettings.mode) {
                        RuntimeMode.RADAR -> alertDecisions
                        RuntimeMode.ONE_TAP -> oneTapDecisions
                        RuntimeMode.AUTO, RuntimeMode.SHADOW_AUTO -> actionDecisions
                    }
                    val joinMessage = handleOneTapFrame(analysis, tracking, oneTapPolicy, currentSettings)
                    val refreshMessage = if (oneTapCoordinator.isActive()) null else handleRefresh(analysis, currentSettings)
                    val visibleNowIds = visibleNow.mapTo(hashSetOf()) { it.id }
                    val currentRadarDecisions = alertDecisions.filter { it.rallyId == null || it.rallyId in visibleNowIds }
                    val currentActionDecisions = actionDecisions.filter { it.rallyId == null || it.rallyId in visibleNowIds }
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
                        alertDecisions.filter { decision ->
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
                    if (captureLabStore.isArmed()) {
                        frameBuffer.snapshotDownscaled().also { small ->
                            captureLabStore.add(
                                bitmap = small,
                                analysis = analysis,
                                mode = currentSettings.mode,
                                radarDecisions = currentRadarDecisions,
                                actionDecisions = currentActionDecisions,
                                currentTracks = visibleNow,
                                shadowUpdate = shadowUpdate,
                                actualAlertEmitted = newlyAlerted.isNotEmpty(),
                                metadata = CaptureLabMetadata(
                                    appVersion = BuildConfig.VERSION_NAME,
                                    buildNumber = BuildConfig.VERSION_CODE,
                                    gitSha = BuildConfig.GIT_SHA,
                                    calibrationProfileId = currentSettings.calibrationProfile,
                                    captureWidth = captureWidth,
                                    captureHeight = captureHeight,
                                    densityDpi = captureDensityDpi,
                                    selectedLevels = currentSettings.selectedLevels,
                                    mode = currentSettings.mode,
                                    delayMinSeconds = currentSettings.delayMinSeconds,
                                    delayMaxSeconds = currentSettings.delayMaxSeconds,
                                    skipMin = currentSettings.skipMin,
                                    skipMax = currentSettings.skipMax,
                                    safetyMarginSeconds = currentSettings.safetyMarginSeconds,
                                    detectorVersion = "c3-event-refresh-identity-v4",
                                    templateVersion = "runtime-template-v2",
                                ),
                            )
                            small.recycle()
                        }
                    }
                    handleGuidedAndEvidence(analysis, visibleNow, tracking.expired, currentSettings)
                    val pinnedRallyId = oneTapCoordinator.currentRallyId()
                    val selectedForOverlay = targetSelector.select(
                        analysis.frameId,
                        tracking,
                        if (currentSettings.mode == RuntimeMode.ONE_TAP) alertDecisions else decisions,
                    )
                    val overlayRallyId = pinnedRallyId ?: shadowUpdate.rallyId ?: selectedForOverlay?.id
                    val overlayRally = overlayRallyId?.let { id ->
                        visibleNow.firstOrNull { it.id == id }?.candidate
                    }
                    val delayRemaining = shadowUpdate.dueAtMonotonicMs?.let { due ->
                        ((due - observedMs).coerceAtLeast(0L) + 999L) / 1_000L
                    }?.toInt()
                    if (currentSettings.mode == RuntimeMode.ONE_TAP && !oneTapCoordinator.isActive()) {
                        overlayJoinState = when {
                            joinMessage != null && overlayJoinState.phase == OverlayJoinPhase.FAILED -> overlayJoinState
                            analysis.screen == ScreenState.MARCH_SCREEN &&
                                overlayJoinState.phase in setOf(
                                    OverlayJoinPhase.MANUAL_FALLBACK,
                                    OverlayJoinPhase.FAILED,
                                ) -> overlayJoinState
                            !GestureActionController.connected.value -> OverlayJoinState(
                                phase = OverlayJoinPhase.IDLE,
                                actionsAvailable = false,
                                detail = "Нужно разрешить действия",
                            )
                            overlayRallyId != null && oneTapDecisions.any {
                                it.rallyId == overlayRallyId && it.kind == DecisionKind.WOULD_SELECT
                            } -> OverlayJoinState(phase = OverlayJoinPhase.READY)
                            overlayRallyId != null -> OverlayJoinState(
                                phase = OverlayJoinPhase.CHECKING,
                                detail = "Проверяю цель…",
                            )
                            else -> OverlayJoinState()
                        }
                    }
                    overlay.update(
                        currentSettings.mode,
                        overlayRallyId,
                        analysis.frameId,
                        overlayRally,
                        RadarRuntime.status.value.toOverlayCounters(
                            skipped = shadowUpdate.policySkips.toLong(),
                            shadowWouldAttempt = shadowUpdate.virtualAttempts.toLong(),
                        ),
                        OverlayAutomationState(automationPaused, shadowUpdate.phase.name, delayRemaining),
                        overlayJoinState,
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
                            shadowWouldAttempts = shadowUpdate.virtualAttempts.toLong(),
                            safetyRejects = safetyRejectCount,
                            visionRejects = visionRejectCount,
                            safetyAborts = safetyAbortCount,
                            refreshDetected = refreshCoordinator.metrics().detected,
                            refreshRequests = refreshCoordinator.metrics().requests,
                            refreshGestureAccepted = refreshCoordinator.metrics().gestureAccepted,
                            refreshGestureCompleted = refreshCoordinator.metrics().gestureCompleted,
                            refreshVerifiedSuccesses = refreshCoordinator.metrics().verifiedSuccess,
                            refreshVerifiedFailures = refreshCoordinator.metrics().verifiedFailure,
                            refreshSafetyRejects = refreshCoordinator.metrics().rejectedBySafety,
                            refreshAlerts = refreshCoordinator.metrics().alerts,
                            refreshStuck = refreshCoordinator.metrics().stuck,
                            squadDiagnostics = analysis.marchSquads.takeIf { it.isNotEmpty() }?.joinToString(" · ") { squad ->
                                "S${squad.slotIndex}:${squad.state} ${"%.2f".format(squad.stateConfidence)}" +
                                    if (squad.selected) " ✓" else ""
                            },
                            averageLatencyMs = if (latencySamples == 0L) null else latencyTotalMs / latencySamples,
                            p50LatencyMs = sortedLatencies.percentile(0.50),
                            p95LatencyMs = sortedLatencies.percentile(0.95),
                            mode = currentSettings.mode,
                            refreshMode = currentSettings.refreshMode,
                            message = when {
                                joinMessage != null -> joinMessage
                                refreshMessage != null -> refreshMessage
                                automationPaused -> "Автоматизация на паузе; Radar и уведомления продолжают работать"
                                currentSettings.mode == RuntimeMode.SHADOW_AUTO ->
                                    "Shadow ${shadowUpdate.phase}: ${shadowUpdate.reason ?: "наблюдение"}"
                                currentSettings.mode == RuntimeMode.ONE_TAP -> "ONE_TAP experimental · ожидается цель"
                                currentSettings.mode == RuntimeMode.AUTO -> "AUTO заблокирован"
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

    private fun handleGuidedAndEvidence(
        analysis: FrameAnalysis,
        visibleTracks: List<radar.vision.TrackedRally>,
        expired: List<radar.vision.RallyId>,
        currentSettings: RadarSettings,
    ) {
        guidedValidationStore.active()?.let { active ->
            val elapsed = analysis.observedAtMonotonicMs - active.startedAtMonotonicMs
            if (elapsed >= GuidedValidationStore.WINDOW_MS) {
                guidedValidationStore.finish(active.case, GuidedValidationStatus.NOT_OBSERVED)
                RadarRuntime.update { it.copy(message = "${active.case.id}: NOT_OBSERVED; 45 sec session finished") }
            } else if (matchesGuidedCase(active.case.id, analysis, expired)) {
                val optionalValue = analysis.travelTime.value.takeIf { active.case.id.startsWith("T") }
                if (captureLabStore.markScenario(
                        active.case.captureLabel,
                        optionalValue,
                        CaptureDatasetSplit.TUNING,
                        groundTruthConfirmed = false,
                    )
                ) {
                    guidedValidationStore.finish(active.case, GuidedValidationStatus.AWAITING_CONFIRMATION)
                    RadarRuntime.update { it.copy(message = "${active.case.id}: sequence captured; confirm ground truth") }
                }
            }
        }
        if (currentSettings.evidenceCollectorEnabled) maybeCollectEvidence(analysis, visibleTracks, expired)
        if (
            guidedValidationStore.active() == null && !currentSettings.captureLabArmed &&
            !currentSettings.evidenceCollectorEnabled && !captureLabStore.hasPendingScenario()
        ) captureLabStore.setArmed(false)
    }

    private fun matchesGuidedCase(
        id: String,
        analysis: FrameAnalysis,
        expired: List<radar.vision.RallyId>,
    ): Boolean = when (id) {
        "R1" -> analysis.rallies.any { it.bossType == BossType.TARGET && it.level == 5 && it.joinedState == JoinedState.JOINABLE }
        "R2" -> analysis.rallies.any { it.bossType == BossType.TARGET && it.level == 10 && it.joinedState == JoinedState.JOINABLE }
        "R3" -> analysis.rallies.any { it.bossType == BossType.NON_TARGET && it.joinPlusBounds.isNotEmpty() }
        "R4" -> analysis.rallies.any { it.bossType == BossType.TARGET && it.joinedState == JoinedState.FULL }
        "R5" -> analysis.rallies.any { it.bossType == BossType.TARGET && (it.joinPlusBounds.isEmpty() || it.joinedState == JoinedState.ALREADY_JOINED) }
        "R6" -> analysis.rallies.size >= 2
        "R7" -> analysis.refreshButton.accepted
        "R8" -> expired.any { lastCandidateById[it.value]?.bossType == BossType.TARGET }
        "M1" -> analysis.screen == ScreenState.MARCH_SCREEN
        "S1" -> analysis.squads.any { it.state == SquadState.FREE }
        "S2" -> analysis.squads.any { it.state in setOf(SquadState.MOVING, SquadState.GATHERING, SquadState.OCCUPIED_OTHER) }
        "S3" -> analysis.squads.any { it.state == SquadState.RETURNING }
        else -> id.startsWith("T") && analysis.travelTime.accepted && analysis.travelTime.value != null
    }

    private fun maybeCollectEvidence(
        analysis: FrameAnalysis,
        tracks: List<radar.vision.TrackedRally>,
        expired: List<radar.vision.RallyId>,
    ) {
        data class Evidence(val key: String, val label: CaptureLabLabel, val value: Int? = null)
        val candidates = mutableListOf<Evidence>()
        tracks.forEach { track ->
            val rally = track.candidate
            val id = track.id.value
            if (rally.bossType == BossType.TARGET && rally.level == 5 && rally.joinedState == JoinedState.JOINABLE) {
                candidates += Evidence("$id:target-l5", CaptureLabLabel.TARGET_LEVEL_5_JOINABLE)
            }
            if (rally.bossType == BossType.TARGET && rally.level == 10 && rally.joinedState == JoinedState.JOINABLE) {
                candidates += Evidence("$id:target-l10", CaptureLabLabel.TARGET_LEVEL_10_JOINABLE)
            }
            if (rally.bossType == BossType.NON_TARGET && rally.joinPlusBounds.isNotEmpty()) {
                candidates += Evidence("$id:non-target-plus", CaptureLabLabel.NON_TARGET)
            }
            if (rally.bossType == BossType.TARGET && rally.joinedState == JoinedState.FULL) {
                candidates += Evidence("$id:full", CaptureLabLabel.TARGET_FULL)
            }
            val previous = lastCandidateById[id]
            if (previous?.joinPlusBounds?.isNotEmpty() == true && rally.joinPlusBounds.isEmpty()) {
                candidates += Evidence("$id:plus-disappeared", CaptureLabLabel.TARGET_ALREADY_JOINED)
            }
            if (rally.bossType == BossType.UNKNOWN && rally.confidences.card >= .85f) {
                candidates += Evidence("$id:unknown-conflict", CaptureLabLabel.UNKNOWN_UI)
            }
        }
        if (analysis.rallies.size >= 2) {
            candidates += Evidence("multi:${tracks.map { it.id.value }.sorted()}", CaptureLabLabel.MULTIPLE_TARGETS)
        }
        if (analysis.refreshButton.accepted) candidates += Evidence("refresh-control", CaptureLabLabel.REFRESH_REORDER)
        if (expired.any { lastCandidateById[it.value]?.bossType == BossType.TARGET }) {
            candidates += Evidence("target-expired:${expired.joinToString { it.value }}", CaptureLabLabel.TARGET_DISAPPEARS)
        }
        if (analysis.screen == ScreenState.MARCH_SCREEN) candidates += Evidence("march-screen", CaptureLabLabel.MARCH_SCREEN)
        analysis.squads.forEach { squad ->
            val label = when (squad.state) {
                SquadState.FREE -> CaptureLabLabel.SQUAD_FREE
                SquadState.MOVING -> CaptureLabLabel.SQUAD_MOVING
                SquadState.RETURNING -> CaptureLabLabel.SQUAD_RETURNING
                SquadState.GATHERING -> CaptureLabLabel.SQUAD_GATHERING
                SquadState.OCCUPIED_OTHER, SquadState.LOCKED -> CaptureLabLabel.SQUAD_OTHER_BUSY
                SquadState.UNKNOWN -> CaptureLabLabel.SQUAD_UNKNOWN
            }
            candidates += Evidence("squad:${squad.slotIndex}:${squad.state}", label)
        }
        analysis.travelTime.value?.takeIf { analysis.travelTime.accepted }?.let { seconds ->
            candidates += Evidence("travel:$seconds", CaptureLabLabel.TRAVEL_TIME, seconds)
        }
        candidates.firstOrNull { it.key !in evidenceKeys }?.let { evidence ->
            if (captureLabStore.markScenario(
                    evidence.label,
                    evidence.value,
                    CaptureDatasetSplit.TUNING,
                    groundTruthConfirmed = false,
                )
            ) evidenceKeys += evidence.key
        }
    }

    private fun handleRefresh(analysis: FrameAnalysis, currentSettings: RadarSettings): String? {
        if (oneTapCoordinator.isActive()) return null
        val evaluation = refreshCoordinator.onFrame(
            frame = analysis,
            mode = if (currentSettings.mode == RuntimeMode.ONE_TAP) {
                RefreshMode.AUTO_REFRESH
            } else currentSettings.refreshMode,
            expectedPackage = BuildConfig.VERIFIED_TARGET_PACKAGE,
        )
        if (evaluation.shouldAlert) emitRefreshAlert(currentSettings)
        evaluation.request?.let { unscopedRequest ->
            val request = unscopedRequest.copy(
                expectedForegroundGeneration = GestureActionController.foregroundSnapshot()?.generation ?: -1,
                projectionSessionGeneration = projectionSessionGeneration,
            )
            if (!gestureCoordinator.tryAcquire(request)) {
                refreshCoordinator.cancelPending()
                return "Refresh отложен: выполняется другое действие"
            }
            Log.i(REFRESH_LOG_TAG, "request id=${request.requestId} frame=${request.sourceFrameId}")
            GestureActionController.dispatch(
                request = request,
                displayWidth = captureWidth,
                displayHeight = captureHeight,
                verifiedPackage = BuildConfig.VERIFIED_TARGET_PACKAGE,
                currentSourceFrameId = lastAnalyzedFrameId ?: request.sourceFrameId,
                onAccepted = {
                    refreshCoordinator.onGestureAccepted(request.requestId)
                    updateRefreshRuntime("Refresh-жест принят Android; ждём визуальную проверку")
                },
                onRejected = { reason ->
                    gestureCoordinator.release(request.requestId)
                    refreshCoordinator.onGestureRejected(request.requestId)
                    Log.w(REFRESH_LOG_TAG, "rejected id=${request.requestId} reason=$reason")
                    updateRefreshRuntime("Refresh отклонён безопасностью: $reason")
                },
                onCompleted = { completed, completedAt ->
                    gestureCoordinator.release(request.requestId)
                    refreshCoordinator.onGestureCompleted(request.requestId, completedAt, completed)
                    Log.i(REFRESH_LOG_TAG, "completed id=${request.requestId} completed=$completed")
                    updateRefreshRuntime(
                        if (completed) "Refresh-жест завершён; успех ещё не подтверждён"
                        else "Refresh-жест отменён; разрешена одна ограниченная повторная попытка",
                    )
                },
            )
        }
        return when {
            evaluation.autoRefreshPaused -> "REFRESH_STUCK: автообновление остановлено до исчезновения кнопки"
            evaluation.verifiedSuccess -> "Refresh подтверждён изменением интерфейса"
            evaluation.verifiedFailure -> "Refresh не подтверждён интерфейсом"
            evaluation.shouldAlert -> "Доступно обновление списка"
            else -> null
        }
    }

    private fun updateRefreshRuntime(message: String) {
        val metrics = refreshCoordinator.metrics()
        RadarRuntime.update {
            it.copy(
                refreshDetected = metrics.detected,
                refreshRequests = metrics.requests,
                refreshGestureAccepted = metrics.gestureAccepted,
                refreshGestureCompleted = metrics.gestureCompleted,
                refreshVerifiedSuccesses = metrics.verifiedSuccess,
                refreshVerifiedFailures = metrics.verifiedFailure,
                refreshSafetyRejects = metrics.rejectedBySafety,
                refreshAlerts = metrics.alerts,
                refreshStuck = metrics.stuck,
                message = message,
            )
        }
    }

    private fun emitRefreshAlert(currentSettings: RadarSettings) {
        alertFeedback.emit(
            currentSettings.soundEnabled,
            currentSettings.vibrationEnabled,
            currentSettings.alertSoundMode,
        )
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            notification("Доступно обновление списка"),
        )
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
        alertFeedback.emit(
            currentSettings.soundEnabled,
            currentSettings.vibrationEnabled,
            currentSettings.alertSoundMode,
        )
        getSystemService(NotificationManager::class.java).notify(
            TARGET_ALERT_NOTIFICATION_ID,
            targetAlertNotification(level),
        )
    }

    private fun oneTapDecisions(
        analysis: FrameAnalysis,
        tracking: radar.vision.TrackingUpdate,
        policy: OneTapOpenPolicy,
    ): List<radar.vision.DetectorDecision> = tracking.active.map { track ->
        val decision = policy.evaluate(
            UserJoinRequest(
                rallyId = track.id,
                displayedFrameId = analysis.frameId - 1,
                requestedAtMonotonicMs = analysis.observedAtMonotonicMs,
            ),
            analysis,
            tracking,
        )
        if (decision.allowed) {
            radar.vision.DetectorDecision(
                DecisionKind.WOULD_SELECT,
                track.id,
                "eligible one-tap open",
                decision.plusBounds,
            )
        } else {
            radar.vision.DetectorDecision(DecisionKind.REJECT, track.id, decision.reason.name)
        }
    }

    private fun handleOneTapFrame(
        analysis: FrameAnalysis,
        tracking: radar.vision.TrackingUpdate,
        policy: OneTapOpenPolicy,
        currentSettings: RadarSettings,
    ): String? {
        if (currentSettings.mode != RuntimeMode.ONE_TAP) return null
        if (!oneTapCoordinator.isActive()) return null
        if (!gestureCoordinator.isIdle()) {
            return "ONE TAP: ждём завершения текущего действия"
        }
        val update = oneTapCoordinator.onFrame(
            frame = analysis,
            tracking = tracking,
            openPolicy = policy,
            config = OneTapCompleteConfig(
                squadPriority = currentSettings.squadPriority,
                allowReturning = currentSettings.allowReturningSquads,
                sendWhenTravelUnknown = currentSettings.sendWhenTravelUnknown,
                safetyMarginSeconds = currentSettings.safetyMarginSeconds,
            ),
            expectedPackage = BuildVariantHooks.expectedGesturePackage(
                applicationContext,
                BuildConfig.VERIFIED_TARGET_PACKAGE,
            ),
            foregroundGeneration = GestureActionController.foregroundSnapshot()?.generation ?: -1,
            projectionGeneration = projectionSessionGeneration,
        )
        return applyOneTapUpdate(update, analysis, tracking)
    }

    private fun applyOneTapUpdate(
        update: OneTapCompleteUpdate,
        analysis: FrameAnalysis? = null,
        tracking: radar.vision.TrackingUpdate? = null,
    ): String? = when (update) {
        OneTapCompleteUpdate.Ignored -> null
        is OneTapCompleteUpdate.Progress -> {
            overlayJoinState = OverlayJoinState(update.stage.toOverlayPhase(), detail = update.detail)
            "ONE TAP: ${update.stage.name}"
        }
        is OneTapCompleteUpdate.Rejected -> {
            if (update.reason == radar.vision.OneTapRejectReason.FULL) {
                RadarRuntime.update { it.copy(fullBeforeJoin = it.fullBeforeJoin + 1) }
            }
            finishOneTapAttempt(false, "Цель изменилась · ${update.reason.name}", analysis)
            recordAbortOnce("one-tap-open:${update.reason.name}")
            "ONE TAP: ${update.reason.name}"
        }
        is OneTapCompleteUpdate.Dispatch -> {
            markOneTapMilestones(update.openVerified, update.selectionVerified)
            dispatchOneTapGesture(update.request, analysis, tracking)
            "ONE TAP: ${update.request.purpose.name}"
        }
        is OneTapCompleteUpdate.ManualFallback -> {
            markOneTapMilestones(update.openVerified, update.squadSelected)
            RadarRuntime.update { status ->
                when (update.reason) {
                    "NO_ELIGIBLE_SQUAD" -> status.copy(noSquad = status.noSquad + 1)
                    "TOO_LATE" -> status.copy(tooLate = status.tooLate + 1)
                    else -> status
                }
            }
            val detail = when {
                !update.openVerified -> "Не удалось подтвердить экран отряда · завершите вручную"
                update.squadSelected -> "Отряд выбран · ${update.reason} · нажмите Отправиться вручную"
                else -> "Штурм открыт · ${update.reason} · завершите вручную"
            }
            finishOneTapAttempt(false, detail, analysis, manualFallback = true)
            "ONE TAP MANUAL: ${update.reason}"
        }
        is OneTapCompleteUpdate.Success -> {
            markOneTapMilestones(openVerified = true, selectionVerified = true)
            RadarRuntime.update {
                it.copy(
                    sendVerifiedSuccesses = it.sendVerifiedSuccesses + 1,
                    joinSuccesses = it.joinSuccesses + 1,
                    message = "ONE_TAP_JOIN_SUCCESS",
                )
            }
            overlayJoinState = OverlayJoinState(OverlayJoinPhase.SUCCESS, detail = "Отряд отправлен ✓")
            oneTapAttemptStore.recordResult(pendingOneTapRequestId, true, "Отряд отправлен", analysis)
            pendingOneTapRequestId = null
            "ONE TAP SUCCESS"
        }
        is OneTapCompleteUpdate.Failure -> {
            finishOneTapAttempt(false, update.reason, analysis)
            "ONE TAP FAILURE: ${update.reason}"
        }
    }

    private fun dispatchOneTapGesture(
        request: radar.vision.GestureRequest,
        analysis: FrameAnalysis?,
        tracking: radar.vision.TrackingUpdate?,
    ) {
        if (request.purpose == GesturePurpose.JOIN_PLUS) {
            pendingOneTapRequestId = request.requestId
            analysis?.let { frame ->
                oneTapAttemptStore.recordDispatch(
                    request,
                    tracking?.active?.firstOrNull { it.id == request.rallyId }?.candidate,
                )
            }
        }
        RadarRuntime.update { status ->
            when (request.purpose) {
                GesturePurpose.SELECT_SQUAD -> status.copy(
                    squadSelectionAttempts = status.squadSelectionAttempts + 1,
                    message = "Выбираю отряд ${request.squadSlotIndex}",
                ).also { oneTapSelectionAttempted = true }
                GesturePurpose.SEND -> status.copy(
                    sendAttempts = status.sendAttempts + 1,
                    joinAttempts = status.joinAttempts + 1,
                    message = "Отправляю отряд",
                ).also { oneTapSendAttempted = true }
                else -> status
            }
        }
        if (!gestureCoordinator.tryAcquire(request)) {
            applyOneTapUpdate(oneTapCoordinator.cancel("GESTURE_COORDINATOR_BUSY"), analysis, tracking)
            return
        }
        overlayJoinState = OverlayJoinState(request.purpose.toOverlayPhase(), detail = request.purpose.progressText())
        val dispatchGeneration = actionInvalidationGeneration.get()
        val dispatchAction = Runnable {
            if (dispatchGeneration != actionInvalidationGeneration.get() || !oneTapCoordinator.isActive()) {
                gestureCoordinator.release(request.requestId)
                Log.w("ONE_TAP", "scheduled gesture cancelled before dispatch")
            } else GestureActionController.dispatch(
                request = request,
                displayWidth = captureWidth,
                displayHeight = captureHeight,
                verifiedPackage = BuildConfig.VERIFIED_TARGET_PACKAGE,
                activeOneTapFlowId = oneTapCoordinator.activeFlowId(),
                currentSourceFrameId = lastAnalyzedFrameId ?: request.sourceFrameId,
                onAccepted = { RadarRuntime.update { it.copy(message = "ONE TAP: жест принят; проверяю результат") } },
                onRejected = { reason ->
                    gestureCoordinator.release(request.requestId)
                    safetyRejectCount++
                    recordAbortOnce("one-tap-gesture:$reason")
                    applyOneTapUpdate(oneTapCoordinator.cancel("SAFETY_$reason"), analysis, tracking)
                },
                onCompleted = { completed, completedAt ->
                    gestureCoordinator.release(request.requestId)
                    applyOneTapUpdate(
                        oneTapCoordinator.onGestureCompleted(request.requestId, completed, completedAt),
                        analysis,
                        tracking,
                    )
                },
            )
        }
        val dispatchDelayMs = BuildVariantHooks.dispatchDelayMs(applicationContext)
        if (dispatchDelayMs > 0) Handler(Looper.getMainLooper()).postDelayed(dispatchAction, dispatchDelayMs)
        else dispatchAction.run()
    }

    private fun markOneTapMilestones(openVerified: Boolean, selectionVerified: Boolean) {
        if (openVerified && !oneTapOpenCounted) {
            oneTapOpenCounted = true
            RadarRuntime.update { it.copy(oneTapOpenSuccesses = it.oneTapOpenSuccesses + 1) }
        }
        if (selectionVerified && !oneTapSelectionCounted) {
            oneTapSelectionCounted = true
            RadarRuntime.update { it.copy(squadSelectionSuccesses = it.squadSelectionSuccesses + 1) }
        }
    }

    private fun handleJoinRequested(request: OneTapRequest) {
        val currentSettings = settings.get()
        if (currentSettings.mode != RuntimeMode.ONE_TAP || capture == null || !capturedContentVisible.get()) {
            recordAbortOnce("one-tap-request:session-not-ready")
            RadarRuntime.update { it.copy(message = "ONE_TAP_A недоступен: сессия захвата не готова") }
            return
        }
        if (!GestureActionController.connected.value) {
            openAccessibilitySettings()
            return
        }
        if (BuildConfig.VERIFIED_TARGET_PACKAGE.isBlank()) {
            recordAbortOnce("one-tap-request:unverified-target-package")
            RadarRuntime.update { it.copy(message = "ONE_TAP_A недоступен: target package не верифицирован") }
            return
        }
        val accepted = oneTapCoordinator.begin(
            UserJoinRequest(
                request.rallyId,
                request.displayedFrameId,
                android.os.SystemClock.elapsedRealtime(),
            ),
        )
        if (!accepted) return
        oneTapOpenCounted = false
        oneTapSelectionCounted = false
        oneTapSelectionAttempted = false
        oneTapSendAttempted = false
        RadarRuntime.update { it.copy(oneTapOpenAttempts = it.oneTapOpenAttempts + 1) }
        refreshCoordinator.cancelPending()
        GestureActionController.cancelAll()
        overlayJoinState = OverlayJoinState(OverlayJoinPhase.CHECKING, detail = "Проверяю свежий кадр…")
        RadarRuntime.update { it.copy(message = "ONE_TAP_A: проверяю выбранную цель на свежем кадре") }
    }

    private fun finishOneTapAttempt(
        success: Boolean,
        detail: String,
        postFrame: FrameAnalysis? = null,
        manualFallback: Boolean = false,
    ) {
        overlayJoinState = OverlayJoinState(
            phase = when {
                success -> OverlayJoinPhase.SUCCESS
                manualFallback -> OverlayJoinPhase.MANUAL_FALLBACK
                else -> OverlayJoinPhase.FAILED
            },
            actionsAvailable = GestureActionController.connected.value,
            detail = detail,
        )
        RadarRuntime.update {
            it.copy(
                oneTapOpenSuccesses = it.oneTapOpenSuccesses + if (success && !oneTapOpenCounted) 1 else 0,
                oneTapOpenFailures = it.oneTapOpenFailures + if (!success && !oneTapOpenCounted) 1 else 0,
                squadSelectionFailures = it.squadSelectionFailures +
                    if (!success && oneTapSelectionAttempted && !oneTapSelectionCounted) 1 else 0,
                sendFailures = it.sendFailures + if (!success && oneTapSendAttempted) 1 else 0,
                joinFailures = it.joinFailures + if (!success && oneTapSendAttempted) 1 else 0,
                message = when {
                    success -> "ONE_TAP_JOIN_SUCCESS"
                    manualFallback -> "ONE_TAP_MANUAL_FALLBACK"
                    else -> "ONE_TAP_FAILURE"
                },
            )
        }
        oneTapAttemptStore.recordResult(pendingOneTapRequestId, success, detail, postFrame)
        pendingOneTapRequestId = null
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun stopFromUi() {
        if (!stopping.compareAndSet(false, true)) return
        cancelAllActions("ONE TAP остановлен пользователем")
        serviceScope.cancel()
        overlay.close()
        capture?.close()
        capture = null
        projection?.stop()
        projection = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopFromUi()
        super.onTaskRemoved(rootIntent)
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

    private fun cancelAllActions(detail: String) {
        actionInvalidationGeneration.incrementAndGet()
        pending.getAndSet(null)?.close()
        oneTapCoordinator.cancel()
        refreshCoordinator.cancelPending()
        gestureCoordinator.cancelAll()
        GestureActionController.cancelAll()
        overlayJoinState = OverlayJoinState(
            phase = OverlayJoinPhase.FAILED,
            actionsAvailable = GestureActionController.connected.value,
            detail = detail,
        )
        val current = RadarRuntime.status.value
        overlay.update(
            current.mode,
            null,
            null,
            null,
            current.toOverlayCounters(),
            join = overlayJoinState,
        )
    }

    private fun pauseForGeometry(reason: String) {
        pending.getAndSet(null)?.close()
        cancelAllActions("Геометрия экрана изменилась")
        GestureActionController.endProjectionSession(projectionSessionGeneration)
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
        stopping.set(true)
        refreshCoordinator.cancelPending()
        oneTapCoordinator.cancel()
        gestureCoordinator.cancelAll()
        GestureActionController.cancelAll()
        pending.getAndSet(null)?.close()
        capture?.close(); capture = null
        projection?.stop(); projection = null
        GestureActionController.endProjectionSession(projectionSessionGeneration)
        executor.shutdownNow()
        captureThread.quitSafely()
        frameBuffer.close()
        overlay.close()
        serviceScope.cancel()
        val summary = RadarRuntime.status.value
        runBlocking(Dispatchers.IO) { sessionId?.let { repository.endSession(it, summary) } }
        repository.close()
        alertFeedback.close()
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
        val builder = NotificationCompat.Builder(this, CHANNEL_RADAR_SERVICE)
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

    private fun targetAlertNotification(level: Int?): Notification {
        val open = PendingIntent.getActivity(
            this,
            11,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_RALLY_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Подходящая цель${level?.let { " · ур.$it" }.orEmpty()}")
            .setContentText("Есть место · нажмите ВСТУПИТЬ")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
    }

    private fun createNotificationChannel() {
        ensureNotificationChannels(this)
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
        private const val REFRESH_LOG_TAG = "RallyRefresh"
        private const val ACTION_START = "com.rallyhelper.START_RADAR"
        private const val ACTION_STOP = "com.rallyhelper.STOP_RADAR"
        private const val ACTION_PAUSE = "com.rallyhelper.PAUSE_AUTOMATION"
        private const val ACTION_RESUME = "com.rallyhelper.RESUME_AUTOMATION"
        private const val ACTION_CAPTURE_LAB = "com.rallyhelper.SAVE_CAPTURE_LAB"
        private const val ACTION_GUIDED_VALIDATION = "com.rallyhelper.GUIDED_VALIDATION"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_CAPTURE_LABEL = "capture_label"
        private const val EXTRA_CAPTURE_VALUE = "capture_value"
        private const val EXTRA_CAPTURE_SPLIT = "capture_split"
        private const val EXTRA_GUIDED_CASE = "guided_case"
        const val CHANNEL_RADAR_SERVICE = "radar_service"
        const val CHANNEL_RALLY_ALERTS = "target_alerts_v2"
        private const val NOTIFICATION_ID = 42
        private const val TARGET_ALERT_NOTIFICATION_ID = 43

        fun ensureNotificationChannels(context: Context) {
            context.getSystemService(NotificationManager::class.java).apply {
                createNotificationChannel(
                    NotificationChannel(CHANNEL_RADAR_SERVICE, "Radar service", NotificationManager.IMPORTANCE_LOW).apply {
                        setSound(null, null)
                        enableVibration(false)
                    },
                )
                createNotificationChannel(
                    NotificationChannel(CHANNEL_RALLY_ALERTS, "Target alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                        // The app-local SYSTEM/MEDIA selector owns audible/vibration feedback,
                        // so this high-importance notification must not produce a second cue.
                        setSound(null, null)
                        enableVibration(false)
                    },
                )
            }
        }

        fun startIntent(context: Context, resultCode: Int, data: Intent) =
            Intent(context, RadarForegroundService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode).putExtra(EXTRA_RESULT_DATA, data)

        fun stopIntent(context: Context) = Intent(context, RadarForegroundService::class.java).setAction(ACTION_STOP)

        fun captureLabIntent(
            context: Context,
            label: CaptureLabLabel,
            optionalIntValue: Int?,
            split: CaptureDatasetSplit,
        ) =
            Intent(context, RadarForegroundService::class.java).setAction(ACTION_CAPTURE_LAB)
                .putExtra(EXTRA_CAPTURE_LABEL, label.name)
                .putExtra(EXTRA_CAPTURE_SPLIT, split.name)
                .apply { optionalIntValue?.let { putExtra(EXTRA_CAPTURE_VALUE, it) } }

        fun guidedValidationIntent(context: Context, caseId: String) =
            Intent(context, RadarForegroundService::class.java)
                .setAction(ACTION_GUIDED_VALIDATION)
                .putExtra(EXTRA_GUIDED_CASE, caseId)
    }
}

private fun RuntimeMode.isAutoLoop(): Boolean = this == RuntimeMode.AUTO || this == RuntimeMode.SHADOW_AUTO

private fun OneTapCompleteStage.toOverlayPhase(): OverlayJoinPhase = when (this) {
    OneTapCompleteStage.AWAITING_FRESH_RALLY -> OverlayJoinPhase.CHECKING
    OneTapCompleteStage.DISPATCHING_JOIN_PLUS, OneTapCompleteStage.AWAITING_MARCH -> OverlayJoinPhase.OPENING
    OneTapCompleteStage.ANALYZING_SQUADS -> OverlayJoinPhase.ANALYZING_SQUADS
    OneTapCompleteStage.SELECTING_SQUAD, OneTapCompleteStage.VERIFYING_SQUAD -> OverlayJoinPhase.SELECTING_SQUAD
    OneTapCompleteStage.CHECKING_SEND -> OverlayJoinPhase.CHECKING_TIME
    OneTapCompleteStage.DISPATCHING_SEND -> OverlayJoinPhase.SENDING
    OneTapCompleteStage.VERIFYING_SEND -> OverlayJoinPhase.VERIFYING_SEND
    OneTapCompleteStage.SUCCESS -> OverlayJoinPhase.SUCCESS
    OneTapCompleteStage.MANUAL_FALLBACK -> OverlayJoinPhase.MANUAL_FALLBACK
    OneTapCompleteStage.FAILED -> OverlayJoinPhase.FAILED
    OneTapCompleteStage.IDLE -> OverlayJoinPhase.IDLE
}

private fun GesturePurpose.toOverlayPhase(): OverlayJoinPhase = when (this) {
    GesturePurpose.JOIN_PLUS -> OverlayJoinPhase.OPENING
    GesturePurpose.SELECT_SQUAD -> OverlayJoinPhase.SELECTING_SQUAD
    GesturePurpose.SEND -> OverlayJoinPhase.SENDING
    GesturePurpose.REFRESH -> OverlayJoinPhase.CHECKING
}

private fun GesturePurpose.progressText(): String = when (this) {
    GesturePurpose.JOIN_PLUS -> "Открываю штурм…"
    GesturePurpose.SELECT_SQUAD -> "Выбираю отряд…"
    GesturePurpose.SEND -> "Отправляю…"
    GesturePurpose.REFRESH -> "Обновляю список…"
}

private fun RadarStatus.toOverlayCounters(
    skipped: Long = policySkipped,
    shadowWouldAttempt: Long = shadowSelections,
) = OverlayCounters(
    totalSeen = ralliesSeen,
    eligible = eligible,
    openSuccess = oneTapOpenSuccesses,
    openFailed = oneTapOpenFailures,
    skipped = skipped,
    shadowWouldAttempt = shadowWouldAttempt,
    // "Not suitable" is a semantic rejection count, not every rally that was not
    // eligible. Full/late/skipped rallies are reported by their own counters.
    ignored = nonTarget + unknown,
    missed = tooLate + fullBeforeJoin,
)

private fun List<Long>.percentile(fraction: Double): Long? {
    if (isEmpty()) return null
    val index = kotlin.math.ceil((size - 1) * fraction).toInt().coerceIn(indices)
    return this[index]
}
