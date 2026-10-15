package com.megatron8000.panelki

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Original synthesizer soundtrack for PANELKI: slow, dark and atmospheric.
 * Smoother oscillator shapes avoid the harsh, high-frequency edge of raw square waves.
 */
class RetroMusic {
    companion object {
        private const val SAMPLE_RATE = 44100
        private const val CHUNK = 1024
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
            audioTrack.setVolume(0.72f)
            audioTrack.play()
            worker = Thread({ synthesize(audioTrack) }, "panelki-synth").apply {
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
        val stepSamples = SAMPLE_RATE * 60.0 / 72.0 / 2.0
        var sample = 0L
        var lowPass = 0.0
        while (running.get()) {
            for (i in buffer.indices) {
                val step = ((sample / stepSamples).toInt() % LEAD.size)
                val withinStep = (sample % stepSamples).toDouble() / stepSamples
                val t = sample.toDouble() / SAMPLE_RATE
                val leadHz = LEAD[step]
                val bassHz = BASS[(step / 2) % BASS.size]
                val chordHz = CHORD[(step / 16) % CHORD.size]
                var value = 0.0

                // Rounded, warm lead: fundamental plus a quiet second harmonic, no hard square wave.
                if (leadHz > 0.0) {
                    val envelope = minOf(1.0, withinStep * 7.0) *
                        minOf(1.0, (1.0 - withinStep) * 2.3)
                    // Phase modulation keeps vibrato bounded; multiplying frequency by elapsed
                    // time makes pitch drift progressively upward and can sound like a whistle.
                    val phase = 2.0 * PI * leadHz * t +
                        0.012 * sin(2.0 * PI * 4.2 * t)
                    value += (sin(phase) + 0.22 * sin(2.0 * phase)) * 6200.0 * envelope
                    // Soft detuned layer adds a synth chorus without a piercing high overtone.
                    value += sin(2.0 * PI * leadHz * 1.003 * t) * 1050.0 * envelope
                }

                // Smooth sub-bass with gentle movement; no bright transient.
                val bassPhase = 2.0 * PI * bassHz * t
                value += (sin(bassPhase) + 0.16 * sin(2.0 * bassPhase)) * 5200.0

                // Low, sustained chord bed made from sine waves rather than a buzzy sawtooth.
                val chordPhase = 2.0 * PI * chordHz * t
                val chordPulse = 0.82 + 0.18 * sin(2.0 * PI * 0.08 * t)
                value += (sin(chordPhase) + 0.12 * sin(2.0 * chordPhase)) * 1450.0 * chordPulse

                // Muted low kick, intentionally restrained.
                val localSample = sample % stepSamples
                if (step % 8 == 0 && localSample < SAMPLE_RATE * 0.12) {
                    val progress = localSample.toDouble() / (SAMPLE_RATE * 0.12)
                    val kickHz = 54.0 - 24.0 * progress
                    value += sin(2.0 * PI * kickHz * localSample / SAMPLE_RATE) *
                        680.0 * (1.0 - progress) * (1.0 - progress)
                }

                // Smooth the combined signal slightly to tame any residual harshness.
                lowPass += 0.24 * (value - lowPass)
                // Keep headroom for the summed voices; hard clipping adds harsh upper harmonics.
                val softened = lowPass / (1.0 + abs(lowPass) / 12000.0)
                buffer[i] = softened.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
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
