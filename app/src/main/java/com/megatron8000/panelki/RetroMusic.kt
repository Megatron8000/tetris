package com.megatron8000.panelki

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer

/**
 * Plays the soundtrack provided by the project owner from app/src/main/res/raw/song.mp3.
 * The music remains local to the APK and loops until muted or the activity pauses.
 */
class RetroMusic(context: Context) {
    private var player: MediaPlayer? = null

    fun start() {
        if (player != null) return
        try {
            val mediaPlayer = MediaPlayer.create(context.applicationContext, R.raw.song)
            if (mediaPlayer != null) {
                mediaPlayer.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                mediaPlayer.isLooping = true
                mediaPlayer.setVolume(0.55f, 0.55f)
                mediaPlayer.setOnErrorListener { mp, _, _ ->
                    try {
                        mp.release()
                    } catch (_: Exception) {
                    }
                    player = null
                    true
                }
                player = mediaPlayer
                mediaPlayer.start()
            }
        } catch (_: Exception) {
            stop()
        }
    }

    fun stop() {
        val oldPlayer = player
        player = null
        try {
            oldPlayer?.stop()
        } catch (_: Exception) {
            // A player may not have reached the started state yet.
        }
        try {
            oldPlayer?.release()
        } catch (_: Exception) {
        }
    }
}
