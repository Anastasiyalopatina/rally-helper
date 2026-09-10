package com.rallyhelper.capture

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import radar.vision.NormalizedRect
import radar.vision.OneTapRequest
import radar.vision.RallyCandidate
import radar.vision.RallyId
import radar.vision.RuntimeMode
import radar.vision.ScreenState
import java.util.concurrent.atomic.AtomicLong

internal data class OverlayCounters(
    val totalSeen: Long,
    val eligible: Long,
    val openSuccess: Long,
    val openFailed: Long,
    val skipped: Long,
    val shadowWouldAttempt: Long,
    val ignored: Long,
    val missed: Long,
)

internal data class OverlayAutomationState(
    val paused: Boolean = false,
    val phase: String = "IDLE",
    val delayRemainingSeconds: Int? = null,
)

internal enum class OverlayJoinPhase {
    IDLE, READY, CHECKING, OPENING, ANALYZING_SQUADS, SELECTING_SQUAD,
    CHECKING_TIME, SENDING, VERIFYING_SEND, SUCCESS, MANUAL_FALLBACK, FAILED,
}

internal data class OverlayJoinState(
    val phase: OverlayJoinPhase = OverlayJoinPhase.IDLE,
    val actionsAvailable: Boolean = true,
    val detail: String? = null,
)

