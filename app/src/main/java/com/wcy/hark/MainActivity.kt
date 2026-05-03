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
import com.wcy.hark.ui.screen.HarkAppScreen
import com.wcy.hark.ui.theme.HarkTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

        registerReceiver(scoStateReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))

        // Request RECORD_AUDIO permission; on grant trigger device detection
        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                Log.d(TAG, "RECORD_AUDIO permission granted.")
                lifecycleScope.launch { checkAndSetAudioDevice() }
            } else {
                Log.w(TAG, "RECORD_AUDIO permission denied.")
                viewModel.statusText.value = "狀態：麥克風權限被拒絕"
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

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
                    onSetBandGain = HarkAudioBridge::setBandGain,
                    onSetBandQ = HarkAudioBridge::setBandQ
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerAudioDeviceCallback()
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

        if (isScoAudioConnected) audioManager.stopBluetoothSco()

        if (HarkAudioBridge.isEngineActuallyRunning()) {
            Log.d(TAG, "onPause: stopping engine.")
            HarkAudioBridge.stopEngine()
        }

        viewModel.statusText.value = if (isEngineRunningByUserIntent)
            "狀態：已暫停 (背景)" else "狀態：已停用"
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

            // 1. Check if any headphone output is available (required to avoid feedback)
            val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val isHeadphoneConnected = outputDevices.any {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
            Log.d(TAG, "Headphone output detected: $isHeadphoneConnected")

            // 2. Guard: no headphones → cannot run safely
            if (isEngineRunningByUserIntent && !isHeadphoneConnected) {
                Log.w(TAG, "No headphone output → forcing engine stop")
                if (HarkAudioBridge.isEngineActuallyRunning()) HarkAudioBridge.stopEngine()
                viewModel.statusText.value = "狀態：請連接耳機"
                return@withLock
            }

            // 3. Select input device (priority: BLE > USB > Wired)
            val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            var targetDeviceId = 0
            var deviceLabel = "內建麥克風"

            inputDevices.find {
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }?.let { targetDeviceId = it.id; deviceLabel = "藍牙耳機" }

            if (targetDeviceId == 0) {
                inputDevices.find { it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
                    ?.let { targetDeviceId = it.id; deviceLabel = "USB 耳機" }
                    ?: inputDevices.find { it.type == AudioDeviceInfo.TYPE_USB_DEVICE }
                        ?.let { targetDeviceId = it.id; deviceLabel = "USB 麥克風" }
            }

            if (targetDeviceId == 0) {
                inputDevices.find { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
                    ?.let { targetDeviceId = it.id; deviceLabel = "有線耳機 (3.5mm)" }
            }
            Log.d(TAG, "Selected input: $deviceLabel (ID=$targetDeviceId)")

            // 4. Set AudioManager mode
            val targetMode = if (isEngineRunningByUserIntent)
                AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
            if (audioManager.mode != targetMode) {
                audioManager.mode = targetMode
                Log.d(TAG, "AudioManager mode → $targetMode")
                if (targetMode == AudioManager.MODE_IN_COMMUNICATION) {
                    kotlinx.coroutines.delay(200) // Let system update communication device list
                }
            }

            // 5. Stop engine for clean reconfiguration
            HarkAudioBridge.stopEngine()

            // 6. Apply selected input device to native engine
            HarkAudioBridge.setAudioInputDeviceId(targetDeviceId)

            // 7. Set communication device (Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val commDevice = audioManager.availableCommunicationDevices.find {
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.id == targetDeviceId
                }
                if (commDevice != null) {
                    val ok = audioManager.setCommunicationDevice(commDevice)
                    Log.d(TAG, "setCommunicationDevice → type=${commDevice.type}, success=$ok")

                    // Fix: For BLUETOOTH_SCO devices, the SCO link takes ~1s to establish.
                    // If we start Oboe streams immediately, the output opens to the built-in
                    // speaker (devIds=2). When SCO becomes active, Android re-routes audio
                    // (devIds 2→49), which disconnects the stream via ErrorDisconnected.
                    // Waiting here gives the SCO link time to become active so the output
                    // stream opens directly to the correct BT device.
                    if (commDevice.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                        Log.d(TAG, "BT SCO device detected — waiting 600ms for SCO link to establish")
                        kotlinx.coroutines.delay(600)
                    }
                } else {
                    audioManager.clearCommunicationDevice()
                    Log.d(TAG, "No matching communication device found. Cleared.")
                }
            }

            // 8. Handle Bluetooth SCO for legacy Android (< API 31)
            val isBluetoothDevice = deviceLabel == "藍牙耳機"
            if (isBluetoothDevice && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                if (!isScoAudioConnected) {
                    Log.d(TAG, "Legacy Android: calling startBluetoothSco()")
                    audioManager.startBluetoothSco()
                    // Engine start deferred to scoStateReceiver on SCO_AUDIO_STATE_CONNECTED
                    return@withLock
                }
            } else if (!isBluetoothDevice && isScoAudioConnected) {
                audioManager.stopBluetoothSco()
                isScoAudioConnected = false
            }

            // 9. Start or confirm engine is stopped
            if (isEngineRunningByUserIntent && isHeadphoneConnected) {
                HarkAudioBridge.startEngine()
                updateEngineStatus(deviceLabel)
            } else {
                viewModel.statusText.value = "狀態：已停用"
            }
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
                Log.d(TAG, "Audio device added")
                lifecycleScope.launch { checkAndSetAudioDevice() }
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                Log.d(TAG, "Audio device removed")
                lifecycleScope.launch { checkAndSetAudioDevice() }
            }
        }
        audioManager.registerAudioDeviceCallback(callback, null)
        audioDeviceCallback = callback
    }

    private fun unregisterAudioDeviceCallback() {
        (audioDeviceCallback as? android.media.AudioDeviceCallback)?.let {
            audioManager.unregisterAudioDeviceCallback(it)
            audioDeviceCallback = null
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}