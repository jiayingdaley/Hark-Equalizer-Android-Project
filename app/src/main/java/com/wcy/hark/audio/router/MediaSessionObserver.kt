package com.wcy.hark.audio.router

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.wcy.hark.audio.service.HarkNotificationListener

/**
 * MediaSessionObserver: Monitors system-wide media playback.
 * 
 * Used to detect if apps like YouTube or Spotify are playing, allowing the
 * hearing aid to automatically switch to CINEMA/MUSIC mode.
 * 
 * Note: Requires "Notification Access" permission from the user.
 */
class MediaSessionObserver(private val context: Context, private val onMediaStateChanged: (Boolean) -> Unit) {

    private val mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val handler = Handler(Looper.getMainLooper())
    private var isMediaPlaying = false

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        updateMediaState(controllers)
    }

    fun start() {
        try {
            // This requires the app to be an enabled notification listener
            val componentName = ComponentName(context, HarkNotificationListener::class.java)
            mediaSessionManager.addOnActiveSessionsChangedListener(sessionListener, componentName)
            updateMediaState(mediaSessionManager.getActiveSessions(componentName))
        } catch (e: SecurityException) {
            Log.w("MediaSessionObserver", "Notification Access not granted. Auto-Media-Detection disabled.")
        }
    }

    fun stop() {
        mediaSessionManager.removeOnActiveSessionsChangedListener(sessionListener)
    }

    private fun updateMediaState(controllers: List<MediaController>?) {
        val playing = controllers?.any { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: false
        if (playing != isMediaPlaying) {
            isMediaPlaying = playing
            onMediaStateChanged(playing)
        }
    }
}
