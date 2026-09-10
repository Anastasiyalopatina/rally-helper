package com.rallyhelper.capture

import android.content.Context
import android.net.Uri
import radar.vision.BossType
import radar.vision.FrameAnalysis
import radar.vision.JoinedState
import radar.vision.NormalizedRect
import radar.vision.RallyCandidate
import radar.vision.RallyConfidences
import radar.vision.RallyIdentityFingerprint
import radar.vision.Recognition
import radar.vision.ScreenState

/** Deterministic fixture compiled only into the oneTapIntegration variant. */
internal object BuildVariantHooks {
    private val uri = Uri.parse("content://com.rallyhelper.testtarget.state/state")

    private data class State(
        val screen: String,
        val delayMs: Long,
        val wrongExpectedPackage: Boolean,
        val geometryLoss: Boolean,
    )

    fun analysisOverride(context: Context, frameId: Long, observedAtMonotonicMs: Long): FrameAnalysis {
        return when (read(context)?.screen) {
            "TEST_EVENT" -> FrameAnalysis(
                frameId = frameId,
                observedAtMonotonicMs = observedAtMonotonicMs,
                screen = ScreenState.EVENT_LIST,
                screenConfidence = 1f,
                rallies = listOf(
                    RallyCandidate(
                        ephemeralId = null,
                        bossType = BossType.TARGET,
                        level = 10,
                        participantCount = 1,
                        capacity = 5,
                        remainingSeconds = 45,
                        firstSeenMonotonicMs = observedAtMonotonicMs,
                        cardBounds = NormalizedRect(.08, .15, .92, .72),
                        joinPlusBounds = listOf(NormalizedRect(.43, .49, .57, .59)),
                        joinable = true,
                        full = false,
                        joinedState = JoinedState.JOINABLE,
                        confidences = RallyConfidences(1f, 1f, 1f, 1f, 1f, 1f),
                        identityFingerprint = RallyIdentityFingerprint(0x10203040, 0x50607080),
                    ),
                ),
                diagnostics = mapOf("oneTapIntegration" to 1.0),
            )
            "TEST_MARCH" -> FrameAnalysis(
                frameId,
                observedAtMonotonicMs,
                ScreenState.MARCH_SCREEN,
                1f,
                travelTime = Recognition.unknown("integration marker"),
                diagnostics = mapOf("oneTapIntegration" to 1.0),
            )
            else -> FrameAnalysis(frameId, observedAtMonotonicMs, ScreenState.UNKNOWN, 0f)
        }
    }

    fun dispatchDelayMs(context: Context): Long = read(context)?.delayMs?.coerceIn(0, 1_500) ?: 0
    fun expectedGesturePackage(context: Context, configured: String): String =
        if (read(context)?.wrongExpectedPackage == true) "invalid.integration.package" else configured
    fun requiresGeometryInvalidation(context: Context): Boolean = read(context)?.geometryLoss == true

    private fun read(context: Context): State? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            State(
                cursor.getString(cursor.getColumnIndexOrThrow("state")),
                cursor.getLong(cursor.getColumnIndexOrThrow("dispatchDelayMs")),
                cursor.getInt(cursor.getColumnIndexOrThrow("wrongExpectedPackage")) != 0,
                cursor.getInt(cursor.getColumnIndexOrThrow("geometryLoss")) != 0,
            )
        }
    }.getOrNull()
}
