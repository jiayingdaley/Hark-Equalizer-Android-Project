package com.wcy.hark.audio.router

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.wcy.hark.audio.bridge.HarkAudioBridge

/**
 * VolumeSyncHelper – Watches system volume changes and synchronizes the linear volume
 * ratio to the JNI master gain.
 */
class VolumeSyncHelper(
    private val context: Context
) {
    companion object {
        private const val TAG = "VolumeSyncHelper"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            syncSystemVolume()
        }
    }

    fun start() {
        context.contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            volumeObserver
        )
        syncSystemVolume()
    }

    fun stop() {
        context.contentResolver.unregisterContentObserver(volumeObserver)
    }

    /**
     * Reads current system voice call or music volume and pushes it to the native engine.
     */
    fun syncSystemVolume() {
        // IN_COMMUNICATION mode maps to voice call stream; otherwise maps to music stream.
        val activeStream = if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION)
            AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
            
        val currentVol = audioManager.getStreamVolume(activeStream)
        val maxVol = audioManager.getStreamMaxVolume(activeStream)
        val volRatio = if (maxVol > 0) currentVol.toFloat() / maxVol.toFloat() else 0f
        
        Log.d(TAG, "Syncing volume ($activeStream): $currentVol/$maxVol (Ratio: $volRatio)")
        
        HarkAudioBridge.setMuted(currentVol == 0)
        // 2.0x gain boost to maintain clarity even at lower system volumes
        HarkAudioBridge.setMasterGain(volRatio * 2.0f)
    }
}
