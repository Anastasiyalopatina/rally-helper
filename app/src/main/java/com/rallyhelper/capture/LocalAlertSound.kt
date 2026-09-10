package com.rallyhelper.capture

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.rallyhelper.data.AlertSoundMode
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.sin

internal data class AlertDiagnostics(
    val mediaVolume: Int,
    val mediaVolumeMax: Int,
    val notificationVolume: Int,
    val notificationVolumeMax: Int,
    val hasVibrator: Boolean,
    val hasAmplitudeControl: Boolean,
)

/** Gentle custom two-tone cue (~0.85 s), routed through the selected Android audio policy. */
internal class LocalAlertSound(context: Context, mode: AlertSoundMode) {
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(
                    if (mode == AlertSoundMode.MEDIA) AudioAttributes.USAGE_MEDIA
                    else AudioAttributes.USAGE_NOTIFICATION_EVENT,
                )
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    @Volatile private var loaded = false
    private val soundId: Int

    init {
        val file = File(context.cacheDir, "rally-helper-two-tone-v2.wav")
        if (!file.isFile) writeCue(file)
        soundId = soundPool.load(file.absolutePath, 1)
        soundPool.setOnLoadCompleteListener { _, id, status -> if (id == soundId && status == 0) loaded = true }
    }

    fun play() {
        if (loaded) soundPool.play(soundId, 1f, 1f, 1, 0, 1f)
    }

    fun close() = soundPool.release()

    private fun writeCue(file: File) {
        val sampleRate = 16_000
        val sampleCount = sampleRate * 850 / 1_000
        val pcmBytes = sampleCount * 2
        DataOutputStream(FileOutputStream(file)).use { out ->
            out.writeBytes("RIFF")
            out.writeIntLE(36 + pcmBytes)
            out.writeBytes("WAVEfmt ")
            out.writeIntLE(16)
            out.writeShortLE(1)
            out.writeShortLE(1)
            out.writeIntLE(sampleRate)
            out.writeIntLE(sampleRate * 2)
            out.writeShortLE(2)
            out.writeShortLE(16)
            out.writeBytes("data")
            out.writeIntLE(pcmBytes)
            repeat(sampleCount) { index ->
                val ms = index * 1_000.0 / sampleRate
                val frequency = when {
                    ms < 360 -> 760.0
                    ms < 455 -> 0.0
                    else -> 570.0
                }
                val localMs = if (ms < 455) ms else ms - 455
                val toneLength = if (ms < 455) 360.0 else 395.0
                val edge = minOf(1.0, localMs.coerceAtLeast(0.0) / 28.0, (toneLength - localMs).coerceAtLeast(0.0) / 45.0)
                val sample = if (frequency == 0.0) 0 else (
                    sin(2.0 * PI * frequency * index / sampleRate) * edge * Short.MAX_VALUE * .72
                    ).toInt()
                out.writeShortLE(sample)
            }
        }
    }
}

/** Shared exact path for preflight and real target alerts. */
internal class LocalAlertFeedback(private val context: Context) {
    private val systemSound = LocalAlertSound(context, AlertSoundMode.SYSTEM)
    private val mediaSound = LocalAlertSound(context, AlertSoundMode.MEDIA)

    fun emit(
        soundEnabled: Boolean,
        vibrationEnabled: Boolean,
        soundMode: AlertSoundMode = AlertSoundMode.SYSTEM,
    ) {
        if (soundEnabled) {
            if (soundMode == AlertSoundMode.MEDIA) mediaSound.play() else systemSound.play()
        }
        if (vibrationEnabled) vibrate()
    }

    fun diagnostics(): AlertDiagnostics {
        val audio = context.getSystemService(AudioManager::class.java)
        val vibrator = vibrator()
        return AlertDiagnostics(
            mediaVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
            mediaVolumeMax = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            notificationVolume = audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION),
            notificationVolumeMax = audio.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION),
            hasVibrator = vibrator.hasVibrator(),
            hasAmplitudeControl = vibrator.hasAmplitudeControl(),
        )
    }

    private fun vibrate() {
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 180), -1)
        val vibrator = vibrator()
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION))
        } else vibrator.vibrate(effect)
    }

    private fun vibrator(): Vibrator = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else @Suppress("DEPRECATION") {
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    fun close() {
        systemSound.close()
        mediaSound.close()
    }
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
