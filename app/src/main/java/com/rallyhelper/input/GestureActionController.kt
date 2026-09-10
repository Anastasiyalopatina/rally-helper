package com.rallyhelper.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import radar.vision.GestureGateDecision
import radar.vision.GestureRejectReason
import radar.vision.GestureRequest
import radar.vision.GestureSafetyGate

/** Low-level purpose-scoped bridge. Screen interpretation and product policy stay outside it. */
object GestureActionController {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mutableConnected = MutableStateFlow(false)
    val connected = mutableConnected.asStateFlow()

    @Volatile private var service: RefreshAccessibilityService? = null
    @Volatile private var cancellationGeneration = 0L

    internal fun attach(value: RefreshAccessibilityService) {
        service = value
        mutableConnected.value = true
    }

    internal fun detach(value: RefreshAccessibilityService) {
        if (service === value) service = null
        mutableConnected.value = service != null
    }

    fun dispatch(
        request: GestureRequest,
        displayWidth: Int,
        displayHeight: Int,
        verifiedPackage: String,
        onAccepted: () -> Unit,
        onRejected: (GestureRejectReason) -> Unit,
        onCompleted: (Boolean, Long) -> Unit,
    ): Boolean {
        val current = service
        val generation = cancellationGeneration
        if (current == null) {
            onRejected(GestureRejectReason.SERVICE_DISCONNECTED)
            return false
        }
        mainHandler.post {
            if (generation != cancellationGeneration) {
                onRejected(GestureRejectReason.CANCELLED)
                return@post
            }
            current.dispatchValidated(
                request = request,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
                verifiedPackage = verifiedPackage,
                onAccepted = onAccepted,
                onRejected = onRejected,
                onCompleted = onCompleted,
            )
        }
        return true
    }

    fun cancelAll() {
        cancellationGeneration++
        mainHandler.post { service?.cancelAllRequests() }
    }
}

class RefreshAccessibilityService : AccessibilityService() {
    @Volatile private var lastForegroundPackage: String? = null
    @Volatile private var lastForegroundEventMonotonicMs: Long? = null
    private val cancelledRequestIds = mutableSetOf<String>()
    private var inFlightRequestId: String? = null

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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        lastForegroundPackage = event.packageName?.toString()
        lastForegroundEventMonotonicMs = SystemClock.elapsedRealtime()
    }

    internal fun cancelAllRequests() {
        inFlightRequestId?.let(cancelledRequestIds::add)
    }

    internal fun dispatchValidated(
        request: GestureRequest,
        displayWidth: Int,
        displayHeight: Int,
        verifiedPackage: String,
        onAccepted: () -> Unit,
        onRejected: (GestureRejectReason) -> Unit,
        onCompleted: (Boolean, Long) -> Unit,
    ) {
        val now = SystemClock.elapsedRealtime()
        val decision: GestureGateDecision = GestureSafetyGate.evaluate(
            request = request,
            verifiedPackage = verifiedPackage,
            lastForegroundPackage = lastForegroundPackage,
            lastForegroundEventMonotonicMs = lastForegroundEventMonotonicMs,
            nowMonotonicMs = now,
            serviceConnected = true,
            cancelled = request.requestId in cancelledRequestIds,
            gestureInFlight = inFlightRequestId != null,
        )
        if (!decision.allowed) {
            cancelledRequestIds.remove(request.requestId)
            onRejected(decision.reason)
            return
        }
        inFlightRequestId = request.requestId
        val path = Path().apply {
            moveTo(
                (request.point.x * displayWidth).toFloat(),
                (request.point.y * displayHeight).toFloat(),
            )
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 45))
            .build()
        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    val cancelled = request.requestId in cancelledRequestIds
                    inFlightRequestId = null
                    cancelledRequestIds.remove(request.requestId)
                    onCompleted(!cancelled, SystemClock.elapsedRealtime())
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    inFlightRequestId = null
                    cancelledRequestIds.remove(request.requestId)
                    onCompleted(false, SystemClock.elapsedRealtime())
                }
            },
            null,
        )
        if (accepted) onAccepted() else {
            inFlightRequestId = null
            onRejected(GestureRejectReason.DISPATCH_REJECTED)
        }
    }
}
