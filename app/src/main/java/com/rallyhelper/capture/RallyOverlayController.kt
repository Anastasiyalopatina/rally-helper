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
import radar.vision.RallyCandidate
import radar.vision.RuntimeMode

internal data class OverlayCounters(val success: Long, val failed: Long, val skipped: Long)

internal class RallyOverlayController(
    private val context: Context,
    private val onJoinRequested: () -> Unit,
    private val onPauseRequested: () -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(WindowManager::class.java)
    @Volatile private var root: LinearLayout? = null
    private var title: TextView? = null
    private var timer: TextView? = null
    private var counters: TextView? = null
    private var action: Button? = null
    @Volatile private var params: WindowManager.LayoutParams? = null
    @Volatile private var enabled = false

    fun setEnabled(value: Boolean) {
        enabled = value
        mainHandler.post { if (value && Settings.canDrawOverlays(context)) ensureView() else removeView() }
    }

    fun update(mode: RuntimeMode, rally: RallyCandidate?, values: OverlayCounters) {
        mainHandler.post {
            if (!enabled || !Settings.canDrawOverlays(context)) return@post
            ensureView()
            title?.text = when {
                rally == null -> mode.displayName()
                else -> "${mode.displayName()} · ${rally.level ?: "?"} ур · " +
                    "${rally.participantCount ?: "?"}/${rally.capacity ?: "?"}"
            }
            timer?.text = rally?.remainingSeconds?.let(::clock) ?: "—"
            counters?.text = "✓ ${values.success}   ✕ ${values.failed}   ↷ ${values.skipped}"
            action?.apply {
                visibility = when (mode) {
                    RuntimeMode.ONE_TAP, RuntimeMode.AUTO -> View.VISIBLE
                    RuntimeMode.RADAR, RuntimeMode.SHADOW_AUTO -> View.GONE
                }
                text = if (mode == RuntimeMode.AUTO) "PAUSE" else "ВСТУПИТЬ"
                setOnClickListener {
                    if (mode == RuntimeMode.AUTO) onPauseRequested() else onJoinRequested()
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

    fun close() = mainHandler.post { removeView() }

    private fun ensureView() {
        if (root != null) return
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
        }
        val clock = TextView(context).apply { setTextColor(Color.WHITE); textSize = 14f; text = "—" }
        val stats = TextView(context).apply { setTextColor(Color.LTGRAY); textSize = 13f; text = "✓ 0   ✕ 0   ↷ 0" }
        val button = Button(context).apply { visibility = View.GONE }
        panel.addView(heading)
        panel.addView(clock)
        panel.addView(stats)
        panel.addView(button)
        val metrics = context.resources.displayMetrics
        val layout = WindowManager.LayoutParams(
            minOf(dp(230), (metrics.widthPixels * 0.28).toInt()),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = (metrics.heightPixels * 0.55).toInt()
        }
        installDrag(panel, layout)
        windowManager.addView(panel, layout)
        root = panel
        title = heading
        timer = clock
        counters = stats
        action = button
        params = layout
    }

    private fun installDrag(view: View, layout: WindowManager.LayoutParams) {
        var originX = 0
        var originY = 0
        var touchX = 0f
        var touchY = 0f
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    originX = layout.x; originY = layout.y; touchX = event.rawX; touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val metrics = context.resources.displayMetrics
                    // Keep the panel in the left safe strip, away from known plus/send regions.
                    val maxX = (metrics.widthPixels * 0.06).toInt()
                    val maxY = minOf(
                        maxOf(0, metrics.heightPixels - view.height),
                        (metrics.heightPixels * 0.64).toInt(),
                    )
                    layout.x = (originX + event.rawX - touchX).toInt().coerceIn(0, maxX)
                    layout.y = (originY + event.rawY - touchY).toInt().coerceIn(0, maxY)
                    windowManager.updateViewLayout(view, layout)
                    true
                }
                else -> false
            }
        }
    }

    private fun removeView() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null; title = null; timer = null; counters = null; action = null; params = null
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
    private fun clock(seconds: Int): String = "%02d:%02d".format(seconds / 60, seconds % 60)
    private fun RuntimeMode.displayName() = when (this) {
        RuntimeMode.RADAR -> "RADAR"
        RuntimeMode.ONE_TAP -> "ONE_TAP"
        RuntimeMode.AUTO -> "AUTO"
        RuntimeMode.SHADOW_AUTO -> "SHADOW"
    }
}
