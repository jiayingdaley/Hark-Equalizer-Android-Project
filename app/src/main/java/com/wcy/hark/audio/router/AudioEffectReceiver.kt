package com.wcy.hark.audio.router

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.util.Log
import com.wcy.hark.audio.manager.SystemDspManager

class AudioEffectReceiver : BroadcastReceiver() {
    companion object {
        const val TAG = "AudioEffectReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
        val packageName = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: "Unknown"

        Log.d(TAG, "Received action: $action for session: $sessionId from package: $packageName")

        if (sessionId == -1) return

        when (action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                SystemDspManager.attachToSession(sessionId)
            }
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                SystemDspManager.detachFromSession(sessionId)
            }
        }
    }
}
