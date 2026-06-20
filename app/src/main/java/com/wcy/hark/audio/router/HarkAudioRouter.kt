package com.wcy.hark.audio.router

import com.wcy.hark.audio.bridge.HarkAudioBridge
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * HarkAudioRouter – Responsible for monitoring audio devices (plug/unplug),
 * managing Bluetooth SCO connection state machines, and configuring system audio modes.
 */
class HarkAudioRouter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val useHeadsetMicProvider: () -> Boolean,
    private val onRoutingChanged: (isHeadphoneConnected: Boolean, deviceLabel: String) -> Unit,
    private val onScoStateChanged: (statusText: String) -> Unit
) {
    companion object {
        private const val TAG = "HarkAudioRouter"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val deviceChangeMutex = Mutex()
    
    var isScoAudioConnected = false
        private set
        
    private var audioDeviceCallback: android.media.AudioDeviceCallback? = null

    private val scoStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
            when (state) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                    Log.d(TAG, "SCO Audio Connected")
                    isScoAudioConnected = true
                    scope.launch { checkAndSetAudioDevice() }
                }
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    Log.d(TAG, "SCO Audio Disconnected")
                    isScoAudioConnected = false
                }
                AudioManager.SCO_AUDIO_STATE_ERROR -> {
                    Log.e(TAG, "SCO Audio Error")
                    isScoAudioConnected = false
                    onScoStateChanged("狀態：通話模式啟動失敗")
                }
            }
        }
    }

    private var mCurrentInputDeviceId = -1
    private var mCurrentOutputDeviceType = -1

    fun start() {
        registerAudioDeviceCallback()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    scoStateReceiver, 
                    IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                context.registerReceiver(
                    scoStateReceiver, 
                    IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error registering SCO receiver: ${e.message}")
        }
        scope.launch { checkAndSetAudioDevice() }
    }

    fun stop() {
        unregisterAudioDeviceCallback()
        try {
            context.unregisterReceiver(scoStateReceiver)
        } catch (_: Exception) {}
        
        if (isScoAudioConnected) {
            try {
                audioManager.stopBluetoothSco()
            } catch (e: Exception) {
                Log.w(TAG, "stopBluetoothSco failed: ${e.message}")
            }
        }
    }

    suspend fun triggerRecheck() {
        checkAndSetAudioDevice()
    }

    fun checkHeadphoneConnection(): Boolean {
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

    private suspend fun checkAndSetAudioDevice() {
        deviceChangeMutex.withLock {
            Log.d(TAG, "checkAndSetAudioDevice: running under lock")

            val isHeadphoneConnected = checkHeadphoneConnection()
            Log.d(TAG, "Headphone output detected: $isHeadphoneConnected")
            HarkAudioBridge.setHeadphonesConnected(isHeadphoneConnected)

            if (!isHeadphoneConnected) {
                Log.w(TAG, "No headphone output → forcing engine stop")
                if (HarkAudioBridge.isEngineActuallyRunning()) HarkAudioBridge.stopEngine()
                onRoutingChanged(false, "請連接耳機")
                return@withLock
            }

            val (targetDeviceId, deviceLabel) = selectBestInputDevice()
            Log.d(TAG, "Selected input: $deviceLabel (ID=$targetDeviceId)")

            if (deviceLabel == "藍牙耳機" && !isScoAudioConnected) {
                Log.d(TAG, "Bluetooth headset selected but SCO not connected. Initiating connection...")
                updateAudioManagerMode(deviceLabel)
                configureCommunicationDevice(targetDeviceId, deviceLabel)
                handleBluetoothScoLegacy(deviceLabel)
                onScoStateChanged("狀態：正在連接藍牙麥克風...")
                return@withLock
            }

            val targetOutputDeviceType = getHeadphoneOutputDeviceType()
            var transitionDelayNeeded = false
            if (HarkAudioBridge.isEngineActuallyRunning()) {
                if (targetDeviceId != mCurrentInputDeviceId || 
                    targetOutputDeviceType != mCurrentOutputDeviceType || 
                    !useHeadsetMicProvider()) {
                    Log.d(TAG, "Stopping engine for clean reconfiguration (device changed).")
                    HarkAudioBridge.stopEngine()
                    transitionDelayNeeded = true
                }
            }
            mCurrentInputDeviceId = targetDeviceId
            mCurrentOutputDeviceType = targetOutputDeviceType

            HarkAudioBridge.setAudioInputDeviceId(targetDeviceId)
            HarkAudioBridge.setUseHeadsetMic(useHeadsetMicProvider())
            HarkAudioBridge.setIsBluetoothInput(deviceLabel == "藍牙耳機")
            HarkAudioBridge.setInputGainOffset(if (deviceLabel.contains("手機")) 15.0f else 0.0f)

            val previousMode = audioManager.mode
            val previousScoConnected = isScoAudioConnected

            if (deviceLabel == "藍牙耳機") {
                updateAudioManagerMode(deviceLabel)
                configureCommunicationDevice(targetDeviceId, deviceLabel)
                handleBluetoothScoLegacy(deviceLabel)
            } else {
                configureCommunicationDevice(targetDeviceId, deviceLabel)
                handleBluetoothScoLegacy(deviceLabel)
                updateAudioManagerMode(deviceLabel)
            }

            if (transitionDelayNeeded || 
                (previousMode == AudioManager.MODE_IN_COMMUNICATION && audioManager.mode == AudioManager.MODE_NORMAL) ||
                (previousScoConnected && !isScoAudioConnected)) {
                Log.d(TAG, "Audio profile transition detected: waiting 2500ms for hardware paths to stabilize...")
                delay(2500)
            }

            onRoutingChanged(true, deviceLabel)
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

        val hasBluetoothOutput = outputDevices.any {
            it.type in listOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET
            )
        }

        if (useHeadsetMicProvider()) {
            val btInput = inputDevices.find {
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
            if (btInput != null) {
                targetDeviceId = btInput.id
                deviceLabel = "藍牙耳機"
            } else if (hasBluetoothOutput) {
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
        val targetMode = if (deviceLabel == "藍牙耳機")
            AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
        if (audioManager.mode != targetMode) {
            audioManager.mode = targetMode
            Log.d(TAG, "AudioManager mode → $targetMode")
            delay(300)
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
                    delay(600)
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

    private fun registerAudioDeviceCallback() {
        val callback = object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                Log.d(TAG, "Audio device added — waiting 1000ms for OS routing tables to stabilize")
                HarkAudioBridge.setHeadphonesConnected(checkHeadphoneConnection())
                scope.launch {
                    delay(1000)
                    checkAndSetAudioDevice()
                }
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                Log.d(TAG, "Audio device removed — checking state immediately")
                HarkAudioBridge.setHeadphonesConnected(checkHeadphoneConnection())
                scope.launch {
                    delay(1000)
                    checkAndSetAudioDevice()
                }
            }
        }
        audioManager.registerAudioDeviceCallback(callback, null)
        audioDeviceCallback = callback
    }

    private fun unregisterAudioDeviceCallback() {
        audioDeviceCallback?.let {
            audioManager.unregisterAudioDeviceCallback(it)
            audioDeviceCallback = null
        }
    }
}
