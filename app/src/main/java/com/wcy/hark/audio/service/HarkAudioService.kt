package com.wcy.hark.audio.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.wcy.hark.R
import com.wcy.hark.MainActivity
import android.content.pm.ServiceInfo
import com.wcy.hark.audio.manager.SceneManager
import com.wcy.hark.audio.bridge.HarkAudioBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * HarkAudioService: A Foreground Service that keeps the Oboe audio engine
 * and SceneManager running in the background.
 *
 * AudioFocus management (KNOWN-ISSUE-005 fix):
 * - Requests AUDIOFOCUS_GAIN on start (USAGE_ASSISTANCE_ACCESSIBILITY)
 * - On AUDIOFOCUS_LOSS       → stop the engine (e.g. phone call)
 * - On AUDIOFOCUS_LOSS_TRANSIENT → mute (e.g. notification sound)
 * - On AUDIOFOCUS_GAIN       → unmute and re-sync volume
 */
class HarkAudioService : Service() {

    companion object {
        const val CHANNEL_ID = "HarkAudioChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        private const val TAG = "HarkAudioService"
        
        // Singleton access for the UI to observe SceneManager
        var sceneManager: SceneManager? = null
            private set

        /**
         * 聽力測驗隔離旗標：語詞/純音等測驗 Activity 進場時設 true（並自行
         * setMuted(true)），離場還原時設 false。旗標為 true 期間，服務內任何
         * 「自動解除靜音」路徑（AudioFocus 恢復、耳機重新接上、系統音量同步）
         * 一律維持靜音——否則測驗中途的裝置/焦點事件會把輔聽麥克風透傳聲
         * 混入測驗音，破壞測驗隔離。
         */
        @Volatile
        var audiometryIsolationActive = false

        /**
         * 實驗手動控制旗標：問卷頁（環境輔聽比較）進場時設 true。與隔離旗標
         * 不同——引擎要出聲，但 ON/OFF、模式、路由全由該頁掌控。旗標為 true
         * 期間：(1) 服務的耳機偵測/AudioFocus 不得改動靜音狀態（僅保留「耳機
         * 拔除即靜音」的回授保護）；(2) HarkAudioRouter 的裝置重設
         * （setCommunicationDevice、清系統 DSP、音量同步）一律跳過。實測未
         * 加此旗標時，背景 MainActivity 的路由重檢每兩秒打斷串流一次——
         * 聽感嚴重斷續，且反覆 ACTION_START 抹掉手動模式鎖。
         */
        @Volatile
        var experimentManualControl = false
    }

    // Service-level CoroutineScope tied to service lifecycle (Fix for KNOWN-ISSUE-007).
    // SceneManager uses this scope so its coroutines are cancelled when the service is destroyed.
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    // WakeLock to keep CPU alive during screen off (sleep mode)
    private var wakeLock: PowerManager.WakeLock? = null
    // Headset detection components to mute engine when unplugged
    private var headsetPlugReceiver: HeadsetPlugReceiver? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var isReceiverRegistered = false


    // AudioFocus change listener: handles interruptions from phone calls, other apps, etc.
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Permanent loss (e.g. phone call started): stop the engine
                Log.d(TAG, "AudioFocus LOSS — stopping engine for call/other app")
                HarkAudioBridge.setMuted(true)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Transient loss (e.g. notification, short alert): mute only
                Log.d(TAG, "AudioFocus LOSS_TRANSIENT — muting engine")
                HarkAudioBridge.setMuted(true)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Duck (e.g. GPS navigation): mute since we are a hearing aid
                Log.d(TAG, "AudioFocus LOSS_TRANSIENT_CAN_DUCK — muting engine")
                HarkAudioBridge.setMuted(true)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // Focus restored: unmute — unless a hearing test is isolating the engine.
                Log.d(TAG, "AudioFocus GAIN — restoring engine")
                if (!experimentManualControl) HarkAudioBridge.setMuted(audiometryIsolationActive)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        // Pass the service-scoped coroutine scope to SceneManager (KNOWN-ISSUE-007 fix)
        sceneManager = SceneManager(serviceScope)

