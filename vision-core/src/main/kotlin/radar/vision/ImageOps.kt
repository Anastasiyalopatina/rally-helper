package radar.vision

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal data class PixelBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

internal fun pixelBox(image: ArgbImage, rect: NormalizedRect) = PixelBox(
    left = (rect.left * image.width).toInt().coerceIn(0, image.width - 1),
    top = (rect.top * image.height).toInt().coerceIn(0, image.height - 1),
    right = (rect.right * image.width).toInt().coerceIn(1, image.width),
    bottom = (rect.bottom * image.height).toInt().coerceIn(1, image.height),
)

internal fun colorRatio(
    image: ArgbImage,
    rect: NormalizedRect,
    sampleWidth: Int = 320,
    predicate: (Int) -> Boolean,
): Double {
    val box = pixelBox(image, rect)
    val step = max(1, image.width / sampleWidth)
    var hits = 0
    var total = 0
    for (y in box.top until box.bottom step step) for (x in box.left until box.right step step) {
        total++
        if (predicate(image.argb(x, y))) hits++
    }
    return if (total == 0) 0.0 else hits.toDouble() / total
}

internal fun patchFeature(image: ArgbImage, rect: NormalizedRect, grid: Int = 20): DoubleArray {
    val box = pixelBox(image, rect)
    val values = DoubleArray(grid * grid * 3)
    var out = 0
    for (gy in 0 until grid) for (gx in 0 until grid) {
        val x = box.left + ((gx + 0.5) * box.width / grid).toInt()
        val y = box.top + ((gy + 0.5) * box.height / grid).toInt()
        val rgb = image.argb(x.coerceAtMost(image.width - 1), y.coerceAtMost(image.height - 1))
        values[out++] = red(rgb) / 255.0
        values[out++] = green(rgb) / 255.0
        values[out++] = blue(rgb) / 255.0
    }
    return values
}

internal fun featureDistance(a: DoubleArray, b: DoubleArray): Double {
    var total = 0.0
    for (i in a.indices) {
        val delta = a[i] - b[i]
        total += delta * delta
    }
    return sqrt(total / a.size)
}

internal fun red(argb: Int) = argb ushr 16 and 0xff
internal fun green(argb: Int) = argb ushr 8 and 0xff
internal fun blue(argb: Int) = argb and 0xff
internal fun luminance(argb: Int) = (red(argb) * 299 + green(argb) * 587 + blue(argb) * 114) / 1000

internal fun isEventCanvas(argb: Int): Boolean {
    val r = red(argb); val g = green(argb); val b = blue(argb)
    return r in 160..235 && g in 175..240 && b in 185..250 && max(r, max(g, b)) - min(r, min(g, b)) < 65
}

internal fun isSendBlue(argb: Int): Boolean {
    val r = red(argb); val g = green(argb); val b = blue(argb)
    return b > 145 && g > 90 && b > r * 1.45 && g > r * 1.15
}

internal fun isWorldGreen(argb: Int): Boolean {
    val r = red(argb); val g = green(argb); val b = blue(argb)
    return g in 70..165 && g > r * 1.12 && g > b * 1.05
}

internal fun isPlusGreen(argb: Int): Boolean {
    val r = red(argb); val g = green(argb); val b = blue(argb)
    return g >= 145 && g > r * 1.45 && g > b * 1.28
}
