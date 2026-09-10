package radar.vision.cli

import org.json.JSONArray
import org.json.JSONObject
import radar.vision.AutoPolicyConfig
import radar.vision.BossType
import radar.vision.DecisionKind
import radar.vision.DetectorTemplates
import radar.vision.JoinedState
import radar.vision.RadarAlertPolicy
import radar.vision.RallyDetector
import radar.vision.RallyTracker
import radar.vision.RuntimeMode
import radar.vision.RuntimeTemplateCodec
import radar.vision.SafetyController
import radar.vision.SafetyPolicy
import radar.vision.ScreenState
import radar.vision.ShadowAutoCoordinator
import radar.vision.SquadState
import radar.vision.TargetSelector
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import kotlin.system.exitProcess

private data class ScenarioArchive(val sourceName: String, val entries: Map<String, ByteArray>)

private data class ReplayObservation(
    val screen: ScreenState,
    val rallies: List<radar.vision.RallyCandidate>,
    val squads: List<radar.vision.SquadInfo>,
    val travelTime: Int?,
    val alertSelections: Int,
    val actionSelections: Int,
    val selectorFound: Boolean,
    val shadowPhase: String,
    val refreshFound: Boolean,
)

private data class ScenarioResult(
    val scenarioId: String,
    val label: String,
    val frameCount: Int,
    val passed: Boolean,
    val skipped: Boolean,
    val assertions: List<String>,
    val uniqueRallies: Set<String>,
)

fun main(args: Array<String>) = runCaptureLabReplay(args)

internal fun runCaptureLabReplay(args: Array<String>) {
    val input = File(requireNotNull(args.getOrNull(0)) { "capture bundle is required" })
    val templateAsset = File(requireNotNull(args.getOrNull(1)) { "runtime template asset is required" })
    val reportDirectory = File(requireNotNull(args.getOrNull(2)) { "report directory is required" })
    require(input.isFile) { "Capture bundle does not exist: ${input.absolutePath}" }
    require(templateAsset.isFile) { "Runtime template asset does not exist: ${templateAsset.absolutePath}" }

    val templates = templateAsset.inputStream().use(RuntimeTemplateCodec::read)
    val scenarios = readScenarios(input)
    require(scenarios.isNotEmpty()) { "No scenario ZIP with manifest.json was found" }
    val results = scenarios.map { replay(it, DetectorTemplates(templates)) }
    writeReports(input.name, results, reportDirectory)

    val passed = results.count { it.passed }
    val skipped = results.count { it.skipped }
    val failed = results.size - passed - skipped
    println("CaptureLab replay: scenarios=${results.size} passed=$passed failed=$failed skipped=$skipped")
    println("Reports: ${File(reportDirectory, "capture-lab-replay.txt").absolutePath}")
    results.forEach { result ->
        println("${when { result.skipped -> "NOT_OBSERVED"; result.passed -> "PASS"; else -> "FAIL" }} ${result.scenarioId} ${result.label}")
        result.assertions.forEach { println("  $it") }
    }
    if (failed > 0) exitProcess(1)
}

private fun readScenarios(input: File): List<ScenarioArchive> {
    val outerEntries = ZipFile(input).use { zip ->
        zip.entries().asSequence().filterNot { it.isDirectory }.associate { entry ->
            entry.name to zip.getInputStream(entry).use { it.readBytes() }
        }
    }
    if ("manifest.json" in outerEntries) return listOf(ScenarioArchive(input.name, outerEntries))
    return outerEntries.entries
        .filter { it.key.endsWith(".zip", ignoreCase = true) }
        .sortedBy { it.key }
        .mapNotNull { (name, bytes) ->
            val entries = readZipBytes(bytes)
            ScenarioArchive(name, entries).takeIf { "manifest.json" in entries }
        }
}

private fun readZipBytes(bytes: ByteArray): Map<String, ByteArray> {
    val result = linkedMapOf<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (!entry.isDirectory) result[entry.name] = zip.readBytes()
            zip.closeEntry()
        }
    }
    return result
}

