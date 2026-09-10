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
import radar.vision.OneTapFlowCoordinator
import radar.vision.OneTapFlowUpdate
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
    private val refreshCoordinator = RefreshCoordinator()
    private val gestureCoordinator = GestureCoordinator()
    private val oneTapCoordinator = OneTapFlowCoordinator()
    private val targetSelector = TargetSelector()
    private val calibration = CalibrationProfile()
    private val detector by lazy { RadarDetectorFactory.create(applicationContext) }
    private val debugStore by lazy { DebugCaptureStore(applicationContext) }
    private val captureLabStore by lazy { CaptureLabStore(applicationContext) }
    private val guidedValidationStore by lazy { GuidedValidationStore(applicationContext) }
    private val settingsStore by lazy { RadarSettingsStore(applicationContext) }
    private val repository by lazy { RadarRepository.create(applicationContext) }
    private val overlay by lazy {
        RallyOverlayController(
            applicationContext,
            onJoinRequested = ::handleJoinRequested,
            onActionsPermissionRequested = ::openAccessibilitySettings,
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
        overlay.setEnabled(updated.overlayEnabled || updated.mode == RuntimeMode.ONE_TAP)
        RadarRuntime.update { it.copy(mode = updated.mode, refreshMode = updated.refreshMode) }
    }

    private fun resetSessionState(currentSettings: RadarSettings) {
        pending.getAndSet(null)?.close()
        tracker.reset()
        refreshCoordinator.reset()
        oneTapCoordinator.cancel()
        gestureCoordinator.cancelAll()
        overlayJoinState = OverlayJoinState()
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
        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Handler(Looper.getMainLooper()).post {
                    if (oneTapCoordinator.cancel()) {
                        overlayJoinState = OverlayJoinState(
                            phase = OverlayJoinPhase.FAILED,
                            actionsAvailable = GestureActionController.connected.value,
                            detail = "Захват экрана остановлен",
                        )
                    }
                    gestureCoordinator.cancelAll()
                    GestureActionController.cancelAll()
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
                    if (
                        overlayBounds != null &&
                        OverlayCvSafety.overlapsCritical(overlayBounds, RadarRuntime.status.value.screen)
                    ) {
                        recordAbortOnce("overlay-overlaps-critical-cv-region")
                        RadarRuntime.update {
                            it.copy(message = "Переместите overlay в свободную нижнюю область: он перекрывает анализ")
                        }
                        continue
                    }
                    overlayBounds?.let(argbImage::mask)
                    val observedMs = android.os.SystemClock.elapsedRealtime()
                    val analysis = detector.analyze(argbImage, frameIds.incrementAndGet(), observedMs)
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
                                overlayJoinState.phase == OverlayJoinPhase.OPENED -> overlayJoinState
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
            mode = currentSettings.refreshMode,
            expectedPackage = BuildConfig.VERIFIED_TARGET_PACKAGE,
        )
        if (evaluation.shouldAlert) emitRefreshAlert(currentSettings)
        evaluation.request?.let { request ->
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
        if (currentSettings.soundEnabled) alertSound.play()
        if (currentSettings.vibrationEnabled) {
            val vibrator = getSystemService(Vibrator::class.java)
            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(90, 70))
            else @Suppress("DEPRECATION") vibrator.vibrate(90)
        }
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
        when (oneTapCoordinator.onVerificationFrame(analysis)) {
            OneTapFlowUpdate.Success -> {
                finishOneTapAttempt(true, "Отряд открыт · отправьте его вручную")
                return "ONE_TAP_A_SUCCESS: экран отряда подтверждён"
            }
            OneTapFlowUpdate.Failure -> {
                finishOneTapAttempt(false, "Не удалось подтвердить экран отряда")
                return "ONE_TAP_A_FAILURE: экран отряда не подтверждён"
            }
            OneTapFlowUpdate.AwaitingMarch -> {
                overlayJoinState = OverlayJoinState(OverlayJoinPhase.OPENING, detail = "Открываю отряд…")
                return "ONE_TAP_A: ждём визуальное подтверждение экрана отряда"
            }
            else -> Unit
        }
        if (!oneTapCoordinator.isActive()) return null
        if (!gestureCoordinator.isIdle()) {
            overlayJoinState = OverlayJoinState(OverlayJoinPhase.CHECKING, detail = "Жду свежий кадр…")
            return "ONE_TAP_A: ждём завершения предыдущего действия"
        }
        return when (val update = oneTapCoordinator.onFreshFrame(
            analysis,
            tracking,
            policy,
            BuildConfig.VERIFIED_TARGET_PACKAGE,
        )) {
            is OneTapFlowUpdate.Rejected -> {
                overlayJoinState = OverlayJoinState(
                    OverlayJoinPhase.FAILED,
                    detail = "Цель изменилась · ${update.reason.name}",
                )
                recordAbortOnce("one-tap-open:${update.reason.name}")
                "ONE_TAP_A: жест отменён, ${update.reason.name}"
            }
            is OneTapFlowUpdate.Dispatch -> {
                val request = update.request
                if (!gestureCoordinator.tryAcquire(request)) {
                    oneTapCoordinator.onGestureCompleted(
                        request.requestId,
                        completed = false,
                        completedAtMonotonicMs = analysis.observedAtMonotonicMs,
                    )
                    overlayJoinState = OverlayJoinState(OverlayJoinPhase.FAILED, detail = "Другое действие ещё выполняется")
                    return "ONE_TAP_A: gesture coordinator busy"
                }
                overlayJoinState = OverlayJoinState(OverlayJoinPhase.OPENING, detail = "Открываю отряд…")
                GestureActionController.dispatch(
                    request = request,
                    displayWidth = captureWidth,
                    displayHeight = captureHeight,
                    verifiedPackage = BuildConfig.VERIFIED_TARGET_PACKAGE,
                    onAccepted = {
                        RadarRuntime.update { it.copy(message = "ONE_TAP_A: жест принят Android; проверяю экран") }
                    },
                    onRejected = { reason ->
                        gestureCoordinator.release(request.requestId)
                        oneTapCoordinator.onGestureCompleted(
                            request.requestId,
                            completed = false,
                            completedAtMonotonicMs = android.os.SystemClock.elapsedRealtime(),
                        )
                        safetyRejectCount++
                        overlayJoinState = OverlayJoinState(
                            OverlayJoinPhase.FAILED,
                            actionsAvailable = GestureActionController.connected.value,
                            detail = "Действие отклонено · $reason",
                        )
                        recordAbortOnce("one-tap-gesture:$reason")
                        RadarRuntime.update { it.copy(message = "ONE_TAP_A: жест отклонён безопасностью: $reason") }
                    },
                    onCompleted = { completed, completedAt ->
                        gestureCoordinator.release(request.requestId)
                        when (oneTapCoordinator.onGestureCompleted(request.requestId, completed, completedAt)) {
                            OneTapFlowUpdate.Failure -> finishOneTapAttempt(false, "Android не выполнил действие")
                            OneTapFlowUpdate.AwaitingMarch -> {
                                overlayJoinState = OverlayJoinState(
                                    OverlayJoinPhase.OPENING,
                                    detail = "Проверяю экран отряда…",
                                )
                            }
                            else -> Unit
                        }
                    },
                )
                "ONE_TAP_A: открываю выбранный rally"
            }
            else -> "ONE_TAP_A: ожидается свежий кадр"
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
        refreshCoordinator.cancelPending()
        GestureActionController.cancelAll()
        overlayJoinState = OverlayJoinState(OverlayJoinPhase.CHECKING, detail = "Проверяю свежий кадр…")
        RadarRuntime.update { it.copy(message = "ONE_TAP_A: проверяю выбранную цель на свежем кадре") }
    }

    private fun finishOneTapAttempt(success: Boolean, detail: String) {
        overlayJoinState = OverlayJoinState(
            phase = if (success) OverlayJoinPhase.OPENED else OverlayJoinPhase.FAILED,
            actionsAvailable = GestureActionController.connected.value,
            detail = detail,
        )
        RadarRuntime.update {
            it.copy(
                actualAttempts = it.actualAttempts + 1,
                actualSuccesses = it.actualSuccesses + if (success) 1 else 0,
                actualFailures = it.actualFailures + if (success) 0 else 1,
                message = if (success) "ONE_TAP_A_SUCCESS" else "ONE_TAP_A_FAILURE",
            )
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
        refreshCoordinator.cancelPending()
        oneTapCoordinator.cancel()
        gestureCoordinator.cancelAll()
        GestureActionController.cancelAll()
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
        private const val CHANNEL_ID = "radar"
        private const val NOTIFICATION_ID = 42

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

private fun RadarStatus.toOverlayCounters(
    skipped: Long = policySkipped,
    shadowWouldAttempt: Long = shadowSelections,
) = OverlayCounters(
    totalSeen = ralliesSeen,
    eligible = eligible,
    realSuccess = actualSuccesses,
    realFailed = actualFailures,
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
