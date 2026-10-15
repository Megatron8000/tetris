package com.megatron8000.panelki

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin

/**
 * Original 8-bit post-punk loop for Panelki.
 * Repetitive minor-key bass, a restrained chiptune motif and a dry drum-machine pulse.
 * It does not reproduce melodies or recordings from existing songs.
 */
class RetroMusic {
    companion object {
        private const val SAMPLE_RATE = 22050
        private const val CHUNK = 1024

        // Original, sparse melody kept in a lower register to avoid the piercing high notes.
        private val MELODY = doubleArrayOf(
            293.66, 0.0, 220.00, 0.0, 246.94, 0.0, 0.0, 196.00,
            0.0, 220.00, 0.0, 261.63, 246.94, 0.0, 220.00, 0.0,
            196.00, 0.0, 220.00, 0.0, 293.66, 0.0, 0.0, 261.63,
            0.0, 246.94, 0.0, 196.00, 220.00, 0.0, 0.0, 0.0
        )

        // Four-bar descending bass progression, repeated steadily.
        private val BASS = doubleArrayOf(
            55.00, 55.00, 55.00, 55.00, 55.00, 55.00, 55.00, 55.00,
            49.00, 49.00, 49.00, 49.00, 49.00, 49.00, 49.00, 49.00,
            43.65, 43.65, 43.65, 43.65, 43.65, 43.65, 43.65, 43.65,
            41.20, 41.20, 41.20, 41.20, 41.20, 41.20, 41.20, 41.20
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
            )
            if (minBuffer <= 0) throw IllegalStateException("Unsupported AudioTrack format")

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
                .setBufferSizeInBytes(maxOf(minBuffer * 2, CHUNK * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            track = audioTrack
            audioTrack.setVolume(0.18f)
            audioTrack.play()
            worker = Thread({ synthesize(audioTrack) }, "panelki-8bit-postpunk").apply {
                isDaemon = true
                start()
            }
        } catch (_: Exception) {
            running.set(false)
            try { track?.release() } catch (_: Exception) { }
            track = null
        }
    }

    private fun synthesize(audioTrack: AudioTrack) {
        val buffer = ShortArray(CHUNK)
        val bpm = 112.0
        val stepSamples = SAMPLE_RATE * 60.0 / bpm / 4.0
        var sample = 0L
        var melodyPhase = 0.0
        var bassPhase = 0.0

        while (running.get()) {
            for (i in buffer.indices) {
                val step = ((sample / stepSamples).toInt() % MELODY.size)
                val withinStep = (sample % stepSamples).toDouble() / stepSamples
                val melodyHz = MELODY[step]
                val bassHz = BASS[(step / 4) % BASS.size]
                var value = 0.0

                // Rounded, low bass: mostly fundamental with only a little second harmonic.
                bassPhase = (bassPhase + bassHz / SAMPLE_RATE).let { if (it >= 1.0) it - 1.0 else it }
                value += (sin(2.0 * PI * bassPhase) * 0.86 +
                    sin(4.0 * PI * bassPhase) * 0.14) * 5600.0 *
                    (if (step % 4 == 0) 1.0 else 0.78)

                // Triangle-like 8-bit lead is much softer than a raw square wave.
                if (melodyHz > 0.0) {
                    melodyPhase = (melodyPhase + melodyHz / SAMPLE_RATE).let {
                        if (it >= 1.0) it - 1.0 else it
                    }
                    val triangle = 1.0 - 4.0 * kotlin.math.abs(melodyPhase - 0.5)
                    val attack = minOf(1.0, withinStep * 18.0)
                    val release = minOf(1.0, (1.0 - withinStep) * 6.0)
                    value += triangle * 1700.0 * attack * release
                } else {
                    // Continue phase naturally through rests, but emit no lead tone.
                }

                val localSample = (sample % stepSamples).toDouble()
                val beat = step % 16

                // Soft electronic kick on each beat.
                if (beat % 4 == 0 && localSample < SAMPLE_RATE * 0.095) {
                    val duration = SAMPLE_RATE * 0.095
                    val progress = localSample / duration
                    val frequency = 82.0 - 34.0 * progress
                    value += sin(2.0 * PI * frequency * localSample / SAMPLE_RATE) *
                        2600.0 * (1.0 - progress) * if (beat == 0) 1.0 else 0.72
                }

                // Muted snare on beats two and four.
                if ((beat == 4 || beat == 12) && localSample < SAMPLE_RATE * 0.055) {
                    val progress = localSample / (SAMPLE_RATE * 0.055)
                    val noise = (((sample * 1103515245L + 12345L) ushr 16) and 0x7fff)
                        .toDouble() / 16384.0 - 1.0
                    value += noise * 650.0 * (1.0 - progress)
                }

                // Very quiet short hi-hat ticks on alternating sixteenths.
                if (step % 2 == 1 && localSample < SAMPLE_RATE * 0.008) {
                    val progress = localSample / (SAMPLE_RATE * 0.008)
                    val noise = (((sample * 1664525L + 1013904223L) ushr 17) and 0x7fff)
                        .toDouble() / 16384.0 - 1.0
                    value += noise * 170.0 * (1.0 - progress)
                }

                buffer[i] = (value * 0.72).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
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

        val oldWorker = worker
        worker = null
        oldWorker?.interrupt()
        try {
            oldWorker?.join(150)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        val oldTrack = track
        track = null
        try {
            oldTrack?.pause()
            oldTrack?.flush()
            oldTrack?.release()
        } catch (_: Exception) {
            // Audio may already have been released during a lifecycle transition.
        }
    }
}