private fun replay(archive: ScenarioArchive, templates: DetectorTemplates): ScenarioResult {
    val manifest = JSONObject(requireNotNull(archive.entries["manifest.json"]) { "manifest missing" }.toString(Charsets.UTF_8))
    val expectedBytes = archive.entries["expected-scenario.json"]
        ?: return ScenarioResult(
            scenarioId = manifest.optString("scenarioId", archive.sourceName.substringBeforeLast('.')),
            label = manifest.optString("label", "UNKNOWN_UI"),
            frameCount = manifest.optInt("frameCount", 0),
            passed = false,
            skipped = true,
            assertions = listOf("NOT_OBSERVED: separate expected-scenario.json is absent"),
            uniqueRallies = emptySet(),
        )
    val expected = JSONObject(expectedBytes.toString(Charsets.UTF_8))
    if (!expected.optBoolean("confirmed", false)) {
        return ScenarioResult(
            scenarioId = expected.optString("scenarioId", archive.sourceName.substringBeforeLast('.')),
            label = expected.optString("label", "UNKNOWN_UI"),
            frameCount = manifest.optInt("frameCount", 0),
            passed = false,
            skipped = true,
            assertions = listOf("NOT_OBSERVED: ground truth has not been manually confirmed"),
            uniqueRallies = emptySet(),
        )
    }
    val scenarioId = expected.getString("scenarioId")
    val label = expected.getString("label")
    val selectedLevels = manifest.optJSONObject("metadata")?.optJSONObject("runtimeSettings")
        ?.optJSONArray("selectedLevels")?.toIntSet().orEmpty().ifEmpty { setOf(5, 10) }
    val settings = manifest.optJSONObject("metadata")?.optJSONObject("runtimeSettings")
    val policy = SafetyPolicy(
        targetLevels = selectedLevels,
        safetyMarginSeconds = settings?.optInt("safetyMarginSeconds", 3) ?: 3,
    )
    val tracker = RallyTracker()
    val detector = RallyDetector(templates)
    val alertPolicy = RadarAlertPolicy(policy)
    val safety = SafetyController(policy)
    val selector = TargetSelector()
    val shadow = ShadowAutoCoordinator(
        AutoPolicyConfig(
            delayMinSeconds = settings?.optInt("delayMinSeconds", 0) ?: 0,
            delayMaxSeconds = settings?.optInt("delayMaxSeconds", 0) ?: 0,
            skipMin = settings?.optInt("skipMin", 0) ?: 0,
            skipMax = settings?.optInt("skipMax", 0) ?: 0,
        ),
    )
    val frameMetadata = manifest.getJSONArray("frames")
    val imageEntries = archive.entries.keys.filter { it.startsWith("frames/") && it.endsWith(".jpg") }.sorted()
    val observations = mutableListOf<ReplayObservation>()
    val uniqueRallies = mutableSetOf<String>()
    imageEntries.forEachIndexed { index, path ->
        val image = requireNotNull(ImageIO.read(ByteArrayInputStream(requireNotNull(archive.entries[path])))) {
            "Cannot decode $path"
        }
        val sourceFrame = frameMetadata.optJSONObject(index) ?: JSONObject()
        val frameId = sourceFrame.optLong("frameId", index.toLong())
        val observedAt = sourceFrame.optLong("observedAtMonotonicMs", index * 100L)
        val analysis = detector.analyze(BufferedArgbImage(image), frameId, observedAt)
        val tracking = tracker.update(analysis)
        val alerts = alertPolicy.decide(analysis, tracking)
        val actions = safety.decide(RuntimeMode.SHADOW_AUTO, analysis, tracking)
        val selected = selector.select(frameId, tracking, actions)
        val shadowUpdate = shadow.onFrame(analysis, tracking, actions)
        analysis.rallies.forEach { rally ->
            val identity = rally.identityFingerprint
            if (identity != null) uniqueRallies += "${identity.targetTitleHash}:${identity.coordinatesHash}"
        }
        observations += ReplayObservation(
            screen = analysis.screen,
            rallies = analysis.rallies,
            squads = analysis.squads,
            travelTime = analysis.travelTime.value,
            alertSelections = alerts.count { it.kind == DecisionKind.WOULD_SELECT },
            actionSelections = actions.count { it.kind == DecisionKind.WOULD_SELECT },
            selectorFound = selected != null,
            shadowPhase = shadowUpdate.phase.name,
            refreshFound = analysis.refreshButton.accepted,
        )
    }
    val assertions = assertionsFor(label, expected.optInt("optionalIntValue", Int.MIN_VALUE), observations)
    return ScenarioResult(
        scenarioId = scenarioId,
        label = label,
        frameCount = observations.size,
        passed = assertions.none { it.startsWith("FAIL") },
        skipped = false,
        assertions = assertions,
        uniqueRallies = uniqueRallies,
    )
}

