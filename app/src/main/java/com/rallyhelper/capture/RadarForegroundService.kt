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
import android.media.AudioManager
import android.media.ToneGenerator
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
import com.rallyhelper.data.DebugCaptureMode
import com.rallyhelper.data.RadarRepository
import com.rallyhelper.data.RadarSettings
import com.rallyhelper.data.RadarSettingsStore
import com.rallyhelper.debug.DebugCaptureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import radar.vision.BossType
import radar.vision.CalibrationProfile
import radar.vision.DecisionKind
import radar.vision.JoinedState
import radar.vision.RallyTracker
import radar.vision.RuntimeMode
import radar.vision.SafetyPolicy
import radar.vision.SafetyController
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
    private val settingsStore by lazy { RadarSettingsStore(applicationContext) }
    private val repository by lazy { RadarRepository.create(applicationContext) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings = AtomicReference(RadarSettings())
    private val frameBuffer = ScreenCaptureController.Companion.ReusableFrameBuffer()
    private val tone by lazy { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70) }
    private lateinit var captureThread: HandlerThread
    private var capture: ScreenCaptureController? = null
    private var projection: MediaProjection? = null
    private var lastDebugCaptureMs = 0L
    private val seenRallies = mutableSetOf<String>()
    private val alertedRallies = mutableSetOf<String>()
    private val persistedRallies = mutableSetOf<String>()
    private val lastDecisionState = mutableMapOf<String, String>()
    private val eligibleRallies = mutableSetOf<String>()
    private val nonTargetRallies = mutableSetOf<String>()
    private val fullRallies = mutableSetOf<String>()
    private val unknownRallies = mutableSetOf<String>()
    private val shadowSelectedRallies = mutableSetOf<String>()
    private val debuggedTargets = mutableSetOf<String>()
    private val recentLatencies = ArrayDeque<Long>()
    private var latencyTotalMs = 0L
    private var latencySamples = 0L
    private var safetyRejectCount = 0L
    private var sessionId: Long? = null
    private var captureWidth = 0
    private var captureHeight = 0
    private var lastAbortReason: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        captureThread = HandlerThread("radar-capture").also { it.start() }
        serviceScope.launch {
            settingsStore.settings.distinctUntilChanged().collect { updated ->
                settings.set(updated)
                debugStore.purgeExpired(updated.retentionDays)
                RadarRuntime.update { it.copy(mode = updated.mode) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || capture != null) return START_NOT_STICKY
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
                    running = false,
                    message = "RADAR_PAUSED_NEEDS_CALIBRATION: ${calibration.incompatibilityReason(metrics.widthPixels, metrics.heightPixels)}",
                )
            }
            stopSelf()
            return
        }
        captureWidth = metrics.widthPixels
        captureHeight = metrics.heightPixels
        sessionId = runBlocking(Dispatchers.IO) {
            repository.beginSession(settings.get().calibrationProfile)
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
            100_000_000L
        } else {
            200_000_000L
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
                    val argbImage = frameBuffer.copyFrom(image)
                    val observedMs = android.os.SystemClock.elapsedRealtime()
                    val analysis = detector.analyze(argbImage, frameIds.incrementAndGet(), observedMs)
                    if (analysis.screen == ScreenState.UNKNOWN) recordAbortOnce("screen:UNKNOWN") else lastAbortReason = null
                    val tracking = tracker.update(analysis)
                    val activeNow = tracking.active.filter { it.stable && it.presentInCurrentFrame && it.lastSeenFrameId == analysis.frameId }
                    activeNow.forEach { seenRallies += it.id.value }
                    val currentSettings = settings.get()
                    val safety = SafetyController(SafetyPolicy(targetLevels = currentSettings.selectedLevels))
                    val decisions = safety.decide(RuntimeMode.SHADOW_AUTO, analysis, tracking)
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
                    val newlyAlerted = if (currentSettings.mode == RuntimeMode.RADAR) {
                        decisions.filter { decision ->
                            decision.kind == DecisionKind.WOULD_SELECT &&
                                decision.rallyId?.value?.let(alertedRallies::add) == true
                        }
                    } else emptyList()
                    newlyAlerted.forEach { decision ->
                        val level = tracking.active.firstOrNull { it.id == decision.rallyId }?.candidate?.level
                        emitAlert(level, currentSettings)
                    }
                    if (currentSettings.mode == RuntimeMode.SHADOW_AUTO) {
                        decisions.filter { it.kind == DecisionKind.WOULD_SELECT }.forEach { it.rallyId?.value?.let(shadowSelectedRallies::add) }
                    }
                    persistMeaningfulEvents(activeNow, decisions)
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
                            shadowSelections = shadowSelectedRallies.size.toLong(),
                            safetyRejects = safetyRejectCount,
                            averageLatencyMs = if (latencySamples == 0L) null else latencyTotalMs / latencySamples,
                            p50LatencyMs = sortedLatencies.percentile(0.50),
                            p95LatencyMs = sortedLatencies.percentile(0.95),
                            mode = currentSettings.mode,
                            message = if (currentSettings.mode == RuntimeMode.SHADOW_AUTO) {
                                "Shadow: ${decisions.count { it.kind == DecisionKind.WOULD_SELECT }} решений (тапов нет)"
                            } else if (newlyAlerted.isNotEmpty()) "Новая подходящая карточка" else "Radar работает",
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
        decisions: List<radar.vision.DetectorDecision>,
    ) {
        val activeSession = sessionId ?: return
        val decisionsById = decisions.mapNotNull { it.rallyId?.value?.let { id -> id to it } }.toMap()
        activeNow.filter { persistedRallies.add(it.id.value) }.forEach { track ->
            serviceScope.launch {
                repository.recordObservation(
                    activeSession,
                    track.id.value,
                    track.candidate,
                    decisionsById[track.id.value]?.kind == DecisionKind.WOULD_SELECT,
                )
            }
        }
        decisions.forEach { decision ->
            val key = decision.rallyId?.value ?: "frame"
            val state = "${decision.kind}:${decision.reason}"
            if (lastDecisionState.put(key, state) != state) {
                if (decision.kind == DecisionKind.REJECT) safetyRejectCount++
                serviceScope.launch { repository.recordDecision(activeSession, decision) }
            }
        }
    }

    private fun emitAlert(level: Int?, currentSettings: RadarSettings) {
        if (currentSettings.soundEnabled) tone.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
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

    private fun recordLatency(value: Long) {
        latencyTotalMs += value
        latencySamples++
        recentLatencies.addLast(value)
        if (recentLatencies.size > 600) recentLatencies.removeFirst()
    }

    private fun recordAbortOnce(reason: String) {
        if (lastAbortReason == reason) return
        lastAbortReason = reason
        val activeSession = sessionId ?: return
        serviceScope.launch { repository.recordAbort(activeSession, reason) }
    }

    private fun pauseForGeometry(reason: String) {
        pending.getAndSet(null)?.close()
        capture?.close()
        capture = null
        recordAbortOnce("geometry:$reason")
        val message = "RADAR_PAUSED_NEEDS_CALIBRATION: $reason"
        RadarRuntime.update { it.copy(message = message) }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }

    override fun onDestroy() {
        pending.getAndSet(null)?.close()
        capture?.close(); capture = null
        projection?.stop(); projection = null
        executor.shutdownNow()
        captureThread.quitSafely()
        frameBuffer.close()
        serviceScope.cancel()
        runBlocking(Dispatchers.IO) { sessionId?.let { repository.endSession(it) } }
        repository.close()
        tone.release()
        RadarRuntime.update { it.copy(running = false, message = "Остановлен") }
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Rally Helper")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Остановить", stop)
            .build()
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
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "radar"
        private const val NOTIFICATION_ID = 42

        fun startIntent(context: Context, resultCode: Int, data: Intent) =
            Intent(context, RadarForegroundService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode).putExtra(EXTRA_RESULT_DATA, data)

        fun stopIntent(context: Context) = Intent(context, RadarForegroundService::class.java).setAction(ACTION_STOP)
    }
}

private fun List<Long>.percentile(fraction: Double): Long? {
    if (isEmpty()) return null
    val index = kotlin.math.ceil((size - 1) * fraction).toInt().coerceIn(indices)
    return this[index]
}
