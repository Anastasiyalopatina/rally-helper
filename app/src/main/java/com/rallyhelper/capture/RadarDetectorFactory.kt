package com.rallyhelper.capture

import android.content.Context
import android.graphics.Bitmap
import radar.vision.ArgbImage
import radar.vision.DetectorTemplates
import radar.vision.RallyDetector
import radar.vision.RuntimeTemplateCodec
import radar.vision.NormalizedRect

internal class BitmapArgbImage(private val bitmap: Bitmap) : ArgbImage {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height
    override fun argb(x: Int, y: Int): Int = bitmap.getPixel(x, y)
}

internal object RadarDetectorFactory {
    fun create(context: Context): RallyDetector {
        val templates = context.assets.open(ASSET_NAME).use(RuntimeTemplateCodec::read)
        return RallyDetector(DetectorTemplates(templates))
    }

    private const val ASSET_NAME = "detector_templates.bin"
}

internal class IntArrayArgbImage(
    private var pixels: IntArray,
    override var width: Int,
    override var height: Int,
) : ArgbImage {
    fun reset(pixels: IntArray, width: Int, height: Int) {
        require(pixels.size >= width * height)
        this.pixels = pixels
        this.width = width
        this.height = height
    }

    override fun argb(x: Int, y: Int): Int = pixels[y * width + x]

    fun mask(rect: NormalizedRect, color: Int = 0xFFB8C4D0.toInt()) {
        val left = (rect.left * width).toInt().coerceIn(0, width)
        val right = (rect.right * width).toInt().coerceIn(left, width)
        val top = (rect.top * height).toInt().coerceIn(0, height)
        val bottom = (rect.bottom * height).toInt().coerceIn(top, height)
        for (y in top until bottom) pixels.fill(color, y * width + left, y * width + right)
    }
}