private fun assertionsFor(label: String, optionalValue: Int, frames: List<ReplayObservation>): List<String> {
    fun result(name: String, predicate: Boolean) = "${if (predicate) "PASS" else "FAIL"}: $name"
    val rallies = frames.flatMap { it.rallies }
    val assertions = mutableListOf<String>()
    assertions += result("recorded monotonic frames replayed", frames.isNotEmpty())
    when (label) {
        "EVENT_EMPTY" -> assertions += result("event list observed without rallies", frames.any { it.screen == ScreenState.EVENT_LIST && it.rallies.isEmpty() })
        "TARGET_LEVEL_5_JOINABLE" -> assertions += result("joinable target level 5 observed", rallies.any { it.bossType == BossType.TARGET && it.level == 5 && it.joinedState == JoinedState.JOINABLE })
        "TARGET_LEVEL_10_JOINABLE" -> assertions += result("joinable target level 10 observed", rallies.any { it.bossType == BossType.TARGET && it.level == 10 && it.joinedState == JoinedState.JOINABLE })
        "TARGET_OTHER_LEVEL_JOINABLE" -> assertions += result("joinable target outside levels 5/10 observed", rallies.any { it.bossType == BossType.TARGET && it.level !in setOf(null, 5, 10) && it.joinedState == JoinedState.JOINABLE })
        "TARGET_FULL" -> assertions += result("full target observed", rallies.any { it.bossType == BossType.TARGET && it.joinedState == JoinedState.FULL })
        "TARGET_ALREADY_JOINED" -> assertions += result("already-joined target observed", rallies.any { it.bossType == BossType.TARGET && it.joinedState == JoinedState.ALREADY_JOINED })
        "NON_TARGET" -> assertions += result("non-target observed", rallies.any { it.bossType == BossType.NON_TARGET })
        "MULTIPLE_TARGETS" -> assertions += result("multiple targets observed in one frame", frames.any { it.rallies.count { r -> r.bossType == BossType.TARGET } >= 2 })
        "TARGET_AND_NON_TARGET" -> assertions += result("target and non-target observed together", frames.any { f -> f.rallies.any { it.bossType == BossType.TARGET } && f.rallies.any { it.bossType == BossType.NON_TARGET } })
        "REFRESH_REORDER" -> assertions += result("refresh control observed", frames.any { it.refreshFound })
        "TARGET_DISAPPEARS" -> assertions += result(
            "target disappears before action",
            frames.zipWithNext().any { (before, after) ->
                before.rallies.any { it.bossType == BossType.TARGET } &&
                    after.rallies.none { it.bossType == BossType.TARGET }
            },
        )
        "SCROLLED_EVENT_LIST" -> assertions += result("event list remains classified", frames.any { it.screen == ScreenState.EVENT_LIST })
        "SQUAD_FREE" -> assertions += squadAssertion(frames, SquadState.FREE)
        "SQUAD_MOVING" -> assertions += squadAssertion(frames, SquadState.MOVING)
        "SQUAD_RETURNING" -> assertions += squadAssertion(frames, SquadState.RETURNING)
        "SQUAD_GATHERING" -> assertions += squadAssertion(frames, SquadState.GATHERING)
        "SQUAD_OTHER_BUSY" -> assertions += squadAssertion(frames, SquadState.OCCUPIED_OTHER)
        "SQUAD_UNKNOWN" -> assertions += squadAssertion(frames, SquadState.UNKNOWN)
        "MARCH_SCREEN" -> assertions += result("march screen observed", frames.any { it.screen == ScreenState.MARCH_SCREEN })
        "TRAVEL_TIME" -> assertions += result("manual travel time matched", optionalValue != Int.MIN_VALUE && frames.any { it.travelTime == optionalValue })
        "RALLY_COUNTDOWN" -> assertions += result("manual countdown matched", optionalValue != Int.MIN_VALUE && rallies.any { it.remainingSeconds == optionalValue })
        "UNKNOWN_UI" -> assertions += result("unknown UI observed", frames.any { it.screen == ScreenState.UNKNOWN })
        else -> assertions += "FAIL: unsupported ground-truth label $label"
    }
    if (label in setOf("TARGET_LEVEL_5_JOINABLE", "TARGET_LEVEL_10_JOINABLE", "TARGET_OTHER_LEVEL_JOINABLE")) {
        assertions += result("read-only alert policy reached selector", frames.any { it.alertSelections > 0 } || frames.size < 2)
        assertions += result("strict safety/selector pipeline executed", frames.any { it.actionSelections > 0 && it.selectorFound } || frames.size < 2)
    }
    return assertions
}

