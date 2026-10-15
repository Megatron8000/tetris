package com.megatron8000.panelki

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer

/** Plays the bundled soundtrack on a loop. */
class RetroMusic(context: Context) {
    private val appContext = context.applicationContext
    private var player: MediaPlayer? = null

    fun start() {
        if (player != null) return

        val mediaPlayer = MediaPlayer.create(appContext, R.raw.song) ?: return
        mediaPlayer.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        mediaPlayer.isLooping = true
        mediaPlayer.setVolume(MUSIC_VOLUME, MUSIC_VOLUME)
        mediaPlayer.setOnErrorListener { failedPlayer, _, _ ->
            if (player === failedPlayer) player = null
            failedPlayer.release()
            true
        }

        player = mediaPlayer
        mediaPlayer.start()
    }

    fun stop() {
        val currentPlayer = player ?: return
        player = null
        currentPlayer.setOnErrorListener(null)
        runCatching { currentPlayer.stop() }
        currentPlayer.release()
    }

    private companion object {
        const val MUSIC_VOLUME = 0.55f
    }
}
