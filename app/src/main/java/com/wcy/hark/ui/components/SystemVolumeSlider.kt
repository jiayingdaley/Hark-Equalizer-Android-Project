package com.wcy.hark.ui.components

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * System Volume Slider
 *
 * Displays a slider bound to the system audio stream volume. Automatically
 * switches between STREAM_VOICE_CALL and STREAM_MUSIC depending on the current
 * AudioManager mode, and listens for hardware volume key changes via BroadcastReceiver.
 *
 * Ref: AudioManager.setStreamVolume –
 *   https://developer.android.com/reference/android/media/AudioManager#setStreamVolume(int,int,int)
 *
 * @param audioManager The system AudioManager service instance.
 */
@Composable
fun SystemVolumeSlider(audioManager: AudioManager) {
    val context = LocalContext.current

    // Force STREAM_MUSIC (Media Volume) control as requested by the user
    val activeStream = AudioManager.STREAM_MUSIC
    val maxVolume = audioManager.getStreamMaxVolume(activeStream)
    var currentVolume by remember(activeStream) {
        mutableStateOf(audioManager.getStreamVolume(activeStream))
    }

    // Listen for hardware volume key changes to keep slider in sync
    DisposableEffect(context, activeStream) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                currentVolume = audioManager.getStreamVolume(activeStream)
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            observer
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }

    val sliderPosition = (currentVolume.toFloat() / maxVolume).coerceIn(0f, 1f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Text("媒體音量")
        Slider(
            value = sliderPosition,
            onValueChange = { newPosition ->
                val newVolume = (newPosition * maxVolume).toInt()
                audioManager.setStreamVolume(activeStream, newVolume, 0)
                currentVolume = newVolume // Immediate UI update; broadcast confirms later
            }
        )
        Text(text = "${(sliderPosition * 100).toInt()}%")
    }
}
