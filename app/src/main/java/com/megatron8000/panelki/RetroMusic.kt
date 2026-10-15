package com.megatron8000.panelki

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Small original procedural chiptune: square-wave melody, subdued bass and sparse drum hits.
 * No recordings or melodies from existing artists are used.
 */
class RetroMusic {
    companion object {
        private const val SAMPLE_RATE = 22050
        private const val CHUNK = 1024
        // A minor / A Phrygian mood: 4 different 4-bar phrases, with pauses and low notes.
        // The 64-step sequence changes shape before looping instead of repeating one short hook.
        private val MELODY = doubleArrayOf(
            220.00, 0.0, 261.63, 0.0, 196.00, 0.0, 174.61, 0.0,
            164.81, 0.0, 196.00, 0.0, 220.00, 0.0, 0.0, 0.0,
            146.83, 0.0, 164.81, 0.0, 196.00, 0.0, 174.61, 0.0,
            164.81, 0.0, 146.83, 0.0, 0.0, 0.0, 130.81, 0.0,
            220.00, 0.0, 0.0, 261.63, 196.00, 0.0, 174.61, 0.0,
            164.81, 0.0, 196.00, 0.0, 0.0, 0.0, 146.83, 0.0,
            130.81, 0.0, 146.83, 0.0, 164.81, 0.0, 146.83, 0.0,
            174.61, 0.0, 164.81, 0.0, 146.83, 0.0, 0.0, 0.0
        )
        private val BASS = doubleArrayOf(
            55.0, 55.0, 46.25, 46.25, 43.65, 43.65, 41.20, 41.20,
            55.0, 55.0, 46.25, 46.25, 43.65, 41.20, 38.89, 41.20
        )
    }

    private val running = AtomicBoolean(false)
    @Volatile private var track: AudioTrack? = null
    @Volatile private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            val minBuffer = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(CHUNK * 2)
            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuffer * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = audioTrack
            audioTrack.setVolume(0.22f)
            audioTrack.play()
            worker = Thread({ synthesize(audioTrack) }, "panelki-chiptune").apply {
                isDaemon = true
                start()
            }
        } catch (_: Exception) {
            running.set(false)
            track?.release()
            track = null
        }
    }

    private fun synthesize(audioTrack: AudioTrack) {
        val buffer = ShortArray(CHUNK)
        val stepSamples = SAMPLE_RATE * 60.0 / 108.0 / 4.0
        var sample = 0L
        while (running.get()) {
            for (i in buffer.indices) {
                val step = ((sample / stepSamples).toInt() % MELODY.size)
                val withinStep = (sample % stepSamples).toDouble() / stepSamples
                val melodyHz = MELODY[step]
                val bassHz = BASS[(step / 4) % BASS.size]
                val t = sample.toDouble() / SAMPLE_RATE
                var value = 0.0

                if (melodyHz > 0.0) {
                    val phase = (t * melodyHz) % 1.0
                    val square = if (phase < 0.5) 1.0 else -1.0
                    val fade = minOf(1.0, withinStep * 12.0, (1.0 - withinStep) * 8.0)
                    value += square * 4200.0 * fade
                }

                val bassPhase = (t * bassHz) % 1.0
                val triangle = 4.0 * abs(bassPhase - 0.5) - 1.0
                value += triangle * 1900.0

                // Uneven, restrained industrial beat: kicks vary by phrase, with sparse snare and hiss.
                val localSample = (sample % stepSamples).toDouble()
                val beatStep = step % 16
                val phrase = step / 16
                val kick = beatStep == 0 || (beatStep == 10 && phrase % 2 == 1)
                val snare = beatStep == 6 || (beatStep == 14 && phrase == 2)
                if (kick && localSample < SAMPLE_RATE * 0.085) {
                    val decay = 1.0 - localSample / (SAMPLE_RATE * 0.085)
                    value += sin(2.0 * PI * (64.0 - 39.0 * localSample / (SAMPLE_RATE * 0.085)) * localSample / SAMPLE_RATE) * 3000.0 * decay
                } else if (snare && localSample < SAMPLE_RATE * 0.045) {
                    val decay = 1.0 - localSample / (SAMPLE_RATE * 0.045)
                    val noise = (((sample * 1103515245L + 12345L) ushr 16) and 0x7fff).toDouble() / 16384.0 - 1.0
                    value += noise * 780.0 * decay
                } else if (beatStep == 12 && phrase != 1 && localSample < SAMPLE_RATE * 0.018) {
                    val decay = 1.0 - localSample / (SAMPLE_RATE * 0.018)
                    val noise = (((sample * 214013L + 2531011L) ushr 16) and 0x7fff).toDouble() / 16384.0 - 1.0
                    value += noise * 180.0 * decay
                }

                // A quiet octave shadow appears only at phrase endings, adding movement without cheer.
                if (melodyHz > 0.0 && beatStep == 14 && phrase != 1) {
                    val shadowPhase = (t * melodyHz * 2.0) % 1.0
                    value += (if (shadowPhase < 0.5) 1.0 else -1.0) * 500.0 * (1.0 - withinStep)
                }

                buffer[i] = value.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                sample++
            }
            try {
                if (audioTrack.write(buffer, 0, buffer.size) < 0) break
            } catch (_: IllegalStateException) {
                break
            }
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        val oldTrack = track
        track = null
        try {
            oldTrack?.pause()
            oldTrack?.flush()
            oldTrack?.release()
        } catch (_: Exception) {
            // Audio may already have been released by Android during a lifecycle transition.
        }
        worker?.interrupt()
        worker = null
    }
}
