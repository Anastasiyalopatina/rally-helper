package com.rallyhelper.capture

import android.content.Context
import radar.vision.FrameAnalysis

internal object BuildVariantHooks {
    fun analysisOverride(context: Context, frameId: Long, observedAtMonotonicMs: Long): FrameAnalysis? = null
    fun dispatchDelayMs(context: Context): Long = 0
    fun expectedGesturePackage(context: Context, configured: String): String = configured
    fun requiresGeometryInvalidation(context: Context): Boolean = false
}
