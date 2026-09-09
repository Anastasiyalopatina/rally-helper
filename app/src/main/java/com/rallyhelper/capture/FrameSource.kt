package com.rallyhelper.capture

import android.media.Image

fun interface FrameSource {
    fun setListener(listener: (Image) -> Unit)
}