internal class RallyOverlayController(
    private val context: Context,
    private val onJoinRequested: (OneTapRequest) -> Unit,
    private val onActionsPermissionRequested: () -> Unit,
    private val onPauseRequested: () -> Unit,
    private val onStopRequested: () -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val lifecycleRevision = AtomicLong()
    @Volatile private var root: LinearLayout? = null
    private var title: TextView? = null
    private var timer: TextView? = null
    private var counters: TextView? = null
    private var action: Button? = null
    @Volatile private var params: WindowManager.LayoutParams? = null
    @Volatile private var enabled = false
    @Volatile private var displayedRallyId: RallyId? = null
    @Volatile private var displayedFrameId: Long? = null

    fun setEnabled(value: Boolean) {
        enabled = value
        val revision = lifecycleRevision.incrementAndGet()
        mainHandler.post {
            if (revision != lifecycleRevision.get()) return@post
            if (value && Settings.canDrawOverlays(context)) ensureView() else removeView()
        }
    }

    fun update(
        mode: RuntimeMode,
        rallyId: RallyId?,
        frameId: Long?,
        rally: RallyCandidate?,
        values: OverlayCounters,
        automation: OverlayAutomationState = OverlayAutomationState(),
        join: OverlayJoinState = OverlayJoinState(),
    ) {
        mainHandler.post {
            if (!enabled || !Settings.canDrawOverlays(context)) return@post
            ensureView()
            displayedRallyId = rallyId
            displayedFrameId = frameId
            title?.text = when {
                mode == RuntimeMode.ONE_TAP -> "ONE TAP"
                rally == null -> mode.displayName()
                else -> "${mode.displayName()} · ${rally.level ?: "?"} ур · " +
                    "${rally.participantCount ?: "?"}/${rally.capacity ?: "?"}"
            }
            timer?.text = when {
                join.detail != null -> join.detail
                automation.paused -> "PAUSED"
                automation.delayRemainingSeconds != null -> "WAIT ${clock(automation.delayRemainingSeconds)}"
                rally != null -> rally.remainingSeconds?.let(::clock) ?: automation.phase
                else -> automation.phase
            }
            counters?.text = if (mode == RuntimeMode.SHADOW_AUTO) {
                "Всего ${values.totalSeen} · подходит ${values.eligible}\n" +
                    "◇ симуляций ${values.shadowWouldAttempt} · пропущено ${values.skipped}\n" +
                    "не подошло ${values.ignored} · не успели ${values.missed}"
            } else {
                "Всего ${values.totalSeen} · подходит ${values.eligible}\n" +
                    "Открыто ${values.openSuccess} · ошибок ${values.openFailed}\n" +
                    "пропущено ${values.skipped + values.ignored} · не успели ${values.missed}"
            }
            action?.apply {
                visibility = when (mode) {
                    RuntimeMode.ONE_TAP, RuntimeMode.AUTO, RuntimeMode.SHADOW_AUTO -> View.VISIBLE
                    RuntimeMode.RADAR -> View.GONE
                }
                text = when {
                    mode == RuntimeMode.ONE_TAP && !join.actionsAvailable -> "РАЗРЕШИТЬ ДЕЙСТВИЯ"
                    mode == RuntimeMode.ONE_TAP && join.phase == OverlayJoinPhase.CHECKING -> "ПРОВЕРКА…"
                    mode == RuntimeMode.ONE_TAP && join.phase == OverlayJoinPhase.OPENING -> "ОТКРЫВАЮ…"
                    mode == RuntimeMode.ONE_TAP && join.phase == OverlayJoinPhase.ANALYZING_SQUADS -> "ИЩУ ОТРЯД…"
                    mode == RuntimeMode.ONE_TAP && join.phase == OverlayJoinPhase.SELECTING_SQUAD -> "ВЫБИРАЮ…"
                    mode == RuntimeMode.ONE_TAP && join.phase == OverlayJoinPhase.CHECKING_TIME -> "ПРОВЕРЯЮ ВРЕМЯ…"
                    mode == RuntimeMode.ONE_TAP && join.phase == OverlayJoinPhase.SENDING -> "ОТПРАВЛЯЮ…"
                    mode == RuntimeMode.ONE_TAP && join.phase == OverlayJoinPhase.VERIFYING_SEND -> "ПРОВЕРЯЮ…"
                    mode == RuntimeMode.ONE_TAP && join.phase == OverlayJoinPhase.SUCCESS -> "ОТПРАВЛЕН ✓"
                    mode == RuntimeMode.ONE_TAP && join.phase == OverlayJoinPhase.MANUAL_FALLBACK -> "ЗАВЕРШИТЕ ВРУЧНУЮ"
                    mode == RuntimeMode.ONE_TAP && join.phase == OverlayJoinPhase.FAILED -> "НЕ УДАЛОСЬ"
                    mode == RuntimeMode.ONE_TAP && rallyId == null -> "НЕТ ЦЕЛИ"
                    mode == RuntimeMode.ONE_TAP -> "ВСТУПИТЬ"
                    automation.paused -> "RESUME"
                    else -> "PAUSE"
                }
                isEnabled = when {
                    mode != RuntimeMode.ONE_TAP -> true
                    !join.actionsAvailable -> true
                    join.phase != OverlayJoinPhase.READY -> false
                    else -> rallyId != null && frameId != null
                }
                setOnClickListener {
                    if (mode == RuntimeMode.ONE_TAP) {
                        if (!join.actionsAvailable) {
                            onActionsPermissionRequested()
                            return@setOnClickListener
                        }
                        val requestedId = displayedRallyId
                        val requestedFrame = displayedFrameId
                        if (requestedId != null && requestedFrame != null) {
                            onJoinRequested(OneTapRequest(requestedId, requestedFrame))
                        }
                    } else onPauseRequested()
                }
            }
        }
    }

    fun boundsNormalized(screenWidth: Int, screenHeight: Int): NormalizedRect? {
        val view = root ?: return null
        val layout = params ?: return null
        val width = view.width.takeIf { it > 0 } ?: dp(230)
        val height = view.height.takeIf { it > 0 } ?: dp(118)
        if (screenWidth <= 0 || screenHeight <= 0) return null
        val left = layout.x.coerceIn(0, screenWidth - 1)
        val top = layout.y.coerceIn(0, screenHeight - 1)
        val right = (left + width).coerceIn(left + 1, screenWidth)
        val bottom = (top + height).coerceIn(top + 1, screenHeight)
        return NormalizedRect(
            left.toDouble() / screenWidth,
            top.toDouble() / screenHeight,
            right.toDouble() / screenWidth,
            bottom.toDouble() / screenHeight,
        )
    }

    fun snapToSafeRegion(screenWidth: Int, screenHeight: Int): Boolean {
        val view = root ?: return false
        val layout = params ?: return false
        if (screenWidth <= 0 || screenHeight <= 0) return false
        mainHandler.post {
            layout.x = dp(8).coerceAtMost(maxOf(0, screenWidth - view.width))
            layout.y = maxOf(0, screenHeight - view.height - dp(72))
            runCatching { windowManager.updateViewLayout(view, layout) }
        }
        return true
    }

    fun close() {
        // Closing is terminal for this controller instance. Without this invalidation, an
        // update already queued on the main thread can recreate the window after STOP.
        enabled = false
        lifecycleRevision.incrementAndGet()
        if (Looper.myLooper() == Looper.getMainLooper()) removeView()
        else mainHandler.postAtFrontOfQueue { removeView() }
    }

    private fun ensureView() {
        if (root != null) return
        // A service restart must never leave two in-process overlay windows attached.
        activeController?.takeIf { it !== this }?.removeView()
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.argb(232, 24, 34, 48))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.argb(230, 125, 211, 252))
            }
        }
        val heading = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            text = "RADAR"
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
        }
        val button = Button(context).apply { visibility = View.GONE }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val close = Button(context).apply {
            text = "×"
            contentDescription = "Остановить Rally Helper и закрыть overlay"
            minWidth = dp(40)
            minimumWidth = dp(40)
            setOnClickListener { onStopRequested() }
        }
        header.addView(heading, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(close, LinearLayout.LayoutParams(dp(44), dp(44)))
        panel.addView(header)
        panel.addView(button)
        val metrics = context.resources.displayMetrics
        val layout = WindowManager.LayoutParams(
            minOf(dp(200), (metrics.widthPixels * 0.25).toInt()),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = (metrics.heightPixels * 0.55).toInt()
        }
        installDrag(heading, panel, layout)
        windowManager.addView(panel, layout)
        activeController = this
        root = panel
        title = heading
        timer = null
        counters = null
        action = button
        params = layout
        panel.post {
            layout.y = maxOf(0, metrics.heightPixels - panel.height - dp(72))
            runCatching { windowManager.updateViewLayout(panel, layout) }
        }
    }

    private fun installDrag(handle: View, view: View, layout: WindowManager.LayoutParams) {
        var originX = 0
        var originY = 0
        var touchX = 0f
        var touchY = 0f
        var updateScheduled = false
        var targetX = 0
        var targetY = 0
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    originX = layout.x; originY = layout.y; touchX = event.rawX; touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val metrics = context.resources.displayMetrics
                    val maxX = maxOf(0, metrics.widthPixels - view.width)
                    val maxY = maxOf(0, metrics.heightPixels - view.height)
                    targetX = (originX + event.rawX - touchX).toInt().coerceIn(0, maxX)
                    targetY = (originY + event.rawY - touchY).toInt().coerceIn(0, maxY)
                    if (!updateScheduled) {
                        updateScheduled = true
                        view.postOnAnimation {
                            updateScheduled = false
                            layout.x = targetX
                            layout.y = targetY
                            runCatching { windowManager.updateViewLayout(view, layout) }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
    }

    private fun removeView() {
        root?.let { view ->
            runCatching {
                if (view.isAttachedToWindow) windowManager.removeViewImmediate(view)
            }
        }
        root = null; title = null; timer = null; counters = null; action = null; params = null
        displayedRallyId = null; displayedFrameId = null
        if (activeController === this) activeController = null
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
    private fun clock(seconds: Int): String = "%02d:%02d".format(seconds / 60, seconds % 60)
    private fun RuntimeMode.displayName() = when (this) {
        RuntimeMode.RADAR -> "RADAR"
        RuntimeMode.ONE_TAP -> "ONE_TAP"
        RuntimeMode.AUTO -> "AUTO"
        RuntimeMode.SHADOW_AUTO -> "SHADOW"
    }

    private companion object {
        @Volatile private var activeController: RallyOverlayController? = null
    }
}

internal object OverlayCvSafety {
    // The default bottom placement stays clear of the populated event-card area. The broader
    // detector scan remains masked, while semantic card evidence rejects masked blank space.
    private val eventCritical = NormalizedRect(0.02, 0.14, 0.98, 0.68)
    private val marchCritical = NormalizedRect(0.14, 0.47, 0.86, 0.82)
    private val worldCritical = NormalizedRect(0.01, 0.145, 0.39, 0.34)

    fun overlapsCritical(bounds: NormalizedRect, screen: ScreenState): Boolean {
        val critical = when (screen) {
            ScreenState.EVENT_LIST -> eventCritical
            ScreenState.MARCH_SCREEN -> marchCritical
            ScreenState.WORLD_MAP -> worldCritical
            ScreenState.UNKNOWN -> return false
        }
        return bounds.left < critical.right && bounds.right > critical.left &&
            bounds.top < critical.bottom && bounds.bottom > critical.top
    }
}
