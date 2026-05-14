package com.wcy.hark.audio

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.wcy.hark.R
import com.wcy.hark.MainActivity
import android.content.pm.ServiceInfo

/**
 * HarkAudioService: A Foreground Service that keeps the Oboe audio engine
 * and SceneManager running in the background.
 */
class HarkAudioService : Service() {

    companion object {
        const val CHANNEL_ID = "HarkAudioChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        
        // Singleton access for the UI to observe SceneManager
        var sceneManager: SceneManager? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        sceneManager = SceneManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundService()
            ACTION_STOP -> {
                stopForeground(true)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hark 助聽器運行中")
            .setContentText("正在背景處理音訊以輔助您的聽力")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (!HarkAudioBridge.isEngineActuallyRunning()) {
            HarkAudioBridge.startEngine()
        }
        sceneManager?.start()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Hark Audio Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        sceneManager?.stop()
        sceneManager = null
        HarkAudioBridge.stopEngine()
        super.onDestroy()
    }
}