        // 前景服務義務必須在 onCreate() 就無條件履行，不能等 onStartCommand 判斷完
        // action 才呼叫——MainActivity.updateAudioStates() 會在耳機路由變動時反覆呼叫
        // ContextCompat.startForegroundService()（例如藍牙瞬間斷連又重連），一旦兩次
        // 呼叫之間出現 STOP/START 競爭，startForeground() 真正執行的時間點就可能被
        // 排到系統限時（Android 15 起 5 秒）之外，導致
        // ForegroundServiceDidNotStartInTimeException 讓整個 App 被砍。onCreate() 對
        // 每個 Service 行程只會執行一次、且一定先於任何 onStartCommand，故在此立刻呼叫
        // 可保證無論哪個 intent（含 null 重啟）觸發，時限都不會被搶佔的路由邏輯吃掉。
        promoteToForeground()
    }

    /** 建立通知並呼叫 startForeground()；必須是 onCreate() 第一批動作之一（見上方註解）。 */
    private fun promoteToForeground() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hark 輔助聽力運行中")
            .setContentText("正在背景處理音訊以輔助您的聽力")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service (probably in background): ${e.message}")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // intent == null 是 START_STICKY 被系統殺掉行程後自動重啟的情形（官方行為：
            // 重啟時不會帶回原本的 intent）。這個 Service 先前是以前景服務身分在跑，重啟
            // 後系統仍要求限時內呼叫 startForeground()，否則直接拋
            // ForegroundServiceDidNotStartInTimeException 讓整個 App 當掉——因此 null 也要
            // 走跟 ACTION_START 相同的路徑，而不是被 when 悄悄吃掉。
            ACTION_START, null -> startForegroundService()
            ACTION_STOP -> {
                abandonAudioFocus()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        // Acquire WakeLock to keep CPU running during sleep mode
        // startForegroundService() 每個 ACTION_START 都會執行；不先釋放舊鎖
        // 就覆蓋參照會洩漏（logcat: "WakeLock finalized while still held"）。
        wakeLock?.takeIf { it.isHeld }?.release()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Hark::AudioCpuWakeLock"
        ).apply {
            acquire()
        }
        Log.d(TAG, "WakeLock acquired for background audio stability")

        // startForeground() 已在 onCreate() 的 promoteToForeground() 呼叫過（見該處註解）；
        // 這裡不需要也不應該重複呼叫。
        requestAudioFocus()

        if (!HarkAudioBridge.isEngineActuallyRunning()) {
            HarkAudioBridge.startEngine()
        }
        // 重新套用持久化的 NLFC 設定：引擎重啟後原生端旗標歸零，
        // 若不重套，使用者開過的移頻會在「關掉輔聽再開」後默默失效。
        serviceScope.launch {
            val nlfc = (application as com.wcy.hark.HarkApplication)
                .eqSettingsRepository.getFrequencyLoweringFlow()
                .first()
            HarkAudioBridge.setFrequencyLoweringParams(4500f, 2.0f)
            HarkAudioBridge.setFrequencyLoweringEnabled(nlfc)
        }
        sceneManager?.start()

        // Setup headset detection. startForegroundService() can run more than once per
        // service lifetime (ACTION_START fires again on toggle-off/on), so unregister any
        // previous receiver first — otherwise each restart leaks another registration
        // (IntentReceiverLeaked) since only onDestroy() used to unregister.
        if (isReceiverRegistered && headsetPlugReceiver != null) {
            try {
                unregisterReceiver(headsetPlugReceiver)
            } catch (e: IllegalArgumentException) {
                // already unregistered elsewhere; ignore
            }
            isReceiverRegistered = false
        }
        headsetPlugReceiver = HeadsetPlugReceiver()
        registerReceiver(headsetPlugReceiver, IntentFilter(Intent.ACTION_HEADSET_PLUG))
        isReceiverRegistered = true
        registerDeviceCallback()

        // Perform initial check on connection
        checkHeadphonesAndControlEngine()
    }

    private inner class HeadsetPlugReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_HEADSET_PLUG) {
                val state = intent.getIntExtra("state", -1)
                Log.d(TAG, "HeadsetPlugReceiver: state changed to $state")
                checkHeadphonesAndControlEngine()
            }
        }
    }

    private fun checkHeadphonesAndControlEngine() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val isHeadphoneConnected = devices.any {
            it.type in listOf(
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            )
        }
        Log.d(TAG, "HarkAudioService: Headphone connected = $isHeadphoneConnected")
        // Mute the audio engine instantly if headphones are disconnected, to prevent
        // screeching feedback loop. Stay muted while a hearing test is isolating the engine.
        // 實驗手動控制期間，靜音狀態由問卷頁掌控（OFF=靜音），服務不得代為解除
        // ——僅保留「耳機拔除即靜音」的回授保護。
        if (experimentManualControl && isHeadphoneConnected) return
        HarkAudioBridge.setMuted(!isHeadphoneConnected || audiometryIsolationActive)
    }

    private fun registerDeviceCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Same leak shape as the headset receiver above: this can run more than once
            // per service lifetime, so drop any previous callback before registering anew.
            unregisterDeviceCallback()
            audioDeviceCallback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                    checkHeadphonesAndControlEngine()
                }
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    checkHeadphonesAndControlEngine()
                }
            }
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        }
    }

    private fun unregisterDeviceCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioDeviceCallback != null) {
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
            audioDeviceCallback = null
        }
    }

    /**
     * Requests AUDIOFOCUS_GAIN with USAGE_ASSISTANCE_ACCESSIBILITY.
     * Using Accessibility usage signals to Android that this is an assistive hearing app,
     * which reduces the chance of being ducked by navigation or media apps.
     * Ref: https://developer.android.com/media/av/audio-focus
     */
    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(true) // We handle ducking manually via setMuted()
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            val result = audioManager.requestAudioFocus(request)
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                audioFocusRequest = request
                Log.d(TAG, "AudioFocus granted (API 26+)")
            } else {
                Log.w(TAG, "AudioFocus NOT granted (result=$result) — continuing anyway")
            }
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            Log.d(TAG, "AudioFocus (legacy) result=$result")
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
                audioFocusRequest = null
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
        Log.d(TAG, "AudioFocus abandoned")
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 當使用者在最近工作列表中滑掉 App 時，徹底關閉引擎與停止服務
        abandonAudioFocus()
        HarkAudioBridge.stopEngine()
        sceneManager?.stop()
        stopSelf()
    }

    override fun onDestroy() {
        // Release WakeLock safely
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing WakeLock: ${e.message}")
        }
        wakeLock = null

        // Unregister headphone listeners
        if (isReceiverRegistered && headsetPlugReceiver != null) {
            try {
                unregisterReceiver(headsetPlugReceiver)
            } catch (e: Exception) { /* no-op */ }
            isReceiverRegistered = false
            headsetPlugReceiver = null
        }
        unregisterDeviceCallback()

        abandonAudioFocus()
        sceneManager?.stop()
        sceneManager = null
        HarkAudioBridge.stopEngine()
        // Cancel all coroutines in the service scope (KNOWN-ISSUE-007 fix)
        serviceScope.cancel()
        serviceJob.cancel()
        super.onDestroy()
    }
}

