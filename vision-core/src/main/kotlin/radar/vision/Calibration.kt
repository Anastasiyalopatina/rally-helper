package radar.vision

data class ClassifierThresholds(
    val absoluteConfidence: Float = 0.62f,
    val winnerMargin: Float = 0.06f,
)

data class CalibrationProfile(
    val id: String = "reference-1280x2800-v2",
    val captureWidth: Int = 1280,
    val captureHeight: Int = 2800,
    val expectedAspectRatio: Double = 1280.0 / 2800.0,
    val aspectTolerance: Double = 0.035,
    val uiScaleSignature: String = "portrait-reference-v2",
    val captureViewport: NormalizedRect = NormalizedRect(0.0, 0.0, 1.0, 1.0),
    val eventCanvas: NormalizedRect = NormalizedRect(0.02, 0.37, 0.98, 0.88),
    val eventHeader: NormalizedRect = NormalizedRect(0.02, 0.045, 0.98, 0.16),
    val firstCard: NormalizedRect = NormalizedRect(0.02, 0.15, 0.98, 0.375),
    val cardScan: NormalizedRect = NormalizedRect(0.02, 0.14, 0.98, 0.88),
    val cardArtworkLocal: NormalizedRect = NormalizedRect(0.01, 0.12, 0.35, 0.80),
    val bossArtworkLocal: NormalizedRect = NormalizedRect(0.02, 0.16, 0.34, 0.76),
    val levelDigitsLocal: NormalizedRect = NormalizedRect(0.075, 0.09, 0.18, 0.205),
    val participantCurrentLocal: NormalizedRect = NormalizedRect(0.84, 0.835, 0.87, 0.93),
    val participantCapacityLocal: NormalizedRect = NormalizedRect(0.887, 0.835, 0.915, 0.93),
    val countdownDigitsLocal: NormalizedRect = NormalizedRect(0.775, 0.33, 0.91, 0.372),
    val plusBandLocal: NormalizedRect = NormalizedRect(0.38, 0.41, 0.98, 0.75),
    val sendButtonBand: NormalizedRect = NormalizedRect(0.30, 0.735, 0.71, 0.805),
    val travelTimerDigits: NormalizedRect = NormalizedRect(0.43, 0.704, 0.62, 0.741),
    val worldSquadPanel: NormalizedRect = NormalizedRect(0.01, 0.145, 0.39, 0.34),
    val classifierThresholds: ClassifierThresholds = ClassifierThresholds(),
) {
    fun isCompatible(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0 || width >= height || width < 720 || height < 1280) return false
        val aspect = width.toDouble() / height
        return kotlin.math.abs(aspect - expectedAspectRatio) <= aspectTolerance
    }

    fun incompatibilityReason(width: Int, height: Int): String? = when {
        width <= 0 || height <= 0 -> "invalid capture dimensions"
        width >= height -> "portrait orientation required"
        width < 720 || height < 1280 -> "captured viewport is too small"
        kotlin.math.abs(width.toDouble() / height - expectedAspectRatio) > aspectTolerance ->
            "captured viewport aspect differs from calibration"
        else -> null
    }
}

data class ReferenceTemplate(
    val id: String,
    val image: ArgbImage,
    val bossType: BossType? = null,
    val level: Int? = null,
    val participantCount: Int? = null,
    val capacity: Int? = null,
    val rallyCountdownSeconds: Int? = null,
    val travelTimeSeconds: Int? = null,
)

data class BossFeatureTemplate(
    val id: String,
    val bossType: BossType,
    val feature: DoubleArray,
)

data class RuntimeTemplateBundle(
    val bossTemplates: List<BossFeatureTemplate>,
    val digitTemplates: List<DigitTemplate>,
    val labelledTravelTimes: Int,
)

class DetectorTemplates private constructor(
    val references: List<ReferenceTemplate>,
    private val runtime: RuntimeTemplateBundle?,
) {
    constructor(references: List<ReferenceTemplate>) : this(references.toList(), null) {
        require(references.any { it.bossType == BossType.TARGET })
        require(references.any { it.bossType == BossType.NON_TARGET })
    }

    constructor(runtime: RuntimeTemplateBundle) : this(emptyList(), runtime)

    fun runtimeOrNull(): RuntimeTemplateBundle? = runtime
}

object RuntimeTemplateCompiler {
    fun compile(
        references: List<ReferenceTemplate>,
        profile: CalibrationProfile = CalibrationProfile(),
    ): RuntimeTemplateBundle {
        require(references.any { it.bossType == BossType.TARGET })
        require(references.any { it.bossType == BossType.NON_TARGET })
        return RuntimeTemplateBundle(
            bossTemplates = references.mapNotNull { reference ->
                reference.bossType?.let { boss ->
                    BossFeatureTemplate(
                        reference.id,
                        boss,
                        patchFeature(reference.image, profile.firstCard.local(profile.bossArtworkLocal)),
                    )
                }
            },
            digitTemplates = DigitRecognizer.compileTemplates(references, profile),
            labelledTravelTimes = references.count { it.travelTimeSeconds != null },
        )
    }
}
