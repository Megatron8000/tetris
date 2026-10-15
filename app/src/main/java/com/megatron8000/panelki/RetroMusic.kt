package com.megatron8000.panelki

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin

/**
 * Original 8-bit post-punk-inspired loop: steady bass, restrained minor-key lead and dry drums.
 * This is an original melody, not a transcription of an existing song.
 */
class RetroMusic {
    companion object {
        private const val SAMPLE_RATE = 22050
        private const val CHUNK = 1024

        private val MELODY = doubleArrayOf(
            293.66, 0.0, 220.00, 0.0, 246.94, 0.0, 0.0, 196.00,
            0.0, 220.00, 0.0, 261.63, 246.94, 0.0, 220.00, 0.0,
            196.00, 0.0, 220.00, 0.0, 293.66, 0.0, 0.0, 261.63,
            0.0, 246.94, 0.0, 196.00, 220.00, 0.0, 0.0, 0.0
        )
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
            if (minBuffer <= 0) throw IllegalStateException("AudioTrack format unavailable")

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

            if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
                audioTrack.release()
                throw IllegalStateException("AudioTrack failed to initialize")
            }

            track = audioTrack
            audioTrack.setVolume(0.55f)
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
        val samplesPerStep = SAMPLE_RATE * 60.0 / bpm / 4.0
        var sample = 0L
        var melodyPhase = 0.0
        var bassPhase = 0.0

        while (running.get()) {
            for (i in buffer.indices) {
                val step = ((sample / samplesPerStep).toInt() % MELODY.size)
                val withinStep = (sample % samplesPerStep) / samplesPerStep
                val melodyHz = MELODY[step]
                val bassHz = BASS[(step / 4) % BASS.size]
                var mix = 0.0

                // Bass is the anchor: a warm, slightly gritty pulse, kept well below clipping.
                bassPhase += bassHz / SAMPLE_RATE
                if (bassPhase >= 1.0) bassPhase -= 1.0
                val bassWave = sin(2.0 * PI * bassPhase)
                val bassEdge = if (bassPhase < 0.5) 1.0 else -1.0
                mix += (bassWave * 0.72 + bassEdge * 0.28) * 7600.0 *
                    if (step % 4 == 0) 1.0 else 0.78

                if (melodyHz > 0.0) {
                    melodyPhase += melodyHz / SAMPLE_RATE
                    if (melodyPhase >= 1.0) melodyPhase -= 1.0
                    // Sine lead prevents the piercing square-wave squeal on phone speakers.
                    val attack = minOf(1.0, withinStep * 16.0)
                    val release = minOf(1.0, (1.0 - withinStep) * 5.0)
                    mix += sin(2.0 * PI * melodyPhase) * 3600.0 * attack * release
                }

                val localSample = (sample % samplesPerStep).toDouble()
                val beat = step % 16

                if (beat % 4 == 0 && localSample < SAMPLE_RATE * 0.09) {
                    val duration = SAMPLE_RATE * 0.09
                    val progress = localSample / duration
                    val frequency = 86.0 - 38.0 * progress
                    mix += sin(2.0 * PI * frequency * localSample / SAMPLE_RATE) *
                        3200.0 * (1.0 - progress) * if (beat == 0) 1.0 else 0.72
                }

                if ((beat == 4 || beat == 12) && localSample < SAMPLE_RATE * 0.05) {
                    val progress = localSample / (SAMPLE_RATE * 0.05)
                    val noise = (((sample * 1103515245L + 12345L) ushr 16) and 0x7fff)
                        .toDouble() / 16384.0 - 1.0
                    mix += noise * 900.0 * (1.0 - progress)
                }

                if (step % 2 == 1 && localSample < SAMPLE_RATE * 0.008) {
                    val progress = localSample / (SAMPLE_RATE * 0.008)
                    val noise = (((sample * 1664525L + 1013904223L) ushr 17) and 0x7fff)
                        .toDouble() / 16384.0 - 1.0
                    mix += noise * 220.0 * (1.0 - progress)
                }

                // Fixed headroom prevents clipping when bass, lead and drums overlap.
                buffer[i] = (mix * 0.62).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                sample++
            }

            try {
                val written = audioTrack.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
                if (written < 0) break
            } catch (_: Exception) {
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
            oldWorker?.join(250)
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
