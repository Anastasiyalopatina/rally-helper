package radar.vision.cli

import radar.vision.ArgbImage
import radar.vision.BossType
import radar.vision.DetectorTemplates
import radar.vision.FrameAnalysis
import radar.vision.JoinedState
import radar.vision.MarchSquadState
import radar.vision.RallyDetector
import radar.vision.ReferenceTemplate
import radar.vision.ScreenState
import radar.vision.RuntimeTemplateCodec
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.system.exitProcess

internal class BufferedArgbImage(private val source: BufferedImage) : ArgbImage {
    override val width: Int get() = source.width
    override val height: Int get() = source.height
    override fun argb(x: Int, y: Int): Int = source.getRGB(x, y)
}

private class BrightnessImage(private val source: ArgbImage, private val factor: Double) : ArgbImage {
    override val width: Int get() = source.width
    override val height: Int get() = source.height
    override fun argb(x: Int, y: Int): Int {
        val pixel = source.argb(x, y)
        fun channel(shift: Int) = (((pixel ushr shift) and 0xff) * factor).toInt().coerceIn(0, 255)
        return (pixel and -0x1000000) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}

private class ContrastImage(private val source: ArgbImage, private val factor: Double) : ArgbImage {
    override val width: Int get() = source.width
    override val height: Int get() = source.height
    override fun argb(x: Int, y: Int): Int {
        val pixel = source.argb(x, y)
        fun channel(shift: Int) = ((((pixel ushr shift) and 0xff) - 128) * factor + 128).toInt().coerceIn(0, 255)
        return (pixel and -0x1000000) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}

private class GeometricImage(
    private val source: ArgbImage,
    private val scale: Double = 1.0,
    private val dx: Int = 0,
    private val dy: Int = 0,
) : ArgbImage {
    override val width: Int get() = source.width
    override val height: Int get() = source.height
    override fun argb(x: Int, y: Int): Int {
        val sx = (((x - width / 2.0) / scale) + width / 2.0 - dx).toInt().coerceIn(0, width - 1)
        val sy = (((y - height / 2.0) / scale) + height / 2.0 - dy).toInt().coerceIn(0, height - 1)
        return source.argb(sx, sy)
    }
}

private class BlurImage(private val source: ArgbImage) : ArgbImage {
    override val width: Int get() = source.width
    override val height: Int get() = source.height
    override fun argb(x: Int, y: Int): Int {
        var r = 0; var g = 0; var b = 0; var count = 0
        for (oy in -1..1) for (ox in -1..1) {
            val pixel = source.argb((x + ox).coerceIn(0, width - 1), (y + oy).coerceIn(0, height - 1))
            r += pixel ushr 16 and 0xff; g += pixel ushr 8 and 0xff; b += pixel and 0xff; count++
        }
        return -0x1000000 or (r / count shl 16) or (g / count shl 8) or (b / count)
    }
}

private class OccludedImage(private val source: ArgbImage) : ArgbImage {
    override val width: Int get() = source.width
    override val height: Int get() = source.height
    override fun argb(x: Int, y: Int): Int =
        if (x in width * 9 / 10 until width && y in height * 2 / 5 until height / 2) 0xff202020.toInt()
        else source.argb(x, y)
}

private data class Expected(
    val screen: ScreenState,
    val boss: BossType? = null,
    val level: Int? = null,
    val joinable: Boolean? = null,
    val full: Boolean? = null,
    val joinedState: JoinedState? = null,
    val countdownSeconds: Int? = null,
    val travelTimeSeconds: Int? = null,
    val travelTimeUnknown: Boolean? = null,
    val sendButtonFound: Boolean? = null,
    val marchStates: List<MarchSquadState>? = null,
    val selectedSquad: Int? = null,
    val troopsPresent: Boolean? = null,
)

private data class Case(val file: String, val expected: Expected)

fun main(args: Array<String>) {
    val mode = args.getOrNull(0) ?: "calibration"
    val testdata = File(args.getOrNull(1) ?: "vision-core/testdata")
    val calibrationFrames = File(testdata, "calibration/frames")
    fun loadFrom(directory: File, name: String): ArgbImage = BufferedArgbImage(
        requireNotNull(ImageIO.read(File(directory, name))) { "Cannot decode $name" },
    )
    val requiredCalibration = listOf(
        "01_target_l10_joinable.jpg", "04_target_l10_full.jpg", "05_target_l5_joined_2of5.jpg",
        "06_target_l5_joined_4of5.jpg", "07_target_l5_full.jpg", "09_non_target_l100_joinable.jpg",
        "02_march_screen.jpg", "03_world_1of3.jpg", "08_world_2of3.jpg",
    )
    val runtimeAsset = File(testdata.parentFile.parentFile, "app/src/main/assets/detector_templates.bin")
    val detector = if (mode != "runtime-asset" && requiredCalibration.all { File(calibrationFrames, it).isFile }) {
        RallyDetector(DetectorTemplates(referenceTemplates { loadFrom(calibrationFrames, it) }))
    } else {
        require(runtimeAsset.isFile) { "Neither private calibration frames nor compact runtime templates exist" }
        RallyDetector(DetectorTemplates(runtimeAsset.inputStream().use(RuntimeTemplateCodec::read)))
    }
    when (mode) {
        "calibration" -> verifyDirectory(detector, File(testdata, "calibration"), ::parseCase)
        "runtime-asset" -> verifyDirectory(detector, File(testdata, "calibration"), ::parseCase)
        "holdout" -> verifyDirectory(detector, File(testdata, "holdout"), ::parseCase, emptyIsNotRun = true)
        "sequences" -> verifySequences(detector, File(testdata, "sequences"))
        "mutations" -> verifyMutations(detector, calibrationFrames)
        else -> error("Unknown verification mode: $mode")
    }
}

internal fun referenceTemplates(load: (String) -> ArgbImage) = listOf(
    ReferenceTemplate("target-l10-1of5", load("01_target_l10_joinable.jpg"), BossType.TARGET, 10, 1, 5, 53),
    ReferenceTemplate("target-l10-full", load("04_target_l10_full.jpg"), BossType.TARGET, 10, 5, 5, 38),
    ReferenceTemplate("target-l5-2of5", load("05_target_l5_joined_2of5.jpg"), BossType.TARGET, 5, 2, 5, 54),
    ReferenceTemplate("target-l5-4of5", load("06_target_l5_joined_4of5.jpg"), BossType.TARGET, 5, 4, 5, 34),
    ReferenceTemplate("target-l5-full", load("07_target_l5_full.jpg"), BossType.TARGET, 5, 5, 5, 31),
    ReferenceTemplate("non-target-l100", load("09_non_target_l100_joinable.jpg"), BossType.NON_TARGET, 100, 1, 5, 56),
    ReferenceTemplate("march-7s", load("02_march_screen.jpg"), travelTimeSeconds = 7),
    ReferenceTemplate("world-1-of-3", load("03_world_1of3.jpg")),
    ReferenceTemplate("world-2-of-3", load("08_world_2of3.jpg")),
)

private fun verifyDirectory(
    detector: RallyDetector,
    root: File,
    parser: (File) -> Case,
    emptyIsNotRun: Boolean = false,
) {
    val cases = File(root, "expected").listFiles { file -> file.extension == "json" }.orEmpty().sortedBy { it.name }
    val frames = File(root, "frames")
    if (cases.isNotEmpty() && cases.any { !File(frames, it.nameWithoutExtension + ".jpg").isFile }) {
        println("NOT_RUN: calibration/holdout images are private and unavailable in this checkout.")
        return
    }
    if (cases.isEmpty() && emptyIsNotRun) {
        println("NOT_RUN: no independent holdout frames. Calibration results must not be reported as validation.")
        return
    }
    require(cases.isNotEmpty()) { "No expected/*.json cases under ${root.absolutePath}" }
    var failures = 0
    cases.map(parser).forEachIndexed { index, case ->
        val image = BufferedArgbImage(requireNotNull(ImageIO.read(File(frames, case.file))))
        val analysis = detector.analyze(image, index.toLong(), index * 100L)
        val errors = compare(case.expected, analysis)
        println("${if (errors.isEmpty()) "PASS" else "FAIL"}  ${case.file}  ${summary(analysis)}")
        errors.forEach { println("      $it") }
        failures += errors.size
    }
    println("${cases.size - failures.coerceAtMost(cases.size)}/${cases.size} frames without assertion failures; assertions=$failures")
    if (failures != 0) exitProcess(1)
}

private fun verifySequences(detector: RallyDetector, root: File) {
    val frames = root.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("jpg", "png") }.toList()
    if (frames.isEmpty()) {
        println("NOT_RUN: no recorded sequences have been supplied.")
        return
    }
    frames.forEachIndexed { index, file ->
        val image = BufferedArgbImage(requireNotNull(ImageIO.read(file)))
        println("${file.name}: ${summary(detector.analyze(image, index.toLong(), index * 100L))}")
    }
}

private fun verifyMutations(detector: RallyDetector, root: File) {
    val sourceNames = listOf("01_target_l10_joinable.jpg", "02_march_screen.jpg", "09_non_target_l100_joinable.jpg")
    if (sourceNames.any { !File(root, it).isFile }) {
        println("NOT_RUN: private calibration images are unavailable; synthetic mutations are not validation.")
        return
    }
    var failures = 0
    for (name in sourceNames) {
        val source = BufferedArgbImage(requireNotNull(ImageIO.read(File(root, name))))
        val baseline = detector.analyze(source).screen
        val variants = listOf(
            "brightness-0.90" to BrightnessImage(source, 0.90),
            "brightness-1.10" to BrightnessImage(source, 1.10),
            "contrast-0.92" to ContrastImage(source, 0.92),
            "contrast-1.08" to ContrastImage(source, 1.08),
            "scale-1.01" to GeometricImage(source, scale = 1.01),
            "translation" to GeometricImage(source, dx = 3, dy = 5),
            "mild-blur" to BlurImage(source),
            "harmless-occlusion" to OccludedImage(source),
            "status-inset" to GeometricImage(source, dy = 4),
            "jpeg-82" to jpegRoundTrip(source, 0.82f),
        )
        for ((label, variant) in variants) {
            val analysis = detector.analyze(variant)
            val pass = analysis.screen == baseline
            println("${if (pass) "PASS" else "FAIL"} $name mutation=$label screen=${analysis.screen}")
            if (!pass) failures++
        }
    }
    println("mutation assertion failures=$failures (synthetic checks are not holdout validation)")
    if (failures != 0) exitProcess(1)
}

private fun jpegRoundTrip(source: ArgbImage, quality: Float): ArgbImage {
    val image = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until source.height) for (x in 0 until source.width) image.setRGB(x, y, source.argb(x, y))
    val bytes = ByteArrayOutputStream()
    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    val output = ImageIO.createImageOutputStream(bytes)
    writer.output = output
    val params = writer.defaultWriteParam.apply {
        compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
        compressionQuality = quality
    }
    writer.write(null, javax.imageio.IIOImage(image, null, null), params)
    writer.dispose(); output.close()
    return BufferedArgbImage(requireNotNull(ImageIO.read(ByteArrayInputStream(bytes.toByteArray()))))
}

