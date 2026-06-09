package com.wcy.hark

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.wcy.hark.audio.HarkAudioBridge
import com.wcy.hark.audio.HarkAudioService
import com.wcy.hark.ui.screen.HarkAppScreen
import com.wcy.hark.ui.theme.HarkTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.wcy.hark.AudioSourceMode

/**
 * MainActivity – Entry point and lifecycle owner of the Hark hearing-aid app.
 *
 * Responsibilities (Single Responsibility after refactor):
 *  1. Android lifecycle management (onCreate / onResume / onPause).
 *  2. Runtime permission request for RECORD_AUDIO.
 *  3. Audio device detection and engine lifecycle control (checkAndSetAudioDevice).
 *  4. Bluetooth SCO state machine for legacy devices.
 *  5. Compose setContent → delegates all UI to [HarkAppScreen].
 *
 * All UI composables live in ui/screen/MainScreen.kt and ui/components/.
 * All JNI calls are routed through [HarkAudioBridge].
 *
 * Ref: Android Activity lifecycle –
 *   https://developer.android.com/guide/components/activities/activity-lifecycle
 */
class MainActivity : ComponentActivity() {

    // Mutex prevents concurrent calls to checkAndSetAudioDevice (e.g. device add + resume racing)
    private val deviceChangeMutex = Mutex()

    private val viewModel: EqViewModel by viewModels {
        EqViewModelFactory((application as HarkApplication).eqSettingsRepository)
    }

    private lateinit var audioManager: AudioManager
    
    // User's *intent*: does the user want the engine running?
    // Persists across onPause/onResume so we can restart on resume if needed.
    private var isEngineRunningByUserIntent by mutableStateOf(false)

    private var audioDeviceCallback: Any? = null
    private var mCurrentInputDeviceId: Int = -1
    private var mCurrentOutputDeviceType: Int = -1

    // -----------------------------------------------------------------------
    // Bluetooth SCO state (legacy Android < 12 fallback)
    // -----------------------------------------------------------------------

    private var isScoAudioConnected = false

