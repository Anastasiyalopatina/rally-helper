package radar.vision.cli

import org.json.JSONArray
import org.json.JSONObject
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

fun main(args: Array<String>) {
    val workDirectory = File(requireNotNull(args.getOrNull(0))).apply { mkdirs() }
    val templateAsset = requireNotNull(args.getOrNull(1))
    val reportDirectory = requireNotNull(args.getOrNull(2))
    val scenario = File(workDirectory, "synthetic-replay-infrastructure.zip")
    val imageBytes = ByteArrayOutputStream().use { output ->
        val image = BufferedImage(320, 700, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color(22, 28, 36)
        graphics.fillRect(0, 0, image.width, image.height)
        graphics.dispose()
        ImageIO.write(image, "jpg", output)
        output.toByteArray()
    }
    val frames = JSONArray()
        .put(JSONObject().put("frameId", 1).put("observedAtMonotonicMs", 1_000))
        .put(JSONObject().put("frameId", 2).put("observedAtMonotonicMs", 1_125))
    val manifest = JSONObject()
        .put("schemaVersion", 4)
        .put("scenarioId", "synthetic-replay-infrastructure")
        .put("label", "UNKNOWN_UI")
        .put("groundTruthConfirmed", true)
        .put("frameCount", 2)
        .put("metadata", JSONObject().put("runtimeSettings", JSONObject()
            .put("selectedLevels", JSONArray(listOf(5, 10)))
            .put("delayMinSeconds", 0)
            .put("delayMaxSeconds", 0)
            .put("skipMin", 0)
            .put("skipMax", 0)
            .put("safetyMarginSeconds", 3)))
        .put("frames", frames)
    val expected = JSONObject()
        .put("schemaVersion", 1)
        .put("scenarioId", "synthetic-replay-infrastructure")
        .put("label", "UNKNOWN_UI")
        .put("confirmed", true)
    ZipOutputStream(scenario.outputStream()).use { zip ->
        listOf("frames/000-1.jpg", "frames/001-2.jpg").forEach { name ->
            zip.putNextEntry(ZipEntry(name)); zip.write(imageBytes); zip.closeEntry()
        }
        zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toString().toByteArray()); zip.closeEntry()
        zip.putNextEntry(ZipEntry("expected-scenario.json")); zip.write(expected.toString().toByteArray()); zip.closeEntry()
    }
    runCaptureLabReplay(arrayOf(scenario.absolutePath, templateAsset, reportDirectory))
}
