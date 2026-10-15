package com.megatron8000.panelki

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Original, procedural post-punk inspired game loop:
 * repetitive minor-key bass, sparse cold synth notes and a dry, steady drum machine.
 * No recordings or melodies from existing artists are used.
 */
class RetroMusic {
    companion object {
        private const val SAMPLE_RATE = 22050
        private const val CHUNK = 1024

        // A minor / F / G / E: restrained, nocturnal and slightly uneasy.
        // Sixteenth-note grid; rests leave space for the bass and drums.
        private val MELODY = doubleArrayOf(
            440.00, 0.0, 329.63, 0.0, 293.66, 0.0, 0.0, 261.63,
            0.0, 329.63, 0.0, 392.00, 349.23, 0.0, 293.66, 0.0,
            261.63, 0.0, 329.63, 0.0, 392.00, 0.0, 0.0, 349.23,
            0.0, 293.66, 0.0, 261.63, 293.66, 0.0, 0.0, 0.0
        )

        // Repetitive eighth-note bass movement, changing slowly by bar.
        private val BASS = doubleArrayOf(
            55.00, 55.00, 55.00, 55.00, 55.00, 55.00, 55.00, 55.00,
            43.65, 43.65, 43.65, 43.65, 43.65, 43.65, 43.65, 43.65,
            49.00, 49.00, 49.00, 49.00, 49.00, 49.00, 49.00, 49.00,
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
            audioTrack.setVolume(0.24f)
            audioTrack.play()
            worker = Thread({ synthesize(audioTrack) }, "panelki-postpunk").apply {
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
        val bpm = 116.0
        val stepSamples = SAMPLE_RATE * 60.0 / bpm / 4.0
        var sample = 0L

        while (running.get()) {
            for (i in buffer.indices) {
                val step = ((sample / stepSamples).toInt() % MELODY.size)
                val withinStep = (sample % stepSamples).toDouble() / stepSamples
                val melodyHz = MELODY[step]
                val bassHz = BASS[(step / 4) % BASS.size]
                val t = sample.toDouble() / SAMPLE_RATE
                var value = 0.0

                // Dark, rounded pulse bass. A little harmonic edge helps it read on phone speakers.
                val bassPhase = (t * bassHz) % 1.0
                val bassFundamental = sin(2.0 * PI * bassPhase)
                val bassHarmonic = sin(4.0 * PI * bassPhase)
                val bassAccent = if (step % 4 == 0) 1.0 else 0.76
                value += (bassFundamental * 0.78 + bassHarmonic * 0.22) * 7600.0 * bassAccent

                // Sparse, thin, slightly hollow synth line instead of the old arcade square-wave tune.
                if (melodyHz > 0.0) {
                    val phase = (t * melodyHz) % 1.0
                    val pulse = if (phase < 0.34) 1.0 else -0.52
                    val softEdge = sin(2.0 * PI * melodyHz * t) * 0.28
                    val fadeIn = minOf(1.0, withinStep * 16.0)
                    val fadeOut = minOf(1.0, (1.0 - withinStep) * 5.5)
                    val envelope = fadeIn * fadeOut
                    value += (pulse + softEdge) * 1350.0 * envelope
                }

                val localSample = (sample % stepSamples).toDouble()
                val beat = step % 16

                // Dry four-on-the-floor kick, with a stronger downbeat.
                if (beat % 4 == 0 && localSample < SAMPLE_RATE * 0.105) {
                    val duration = SAMPLE_RATE * 0.105
                    val progress = localSample / duration
                    val frequency = 92.0 - 48.0 * progress
                    val decay = (1.0 - progress) * (if (beat == 0) 1.0 else 0.72)
                    value += sin(2.0 * PI * frequency * localSample / SAMPLE_RATE) * 4300.0 * decay
                }

                // Snare/clap on beats two and four, short and deliberately not too bright.
                if ((beat == 4 || beat == 12) && localSample < SAMPLE_RATE * 0.075) {
                    val progress = localSample / (SAMPLE_RATE * 0.075)
                    val noise = (((sample * 1103515245L + 12345L) ushr 16) and 0x7fff)
                        .toDouble() / 16384.0 - 1.0
                    val body = sin(2.0 * PI * 185.0 * localSample / SAMPLE_RATE)
                    value += (noise * 0.72 + body * 0.28) * 1800.0 * (1.0 - progress)
                }

                // Quiet closed hi-hat eighths give the loop a persistent motorik pulse.
                if (step % 2 == 1 && localSample < SAMPLE_RATE * 0.012) {
                    val progress = localSample / (SAMPLE_RATE * 0.012)
                    val noise = (((sample * 1664525L + 1013904223L) ushr 17) and 0x7fff)
                        .toDouble() / 16384.0 - 1.0
                    value += noise * 430.0 * (1.0 - progress)
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
