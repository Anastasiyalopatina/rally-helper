package com.rallyhelper.debug

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import radar.vision.DecisionKind
import radar.vision.DetectorDecision
import radar.vision.FrameAnalysis
import radar.vision.RuntimeMode
import radar.vision.ShadowAutoUpdate
import radar.vision.TrackedRally
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

enum class CaptureLabLabel {
    EVENT_EMPTY,
    TARGET_LEVEL_5_JOINABLE,
    TARGET_LEVEL_10_JOINABLE,
    TARGET_OTHER_LEVEL_JOINABLE,
    TARGET_FULL,
    TARGET_ALREADY_JOINED,
    NON_TARGET,
    MULTIPLE_TARGETS,
    TARGET_AND_NON_TARGET,
    REFRESH_REORDER,
    SCROLLED_EVENT_LIST,
    SQUAD_FREE,
    SQUAD_MOVING,
    SQUAD_RETURNING,
    SQUAD_GATHERING,
    SQUAD_OTHER_BUSY,
    SQUAD_UNKNOWN,
    MARCH_SCREEN,
    TRAVEL_TIME,
    RALLY_COUNTDOWN,
    UNKNOWN_UI,
}

enum class CaptureDatasetSplit { TUNING, HOLDOUT }

data class CaptureLabMetadata(
    val appVersion: String,
    val buildNumber: Int,
    val gitSha: String,
    val calibrationProfileId: String,
    val captureWidth: Int,
    val captureHeight: Int,
    val densityDpi: Int,
    val selectedLevels: Set<Int>,
    val mode: RuntimeMode,
    val delayMinSeconds: Int,
    val delayMaxSeconds: Int,
    val skipMin: Int,
    val skipMax: Int,
    val safetyMarginSeconds: Int,
    val detectorVersion: String,
    val templateVersion: String,
)

data class CaptureLabValidationSummary(
    val labelCounts: Map<CaptureLabLabel, Int>,
    val shadowWouldAttempts: Int,
    val distinctTravelTimes: Set<Int>,
    val squadCounts: Map<CaptureLabLabel, Int>,
    val holdoutArchives: Int,
)

/** In-memory bounded ring buffer. Archives are private app files and are never uploaded. */
class CaptureLabStore(private val context: Context, private val windowMs: Long = 6_500) {
    private data class Frame(
        val analysis: FrameAnalysis,
        val mode: RuntimeMode,
        val radarDecisions: List<DetectorDecision>,
        val actionDecisions: List<DetectorDecision>,
        val currentTracks: List<TrackedRally>,
        val shadowUpdate: ShadowAutoUpdate,
        val actualAlertEmitted: Boolean,
        val metadata: CaptureLabMetadata,
        val jpeg: ByteArray,
    )
    private data class PendingScenario(
        val label: CaptureLabLabel,
        val optionalIntValue: Int?,
        val split: CaptureDatasetSplit,
        val startsAtMonotonicMs: Long,
        val endsAtMonotonicMs: Long,
    )
    private val frames = ArrayDeque<Frame>()
    private val directory get() = File(context.filesDir, "capture-lab")
    @Volatile private var armed = false
    private var pendingScenario: PendingScenario? = null

    @Synchronized
    fun setArmed(value: Boolean) {
        armed = value
        if (!value) {
            frames.clear()
            pendingScenario = null
        }
    }

    fun isArmed(): Boolean = armed

