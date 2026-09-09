package com.rallyhelper.capture

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler

internal class ScreenCaptureController(
    private val projection: MediaProjection,
    private var width: Int,
    private var height: Int,
    private var densityDpi: Int,
    private val handler: Handler,
) : FrameSource {
    private var listener: ((Image) -> Unit)? = null
    private var reader = newReader(width, height)
    private val display: VirtualDisplay = projection.createVirtualDisplay(
        "RallyHelperRadar",
        width,
        height,
        densityDpi,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
        reader.surface,
        null,
        handler,
    )

    override fun setListener(listener: (Image) -> Unit) {
        this.listener = listener
        attachListener()
    }

    @Synchronized
    fun resize(width: Int, height: Int, densityDpi: Int) {
        if (this.width == width && this.height == height && this.densityDpi == densityDpi) return
        val previous = reader
        previous.setOnImageAvailableListener(null, null)
        val replacement = newReader(width, height)
        display.resize(width, height, densityDpi)
        display.surface = replacement.surface
        this.width = width
        this.height = height
        this.densityDpi = densityDpi
        reader = replacement
        attachListener()
        previous.close()
    }

    fun close() {
        reader.setOnImageAvailableListener(null, null)
        display.release()
        reader.close()
    }

    private fun newReader(width: Int, height: Int) =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)

    private fun attachListener() {
        val callback = listener ?: return
        reader.setOnImageAvailableListener({ source -> source.acquireLatestImage()?.let(callback) }, handler)
    }

    companion object {
        class ReusableFrameBuffer {
            private var bitmap: Bitmap? = null
            private var pixels = IntArray(0)
            private val argbImage = IntArrayArgbImage(IntArray(1), 1, 1)
            private var visibleWidth = 0
            private var visibleHeight = 0

            fun copyFrom(image: Image): IntArrayArgbImage {
                val plane = image.planes.single()
                require(plane.pixelStride == 4) { "Unsupported RGBA pixel stride ${plane.pixelStride}" }
                val paddedWidth = plane.rowStride / plane.pixelStride
                if (bitmap?.width != paddedWidth || bitmap?.height != image.height) {
                    bitmap?.recycle()
                    bitmap = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                }
                val required = image.width * image.height
                if (pixels.size < required) pixels = IntArray(required)
                plane.buffer.rewind()
                bitmap!!.copyPixelsFromBuffer(plane.buffer)
                bitmap!!.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                visibleWidth = image.width
                visibleHeight = image.height
                argbImage.reset(pixels, visibleWidth, visibleHeight)
                return argbImage
            }

            /** Allocated only for an explicitly retained diagnostic capture, never per analyzed frame. */
            fun snapshotBitmap(): Bitmap = Bitmap.createBitmap(
                pixels,
                visibleWidth,
                visibleHeight,
                Bitmap.Config.ARGB_8888,
            )

            fun close() {
                bitmap?.recycle()
                bitmap = null
                pixels = IntArray(0)
            }
        }

        @Deprecated("Use ReusableFrameBuffer; retained for one-off tooling only")
        fun toBitmap(image: Image): Bitmap {
            val plane = image.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * image.width
            val padded = Bitmap.createBitmap(
                image.width + rowPadding / plane.pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888,
            )
            padded.copyPixelsFromBuffer(plane.buffer)
            val result = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            if (result !== padded) padded.recycle()
            return result
        }
    }
}
