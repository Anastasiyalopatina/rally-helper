package radar.vision

import kotlin.math.max

data class MarchSquadSelectionConfig(
    val priority: List<Int> = listOf(1, 2, 3),
    val allowReturning: Boolean = true,
)

data class MarchSquadSelection(
    val squad: MarchSquadInfo?,
    val reason: String,
)

object MarchSquadSelector {
    fun select(squads: List<MarchSquadInfo>, config: MarchSquadSelectionConfig): MarchSquadSelection {
        val bySlot = squads.associateBy(MarchSquadInfo::slotIndex)
        val chosen = config.priority.asSequence()
            .mapNotNull(bySlot::get)
            .firstOrNull { squad ->
                when (squad.state) {
                    MarchSquadState.FREE -> squad.stateConfidence >= 0.78f
                    MarchSquadState.RETURNING -> config.allowReturning && squad.redirectConfirmed &&
                        squad.stateConfidence >= 0.82f
                    MarchSquadState.BUSY, MarchSquadState.UNKNOWN -> false
                }
            }
        return MarchSquadSelection(chosen, if (chosen == null) "NO_ELIGIBLE_SQUAD" else "SQUAD_${chosen.slotIndex}")
    }
}

/** Detector dedicated to the three squad buttons on MARCH_SCREEN. The fourth chest tile is outside every ROI. */
class MarchSquadSelectorDetector(private val profile: CalibrationProfile = CalibrationProfile()) {
    fun detect(image: ArgbImage): List<MarchSquadInfo> = profile.marchSquadSlots.mapIndexed { index, bounds ->
        val blueBadge = ratio(image, bounds.local(NormalizedRect(.02, .02, .36, .48)), ::isSleepBlue)
        val orangeBorder = borderRatios(image, bounds, ::isSelectionOrange)
            .sortedDescending()
            .getOrElse(1) { 0.0 }
        val returnIcon = ratio(image, bounds.local(NormalizedRect(.00, .25, .32, .78)), ::isReturnOrange)
        val timerInk = ratio(image, bounds.local(NormalizedRect(.24, .62, .88, .98))) { luminance(it) < 100 }
        val occupiedVisual = ratio(image, bounds.local(NormalizedRect(.12, .05, .88, .66))) {
            luminance(it) < 115 || saturation(it) > .26
        }

        // Require orange evidence on at least two edges. A selected neighbour's outer
        // bracket can enter one edge of this ROI and must not select two squads at once.
        val selectedConfidence = scaled(orangeBorder, .015, .10)
        val selected = selectedConfidence >= .72f
        val freeConfidence = scaled(blueBadge, .025, .12)
        val returningConfidence = minOf(
            scaled(returnIcon, .035, .18),
            scaled(timerInk, .055, .22),
            scaled(occupiedVisual, .14, .48),
        )
        val busyConfidence = scaled(occupiedVisual, .18, .55)
        val (state, confidence, redirect) = when {
            freeConfidence >= .78f -> Triple(MarchSquadState.FREE, freeConfidence, false)
            returningConfidence >= .82f -> Triple(MarchSquadState.RETURNING, returningConfidence, true)
            busyConfidence >= .72f -> Triple(MarchSquadState.BUSY, busyConfidence, false)
            else -> Triple(MarchSquadState.UNKNOWN, max(freeConfidence, max(returningConfidence, busyConfidence)), false)
        }
        MarchSquadInfo(
            slotIndex = index + 1,
            bounds = bounds,
            state = state,
            selected = selected,
            stateConfidence = confidence,
            selectedConfidence = selectedConfidence,
            statusTimerSeconds = null,
            redirectConfirmed = redirect,
        )
    }

    fun detectSendButton(image: ArgbImage): SendButtonCandidate? {
        val bounds = profile.sendButtonBand
        val blue = ratio(image, bounds, ::isSendBlue)
        if (blue < .14) return null
        return SendButtonCandidate(bounds, enabled = blue >= .18, confidence = scaled(blue, .14, .48))
    }

    fun detectTroops(image: ArgbImage): TroopSanity {
        val green = ratio(image, profile.marchTroopBar, ::isTroopGreen)
        return TroopSanity(nonEmpty = green >= .16, confidence = scaled(green, .10, .48))
    }

    private fun borderRatios(image: ArgbImage, rect: NormalizedRect, predicate: (Int) -> Boolean): List<Double> {
        val top = rect.local(NormalizedRect(0.0, 0.0, 1.0, .12))
        val bottom = rect.local(NormalizedRect(0.0, .88, 1.0, 1.0))
        val left = rect.local(NormalizedRect(0.0, .12, .12, .88))
        val right = rect.local(NormalizedRect(.88, .12, 1.0, .88))
        return listOf(top, bottom, left, right).map { ratio(image, it, predicate) }
    }

    private fun ratio(image: ArgbImage, rect: NormalizedRect, predicate: (Int) -> Boolean): Double {
        val x0 = (rect.left * image.width).toInt().coerceIn(0, image.width - 1)
        val x1 = (rect.right * image.width).toInt().coerceIn(x0 + 1, image.width)
        val y0 = (rect.top * image.height).toInt().coerceIn(0, image.height - 1)
        val y1 = (rect.bottom * image.height).toInt().coerceIn(y0 + 1, image.height)
        val step = max(1, minOf(x1 - x0, y1 - y0) / 28)
        var hits = 0
        var total = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                if (predicate(image.argb(x, y))) hits++
                total++
                x += step
            }
            y += step
        }
        return if (total == 0) 0.0 else hits.toDouble() / total
    }

    private fun isSleepBlue(c: Int): Boolean {
        val r = red(c); val g = green(c); val b = blue(c)
        return b >= 120 && b > r * 1.18 && g > r * 1.05
    }

    private fun isSelectionOrange(c: Int): Boolean {
        val r = red(c); val g = green(c); val b = blue(c)
        return r >= 175 && g in 85..205 && b < 95 && r > g * 1.15
    }

    private fun isReturnOrange(c: Int): Boolean = isSelectionOrange(c)
    private fun isSendBlue(c: Int): Boolean {
        val r = red(c); val g = green(c); val b = blue(c)
        return b >= 135 && g >= 90 && b > r * 1.18 && g > r * 1.05
    }

    private fun isTroopGreen(c: Int): Boolean {
        val r = red(c); val g = green(c); val b = blue(c)
        return g >= 115 && g > r * 1.35 && g > b * 1.12
    }

    private fun scaled(value: Double, threshold: Double, strong: Double): Float =
        ((value - threshold) / (strong - threshold)).coerceIn(0.0, 1.0).toFloat()

    private fun red(c: Int) = c ushr 16 and 0xff
    private fun green(c: Int) = c ushr 8 and 0xff
    private fun blue(c: Int) = c and 0xff
    private fun luminance(c: Int) = (red(c) * 299 + green(c) * 587 + blue(c) * 114) / 1000
    private fun saturation(c: Int): Double {
        val hi = maxOf(red(c), green(c), blue(c))
        val lo = minOf(red(c), green(c), blue(c))
        return if (hi == 0) 0.0 else (hi - lo).toDouble() / hi
    }
}