private fun squadAssertion(frames: List<ReplayObservation>, state: SquadState) =
    "${if (frames.any { frame -> frame.squads.any { it.state == state } }) "PASS" else "FAIL"}: squad $state observed"

private fun writeReports(inputName: String, results: List<ScenarioResult>, directory: File) {
    directory.mkdirs()
    val uniqueRallies = results.flatMapTo(mutableSetOf()) { it.uniqueRallies }
    val json = JSONObject()
        .put("schemaVersion", 1)
        .put("sourceBundle", inputName)
        .put("scenarioCount", results.size)
        .put("uniqueScenarioCount", results.map { it.scenarioId }.toSet().size)
        .put("uniqueDetectedRallyCount", uniqueRallies.size)
        .put("passed", results.count { it.passed })
        .put("failed", results.count { !it.passed && !it.skipped })
        .put("notObserved", results.count { it.skipped })
        .put("scenarios", JSONArray(results.map { result ->
            JSONObject()
                .put("scenarioId", result.scenarioId)
                .put("label", result.label)
                .put("frameCount", result.frameCount)
                .put("status", when { result.skipped -> "NOT_OBSERVED"; result.passed -> "PASS"; else -> "FAIL" })
                .put("assertions", JSONArray(result.assertions))
                .put("uniqueDetectedRallies", result.uniqueRallies.size)
        }))
    File(directory, "capture-lab-replay.json").writeText(json.toString(2))
    File(directory, "capture-lab-replay.txt").writeText(buildString {
        appendLine("CaptureLab deterministic replay")
        appendLine("source=$inputName")
        appendLine("unique scenarios=${results.map { it.scenarioId }.toSet().size}")
        appendLine("unique detected rallies=${uniqueRallies.size}")
        appendLine("pass=${results.count { it.passed }} fail=${results.count { !it.passed && !it.skipped }} not_observed=${results.count { it.skipped }}")
        results.forEach { result ->
            appendLine()
            appendLine("${when { result.skipped -> "NOT_OBSERVED"; result.passed -> "PASS"; else -> "FAIL" }} ${result.scenarioId} ${result.label} frames=${result.frameCount}")
            result.assertions.forEach { appendLine("  $it") }
        }
    })
}

private fun JSONArray.toIntSet(): Set<Int> = buildSet {
    for (index in 0 until length()) add(getInt(index))
}
