package com.rallyhelper.debug

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import radar.vision.FrameAnalysis
import java.io.File
import java.util.concurrent.TimeUnit

class DebugCaptureStore(private val context: Context) {
    private val directory get() = File(context.filesDir, "debug-frames")

    fun saveAnnotated(bitmap: Bitmap, analysis: FrameAnalysis, reason: String) {
        directory.mkdirs()
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(copy)
        val paint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.YELLOW }
        val text = Paint().apply { color = Color.YELLOW; textSize = 32f; isFakeBoldText = true }
        analysis.rallies.forEach { rally ->
            val box = rally.cardBounds
            canvas.drawRect(
                (box.left * copy.width).toFloat(), (box.top * copy.height).toFloat(),
                (box.right * copy.width).toFloat(), (box.bottom * copy.height).toFloat(), paint,
            )
            canvas.drawText(
                "${rally.bossType} L${rally.level ?: "?"} ${rally.participantCount ?: "?"}/${rally.capacity ?: "?"}",
                (box.left * copy.width).toFloat(), (box.top * copy.height - 8).toFloat(), text,
            )
            rally.joinPlusBounds.forEach { plus ->
                paint.color = Color.GREEN
                canvas.drawRect(
                    (plus.left * copy.width).toFloat(), (plus.top * copy.height).toFloat(),
                    (plus.right * copy.width).toFloat(), (plus.bottom * copy.height).toFloat(), paint,
                )
            }
            paint.color = Color.YELLOW
        }
        val baseName = "${System.currentTimeMillis()}-${reason.take(24).replace(Regex("[^a-zA-Z0-9_-]"), "_")}"
        File(directory, "$baseName.jpg").outputStream().use { copy.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        File(directory, "$baseName.json").writeText(
            """{"frameId":${analysis.frameId},"screen":"${analysis.screen}","reason":"${reason.jsonEscaped()}","rallies":${analysis.rallies.size}}""",
        )
        copy.recycle()
    }

    fun purgeExpired(retentionDays: Int) {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays.toLong())
        directory.listFiles().orEmpty().filter { it.lastModified() < cutoff }.forEach(File::delete)
    }

    fun deleteAll(): Int {
        val files = directory.listFiles().orEmpty()
        files.forEach(File::delete)
        return files.size
    }
}

private fun String.jsonEscaped(): String = replace("\\", "\\\\").replace("\"", "\\\"")
