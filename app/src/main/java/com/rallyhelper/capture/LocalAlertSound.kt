package com.rallyhelper.capture

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.sin

/** Small app-local cue loaded through SoundPool; it does not depend on the system notification sound. */
internal class LocalAlertSound(context: Context) {
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    @Volatile private var loaded = false
    private val soundId: Int

    init {
        val file = File(context.cacheDir, "rally-helper-alert-v1.wav")
        if (!file.isFile) writeCue(file)
        soundId = soundPool.load(file.absolutePath, 1)
        soundPool.setOnLoadCompleteListener { _, id, status -> if (id == soundId && status == 0) loaded = true }
    }

    fun play() {
        if (loaded) soundPool.play(soundId, 0.8f, 0.8f, 1, 0, 1f)
    }

    fun close() = soundPool.release()

    private fun writeCue(file: File) {
        val sampleRate = 16_000
        val sampleCount = sampleRate * 160 / 1_000
        val pcmBytes = sampleCount * 2
        DataOutputStream(FileOutputStream(file)).use { out ->
            out.writeBytes("RIFF")
            out.writeIntLE(36 + pcmBytes)
            out.writeBytes("WAVEfmt ")
            out.writeIntLE(16)
            out.writeShortLE(1) // PCM
            out.writeShortLE(1)
            out.writeIntLE(sampleRate)
            out.writeIntLE(sampleRate * 2)
            out.writeShortLE(2)
            out.writeShortLE(16)
            out.writeBytes("data")
            out.writeIntLE(pcmBytes)
            repeat(sampleCount) { index ->
                val envelope = 1.0 - index.toDouble() / sampleCount
                val sample = (sin(2.0 * PI * 720.0 * index / sampleRate) * envelope * Short.MAX_VALUE * 0.45).toInt()
                out.writeShortLE(sample)
            }
        }
    }
}

/** Shared alert path for both real Radar decisions and the explicit preflight button. */
internal class LocalAlertFeedback(private val context: Context) {
    private val sound = LocalAlertSound(context)

    fun emit(soundEnabled: Boolean, vibrationEnabled: Boolean, vibrationMs: Long = 120) {
        if (soundEnabled) sound.play()
        if (vibrationEnabled) {
            val vibrator = context.getSystemService(Vibrator::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(vibrationMs, 90))
            } else @Suppress("DEPRECATION") vibrator.vibrate(vibrationMs)
        }
    }

    fun close() = sound.close()
}

private fun DataOutputStream.writeIntLE(value: Int) {
    writeByte(value and 0xff)
    writeByte(value ushr 8 and 0xff)
    writeByte(value ushr 16 and 0xff)
    writeByte(value ushr 24 and 0xff)
}

private fun DataOutputStream.writeShortLE(value: Int) {
    writeByte(value and 0xff)
    writeByte(value ushr 8 and 0xff)
}
