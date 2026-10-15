package com.megatron8000.panelki

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Original low-fi nocturnal game score: slow minor-key pulse, distant synth tones,
 * restrained bass and occasional mechanical noise. It uses no borrowed melody or recording.
 */
class RetroMusic {
    companion object {
        private const val SAMPLE_RATE = 22050
        private const val CHUNK = 1024

        // Sparse, original 32-step phrase in D minor. Long gaps leave room for the ambience.
        private val LEAD = doubleArrayOf(
            146.83, 0.0, 0.0, 174.61, 0.0, 0.0, 164.81, 0.0,
            0.0, 0.0, 130.81, 0.0, 0.0, 146.83, 0.0, 0.0,
            110.00, 0.0, 0.0, 130.81, 0.0, 0.0, 146.83, 0.0,
            0.0, 0.0, 164.81, 0.0, 146.83, 0.0, 0.0, 0.0
        )
        private val ROOTS = doubleArrayOf(36.71, 43.65, 32.70, 49.00, 36.71, 32.70, 29.14, 32.70)
        private val PADS = doubleArrayOf(73.42, 87.31, 65.41, 98.00, 73.42, 65.41, 58.27, 65.41)
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
            audioTrack.setVolume(0.20f)
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
        // Slow 82 BPM, eighth-note steps.
        val stepSamples = SAMPLE_RATE * 60.0 / 82.0 / 2.0
        var sample = 0L
        while (running.get()) {
            for (i in buffer.indices) {
                val step = ((sample / stepSamples).toInt() % LEAD.size)
                val withinStep = (sample % stepSamples).toDouble() / stepSamples
                val t = sample.toDouble() / SAMPLE_RATE
                val leadHz = LEAD[step]
                val rootHz = ROOTS[(step / 4) % ROOTS.size]
                val padHz = PADS[(step / 4) % PADS.size]
                val phrase = step / 8
                var value = 0.0

                // Soft, slightly hollow lead with a slow attack and a long, imperfect release.
                if (leadHz > 0.0) {
                    val phase = (t * leadHz) % 1.0
                    val pulse = if (phase < 0.43) 1.0 else -1.0
                    val attack = minOf(1.0, withinStep * 5.0)
                    val release = minOf(1.0, (1.0 - withinStep) * 2.8)
                    val drift = sin(2.0 * PI * 0.17 * t) * 0.12
                    value += pulse * 980.0 * attack * release * (1.0 + drift)
                    // A very quiet octave shadow only on selected phrase endings.
                    if (step % 8 == 5 && phrase != 2) {
                        val subPhase = (t * leadHz * 0.5) % 1.0
                        value += (if (subPhase < 0.5) 1.0 else -1.0) * 190.0 * release
                    }
                }

                // Deep, rounded bass: a low triangle wave rather than an upbeat, punchy bassline.
                val bassPhase = (t * rootHz) % 1.0
                val triangle = 1.0 - 4.0 * abs(bassPhase - 0.5)
                value += triangle * 1050.0

                // Distant sustained fifth/tonic pad, gently pulsing instead of bright arpeggios.
                val padPhase = (t * padHz) % 1.0
                val padTriangle = 1.0 - 4.0 * abs(padPhase - 0.5)
                val swell = 0.55 + 0.45 * sin(2.0 * PI * 0.075 * t)
                value += padTriangle * 330.0 * swell

                // Sparse, muted mechanical thump every other beat; no cheerful snare backbeat.
                val beatPosition = sample % (stepSamples * 2.0)
                if (step % 4 == 0 && beatPosition < SAMPLE_RATE * 0.11) {
                    val decay = 1.0 - beatPosition / (SAMPLE_RATE * 0.11)
                    val thumpHz = 48.0 - 19.0 * beatPosition / (SAMPLE_RATE * 0.11)
                    value += sin(2.0 * PI * thumpHz * beatPosition / SAMPLE_RATE) * 780.0 * decay
                }

                // Occasional thin noise texture evokes distant radio/static, never a regular hi-hat.
                if ((step == 7 || step == 23) && withinStep < 0.16) {
                    val noise = (((sample * 1103515245L + 12345L) ushr 16) and 0x7fff).toDouble() / 16384.0 - 1.0
                    value += noise * 150.0 * (1.0 - withinStep / 0.16)
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
