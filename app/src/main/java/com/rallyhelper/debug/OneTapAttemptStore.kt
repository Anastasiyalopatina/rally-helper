package com.rallyhelper.debug

import android.content.Context
import org.json.JSONObject
import radar.vision.FrameAnalysis
import radar.vision.GestureRequest
import radar.vision.RallyCandidate

/** Private metadata-only journal. It never stores pixels or screenshots. */
class OneTapAttemptStore(context: Context) {
    private val file = context.filesDir.resolve("one-tap-attempts.jsonl")

    @Synchronized
    fun recordDispatch(request: GestureRequest, candidate: RallyCandidate?) {
        append(
            JSONObject()
                .put("kind", "OPEN_ATTEMPT")
                .put("recordedAtEpochMs", System.currentTimeMillis())
                .put("requestId", request.requestId)
                .put("rallyId", request.rallyId?.value)
                .put("sourceFrameId", request.sourceFrameId)
                .put("bossConfidence", candidate?.confidences?.boss)
                .put("level", candidate?.level)
                .put("levelConfidence", candidate?.confidences?.level)
                .put("plusConfidence", candidate?.confidences?.plus)
                .put("gestureOutcome", "DISPATCH_REQUESTED"),
        )
    }

    @Synchronized
    fun recordResult(requestId: String?, success: Boolean, reason: String, postFrame: FrameAnalysis? = null) {
        append(
            JSONObject()
                .put("kind", "OPEN_RESULT")
                .put("recordedAtEpochMs", System.currentTimeMillis())
                .put("requestId", requestId)
                .put("gestureOutcome", if (success) "OPENED" else "FAILED")
                .put("postFrameScreen", postFrame?.screen?.name ?: "UNKNOWN")
                .put("postFrameConfidence", postFrame?.screenConfidence)
                .put("reason", reason),
        )
    }

    private fun append(value: JSONObject) {
        file.parentFile?.mkdirs()
        file.appendText(value.toString() + "\n")
    }
}
