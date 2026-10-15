package com.megatron8000.panelki

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Original procedural soundtrack for PANELKI: slow, bleak post-punk / coldwave atmosphere.
 * The tune is newly composed; it does not reproduce any existing recording or melody.
 */
class RetroMusic {
    companion object {
        private const val SAMPLE_RATE = 22050
        private const val CHUNK = 1024

        // Original four-bar motif in D minor, written as eighth-note steps.
        // Repeated motifs and small variations make it feel like a song, not random beeps.
        private val LEAD = doubleArrayOf(
            146.83, 0.0, 174.61, 0.0, 196.00, 0.0, 174.61, 0.0,
            146.83, 0.0, 130.81, 0.0, 146.83, 0.0, 0.0, 0.0,
            130.81, 0.0, 146.83, 0.0, 164.81, 0.0, 146.83, 0.0,
            110.00, 0.0, 130.81, 0.0, 146.83, 0.0, 0.0, 0.0,
            146.83, 0.0, 174.61, 0.0, 196.00, 0.0, 220.00, 0.0,
            196.00, 0.0, 174.61, 0.0, 146.83, 0.0, 0.0, 0.0,
            130.81, 0.0, 146.83, 0.0, 164.81, 0.0, 146.83, 0.0,
            130.81, 0.0, 110.00, 0.0, 98.00, 0.0, 0.0, 0.0
        )
        private val BASS = doubleArrayOf(
            36.71, 36.71, 36.71, 36.71, 46.25, 46.25, 46.25, 46.25,
            34.65, 34.65, 34.65, 34.65, 41.20, 41.20, 41.20, 41.20
        )
        private val CHORD = doubleArrayOf(73.42, 69.30, 65.41, 61.74)
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
        // About 72 BPM; each melody step is an eighth note.
        val stepSamples = SAMPLE_RATE * 60.0 / 72.0 / 2.0
        var sample = 0L
        while (running.get()) {
            for (i in buffer.indices) {
                val step = ((sample / stepSamples).toInt() % LEAD.size)
                val withinStep = (sample % stepSamples).toDouble() / stepSamples
                val t = sample.toDouble() / SAMPLE_RATE
                val leadHz = LEAD[step]
                val bassHz = BASS[(step / 2) % BASS.size]
                val chordHz = CHORD[(step / 16) % CHORD.size]
                var value = 0.0

                // Hollow pulse-wave lead with a short attack and a little tail between notes.
                if (leadHz > 0.0) {
                    val phase = (t * leadHz) % 1.0
                    val pulse = if (phase < 0.38) 1.0 else -1.0
                    val envelope = minOf(1.0, withinStep * 9.0) * minOf(1.0, (1.0 - withinStep) * 3.0)
                    value += pulse * 1250.0 * envelope
                    // Quiet detuned layer gives the lead a slightly worn, analog edge.
                    val phase2 = (t * leadHz * 1.006) % 1.0
                    value += (if (phase2 < 0.42) 1.0 else -1.0) * 250.0 * envelope
                }

                // Repeating low bass ostinato anchors the melody and supplies forward motion.
                val bassPhase = (t * bassHz) % 1.0
                val bassTriangle = 1.0 - 4.0 * abs(bassPhase - 0.5)
                val bassGate = if (step % 2 == 0) 1.0 else 0.68
                value += bassTriangle * 1550.0 * bassGate

                // Dark sustained chord tone, soft enough to sit behind the melody.
                val chordPhase = (t * chordHz) % 1.0
                val chordSaw = 2.0 * chordPhase - 1.0
                val slowPulse = 0.78 + 0.22 * sin(2.0 * PI * 0.09 * t)
                value += chordSaw * 310.0 * slowPulse

                // Restrained, regular kick and a distant snare on alternate bars.
                val beatStep = step % 8
                val localSample = sample % stepSamples
                if (beatStep == 0 && localSample < SAMPLE_RATE * 0.10) {
                    val progress = localSample.toDouble() / (SAMPLE_RATE * 0.10)
                    val decay = 1.0 - progress
                    value += sin(2.0 * PI * (58.0 - 22.0 * progress) * localSample / SAMPLE_RATE) * 1050.0 * decay
                }
                if (beatStep == 4 && (step / 8) % 2 == 1 && localSample < SAMPLE_RATE * 0.055) {
                    val progress = localSample.toDouble() / (SAMPLE_RATE * 0.055)
                    val noise = (((sample * 1103515245L + 12345L) ushr 16) and 0x7fff).toDouble() / 16384.0 - 1.0
                    value += noise * 260.0 * (1.0 - progress)
                }

                // A faint high-frequency tick appears only at the end of every second phrase.
                if (step % 32 == 30 && withinStep < 0.08) {
                    val noise = (((sample * 214013L + 2531011L) ushr 16) and 0x7fff).toDouble() / 16384.0 - 1.0
                    value += noise * 100.0 * (1.0 - withinStep / 0.08)
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
