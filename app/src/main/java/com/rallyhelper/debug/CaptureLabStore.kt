package com.rallyhelper.debug

import android.content.Context
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class CaptureLabLabel {
    TARGET_LEVEL_5,
    TARGET_LEVEL_10,
    NON_TARGET,
    FULL,
    ALREADY_JOINED,
    SQUAD_BUSY,
    TRAVEL_VISIBLE,
    UNKNOWN,
}

/** In-memory five-second ring buffer. Archives are private app files and are never uploaded. */
class CaptureLabStore(private val context: Context, private val windowMs: Long = 5_000) {
    private data class Frame(val frameId: Long, val observedAtMonotonicMs: Long, val jpeg: ByteArray)
    private val frames = ArrayDeque<Frame>()
    private val directory get() = File(context.filesDir, "capture-lab")

    @Synchronized
    fun add(bitmap: Bitmap, frameId: Long, observedAtMonotonicMs: Long) {
        val bytes = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 72, output)
            output.toByteArray()
        }
        frames.addLast(Frame(frameId, observedAtMonotonicMs, bytes))
        while (frames.firstOrNull()?.let { observedAtMonotonicMs - it.observedAtMonotonicMs > windowMs } == true) {
            frames.removeFirst()
        }
    }

    @Synchronized
    fun save(label: CaptureLabLabel): File? {
        if (frames.isEmpty()) return null
        directory.mkdirs()
        val snapshot = frames.toList()
        val archive = File(directory, "${System.currentTimeMillis()}-${label.name.lowercase()}.zip")
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            snapshot.forEachIndexed { index, frame ->
                zip.putNextEntry(ZipEntry("frames/${index.toString().padStart(3, '0')}-${frame.frameId}.jpg"))
                zip.write(frame.jpeg)
                zip.closeEntry()
            }
            val manifest = buildString {
                append("{\"label\":\"").append(label.name).append("\",\"createdAtEpochMs\":")
                append(System.currentTimeMillis()).append(",\"frameCount\":").append(snapshot.size)
                append(",\"frames\":[")
                snapshot.forEachIndexed { index, frame ->
                    if (index > 0) append(',')
                    append("{\"frameId\":").append(frame.frameId)
                    append(",\"observedAtMonotonicMs\":").append(frame.observedAtMonotonicMs).append('}')
                }
                append("]}")
            }
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return archive
    }
}
