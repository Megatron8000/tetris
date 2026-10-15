package com.megatron8000.panelki

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager

class MainActivity : Activity() {
    private lateinit var gameView: GameView
    private var music: RetroMusic? = null

    @Suppress("DEPRECATION")
    private fun immersiveMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        immersiveMode()
        gameView = GameView(this)
        gameView.onSoundToggle = { enabled ->
            if (enabled) {
                music?.stop()
                music = RetroMusic(this).also { it.start() }
            } else {
                music?.stop()
                music = null
            }
        }
        setContentView(gameView)
    }

    override fun onResume() {
        super.onResume()
        immersiveMode()
        if (::gameView.isInitialized) gameView.resumeGameClock()
        if (gameView.soundEnabled) music = RetroMusic(this).also { it.start() }
    }

    override fun onPause() {
        music?.stop()
        music = null
        if (::gameView.isInitialized) gameView.pauseGameClock()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersiveMode()
    }
}
