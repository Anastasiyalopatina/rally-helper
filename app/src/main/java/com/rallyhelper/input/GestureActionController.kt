package com.rallyhelper.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import radar.vision.NormalizedPoint

/** Low-level gesture bridge only. Screen interpretation and policy stay outside this service. */
object GestureActionController {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mutableConnected = MutableStateFlow(false)
    val connected = mutableConnected.asStateFlow()

    @Volatile private var service: RefreshAccessibilityService? = null

    internal fun attach(value: RefreshAccessibilityService) {
        service = value
        mutableConnected.value = true
    }

    internal fun detach(value: RefreshAccessibilityService) {
        if (service === value) service = null
        mutableConnected.value = service != null
    }

    fun tap(
        point: NormalizedPoint,
        displayWidth: Int,
        displayHeight: Int,
        onResult: (Boolean) -> Unit,
    ): Boolean {
        val current = service ?: return false
        mainHandler.post {
            current.dispatchTap(
                x = (point.x * displayWidth).toFloat(),
                y = (point.y * displayHeight).toFloat(),
                onResult = onResult,
            )
        }
        return true
    }
}

class RefreshAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        GestureActionController.attach(this)
    }

    override fun onDestroy() {
        GestureActionController.detach(this)
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        GestureActionController.detach(this)
        return super.onUnbind(intent)
    }

    override fun onInterrupt() = Unit
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    internal fun dispatchTap(x: Float, y: Float, onResult: (Boolean) -> Unit) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 45))
            .build()
        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) = onResult(true)
                override fun onCancelled(gestureDescription: GestureDescription?) = onResult(false)
            },
            null,
        )
        if (!accepted) onResult(false)
    }
}
