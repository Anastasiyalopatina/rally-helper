package com.rallyhelper.debug

import android.content.Context
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
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

/** In-memory five-second ring buffer. Archives are private app files and are never uploaded. */
class CaptureLabStore(private val context: Context, private val windowMs: Long = 5_000) {
    private data class Frame(val frameId: Long, val observedAtMonotonicMs: Long, val jpeg: ByteArray)
    private val frames = ArrayDeque<Frame>()
    private val directory get() = File(context.filesDir, "capture-lab")
    @Volatile private var armed = false

    @Synchronized
    fun setArmed(value: Boolean) {
        armed = value
        if (!value) frames.clear()
    }

    fun isArmed(): Boolean = armed

    @Synchronized
    fun add(bitmap: Bitmap, frameId: Long, observedAtMonotonicMs: Long) {
        if (!armed) return
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
    fun save(label: CaptureLabLabel, optionalIntValue: Int? = null): File? {
        if (!armed || frames.isEmpty()) return null
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
                append("{\"label\":\"").append(label.name).append("\",\"optionalIntValue\":")
                append(optionalIntValue ?: "null").append(",\"createdAtEpochMs\":")
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
}