    /**
     * Receives Bluetooth SCO connection state changes.
     *
     * Fix: On SCO_AUDIO_STATE_CONNECTED we call checkAndSetAudioDevice() instead of
     * startEngine() directly. This is critical because:
     *   - If a stream was disconnected by Oboe's error thread (devIds changed when SCO
     *     link became active), onErrorAfterClose() has already reset mIsRunning=false.
     *   - checkAndSetAudioDevice() performs a full, clean restart with correct routing,
     *     whereas startEngine() alone would be called without proper mode/device setup.
     */
    private val scoStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
            when (state) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                    Log.d(TAG, "SCO Audio Connected")
                    isScoAudioConnected = true
                    if (isEngineRunningByUserIntent) {
                        // Full restart — ensures clean state after possible stream disconnect
                        lifecycleScope.launch { checkAndSetAudioDevice() }
                    }
                }
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    Log.d(TAG, "SCO Audio Disconnected")
                    isScoAudioConnected = false
                }
                AudioManager.SCO_AUDIO_STATE_ERROR -> {
                    Log.e(TAG, "SCO Audio Error")
                    isScoAudioConnected = false
                    if (isEngineRunningByUserIntent) {
                        viewModel.statusText.value = "狀態：通話模式啟動失敗"
                    }
                }
            }
        }
    }

    private val volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            syncSystemVolume()
        }
    }

    /**
     * Reads current system voice call volume and pushes it to the native engine.
     * Mutes if volume is 0.
     */
    private fun syncSystemVolume() {
        // 根據目前引擎模式決定同步對象：IN_COMMUNICATION 同步通話音量，否則同步媒體音量
        val activeStream = if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION)
            AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
            
        val currentVol = audioManager.getStreamVolume(activeStream)
        val maxVol = audioManager.getStreamMaxVolume(activeStream)
        val volRatio = if (maxVol > 0) currentVol.toFloat() / maxVol.toFloat() else 0f
        
        Log.d(TAG, "Syncing volume ($activeStream): $currentVol/$maxVol (Ratio: $volRatio)")
        
        HarkAudioBridge.setMuted(currentVol == 0)
        // 增強整體增益：改用線性比例並乘以 2.0 倍的主動增益增強，低音量時也保持清晰
        HarkAudioBridge.setMasterGain(volRatio * 2.0f)
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Load the native "hark" library before any HarkAudioBridge calls.
        // HarkAudioBridge holds the external fun declarations; library must be
        // loaded here at a well-known lifecycle point rather than lazily.
        System.loadLibrary("hark")

        // Centralized RequestMultiplePermissions to prevent launcher race conditions
        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
            val notifyGranted = permissions[Manifest.permission.POST_NOTIFICATIONS] ?: true
            
            viewModel.isMicrophonePermissionGranted.value = audioGranted
            if (audioGranted) {
                Log.d(TAG, "RECORD_AUDIO permission granted atomically.")
                lifecycleScope.launch { checkAndSetAudioDevice() }
            } else {
                Log.w(TAG, "RECORD_AUDIO permission denied.")
                viewModel.statusText.value = "狀態：麥克風權限被拒絕"
            }
        }

        // Build list of permissions to request atomically on startup
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            viewModel.isMicrophonePermissionGranted.value = true
        }

        syncSystemVolume()

        setContent {
            HarkTheme {
                HarkAppScreen(
                    viewModel = viewModel,
                    audioManager = audioManager,
                    isEngineOn = isEngineRunningByUserIntent,
                    onEngineStateChange = { userWantsOn ->
                        isEngineRunningByUserIntent = userWantsOn
                        viewModel.statusText.value = if (userWantsOn)
                            "狀態：正在偵測音訊裝置..." else "狀態：已停用"
                        lifecycleScope.launch { checkAndSetAudioDevice() }
                    },
                    onSourceModeChanged = { newMode ->
                        if (newMode == AudioSourceMode.INTERNAL_MEDIA) {
                            if (!android.provider.Settings.canDrawOverlays(this@MainActivity)) {
                                val intent = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))
                                startActivity(intent)
                                viewModel.currentSourceMode.value = AudioSourceMode.MICROPHONE
                            } else {
                                isEngineRunningByUserIntent = false
                                viewModel.statusText.value = "狀態：手機影音 DSP 啟動中"
                                lifecycleScope.launch { checkAndSetAudioDevice() }
                                com.wcy.hark.audio.SystemDspManager.setEnabled(viewModel.isSystemDspOn.value)
                                // Try attaching to Session 0 (Global Mix) as a fallback
                                com.wcy.hark.audio.SystemDspManager.attachToSession(0)
                                startService(Intent(this@MainActivity, com.wcy.hark.audio.FloatingEqService::class.java))
                            }
                        } else {
                            com.wcy.hark.audio.SystemDspManager.setEnabled(false)
                            com.wcy.hark.audio.SystemDspManager.clearAllEffects()
                            stopService(Intent(this@MainActivity, com.wcy.hark.audio.FloatingEqService::class.java))
                        }
                    },
                    onSetBandGain = HarkAudioBridge::setBandGain,
                    onSetBandQ = HarkAudioBridge::setBandQ
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        
        // Sync permission state live on resume to keep UI perfectly reactive
        val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        viewModel.isMicrophonePermissionGranted.value = hasPermission
        
        registerAudioDeviceCallback()
        
        // Re-register volume observer and Bluetooth SCO receiver on resume to avoid lifecycle dead state
        contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            volumeObserver
        )
        try {
            registerReceiver(scoStateReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        } catch (e: Exception) {
            Log.w(TAG, "Error registering SCO receiver: ${e.message}")
        }

        if (isEngineRunningByUserIntent) {
            Log.d(TAG, "onResume: user intent ON, re-checking audio device.")
            lifecycleScope.launch { checkAndSetAudioDevice() }
        } else {
            viewModel.statusText.value = "狀態：已停用"
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterAudioDeviceCallback()

        try { unregisterReceiver(scoStateReceiver) } catch (_: Exception) { /* ignore if not registered */ }

        if (isScoAudioConnected && !isEngineRunningByUserIntent) {
            audioManager.stopBluetoothSco()
        }

        // Background Support: We NO LONGER stop the engine here if the service is running.
        // The foreground service keeps the engine alive.
        
        viewModel.statusText.value = if (isEngineRunningByUserIntent)
            "狀態：已切換至背景" else "狀態：已停用"
    }

    // -----------------------------------------------------------------------
    // Core: Audio Device Detection + Engine Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Detects connected headphones/earphones, selects the best input device,
     * configures AudioManager mode and communication device, then starts or
     * stops the native engine based on [isEngineRunningByUserIntent].
     *
     * Protected by [deviceChangeMutex] to prevent concurrent execution when
     * device add/remove events race with onResume.
     *
     * Priority order for input selection: BLE/SCO > USB headset > Wired 3.5mm > Built-in mic
     */
    private suspend fun checkAndSetAudioDevice() {
        deviceChangeMutex.withLock {
            Log.d(TAG, "checkAndSetAudioDevice: running under lock")

            val isHeadphoneConnected = checkHeadphoneConnection()
            Log.d(TAG, "Headphone output detected: $isHeadphoneConnected")
            HarkAudioBridge.setHeadphonesConnected(isHeadphoneConnected)

            if (isEngineRunningByUserIntent && !isHeadphoneConnected) {
                Log.w(TAG, "No headphone output → forcing engine stop")
                if (HarkAudioBridge.isEngineActuallyRunning()) HarkAudioBridge.stopEngine()
                
                // 確保同時關閉前景服務與常駐通知，徹底釋放音訊佔用
                val serviceIntent = Intent(this@MainActivity, HarkAudioService::class.java).apply {
                    action = HarkAudioService.ACTION_STOP
                }
                startService(serviceIntent)
                
                viewModel.statusText.value = "狀態：請連接耳機"
                return@withLock
            }

            val (targetDeviceId, deviceLabel) = selectBestInputDevice()
            Log.d(TAG, "Selected input: $deviceLabel (ID=$targetDeviceId)")

            // 藍牙耳機收音防禦：若選擇藍牙麥克風收音但 SCO 尚未建立連結，先啟動 SCO 連結並退出。
            // 等待 `scoStateReceiver` 收到 `SCO_AUDIO_STATE_CONNECTED` 廣播後，會重新調用此方法進入第二階段啟動引擎。
            if (deviceLabel == "藍牙耳機" && !isScoAudioConnected) {
                Log.d(TAG, "Bluetooth headset selected but SCO not connected. Initiating connection...")
                updateAudioManagerMode(deviceLabel)
                configureCommunicationDevice(targetDeviceId, deviceLabel)
                handleBluetoothScoLegacy(deviceLabel)
                if (isEngineRunningByUserIntent) {
                    viewModel.statusText.value = "狀態：正在連接藍牙麥克風..."
                }
                return@withLock
            }

            // 1. 如果引擎正在運行，且設定/裝置改變，先停止引擎
            val targetOutputDeviceType = getHeadphoneOutputDeviceType()
            var transitionDelayNeeded = false
            if (HarkAudioBridge.isEngineActuallyRunning()) {
                if (targetDeviceId != mCurrentInputDeviceId || 
                    targetOutputDeviceType != mCurrentOutputDeviceType || 
                    !viewModel.useHeadsetMic.value) {
                    Log.d(TAG, "Stopping engine for clean reconfiguration (device changed).")
                    HarkAudioBridge.stopEngine()
                    transitionDelayNeeded = true
                }
            }
            mCurrentInputDeviceId = targetDeviceId
            mCurrentOutputDeviceType = targetOutputDeviceType

            // 2. 更新 AudioManager 路由與模式（先清理舊路由與釋放藍牙 SCO，最後再更改模式，防止 Android 進入聽筒通話衝突狀態！）
            HarkAudioBridge.setAudioInputDeviceId(targetDeviceId)
            HarkAudioBridge.setUseHeadsetMic(viewModel.useHeadsetMic.value)
            HarkAudioBridge.setIsBluetoothInput(deviceLabel == "藍牙耳機")
            HarkAudioBridge.setInputGainOffset(if (deviceLabel.contains("手機")) 15.0f else 0.0f)

            val previousMode = audioManager.mode
            val previousScoConnected = isScoAudioConnected

            // 根據是否為藍牙耳機收音，調整模式與配置順序：
            // 1. 如果是藍牙耳機收音：必須先將 mode 設為 MODE_IN_COMMUNICATION，之後 setCommunicationDevice 才能成功綁定藍牙 SCO，否則會退回手機聽筒！
            // 2. 如果是其他收音（手機/有線/USB）：先清理通訊路由與 SCO 連線，再退回 MODE_NORMAL，以順利重定向至高品質 A2DP。
            if (deviceLabel == "藍牙耳機") {
                updateAudioManagerMode(deviceLabel)
                configureCommunicationDevice(targetDeviceId, deviceLabel)
                handleBluetoothScoLegacy(deviceLabel)
            } else {
                configureCommunicationDevice(targetDeviceId, deviceLabel)
                handleBluetoothScoLegacy(deviceLabel)
                updateAudioManagerMode(deviceLabel)
            }

            // 3. 安定時間偵測：
            // 如果我們從 MODE_IN_COMMUNICATION (3) 切換到 MODE_NORMAL (0)
            // 或者關閉了藍牙 SCO 連線，代表硬體正在從通話 profile 切換回 A2DP/媒體播放 profile。
            // 這需要 1500ms 的物理緩衝安定時間，否則在硬體切換中途開啟 OpenStream 會被系統直接 Disconnect，或者溢出到手機喇叭播放！
            if (transitionDelayNeeded || 
                (previousMode == AudioManager.MODE_IN_COMMUNICATION && audioManager.mode == AudioManager.MODE_NORMAL) ||
                (previousScoConnected && !isScoAudioConnected)) {
                Log.d(TAG, "Audio profile transition detected: waiting 2500ms for hardware paths to stabilize...")
                kotlinx.coroutines.delay(2500)
            }

            controlAudioEngineService(isHeadphoneConnected, deviceLabel)
        }
    }

    private fun checkHeadphoneConnection(): Boolean {
        val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outputDevices.any {
            it.type in listOf(
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            )
        }
    }

    private fun getHeadphoneOutputDeviceType(): Int {
        val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val headset = outputDevices.find {
            it.type in listOf(
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            )
        }
        return headset?.type ?: -1
    }

    private fun selectBestInputDevice(): Pair<Int, String> {
        val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        var targetDeviceId = 0
        var deviceLabel = "內建麥克風"

        // 檢查輸出端是否有藍牙設備已連線
        val hasBluetoothOutput = outputDevices.any {
            it.type in listOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET
            )
        }

        if (viewModel.useHeadsetMic.value) {
            val btInput = inputDevices.find {
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
            if (btInput != null) {
                targetDeviceId = btInput.id
                deviceLabel = "藍牙耳機"
            } else if (hasBluetoothOutput) {
                // 輸出端已連線藍牙，但輸入端尚未建立 SCO Link，此時仍標記為藍牙耳機以啟動通訊路由
                targetDeviceId = 0
                deviceLabel = "藍牙耳機"
            }

            if (targetDeviceId == 0 && deviceLabel != "藍牙耳機") {
                inputDevices.find { it.type == AudioDeviceInfo.TYPE_USB_HEADSET }?.let {
                    targetDeviceId = it.id
                    deviceLabel = "USB 耳機"
                } ?: inputDevices.find { it.type == AudioDeviceInfo.TYPE_USB_DEVICE }?.let {
                    targetDeviceId = it.id
                    deviceLabel = "USB 麥克風"
                }
            }

            if (targetDeviceId == 0 && deviceLabel != "藍牙耳機") {
                inputDevices.find { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }?.let {
                    targetDeviceId = it.id
                    deviceLabel = "有線耳機 (3.5mm)"
                }
            }
        } else {
            inputDevices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }?.let {
                targetDeviceId = it.id
                deviceLabel = "手機內建麥克風 (強制)"
            }
        }
        return Pair(targetDeviceId, deviceLabel)
    }

    private suspend fun updateAudioManagerMode(deviceLabel: String) {
        val targetMode = if (isEngineRunningByUserIntent && deviceLabel == "藍牙耳機")
            AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
        if (audioManager.mode != targetMode) {
            audioManager.mode = targetMode
            Log.d(TAG, "AudioManager mode → $targetMode")
            kotlinx.coroutines.delay(300)
        }
    }

    private suspend fun configureCommunicationDevice(targetDeviceId: Int, deviceLabel: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val commDevices = audioManager.availableCommunicationDevices
            Log.d(TAG, "Available comm devices: ${commDevices.map { "${it.id}:type=${it.type}" }}")

            val headsetDevice = if (deviceLabel == "藍牙耳機") {
                commDevices.find {
                    it.type in listOf(
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        AudioDeviceInfo.TYPE_BLE_HEADSET
                    )
                }
            } else {
                commDevices.find {
                    it.type in listOf(
                        AudioDeviceInfo.TYPE_USB_HEADSET,
                        AudioDeviceInfo.TYPE_USB_DEVICE
                    )
                } ?: commDevices.find {
                    it.type in listOf(
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    )
                }
            }

            if (headsetDevice != null) {
                val ok = audioManager.setCommunicationDevice(headsetDevice)
                Log.d(TAG, "setCommunicationDevice → type=${headsetDevice.type}, ok=$ok")
                if (deviceLabel == "藍牙耳機" && headsetDevice.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    kotlinx.coroutines.delay(600)
                }
            } else {
                audioManager.clearCommunicationDevice()
                Log.d(TAG, "Cleared communication device routing")
            }
        }
    }

    private fun handleBluetoothScoLegacy(deviceLabel: String) {
        val isBluetooth = deviceLabel == "藍牙耳機"
        if (isBluetooth) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isScoAudioConnected) {
                Log.d(TAG, "Legacy Android: calling startBluetoothSco()")
                audioManager.startBluetoothSco()
            }
        } else {
            try {
                audioManager.stopBluetoothSco()
                Log.d(TAG, "stopBluetoothSco() — ensuring SCO link is cleared")
            } catch (e: Exception) {
                Log.w(TAG, "stopBluetoothSco ignored: ${e.message}")
            }
            isScoAudioConnected = false
        }
    }

    private fun controlAudioEngineService(isHeadphoneConnected: Boolean, deviceLabel: String) {
        if (isEngineRunningByUserIntent && isHeadphoneConnected && checkHeadphoneConnection()) {
            val serviceIntent = Intent(this@MainActivity, HarkAudioService::class.java).apply {
                action = HarkAudioService.ACTION_START
            }
            ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
            
            lifecycleScope.launch {
                viewModel.statusText.value = "狀態：正在啟動..."
                kotlinx.coroutines.delay(1000)
                updateEngineStatus(deviceLabel)
            }
        } else {
            val serviceIntent = Intent(this@MainActivity, HarkAudioService::class.java).apply {
                action = HarkAudioService.ACTION_STOP
            }
            startService(serviceIntent)
            viewModel.statusText.value = "狀態：已停用"
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Updates the UI status text based on whether the native engine is actually running.
     * @param deviceLabel Human-readable label of the selected audio device.
     */
    private fun updateEngineStatus(deviceLabel: String = "") {
        viewModel.statusText.value = if (HarkAudioBridge.isEngineActuallyRunning()) {
            "狀態：運作中${if (deviceLabel.isNotEmpty()) " ($deviceLabel)" else ""}"
        } else {
            Log.e(TAG, "Engine failed to start. Device: $deviceLabel")
            "狀態：引擎啟動失敗"
        }
    }

    /**
     * Registers a callback to react to audio device plug/unplug events.
     * Requires API 23+ (already guaranteed by minSdk = 29).
     */
    private fun registerAudioDeviceCallback() {
        val callback = object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                Log.d(TAG, "Audio device added — waiting 1000ms for OS routing tables to stabilize")
                HarkAudioBridge.setHeadphonesConnected(checkHeadphoneConnection())
                lifecycleScope.launch {
                    kotlinx.coroutines.delay(1000)
                    checkAndSetAudioDevice()
                }
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                Log.d(TAG, "Audio device removed — checking state immediately")
                HarkAudioBridge.setHeadphonesConnected(checkHeadphoneConnection())
                lifecycleScope.launch {
                    kotlinx.coroutines.delay(1000)
                    checkAndSetAudioDevice()
                }
            }
        }
        audioManager.registerAudioDeviceCallback(callback, null)
        audioDeviceCallback = callback
    }

    private fun unregisterAudioDeviceCallback() {
        contentResolver.unregisterContentObserver(volumeObserver)
        (audioDeviceCallback as? android.media.AudioDeviceCallback)?.let {
            audioManager.unregisterAudioDeviceCallback(it)
            audioDeviceCallback = null
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}