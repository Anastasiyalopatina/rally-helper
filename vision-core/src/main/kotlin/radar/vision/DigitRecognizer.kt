package radar.vision

import kotlin.math.abs
import kotlin.math.max

data class DigitTemplate(val digit: Int, val feature: DoubleArray, val lightOnDark: Boolean)

class DigitRecognizer private constructor(
    private val templates: List<DigitTemplate>,
    private val absoluteThreshold: Float = 0.58f,
    private val marginThreshold: Float = 0.035f,
) {
    val supportedDigits: Set<Int> = templates.map { it.digit }.toSet()

    fun readInteger(image: ArgbImage, rect: NormalizedRect, maxDigits: Int = 3): Recognition<Int> {
        val glyphs = segmentGlyphs(image, rect, lightOnDark = false).take(maxDigits)
        if (glyphs.isEmpty()) return Recognition.unknown("no digit glyphs")
        val recognized = glyphs.map { classify(it, lightOnDark = false) }
        val rejected = recognized.firstOrNull { !it.accepted || it.value == null }
        if (rejected != null) return Recognition.unknown(
            rejected.rejectionReason ?: "digit rejected",
            recognized.minOf { it.confidence },
        )
        val value = recognized.joinToString("") { it.value.toString() }.toIntOrNull()
            ?: return Recognition.unknown("integer parse failed")
        return Recognition(
            value = value,
            confidence = recognized.minOf { it.confidence },
            runnerUpConfidence = recognized.maxOf { it.runnerUpConfidence },
            accepted = true,
        )
    }

    fun readLevel(image: ArgbImage, rect: NormalizedRect): Recognition<Int> =
        readFeatures(levelGlyphs(image, rect), lightOnDark = false)

    fun readClock(image: ArgbImage, rect: NormalizedRect, lightOnDark: Boolean = false): Recognition<Int> {
        val glyphs = segmentGlyphs(image, rect, lightOnDark)
        if (glyphs.size !in setOf(4, 6)) {
            return Recognition.unknown("expected 4 or 6 clock digits, found ${glyphs.size}")
        }
        val recognized = glyphs.map { classify(it, lightOnDark) }
        if (recognized.any { !it.accepted || it.value == null }) {
            return Recognition(
                value = null,
                confidence = recognized.minOf { it.confidence },
                runnerUpConfidence = recognized.maxOf { it.runnerUpConfidence },
                accepted = false,
                rejectionReason = "one or more clock digits rejected",
            )
        }
        val digits = recognized.map { requireNotNull(it.value) }
        val seconds = if (digits.size == 6) {
            (digits[0] * 10 + digits[1]) * 3600 + (digits[2] * 10 + digits[3]) * 60 + digits[4] * 10 + digits[5]
        } else {
            (digits[0] * 10 + digits[1]) * 60 + digits[2] * 10 + digits[3]
        }
        return Recognition(
            value = seconds,
            confidence = recognized.minOf { it.confidence },
            runnerUpConfidence = recognized.maxOf { it.runnerUpConfidence },
            accepted = true,
        )
    }

    private fun readFeatures(glyphs: List<DoubleArray>, lightOnDark: Boolean): Recognition<Int> {
        if (glyphs.isEmpty()) return Recognition.unknown("no digit glyphs")
        val recognized = glyphs.map { classify(it, lightOnDark) }
        val rejected = recognized.firstOrNull { !it.accepted || it.value == null }
        if (rejected != null) return Recognition.unknown(
            rejected.rejectionReason ?: "digit rejected",
            recognized.minOf { it.confidence },
        )
        return Recognition(
            value = recognized.joinToString("") { it.value.toString() }.toIntOrNull(),
            confidence = recognized.minOf { it.confidence },
            runnerUpConfidence = recognized.maxOf { it.runnerUpConfidence },
            accepted = true,
        )
    }

    private fun classify(feature: DoubleArray, lightOnDark: Boolean): Recognition<Int> {
        val eligible = templates.filter { it.lightOnDark == lightOnDark }
        if (eligible.isEmpty()) return Recognition.unknown("digit library is empty")
        val byDigit = eligible.groupBy { it.digit }.mapValues { (_, samples) ->
            samples.minOf { featureDistance(feature, it.feature) }
        }.entries.sortedBy { it.value }
        val winner = byDigit.first()
        val runner = byDigit.getOrNull(1)
        val confidence = (1.0 - winner.value / 0.72).coerceIn(0.0, 1.0).toFloat()
        val runnerConfidence = runner?.let { (1.0 - it.value / 0.72).coerceIn(0.0, 1.0).toFloat() } ?: 0f
        val margin = confidence - runnerConfidence
        val accepted = confidence >= absoluteThreshold && margin >= marginThreshold
        return Recognition(
            value = winner.key.takeIf { accepted },
            confidence = confidence,
            runnerUpConfidence = runnerConfidence,
            accepted = accepted,
            rejectionReason = when {
                confidence < absoluteThreshold -> "digit confidence below threshold"
                margin < marginThreshold -> "digit winner margin too small"
                else -> null
            },
        )
    }

    companion object {
        fun fromTemplates(templates: List<DigitTemplate>): DigitRecognizer = DigitRecognizer(templates)

        fun calibrate(
            references: List<ReferenceTemplate>,
            profile: CalibrationProfile,
        ): DigitRecognizer = DigitRecognizer(compileTemplates(references, profile))

        fun compileTemplates(
            references: List<ReferenceTemplate>,
            profile: CalibrationProfile,
        ): List<DigitTemplate> {
            val samples = mutableListOf<DigitTemplate>()
            fun add(image: ArgbImage, rect: NormalizedRect, label: String, lightOnDark: Boolean = false) {
                val glyphs = segmentGlyphs(image, rect, lightOnDark)
                if (glyphs.size != label.length) return
                glyphs.zip(label.toList()).forEach { (feature, char) ->
                    if (char.isDigit()) samples += DigitTemplate(char.digitToInt(), feature, lightOnDark)
                }
            }
            references.forEach { reference ->
                reference.level?.let { level ->
                    val glyphs = levelGlyphs(reference.image, profile.firstCard.local(profile.levelDigitsLocal))
                    if (glyphs.size == level.toString().length) glyphs.zip(level.toString().toList()).forEach { (feature, char) ->
                        samples += DigitTemplate(char.digitToInt(), feature, false)
                    }
                }
                reference.participantCount?.let {
                    add(reference.image, profile.firstCard.local(profile.participantCurrentLocal), it.toString())
                }
                reference.capacity?.let {
                    add(reference.image, profile.firstCard.local(profile.participantCapacityLocal), it.toString())
                }
                reference.rallyCountdownSeconds?.let {
                    val hhmmss = "%06d".format((it / 3600) * 10000 + ((it / 60) % 60) * 100 + it % 60)
                    add(reference.image, profile.firstCard.local(profile.countdownDigitsLocal), hhmmss, lightOnDark = true)
                }
                reference.travelTimeSeconds?.let {
                    val hhmmss = "%06d".format((it / 3600) * 10000 + ((it / 60) % 60) * 100 + it % 60)
                    add(reference.image, profile.travelTimerDigits, hhmmss)
                }
            }
            return samples
        }

        internal fun segmentGlyphs(
            image: ArgbImage,
            rect: NormalizedRect,
            lightOnDark: Boolean = false,
        ): List<DoubleArray> {
            return segmentGlyphBoxes(image, rect, lightOnDark).map { glyphFeature(image, it, lightOnDark = lightOnDark) }
        }

        private data class GlyphBox(val left: Int, val top: Int, val right: Int, val bottom: Int)

        private fun levelGlyphs(image: ArgbImage, rect: NormalizedRect): List<DoubleArray> {
            val boxes = segmentGlyphBoxes(image, rect, lightOnDark = false).flatMap { box ->
                val width = box.right - box.left
                val height = box.bottom - box.top
                if (width.toDouble() / height > 1.15) {
                    val middle = (box.left + box.right) / 2
                    listOf(box.copy(right = middle), box.copy(left = middle))
                } else listOf(box)
            }
            if (boxes.isEmpty()) return emptyList()
            val first = boxes.first()
            val result = mutableListOf(first)
            for (next in boxes.drop(1)) {
                val previous = result.last()
                val typicalHeight = max(previous.bottom - previous.top, next.bottom - next.top)
                if (next.left - previous.right > max(7, (typicalHeight * 0.30).toInt())) break
                result += next
                if (result.size == 3) break
            }
            return result.map { glyphFeature(image, it, lightOnDark = false) }
        }

        private fun segmentGlyphBoxes(
            image: ArgbImage,
            rect: NormalizedRect,
            lightOnDark: Boolean,
        ): List<GlyphBox> {
            val box = pixelBox(image, rect)
            val mask = BooleanArray(box.width * box.height)
            for (y in 0 until box.height) for (x in 0 until box.width) {
                val pixel = image.argb(box.left + x, box.top + y)
                mask[y * box.width + x] = if (lightOnDark) {
                    luminance(pixel) > 205 && maxOf(red(pixel), green(pixel), blue(pixel)) -
                        minOf(red(pixel), green(pixel), blue(pixel)) < 48
                } else luminance(pixel) < 115
            }
            val seen = BooleanArray(mask.size)
            val queue = IntArray(mask.size)
            val components = mutableListOf<Pair<Int, GlyphBox>>()
            for (start in mask.indices) {
                if (!mask[start] || seen[start]) continue
                var head = 0; var tail = 0; var count = 0
                var minX = box.width; var minY = box.height; var maxX = 0; var maxY = 0
                queue[tail++] = start; seen[start] = true
                while (head < tail) {
                    val current = queue[head++]; val x = current % box.width; val y = current / box.width
                    count++; minX = minOf(minX, x); minY = minOf(minY, y); maxX = maxOf(maxX, x); maxY = maxOf(maxY, y)
                    for (dy in -1..1) for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx; val ny = y + dy
                        if (nx !in 0 until box.width || ny !in 0 until box.height) continue
                        val index = ny * box.width + nx
                        if (mask[index] && !seen[index]) { seen[index] = true; queue[tail++] = index }
                    }
                }
                val width = maxX - minX + 1; val height = maxY - minY + 1
                val minimumHeight = if (lightOnDark) box.height * 0.62 else box.height * 0.27
                val aspect = width.toDouble() / height.coerceAtLeast(1)
                val maximumAspect = if (lightOnDark) 1.4 else 2.0
                if (count >= 12 && width >= 3 && height >= minimumHeight && aspect <= maximumAspect) {
                    components += count to GlyphBox(box.left + minX, box.top + minY, box.left + maxX + 1, box.top + maxY + 1)
                }
            }
            return components.sortedBy { it.second.left }.map { it.second }
        }

        private fun glyphFeature(
            image: ArgbImage,
            glyph: GlyphBox,
            gridW: Int = 14,
            gridH: Int = 22,
            lightOnDark: Boolean,
        ): DoubleArray {
            val width = glyph.right - glyph.left
            val height = glyph.bottom - glyph.top
            val feature = DoubleArray(gridW * gridH + 1)
            feature[0] = width.toDouble() / height.coerceAtLeast(1)
            var index = 1
            for (gy in 0 until gridH) for (gx in 0 until gridW) {
                val x = glyph.left + ((gx + 0.5) * width / gridW).toInt().coerceAtMost(width - 1)
                val y = glyph.top + ((gy + 0.5) * height / gridH).toInt().coerceAtMost(height - 1)
                feature[index++] = if (
                    if (lightOnDark) luminance(image.argb(x, y)) > 190 else luminance(image.argb(x, y)) < 130
                ) 1.0 else 0.0
            }
            return feature
        }
    }
}