private fun parseCase(file: File): Case {
    val json = file.readText()
    fun string(key: String): String? = Regex("\\\"$key\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(json)?.groupValues?.get(1)
    fun int(key: String): Int? = Regex("\\\"$key\\\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toInt()
    fun bool(key: String): Boolean? = Regex("\\\"$key\\\"\\s*:\\s*(true|false)").find(json)?.groupValues?.get(1)?.toBooleanStrict()
    return Case(
        file.nameWithoutExtension + ".jpg",
        Expected(
            screen = ScreenState.valueOf(requireNotNull(string("screen"))),
            boss = string("boss")?.let(BossType::valueOf),
            level = int("level"),
            joinable = bool("joinable"),
            full = bool("full"),
            joinedState = string("joinedState")?.let(JoinedState::valueOf),
            countdownSeconds = int("countdownSeconds"),
            travelTimeSeconds = int("travelTimeSeconds"),
            travelTimeUnknown = bool("travelTimeUnknown"),
            sendButtonFound = bool("sendButtonFound"),
            marchStates = string("marchStates")?.split(',')?.map(MarchSquadState::valueOf),
            selectedSquad = int("selectedSquad"),
            troopsPresent = bool("troopsPresent"),
        ),
    )
}

private fun compare(expected: Expected, actual: FrameAnalysis): List<String> = buildList {
    val rally = actual.rallies.singleOrNull()
    fun check(label: String, expectedValue: Any?, actualValue: Any?) {
        if (expectedValue != null && expectedValue != actualValue) add("$label expected=$expectedValue actual=$actualValue")
    }
    check("screen", expected.screen, actual.screen)
    check("boss", expected.boss, rally?.bossType)
    check("level", expected.level, rally?.level)
    check("joinable", expected.joinable, rally?.joinable)
    check("full", expected.full, rally?.full)
    check("joinedState", expected.joinedState, rally?.joinedState)
    check("countdownSeconds", expected.countdownSeconds, rally?.remainingSeconds)
    check("travelTimeSeconds", expected.travelTimeSeconds, actual.travelTime.value)
    check("travelTimeUnknown", expected.travelTimeUnknown, !actual.travelTime.accepted)
    check("sendButtonFound", expected.sendButtonFound, actual.sendButtonFound)
    check("marchStates", expected.marchStates, actual.marchSquads.map { it.state })
    check("selectedSquad", expected.selectedSquad, actual.marchSquads.singleOrNull { it.selected }?.slotIndex)
    check("troopsPresent", expected.troopsPresent, actual.troopSanity.nonEmpty)
}

private fun summary(a: FrameAnalysis): String = when (a.screen) {
    ScreenState.EVENT_LIST -> a.rallies.joinToString(prefix = "rallies=[", postfix = "]") {
        "boss=${it.bossType} level=${it.level} count=${it.participantCount}/${it.capacity} timer=${it.remainingSeconds} plus=${it.joinPlusBounds.size} state=${it.joinedState} conf=${it.confidences} diag=${a.diagnostics.filterKeys { key -> key.startsWith("card") }}"
    }
    ScreenState.MARCH_SCREEN -> "travel=${a.travelTime.value ?: "UNKNOWN"} conf=${a.travelTime.confidence} " +
        "send=${a.sendButtonFound} squads=${a.marchSquads.map { "${it.slotIndex}:${it.state}:${it.selected}" }} " +
        "troops=${a.troopSanity.nonEmpty}"
    ScreenState.WORLD_MAP -> "squadRows=${a.squads.size} states=${a.squads.map { it.state }}"
    ScreenState.UNKNOWN -> "unknown diagnostics=${a.diagnostics}"
}