    @Synchronized
    fun add(
        bitmap: Bitmap,
        analysis: FrameAnalysis,
        mode: RuntimeMode,
        radarDecisions: List<DetectorDecision>,
        actionDecisions: List<DetectorDecision>,
        currentTracks: List<TrackedRally>,
        shadowUpdate: ShadowAutoUpdate,
        actualAlertEmitted: Boolean,
        metadata: CaptureLabMetadata,
    ) {
        if (!armed) return
        val bytes = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 72, output)
            output.toByteArray()
        }
        frames.addLast(
            Frame(
                analysis = analysis,
                mode = mode,
                radarDecisions = radarDecisions,
                actionDecisions = actionDecisions,
                currentTracks = currentTracks,
                shadowUpdate = shadowUpdate,
                actualAlertEmitted = actualAlertEmitted,
                metadata = metadata,
                jpeg = bytes,
            ),
        )
        while (
            frames.firstOrNull()?.let {
                analysis.observedAtMonotonicMs - it.analysis.observedAtMonotonicMs > windowMs
            } == true
        ) {
            frames.removeFirst()
        }
        pendingScenario?.takeIf { analysis.observedAtMonotonicMs >= it.endsAtMonotonicMs }?.let { pending ->
            val snapshot = frames.filter { it.analysis.observedAtMonotonicMs >= pending.startsAtMonotonicMs }
            saveSnapshot(pending.label, pending.optionalIntValue, pending.split, snapshot)
            pendingScenario = null
        }
    }

    @Synchronized
    fun markScenario(
        label: CaptureLabLabel,
        optionalIntValue: Int? = null,
        split: CaptureDatasetSplit = CaptureDatasetSplit.TUNING,
    ): Boolean {
        if (!armed || frames.isEmpty() || pendingScenario != null) return false
        val markedAt = frames.last().analysis.observedAtMonotonicMs
        pendingScenario = PendingScenario(label, optionalIntValue, split, markedAt - 3_000, markedAt + 3_000)
        return true
    }

    @Synchronized
    fun save(
        label: CaptureLabLabel,
        optionalIntValue: Int? = null,
        split: CaptureDatasetSplit = CaptureDatasetSplit.TUNING,
    ): File? {
        if (!armed || frames.isEmpty()) return null
        return saveSnapshot(label, optionalIntValue, split, frames.toList())
    }

    private fun saveSnapshot(
        label: CaptureLabLabel,
        optionalIntValue: Int?,
        split: CaptureDatasetSplit,
        snapshot: List<Frame>,
    ): File? {
        if (snapshot.isEmpty()) return null
        directory.mkdirs()
        val archive = File(directory, "${System.currentTimeMillis()}-${label.name.lowercase()}.zip")
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            snapshot.forEachIndexed { index, frame ->
                zip.putNextEntry(
                    ZipEntry("frames/${index.toString().padStart(3, '0')}-${frame.analysis.frameId}.jpg"),
                )
                zip.write(frame.jpeg)
                zip.closeEntry()
            }
            val manifest = JSONObject()
                .put("schemaVersion", 3)
                .put("label", label.name)
                .put("datasetSplit", split.name)
                .put("optionalIntValue", optionalIntValue ?: JSONObject.NULL)
                .put("createdAtEpochMs", System.currentTimeMillis())
                .put("frameCount", snapshot.size)
                .put("metadata", metadataJson(snapshot.last().metadata))
                .put("frames", JSONArray(snapshot.map(::frameJson)))
                .toString()
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return archive
    }

    fun deleteAll(): Int {
        val files = directory.listFiles().orEmpty()
        files.forEach(File::delete)
        return files.size
    }

    fun createExportBundle(): File? {
        val archives = directory.listFiles { file -> file.extension.equals("zip", ignoreCase = true) }
            .orEmpty().sortedBy(File::lastModified)
        if (archives.isEmpty()) return null
        val bundle = File(context.cacheDir, "capture-lab-export-${System.currentTimeMillis()}.zip")
        ZipOutputStream(bundle.outputStream().buffered()).use { zip ->
            archives.forEach { archive ->
                zip.putNextEntry(ZipEntry(archive.name))
                archive.inputStream().buffered().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return bundle
    }

    fun validationSummary(): CaptureLabValidationSummary {
        val labels = mutableMapOf<CaptureLabLabel, Int>()
        val shadowIds = mutableSetOf<String>()
        val travelTimes = mutableSetOf<Int>()
        var holdout = 0
        directory.listFiles { file -> file.extension.equals("zip", ignoreCase = true) }.orEmpty().forEach { archive ->
            runCatching {
                ZipFile(archive).use { zip ->
                    val entry = zip.getEntry("manifest.json") ?: return@use
                    val manifest = JSONObject(zip.getInputStream(entry).bufferedReader().use { it.readText() })
                    val label = runCatching { CaptureLabLabel.valueOf(manifest.getString("label")) }.getOrNull()
                    if (label != null) labels[label] = (labels[label] ?: 0) + 1
                    if (manifest.optString("datasetSplit") == CaptureDatasetSplit.HOLDOUT.name) holdout++
                    manifest.optInt("optionalIntValue", Int.MIN_VALUE).takeUnless { it == Int.MIN_VALUE }?.let { value ->
                        if (label == CaptureLabLabel.TRAVEL_TIME) travelTimes += value
                    }
                    val frameItems = manifest.optJSONArray("frames") ?: JSONArray()
                    for (index in 0 until frameItems.length()) {
                        val shadow = frameItems.optJSONObject(index)?.optJSONObject("shadow") ?: continue
                        if (shadow.optString("phase") == "WOULD_START_JOIN_FLOW") {
                            shadow.optString("trackId").takeIf { it.isNotBlank() && it != "null" }
                                ?.let { shadowIds += "${archive.name}:$it" }
                        }
                    }
                }
            }
        }
        val squadLabels = setOf(
            CaptureLabLabel.SQUAD_FREE,
            CaptureLabLabel.SQUAD_MOVING,
            CaptureLabLabel.SQUAD_RETURNING,
            CaptureLabLabel.SQUAD_GATHERING,
            CaptureLabLabel.SQUAD_OTHER_BUSY,
            CaptureLabLabel.SQUAD_UNKNOWN,
        )
        return CaptureLabValidationSummary(
            labelCounts = labels,
            shadowWouldAttempts = shadowIds.size,
            distinctTravelTimes = travelTimes,
            squadCounts = labels.filterKeys { it in squadLabels },
            holdoutArchives = holdout,
        )
    }

    private fun frameJson(frame: Frame): JSONObject {
        val analysis = frame.analysis
        return JSONObject()
            .put("frameId", analysis.frameId)
            .put("observedAtMonotonicMs", analysis.observedAtMonotonicMs)
            .put("mode", frame.mode.name)
            .put("screen", analysis.screen.name)
            .put("screenConfidence", analysis.screenConfidence.toDouble())
            .put("rallies", JSONArray(analysis.rallies.map { rally ->
                JSONObject()
                    .put("boss", rally.bossType.name)
                    .put("level", rally.level ?: JSONObject.NULL)
                    .put("participantCount", rally.participantCount ?: JSONObject.NULL)
                    .put("capacity", rally.capacity ?: JSONObject.NULL)
                    .put("remainingSeconds", rally.remainingSeconds ?: JSONObject.NULL)
                    .put("plusCount", rally.joinPlusBounds.size)
                    .put("joinable", rally.joinable)
                    .put("full", rally.full ?: JSONObject.NULL)
                    .put("joinedState", rally.joinedState.name)
                    .put("cardBounds", rectJson(rally.cardBounds))
                    .put("joinPlusBounds", JSONArray(rally.joinPlusBounds.map(::rectJson)))
                    .put("confidence", JSONObject()
                        .put("card", rally.confidences.card.toDouble())
                        .put("boss", rally.confidences.boss.toDouble())
                        .put("level", rally.confidences.level.toDouble())
                        .put("participant", rally.confidences.participant.toDouble())
                        .put("plus", rally.confidences.plus.toDouble())
                        .put("timer", rally.confidences.timer.toDouble()))
            }))
            .put("travelTime", JSONObject()
                .put("value", analysis.travelTime.value ?: JSONObject.NULL)
                .put("confidence", analysis.travelTime.confidence.toDouble())
                .put("accepted", analysis.travelTime.accepted)
                .put("rejectionReason", analysis.travelTime.rejectionReason ?: JSONObject.NULL))
            .put("sendButtonFound", analysis.sendButtonFound)
            .put("refreshButton", JSONObject()
                .put("found", analysis.refreshButton.accepted)
                .put("confidence", analysis.refreshButton.confidence.toDouble())
                .put("bounds", analysis.refreshButton.value?.let(::rectJson) ?: JSONObject.NULL)
                .put("rejectionReason", analysis.refreshButton.rejectionReason ?: JSONObject.NULL))
            .put("currentTracks", JSONArray(frame.currentTracks.map { track ->
                JSONObject()
                    .put("trackId", track.id.value)
                    .put("observations", track.observations)
                    .put("stable", track.stable)
                    .put("presentInCurrentFrame", track.presentInCurrentFrame)
                    .put("lastSeenFrameId", track.lastSeenFrameId)
                    .put("boss", track.candidate.bossType.name)
                    .put("level", track.candidate.level ?: JSONObject.NULL)
                    .put("participantCount", track.candidate.participantCount ?: JSONObject.NULL)
                    .put("capacity", track.candidate.capacity ?: JSONObject.NULL)
                    .put("remainingSeconds", track.candidate.remainingSeconds ?: JSONObject.NULL)
                    .put("plusCount", track.candidate.joinPlusBounds.size)
                    .put("joinedState", track.candidate.joinedState.name)
                    .put("cardBounds", rectJson(track.candidate.cardBounds))
                    .put("joinPlusBounds", JSONArray(track.candidate.joinPlusBounds.map(::rectJson)))
            }))
            .put("squads", JSONArray(analysis.squads.map { squad ->
                JSONObject()
                    .put("slotIndex", squad.slotIndex)
                    .put("state", squad.state.name)
                    .put("remainingSeconds", squad.remainingSeconds ?: JSONObject.NULL)
                    .put("redirectPossible", squad.redirectPossible ?: JSONObject.NULL)
                    .put("confidence", squad.confidence.toDouble())
            }))
            .put("radarDecisions", decisionsJson(frame.radarDecisions))
            .put("actionDecisions", decisionsJson(frame.actionDecisions))
            .put("shadow", JSONObject()
                .put("phase", frame.shadowUpdate.phase.name)
                .put("trackId", frame.shadowUpdate.rallyId?.value ?: JSONObject.NULL)
                .put("delaySeconds", frame.shadowUpdate.delaySeconds ?: JSONObject.NULL)
                .put("dueAtMonotonicMs", frame.shadowUpdate.dueAtMonotonicMs ?: JSONObject.NULL)
                .put("policySkips", frame.shadowUpdate.policySkips)
                .put("virtualAttempts", frame.shadowUpdate.virtualAttempts))
            .put("radarWouldAlert", frame.radarDecisions.any { it.kind == DecisionKind.WOULD_SELECT })
            .put("actionWouldSelect", frame.actionDecisions.any { it.kind == DecisionKind.WOULD_SELECT })
            .put("actualAlertEmitted", frame.actualAlertEmitted)
    }

    private fun decisionsJson(decisions: List<DetectorDecision>) = JSONArray(decisions.map { decision ->
        JSONObject()
            .put("trackId", decision.rallyId?.value ?: JSONObject.NULL)
            .put("kind", decision.kind.name)
            .put("reason", decision.reason)
            .put("targetBounds", decision.targetBounds?.let(::rectJson) ?: JSONObject.NULL)
    })

    private fun metadataJson(value: CaptureLabMetadata) = JSONObject()
        .put("appVersion", value.appVersion)
        .put("buildNumber", value.buildNumber)
        .put("gitSha", value.gitSha)
        .put("calibrationProfileId", value.calibrationProfileId)
        .put("captureGeometry", JSONObject()
            .put("width", value.captureWidth)
            .put("height", value.captureHeight)
            .put("densityDpi", value.densityDpi))
        .put("runtimeSettings", JSONObject()
            .put("mode", value.mode.name)
            .put("selectedLevels", JSONArray(value.selectedLevels.sorted()))
            .put("delayMinSeconds", value.delayMinSeconds)
            .put("delayMaxSeconds", value.delayMaxSeconds)
            .put("skipMin", value.skipMin)
            .put("skipMax", value.skipMax)
            .put("minimumFreeSlots", 1)
            .put("safetyMarginSeconds", value.safetyMarginSeconds)
            .put("maxTravelSeconds", JSONObject.NULL))
        .put("detectorVersion", value.detectorVersion)
        .put("templateVersion", value.templateVersion)

    private fun rectJson(rect: radar.vision.NormalizedRect) = JSONObject()
        .put("left", rect.left)
        .put("top", rect.top)
        .put("right", rect.right)
        .put("bottom", rect.bottom)
}
