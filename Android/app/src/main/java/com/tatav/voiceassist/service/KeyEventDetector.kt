package com.tatav.voiceassist.service

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent

class KeyEventDetector(
    private val thresholdMs: Long = 400L,
    private val onDoubleTap: () -> Unit,
    private val onSingleTapFallback: () -> Unit
) {
    private var lastVolumeUpTime = 0L
    private var waitingForSecondTap = false
    private val handler = Handler(Looper.getMainLooper())

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP) return false
        if (event.action != KeyEvent.ACTION_DOWN) return waitingForSecondTap

        val now = SystemClock.elapsedRealtime()
        if (waitingForSecondTap && (now - lastVolumeUpTime) <= thresholdMs) {
            waitingForSecondTap = false
            handler.removeCallbacksAndMessages(null)
            onDoubleTap()
            return true
        }

        lastVolumeUpTime = now
        waitingForSecondTap = true
        handler.postDelayed({
            if (waitingForSecondTap) {
                waitingForSecondTap = false
                onSingleTapFallback()
            }
        }, thresholdMs)
        return true
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        waitingForSecondTap = false
    }
}
