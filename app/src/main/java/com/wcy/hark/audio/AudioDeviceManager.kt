package com.wcy.hark.audio

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

class AudioDeviceManager(
    private val context: Context,
    private val audioEngine: AudioEngineInterface
) {

    interface AudioEngineInterface {
        fun setRecordingDeviceId(deviceId: Int)
        fun setPlaybackDeviceId(deviceId: Int)
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothProfile: BluetoothProfile? = null

    private val TAG = "AudioDeviceManager"

    private val wiredHeadsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_HEADSET_PLUG) {
                Log.d(TAG, "Wired headset event detected.")
                updateAudioDevices()
            }
        }
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON) {
                    Log.d(TAG, "Bluetooth is on. Setting up profile proxy.")
                    setupBluetoothProfileProxy()
                }
            }
        }
    }

    private val bluetoothProfileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.A2DP) {
                Log.d(TAG, "Bluetooth A2DP profile connected.")
                bluetoothProfile = proxy
                updateAudioDevices()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) {
                Log.d(TAG, "Bluetooth A2DP profile disconnected.")
                bluetoothProfile = null
                updateAudioDevices()
            }
        }
    }

    fun start() {
        context.registerReceiver(wiredHeadsetReceiver, IntentFilter(AudioManager.ACTION_HEADSET_PLUG))
        
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter != null) {
            context.registerReceiver(bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
            setupBluetoothProfileProxy()
        }
        updateAudioDevices()
    }

    fun stop() {
        context.unregisterReceiver(wiredHeadsetReceiver)
        if (bluetoothAdapter != null) {
            context.unregisterReceiver(bluetoothStateReceiver)
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.A2DP, bluetoothProfile)
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupBluetoothProfileProxy() {
        bluetoothAdapter?.getProfileProxy(context, bluetoothProfileListener, BluetoothProfile.A2DP)
    }

    @SuppressLint("MissingPermission")
    private fun isBluetoothHeadsetConnected(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return bluetoothAdapter?.isEnabled == true &&
               bluetoothProfile != null &&
               (bluetoothProfile?.connectedDevices?.size ?: 0) > 0
    }

    private fun isWiredHeadsetConnected(): Boolean {
        val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val allDevices = inputDevices + outputDevices
        return allDevices.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES }
    }

    fun updateAudioDevices() { // Now public
        var inputDeviceId = 0 // Default to 0 (system default)
        var outputDeviceId = 0

        if (isBluetoothHeadsetConnected()) {
            Log.d(TAG, "Bluetooth headset is connected.")
            val btInput = findAudioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, true)
            val btOutput = findAudioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, false)
            if (btInput != null && btOutput != null) {
                inputDeviceId = btInput.id
                outputDeviceId = btOutput.id
                Log.d(TAG, "Using Bluetooth device: Input ID $inputDeviceId, Output ID $outputDeviceId")
            }
        } else if (isWiredHeadsetConnected()) {
            Log.d(TAG, "Wired headset is connected.")
            val wiredInput = findAudioDevice(AudioDeviceInfo.TYPE_WIRED_HEADSET, true)
            val wiredOutput = findAudioDevice(AudioDeviceInfo.TYPE_WIRED_HEADSET, false)
            if (wiredInput != null && wiredOutput != null) {
                inputDeviceId = wiredInput.id
                outputDeviceId = wiredOutput.id
                Log.d(TAG, "Using Wired headset: Input ID $inputDeviceId, Output ID $outputDeviceId")
            }
        } else {
            Log.d(TAG, "No external headset connected. Using default devices.")
            // Find built-in devices as a fallback
            val builtInMic = findAudioDevice(AudioDeviceInfo.TYPE_BUILTIN_MIC, true)
            val builtInSpeaker = findAudioDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, false)
            inputDeviceId = builtInMic?.id ?: 0
            outputDeviceId = builtInSpeaker?.id ?: 0
            Log.d(TAG, "Using built-in devices: Input ID $inputDeviceId, Output ID $outputDeviceId")
        }

        audioEngine.setRecordingDeviceId(inputDeviceId)
        audioEngine.setPlaybackDeviceId(outputDeviceId)
    }

    private fun findAudioDevice(deviceType: Int, isInput: Boolean): AudioDeviceInfo? {
        val directionFlag = if (isInput) AudioManager.GET_DEVICES_INPUTS else AudioManager.GET_DEVICES_OUTPUTS
        val devices = audioManager.getDevices(directionFlag)
        return devices.find { it.type == deviceType }
    }
}