package radar.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class RallyDetector(
    templates: DetectorTemplates,
    private val profile: CalibrationProfile = CalibrationProfile(),
) {
    private val runtimeTemplates = templates.runtimeOrNull()
        ?: RuntimeTemplateCompiler.compile(templates.references, profile)
    private val digits = DigitRecognizer.fromTemplates(runtimeTemplates.digitTemplates)

    fun analyze(image: ArgbImage, frameId: Long = 0, monotonicMs: Long = 0): FrameAnalysis {
        if (!profile.isCompatible(image.width, image.height)) {
            return FrameAnalysis(
                frameId, monotonicMs, ScreenState.UNKNOWN, 0f,
                diagnostics = mapOf(
                    "aspectRatio" to image.width.toDouble() / image.height,
                    "geometryCompatible" to 0.0,
                ),
            )
        }
        val eventCanvas = colorRatio(image, profile.eventCanvas, predicate = ::isEventCanvas)
        val eventHeader = colorRatio(image, profile.eventHeader, predicate = ::isDarkBlue)
        val eventTabsDark = colorRatio(
            image, NormalizedRect(0.02, 0.105, 0.65, 0.155), predicate = ::isDarkBlue,
        )
        val eventSelectedTabLight = colorRatio(
            image, NormalizedRect(0.68, 0.105, 0.98, 0.155), predicate = ::isEventCanvas,
        )
        val sendBlue = colorRatio(image, profile.sendButtonBand, predicate = ::isSendBlue)
        val marchPanelLight = colorRatio(
            image, NormalizedRect(0.14, 0.47, 0.86, 0.82), predicate = { luminance(it) in 145..245 },
        )
        val worldGreen = colorRatio(image, NormalizedRect(0.12, 0.38, 0.88, 0.68), predicate = ::isWorldGreen)
        val worldUiInk = colorRatio(
            image, NormalizedRect(0.01, 0.04, 0.42, 0.34), predicate = { luminance(it) < 105 },
        )
        val screen = when {
            sendBlue >= 0.16 && marchPanelLight >= 0.42 -> ScreenState.MARCH_SCREEN
            // A populated list contains large saturated artwork/avatar regions, so its
            // neutral canvas ratio is naturally lower than the empty-state reference.
            // The dark-blue header remains the strong discriminator from helper UI.
            eventCanvas >= 0.52 && eventHeader >= 0.25 &&
                eventTabsDark >= 0.35 && eventSelectedTabLight >= 0.35 -> ScreenState.EVENT_LIST
            worldGreen >= 0.42 && worldUiInk >= 0.10 -> ScreenState.WORLD_MAP
            else -> ScreenState.UNKNOWN
        }
        val confidence = when (screen) {
            ScreenState.MARCH_SCREEN -> minOf(scaled(sendBlue, 0.16, 0.48), scaled(marchPanelLight, 0.42, 0.75))
            ScreenState.EVENT_LIST -> minOf(scaled(eventCanvas, 0.39, 0.64), scaled(eventHeader, 0.25, 0.72))
            ScreenState.WORLD_MAP -> minOf(scaled(worldGreen, 0.42, 0.74), scaled(worldUiInk, 0.10, 0.30))
            ScreenState.UNKNOWN -> 0f
        }
        val diagnostics = linkedMapOf(
            "eventCanvasRatio" to eventCanvas,
            "eventHeaderRatio" to eventHeader,
            "eventTabsDarkRatio" to eventTabsDark,
            "eventSelectedTabLightRatio" to eventSelectedTabLight,
            "sendBlueRatio" to sendBlue,
            "marchPanelLightRatio" to marchPanelLight,
            "worldGreenRatio" to worldGreen,
            "worldUiInkRatio" to worldUiInk,
        )
        return when (screen) {
            ScreenState.EVENT_LIST -> FrameAnalysis(
                frameId = frameId,
                observedAtMonotonicMs = monotonicMs,
                screen = screen,
                screenConfidence = confidence,
                rallies = detectCards(image).mapIndexed { index, card ->
                    analyzeCard(image, card, index, monotonicMs, diagnostics)
                }.filter(::hasStructuralCardEvidence),
                refreshButton = detectRefreshButton(image),
                diagnostics = diagnostics,
            )
            ScreenState.MARCH_SCREEN -> FrameAnalysis(
                frameId = frameId,
                observedAtMonotonicMs = monotonicMs,
                screen = screen,
                screenConfidence = confidence,
                travelTime = if (runtimeTemplates.labelledTravelTimes >= 2) {
                    digits.readClock(image, profile.travelTimerDigits)
                } else Recognition.unknown("travel timer unverified: fewer than two labelled times"),
                sendButtonFound = sendBlue >= 0.16,
                diagnostics = diagnostics + ("digitLibrarySize" to digits.supportedDigits.size.toDouble()),
            )
            ScreenState.WORLD_MAP -> FrameAnalysis(
                frameId = frameId,
                observedAtMonotonicMs = monotonicMs,
                screen = screen,
                screenConfidence = confidence,
                squads = detectSquadSlots(image),
                diagnostics = diagnostics,
            )
            ScreenState.UNKNOWN -> FrameAnalysis(frameId, monotonicMs, screen, confidence, diagnostics = diagnostics)
        }
    }

    private fun hasStructuralCardEvidence(candidate: RallyCandidate): Boolean =
        candidate.bossType == BossType.TARGET ||
            candidate.level != null ||
            (candidate.participantCount != null && candidate.capacity != null) ||
            candidate.confidences.plus >= profile.classifierThresholds.absoluteConfidence

    private fun detectCards(image: ArgbImage): List<NormalizedRect> {
        val height = profile.firstCard.height
        val lattice = mutableListOf<ScoredCardCandidate>()
        var latticeTop = profile.firstCard.top
        while (latticeTop + height <= profile.cardScan.bottom + 0.001) {
            val card = NormalizedRect(profile.firstCard.left, latticeTop, profile.firstCard.right, latticeTop + height)
            val artworkBlue = colorRatio(image, card.local(profile.cardArtworkLocal), predicate = ::isArtworkBlue)
            val contentInk = colorRatio(
                image, card.local(NormalizedRect(0.38, 0.10, 0.96, 0.52)),
                predicate = { luminance(it) < 105 },
            )
            if (artworkBlue >= 0.16 && contentInk >= 0.09) {
                lattice += ScoredCardCandidate(card, artworkBlue * 0.65 + contentInk * 0.35, CardCandidateSource.LATTICE)
            }
            latticeTop += height * 0.94
        }
        if (lattice.none { it.bounds.intersectionOverUnion(profile.firstCard) >= 0.90 }) {
            val referenceArtwork = colorRatio(
                image,
                profile.firstCard.local(profile.cardArtworkLocal),
                predicate = ::isArtworkBlue,
            )
            if (referenceArtwork >= 0.12) {
                lattice += ScoredCardCandidate(profile.firstCard, referenceArtwork, CardCandidateSource.LATTICE)
            }
        }

        // Always scan as well: a visible lattice card must not hide a shifted or partially-scrolled card.
        val scanStep = height / 18.0
        val scan = mutableListOf<ScoredCardCandidate>()
        var top = profile.cardScan.top
        val lastTop = minOf(1.0 - height, profile.cardScan.bottom - height * MIN_VISIBLE_CARD_FRACTION)
        while (top <= lastTop + 0.001) {
            val card = NormalizedRect(profile.firstCard.left, top, profile.firstCard.right, top + height)
            val artworkBlue = colorRatio(image, card.local(profile.cardArtworkLocal), predicate = ::isArtworkBlue)
            val contentInk = colorRatio(
                image, card.local(NormalizedRect(0.38, 0.10, 0.96, 0.52)),
                predicate = { luminance(it) < 105 },
            )
            if (artworkBlue >= 0.16 && contentInk >= 0.09) {
                scan += ScoredCardCandidate(card, artworkBlue * 0.65 + contentInk * 0.35, CardCandidateSource.FREE_SCAN)
            }
            top += scanStep
        }
        return mergeCardCandidates(lattice + scan, height)
    }

    private fun analyzeCard(
        image: ArgbImage,
        card: NormalizedRect,
        index: Int,
        monotonicMs: Long,
        diagnostics: MutableMap<String, Double>,
    ): RallyCandidate {
        val boss = classifyBoss(image, card)
        val level = digits.readLevel(image, card.local(profile.levelDigitsLocal))
        val current = digits.readInteger(image, card.local(profile.participantCurrentLocal), maxDigits = 2)
        val capacity = digits.readInteger(image, card.local(profile.participantCapacityLocal), maxDigits = 2)
        val countdown = digits.readClock(image, card.local(profile.countdownDigitsLocal), lightOnDark = true)
        val pluses = brightGreenComponents(image, card.local(profile.plusBandLocal)).filter { component ->
            val aspect = component.bounds.width / component.bounds.height
            component.pixelCount >= max(18, image.width * image.height / 130_000) &&
                aspect in 1.3..3.2 && component.centroid.x > card.left + card.width * 0.38
        }
        val plusConfidence = pluses.maxOfOrNull { it.confidence } ?: 0f
        val isFull = if (current.accepted && capacity.accepted) current.value!! >= capacity.value!! else null
        val joinedState = when {
            boss.value == BossType.NON_TARGET -> JoinedState.NOT_TARGET
            boss.value != BossType.TARGET -> JoinedState.UNKNOWN
            isFull == true -> JoinedState.FULL
            pluses.isNotEmpty() -> JoinedState.JOINABLE
            else -> JoinedState.UNKNOWN
        }
        val cardConfidence = minOf(
            colorRatio(image, card.local(profile.cardArtworkLocal), predicate = ::isArtworkBlue).toFloat() * 3f,
            1f,
        )
        diagnostics["card${index}.bossWinner"] = boss.confidence.toDouble()
        diagnostics["card${index}.bossRunnerUp"] = boss.runnerUpConfidence.toDouble()
        diagnostics["card${index}.plusCount"] = pluses.size.toDouble()
        diagnostics["card${index}.levelConfidence"] = level.confidence.toDouble()
        diagnostics["card${index}.participantConfidence"] = minOf(current.confidence, capacity.confidence).toDouble()
        diagnostics["card${index}.timerConfidence"] = countdown.confidence.toDouble()
        diagnostics["card${index}.timerRunnerUp"] = countdown.runnerUpConfidence.toDouble()
        return RallyCandidate(
            ephemeralId = null,
            bossType = boss.value ?: BossType.UNKNOWN,
            level = level.value,
            participantCount = current.value,
            capacity = capacity.value,
            remainingSeconds = countdown.value,
            firstSeenMonotonicMs = monotonicMs,
            cardBounds = card,
            joinPlusBounds = pluses.map { it.bounds },
            joinable = joinedState == JoinedState.JOINABLE,
            full = isFull,
            joinedState = joinedState,
            confidences = RallyConfidences(
                card = cardConfidence,
                boss = boss.confidence,
                level = level.confidence,
                participant = minOf(current.confidence, capacity.confidence),
                plus = plusConfidence,
                timer = countdown.confidence,
            ),
            identityFingerprint = RallyIdentityFingerprint(
                targetTitleHash = darkAverageHash(image, card.local(profile.identityTitleLocal)),
                coordinatesHash = darkAverageHash(image, card.local(profile.identityCoordinatesLocal)),
            ),
        )
    }

    private fun darkAverageHash(image: ArgbImage, rect: NormalizedRect): Long {
        val box = pixelBox(image, rect)
        val values = IntArray(64)
        var sum = 0L
        var index = 0
        for (gy in 0 until 4) for (gx in 0 until 16) {
            val x = box.left + ((gx + 0.5) * box.width / 16).toInt()
            val y = box.top + ((gy + 0.5) * box.height / 4).toInt()
            val value = luminance(image.argb(x.coerceAtMost(image.width - 1), y.coerceAtMost(image.height - 1)))
            values[index++] = value
            sum += value
        }
        val mean = sum.toDouble() / values.size
        var hash = 0L
        values.forEachIndexed { bit, value ->
            if (value < mean) hash = hash or (1L shl bit)
        }
        return hash
    }

    private fun classifyBoss(image: ArgbImage, card: NormalizedRect): Recognition<BossType> {
        val candidates = runtimeTemplates.bossTemplates
        if (candidates.isEmpty()) return Recognition.unknown("boss library is empty")
        val feature = patchFeature(image, card.local(profile.bossArtworkLocal))
        val ranked = candidates.groupBy { it.bossType }.mapValues { (_, samples) ->
            samples.minOf { sample ->
                featureDistance(feature, sample.feature)
            }
        }.map { (label, distance) -> label to (1.0 - distance / 0.42).coerceIn(0.0, 1.0).toFloat() }
            .sortedByDescending { it.second }
        val winner = ranked.first()
        val runner = ranked.getOrNull(1)?.second ?: 0f
        val thresholds = profile.classifierThresholds
        // This is intentionally a one-class safety decision: only a positively confirmed
        // target may become TARGET. Every other valid event card is NON_TARGET, which keeps
        // unfamiliar creature variants fail-closed instead of making them actionable.
        val targetAccepted = winner.first == BossType.TARGET &&
            winner.second >= thresholds.absoluteConfidence &&
            winner.second - runner >= thresholds.winnerMargin
        if (!targetAccepted) {
            val targetConfidence = ranked.firstOrNull { it.first == BossType.TARGET }?.second ?: 0f
            return Recognition(
                value = BossType.NON_TARGET,
                confidence = (1f - targetConfidence).coerceIn(0f, 1f),
                runnerUpConfidence = targetConfidence,
                accepted = true,
                rejectionReason = "target signature not confirmed",
            )
        }
        return Recognition(
            value = BossType.TARGET,
            confidence = winner.second,
            runnerUpConfidence = runner,
            accepted = true,
        )
    }

    private fun brightGreenComponents(image: ArgbImage, rect: NormalizedRect): List<VisualComponent> {
        val box = pixelBox(image, rect)
        val step = max(1, image.width / 640)
        val width = max(1, (box.width + step - 1) / step)
        val height = max(1, (box.height + step - 1) / step)
        val mask = BooleanArray(width * height)
        for (gy in 0 until height) for (gx in 0 until width) {
            val x = min(box.right - 1, box.left + gx * step)
            val y = min(box.bottom - 1, box.top + gy * step)
            mask[gy * width + gx] = isPlusGreen(image.argb(x, y))
        }
        val seen = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        val result = mutableListOf<VisualComponent>()
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            var head = 0; var tail = 0; var count = 0
            var minX = width; var minY = height; var maxX = 0; var maxY = 0
            var sumX = 0L; var sumY = 0L
            queue[tail++] = start; seen[start] = true
            while (head < tail) {
                val current = queue[head++]; val x = current % width; val y = current / width
                count++; minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
                sumX += x; sumY += y
                if (x > 0) tail = enqueue(current - 1, mask, seen, queue, tail)
                if (x + 1 < width) tail = enqueue(current + 1, mask, seen, queue, tail)
                if (y > 0) tail = enqueue(current - width, mask, seen, queue, tail)
                if (y + 1 < height) tail = enqueue(current + width, mask, seen, queue, tail)
            }
            if (count < 5) continue
            val bounds = NormalizedRect(
                (box.left + minX * step).toDouble() / image.width,
                (box.top + minY * step).toDouble() / image.height,
                min(image.width, box.left + (maxX + 1) * step).toDouble() / image.width,
                min(image.height, box.top + (maxY + 1) * step).toDouble() / image.height,
            )
            val centroid = NormalizedPoint(
                (box.left + sumX.toDouble() / count * step) / image.width,
                (box.top + sumY.toDouble() / count * step) / image.height,
            )
            val aspect = bounds.width / bounds.height
            val expectedNormalizedAspect = profile.expectedAspectRatio.let { 1.0 / it }
            val shape = (1.0 - abs(aspect - expectedNormalizedAspect) / 1.5).coerceIn(0.0, 1.0)
            val size = (count / 120.0).coerceIn(0.0, 1.0)
            result += VisualComponent(count, bounds, centroid, (shape * 0.55 + size * 0.45).toFloat())
        }
        return result
    }

    private fun detectRefreshButton(image: ArgbImage): Recognition<NormalizedRect> {
        val candidates = refreshOrangeComponents(image, profile.refreshButtonBand).filter { component ->
            val aspect = component.bounds.width / component.bounds.height
            component.pixelCount >= max(240, image.width * image.height / 18_000) &&
                component.bounds.width >= 0.20 &&
                aspect in 3.0..14.0
        }
        val winner = candidates.maxByOrNull { it.pixelCount }
            ?: return Recognition.unknown("refresh button not visible")
        val confidence = (winner.pixelCount.toDouble() / (image.width * image.height / 7_000.0))
            .coerceIn(0.0, 1.0).toFloat()
        return Recognition(
            value = winner.bounds,
            confidence = confidence,
            accepted = confidence >= 0.70f,
            rejectionReason = "refresh confidence below threshold".takeIf { confidence < 0.70f },
        )
    }

    private fun refreshOrangeComponents(image: ArgbImage, rect: NormalizedRect): List<VisualComponent> {
        val box = pixelBox(image, rect)
        val step = max(1, image.width / 640)
        val width = max(1, (box.width + step - 1) / step)
        val height = max(1, (box.height + step - 1) / step)
        val mask = BooleanArray(width * height)
        for (gy in 0 until height) for (gx in 0 until width) {
            val x = min(box.right - 1, box.left + gx * step)
            val y = min(box.bottom - 1, box.top + gy * step)
            val color = image.argb(x, y)
            val r = red(color); val g = green(color); val b = blue(color)
            mask[gy * width + gx] = r >= 170 && g in 75..215 && b <= 135 && r >= g + 25
        }
        val seen = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        val result = mutableListOf<VisualComponent>()
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            var head = 0; var tail = 0; var count = 0
            var minX = width; var minY = height; var maxX = 0; var maxY = 0
            var sumX = 0L; var sumY = 0L
            queue[tail++] = start; seen[start] = true
            while (head < tail) {
                val current = queue[head++]; val x = current % width; val y = current / width
                count++; minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
                sumX += x; sumY += y
                if (x > 0) tail = enqueue(current - 1, mask, seen, queue, tail)
                if (x + 1 < width) tail = enqueue(current + 1, mask, seen, queue, tail)
                if (y > 0) tail = enqueue(current - width, mask, seen, queue, tail)
                if (y + 1 < height) tail = enqueue(current + width, mask, seen, queue, tail)
            }
            if (count < 20) continue
            val bounds = NormalizedRect(
                (box.left + minX * step).toDouble() / image.width,
                (box.top + minY * step).toDouble() / image.height,
                min(image.width, box.left + (maxX + 1) * step).toDouble() / image.width,
                min(image.height, box.top + (maxY + 1) * step).toDouble() / image.height,
            )
            result += VisualComponent(
                pixelCount = count,
                bounds = bounds,
                centroid = bounds.center,
                confidence = 1f,
            )
        }
        return result
    }

    private fun detectSquadSlots(image: ArgbImage): List<SquadInfo> {
        val panel = profile.worldSquadPanel
        return (0 until 3).mapNotNull { slot ->
            val row = NormalizedRect(
                panel.left, panel.top + panel.height * slot / 3,
                panel.right, panel.top + panel.height * (slot + 1) / 3,
            )
            val ink = colorRatio(image, row, predicate = { luminance(it) < 90 })
            if (ink < 0.07) null else SquadInfo(
                slot, SquadState.UNKNOWN, null, null, scaled(ink, 0.07, 0.25), row,
            )
        }
    }

    private fun enqueue(index: Int, mask: BooleanArray, seen: BooleanArray, queue: IntArray, tail: Int): Int {
        if (!mask[index] || seen[index]) return tail
        seen[index] = true; queue[tail] = index
        return tail + 1
    }

    private fun scaled(value: Double, threshold: Double, strong: Double): Float =
        ((value - threshold) / (strong - threshold)).coerceIn(0.0, 1.0).toFloat()

    private fun isDarkBlue(argb: Int): Boolean {
        val r = red(argb); val g = green(argb); val b = blue(argb)
        return b > r * 1.12 && b > g * 1.02 && b in 45..135 && r < 95
    }

    private fun isArtworkBlue(argb: Int): Boolean {
        val r = red(argb); val g = green(argb); val b = blue(argb)
        return b >= 125 && g >= 115 && b > r * 1.05 && g > r * 1.04
    }
}

enum class CardCandidateSource { LATTICE, FREE_SCAN }

data class ScoredCardCandidate(
    val bounds: NormalizedRect,
    val score: Double,
    val source: CardCandidateSource,
)

/** Score-first NMS shared by the calibrated lattice and the scroll-tolerant free scan. */
fun mergeCardCandidates(
    candidates: List<ScoredCardCandidate>,
    expectedHeight: Double,
): List<NormalizedRect> {
    val kept = mutableListOf<ScoredCardCandidate>()
    candidates.sortedWith(
        compareByDescending<ScoredCardCandidate> {
            it.score + if (it.source == CardCandidateSource.LATTICE) 0.10 else 0.0
        }
            .thenByDescending { it.source == CardCandidateSource.LATTICE }
            .thenBy { it.bounds.top },
    ).forEach { candidate ->
        val duplicate = kept.any { selected ->
            candidate.bounds.intersectionOverUnion(selected.bounds) >= 0.48 ||
                abs(candidate.bounds.center.y - selected.bounds.center.y) < expectedHeight * 0.38
        }
        if (!duplicate) kept += candidate
    }
    return kept.map { it.bounds }.sortedBy { it.top }
}

private const val MIN_VISIBLE_CARD_FRACTION = 0.55
