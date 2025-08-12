package com.example.hark

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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.forEachGesture
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.hark.ui.theme.HarkTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Object to hold all tunable UI parameters
object UIConstants {
    val BAND_CONTAINER_WIDTH: Dp = 70.dp
}

class MainActivity : ComponentActivity() {
    private val deviceChangeMutex = Mutex()
    private val viewModel: EqViewModel by viewModels()
    private lateinit var audioManager: AudioManager

    // --- State Management ---
    // 1. 使用者的「意圖」：使用者是否希望引擎處於運作狀態？
    private var isEngineRunningByUserIntent by mutableStateOf(false)
    // 2. 藍牙 SCO 音訊通道的「實際」狀態
    private var isScoAudioConnected by mutableStateOf(false)

    private var audioDeviceCallback: Any? = null

    // --- JNI Functions ---
    private external fun startEngine()
    private external fun stopEngine()
    private external fun setBandGain(bandIndex: Int, gainDb: Float)
    private external fun setBandQ(bandIndex: Int, q_factor: Float)
    private external fun setAudioInputDeviceId(deviceId: Int)
    private external fun isEngineActuallyRunning(): Boolean

    // --- Bluetooth SCO Management ---
    private val scoStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
            when (state) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                    val previouslyConnected = isScoAudioConnected
                    isScoAudioConnected = true
                    Log.d("Hark", "Event: Bluetooth SCO Audio connected.")
                    // 只有在狀態改變時才觸發，避免不必要的重複調用
                    if (!previouslyConnected) {
                        lifecycleScope.launch { checkAndSetAudioDevice() }
                    }
                }
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    val previouslyConnected = isScoAudioConnected
                    isScoAudioConnected = false
                    Log.d("Hark", "Event: Bluetooth SCO Audio disconnected.")
                    if (previouslyConnected) {
                        lifecycleScope.launch { checkAndSetAudioDevice() }
                    }
                }
                AudioManager.SCO_AUDIO_STATE_ERROR -> {
                    val previouslyConnected = isScoAudioConnected
                    isScoAudioConnected = false // 即使錯誤，也更新 SCO 狀態
                    Log.e("Hark", "Event: Bluetooth SCO Audio error.")
                    if (previouslyConnected) { // 或者即使之前沒連接也檢查，因為錯誤可能意味著需要切換
                        lifecycleScope.launch { checkAndSetAudioDevice() }
                    }
                }
            }
        }
    }

    private fun registerScoStateReceiver() {
        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        // Android 13 (TIRAMISU) and above require specifying receiver export status
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(scoStateReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(scoStateReceiver, filter)
        }
    }
    // --- End of Bluetooth SCO Management ---

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        System.loadLibrary("hark")

        // 權限請求
        val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Log.d("Hark", "RECORD_AUDIO permission granted.")
                lifecycleScope.launch { checkAndSetAudioDevice() } // 獲取權限後檢查設備
            } else {
                Log.w("Hark", "RECORD_AUDIO permission denied.")
                viewModel.statusText.value = "狀態：麥克風權限被拒絕"
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            HarkTheme {
                HarkAppScreen(
                    viewModel = viewModel,
                    audioManager = audioManager,
                    isEngineOn = isEngineRunningByUserIntent,
                    onEngineStateChange = { userWantsToRun ->
                    if (userWantsToRun) {
                        isEngineRunningByUserIntent = true
                        viewModel.statusText.value = "狀態：正在偵測音訊裝置..."

                        // 檢查是否需要啟動藍牙 SCO
                        // isBluetoothScoHeadsetConnected() 檢查是否有 SCO *兼容* 設備
                        if (isBluetoothScoHeadsetConnected() && !audioManager.isBluetoothScoOn) {
                            Log.d("Hark", "User wants to run engine. Bluetooth SCO compatible device detected and SCO is off. Starting SCO...")
                            viewModel.statusText.value = "狀態：正在連接藍牙..."
                            audioManager.isSpeakerphoneOn = false // 嘗試確保音訊路由到藍牙
                            audioManager.startBluetoothSco()
                            // scoStateReceiver 會在 SCO 連接成功或失敗後調用 checkAndSetAudioDevice
                        } else {
                            // 如果 SCO 已連線，或不使用藍牙 (例如使用有線耳機)，直接檢查設備並嘗試啟動引擎
                            Log.d("Hark", "User wants to run engine. SCO not needed or already on. Calling checkAndSetAudioDevice.")
                            lifecycleScope.launch { checkAndSetAudioDevice() }
                        }
                    } else {
                        isEngineRunningByUserIntent = false
                        Log.d("Hark", "User wants to stop engine.")
                        // checkAndSetAudioDevice 內部會處理停止引擎的邏輯
                        lifecycleScope.launch { checkAndSetAudioDevice() } // 即使是關閉，也調用一次以確保狀態正確

                        // 如果之前啟動了 SCO，現在應該關閉它
                        if (audioManager.isBluetoothScoOn) {
                            Log.d("Hark", "Stopping Bluetooth SCO as engine is being turned off.")
                            audioManager.stopBluetoothSco()
                            isScoAudioConnected = false // 立即更新狀態，儘管 receiver 也會收到通知
                            }
                        }
                    },
                    // Pass the JNI functions as method references
                    onSetBandGain = ::setBandGain,
                    onSetBandQ = ::setBandQ
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerAudioDeviceCallback()
        registerScoStateReceiver()

        if (isEngineRunningByUserIntent) {
            Log.d("Hark", "Resuming app with user intent to run engine.")
            // 觸發一次完整的檢查與啟動流程
            // 如果上次是因為藍牙耳機而運行的，嘗試重新連接 SCO
            if (isBluetoothScoHeadsetConnected() && !audioManager.isBluetoothScoOn) {
                Log.d("Hark", "onResume: Attempting to restart Bluetooth SCO.")
                audioManager.startBluetoothSco()
                // 等待 scoStateReceiver 回調
            } else {
                // 其他情況（有線耳機，或 SCO 已連接）
                Log.d("Hark", "onResume: Calling checkAndSetAudioDevice directly.")
                lifecycleScope.launch { checkAndSetAudioDevice() }
            }
        } else {
            Log.d("Hark", "Resuming app, user intent is engine off. Updating status.")
            viewModel.statusText.value = "狀態：已停用" // 確保UI同步
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterAudioDeviceCallback()
        unregisterReceiver(scoStateReceiver)

        if (isEngineActuallyRunning()) {
            Log.d("Hark", "Pausing app. Stopping engine.")
            stopEngine() // stopEngine() 應該只停止 JNI 引擎
            // viewModel.statusText.value = "狀態：已暫停" // 移到 checkAndSetAudioDevice 或引擎停止後
        }

        // App 退到背景時，如果 SCO 是開啟的，考慮停止它以釋放資源
        // 除非你的應用需要在背景持續使用 SCO
        if (audioManager.isBluetoothScoOn) {
            Log.d("Hark", "Pausing app. Stopping Bluetooth SCO.")
            audioManager.stopBluetoothSco()
            isScoAudioConnected = false
        }

        // 狀態更新應基於 isEngineRunningByUserIntent
        if (isEngineRunningByUserIntent) {
            viewModel.statusText.value = "狀態：已暫停 (請連接耳機以恢復)" // 或者更通用的暫停提示
        } else {
            viewModel.statusText.value = "狀態：已停用"
        }
        // 注意：isEngineRunningByUserIntent 保持不變，以便 onResume 時可以知道是否需要重啟
    }

    // --- 核心設備選擇與引擎管理邏輯 ---
    // 將函數標記為 suspend
private suspend fun checkAndSetAudioDevice() {
    deviceChangeMutex.withLock {
        Log.d("Hark", "Running checkAndSetAudioDevice under lock...")

        // 1. 檢查是否有任何形式的耳機作為「輸出」設備
        //    這是為了判斷是否可以安全地播放音訊而不打擾他人（或產生回授）
        val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val isAnyHeadphonesOutputConnected = outputDevices.any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || // SCO 通常也意味著輸出到耳機
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP    // A2DP 是高品質音訊輸出到藍牙設備
            // 你可以根據需要添加或移除類型，例如 TYPE_USB_DEVICE 如果它確實代表耳機輸出
        }
        Log.d("Hark", "Is any headphones output connected: $isAnyHeadphonesOutputConnected")

        // 2. 如果使用者意圖是開啟引擎，但「沒有任何耳機輸出」，則提示並停止
        if (isEngineRunningByUserIntent && !isAnyHeadphonesOutputConnected) {
            Log.w("Hark", "No headphones output detected. Forcing engine stop.")
            if (isEngineActuallyRunning()) {
                stopEngine()
            }
            viewModel.statusText.value = "狀態：請連接耳機"
            // 如果之前因為 SCO 連接問題而停止了藍牙，這裡可以考慮是否要再次嘗試連接
            // 但通常在此情況下，用戶應先連接耳機。
            if (audioManager.isBluetoothScoOn) { // 如果 SCO 意外開啟，也關掉
                audioManager.stopBluetoothSco()
                isScoAudioConnected = false
            }
            return@withLock // 終止後續流程
        }

        // 3. 選擇「輸入」設備 (如果需要啟動引擎或引擎已在運行)
        //    即使有耳機輸出，我們仍需選擇正確的輸入源。
        val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        var targetDeviceId = 0 // 預設為 0 (通常是內建麥克風)
        var deviceTypeName = "內建麥克風" // 預設名稱

        // 優先級 1: 已連接的藍牙 SCO (如果 isScoAudioConnected 為 true)
        if (isScoAudioConnected) { // isScoAudioConnected 應由 BroadcastReceiver 更新
            inputDevices.find { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }?.let {
                targetDeviceId = it.id
                deviceTypeName = "藍牙耳機"
                Log.d("Hark", "Input device selected: Bluetooth SCO (ID: $targetDeviceId)")
            }
        }

        // 優先級 2: USB 音訊設備 (耳機或麥克風)
        if (targetDeviceId == 0) { // 只有在藍牙 SCO 未選中時才檢查 USB
            // 首先查找 USB 耳麥 (同時具備輸入輸出)
            inputDevices.find { it.type == AudioDeviceInfo.TYPE_USB_HEADSET }?.let {
                targetDeviceId = it.id
                deviceTypeName = "USB 耳機"
                Log.d("Hark", "Input device selected: USB Headset (ID: $targetDeviceId)")
            } ?: inputDevices.find { it.type == AudioDeviceInfo.TYPE_USB_DEVICE }?.let {
                // 然後查找通用的 USB 音訊設備 (可能是獨立麥克風)
                // 注意：TYPE_USB_DEVICE 可能也包括沒有麥克風的 USB DAC，需要根據實際情況調整
                targetDeviceId = it.id
                deviceTypeName = "USB 麥克風/設備"
                Log.d("Hark", "Input device selected: USB Device (ID: $targetDeviceId)")
            }
        }

        // 優先級 3: 有線耳機 (3.5mm)
        if (targetDeviceId == 0) { // 只有在前兩者都未選中時才檢查有線耳機
            inputDevices.find { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }?.let {
                targetDeviceId = it.id
                deviceTypeName = "有線耳機 (3.5mm)"
                Log.d("Hark", "Input device selected: Wired Headset (ID: $targetDeviceId)")
            }
        }

        Log.d("Hark", "Final input device decision. Target Device: $deviceTypeName (ID: $targetDeviceId)")

        // 4. 根據最終結果和用戶意圖，管理引擎狀態
        val engineWasActuallyRunning = isEngineActuallyRunning()

        // 停止當前引擎（如果正在運行），以便使用新選擇的設備ID重新啟動
        // 或者如果用戶意圖是關閉，也需要停止。
        if (engineWasActuallyRunning) {
            stopEngine()
            Log.d("Hark", "Engine stopped to apply new settings or due to user intent.")
        }

        // 設定選擇的音訊輸入設備 ID
        setAudioInputDeviceId(targetDeviceId)
        Log.d("Hark", "Audio input device ID set to: $targetDeviceId")


        if (isEngineRunningByUserIntent) {
            // 只有在檢測到耳機輸出 (或沒有強制停止的情況下) 且用戶意圖開啟時才嘗試啟動
            if (isAnyHeadphonesOutputConnected) { // 再次確認，以防萬一狀態在鎖內改變 (可能性小但安全)
                Log.d("Hark", "Attempting to start engine...")
                startEngine()
                if (isEngineActuallyRunning()) {
                    viewModel.statusText.value = "狀態：運作中 ($deviceTypeName)"
                    Log.i("Hark", "Engine started successfully with $deviceTypeName")
                } else {
                    viewModel.statusText.value = "狀態：引擎啟動失敗"
                    Log.e("Hark", "Engine failed to start after setting device to $deviceTypeName (ID: $targetDeviceId)")
                    // isEngineRunningByUserIntent = false // 可以考慮如果啟動失敗，是否重置用戶意圖
                }
            } else {
                // 這個分支理論上不應該執行到，因為前面已經有 return@withLock
                // 但作為防禦性程式碼保留
                Log.w("Hark", "Engine start aborted as no headphones output detected (should have been caught earlier).")
                if (isEngineActuallyRunning()) stopEngine() // 確保停止
                viewModel.statusText.value = "狀態：請連接耳機"
            }
        } else {
            // 用戶意圖是關閉引擎
            // 引擎已在上面被停止 (if engineWasActuallyRunning)，這裡只需更新狀態
            viewModel.statusText.value = "狀態：已停用"
            Log.d("Hark", "User intent is to keep engine off. Status: Disabled.")
        }
    }
}

    // --- Helper Functions ---
    // 這個函數判斷是否有 SCO *兼容* 的藍牙耳機連接，但不一定代表 SCO *通道* 已開啟
    private fun isBluetoothScoHeadsetConnected(): Boolean {
        // 檢查是否有任何輸入設備是 TYPE_BLUETOOTH_SCO
        // 這表示有一個藍牙設備聲稱它支持 SCO (通常是耳麥)
        // 這不代表 SCO 音訊通道 *已經* 建立，只是表示有這個可能性
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS) // 或者 GET_DEVICES_ALL
        val scoCompatibleDeviceExists = devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        if (scoCompatibleDeviceExists) {
            Log.d("Hark_Debug", "isBluetoothScoHeadsetConnected: Found SCO compatible input device.")
        }

        // 有些情況下，輸出設備列表也可能包含 SCO 設備信息
        val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val scoCompatibleOutputExists = outputDevices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        if (scoCompatibleOutputExists) {
            Log.d("Hark_Debug", "isBluetoothScoHeadsetConnected: Found SCO compatible output device.")
        }

        return scoCompatibleDeviceExists || scoCompatibleOutputExists
    }

    private fun registerAudioDeviceCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val callback = object : android.media.AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                    Log.d("Hark", "Audio device added.")
                    lifecycleScope.launch { checkAndSetAudioDevice() }
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    Log.d("Hark", "Audio device removed.")
                    lifecycleScope.launch { checkAndSetAudioDevice() }
                }
            }
            audioManager.registerAudioDeviceCallback(callback, null)
            audioDeviceCallback = callback
        }
    }

    private fun unregisterAudioDeviceCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioDeviceCallback != null) {
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback as android.media.AudioDeviceCallback)
            audioDeviceCallback = null
        }
    }
}

@Composable
fun HarkAppScreen(
    viewModel: EqViewModel,
    audioManager: AudioManager?,
    isEngineOn: Boolean,
    onEngineStateChange: (Boolean) -> Unit,
    onSetBandGain: (bandIndex: Int, gain: Float) -> Unit, // Explicitly define the contract
    onSetBandQ: (bandIndex: Int, q: Float) -> Unit       // Explicitly define the contract
) {
    val context = LocalContext.current
    val isPermissionGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }

    // This launcher is for UI feedback, the main logic is in MainActivity's onCreate
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { /* isGranted -> you can update a UI state here if needed */ }
    )

    // This LaunchedEffect is good for one-time actions like checking permission on start
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val scrollState = rememberScrollState()
    val currentMode by viewModel.currentMode
    val statusText by viewModel.statusText

    // --- 修改：從 ViewModel 獲取當前模式的數據 ---
    val centerFrequencies = viewModel.currentCenterFrequencies
    val currentBandGains = viewModel.currentBandGains // 這是 List<MutableState<Float>>
    // 如果需要 Q 值，也類似地獲取: val currentBandQs = viewModel.currentBandQs

    val totalBandContainerWidth = UIConstants.BAND_CONTAINER_WIDTH * centerFrequencies.size

    // This effect syncs the ViewModel state to the JNI layer whenever the mode changes.
    LaunchedEffect(currentMode) {
        Log.d("HarkAppScreen", "Mode changed to: $currentMode. Syncing JNI state.")
        // Sync all 16 potential bands to ensure clean state
        for (i in 0 until 16) {
            val gain = viewModel.bandGains16.getOrNull(i)?.value ?: 0f
            val q = viewModel.bandQs16.getOrNull(i)?.value ?: EqViewModel.DEFAULT_Q
            // For 8-band mode, gains/Qs for bands 8-15 will be from the 16-band default values (likely 0 and default Q)
            // if the current mode is 8-band, we still set them to a neutral state.
            val gainForSync = if (currentMode == EqViewModel.EngineMode.BIQUAD_8_MIC && i >= 8) 0f else gain
            val qForSync = if (currentMode == EqViewModel.EngineMode.BIQUAD_8_MIC && i >= 8) EqViewModel.DEFAULT_Q else q
            onSetBandGain(i, gainForSync)
            onSetBandQ(i, qForSync)
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(vertical = 8.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    val activeColor = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
                    val inactiveColor = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)

                    Button(
                        onClick = { viewModel.currentMode.value = EqViewModel.EngineMode.BIQUAD_16_MIC },
                        colors = if (currentMode == EqViewModel.EngineMode.BIQUAD_16_MIC) activeColor else inactiveColor
                    ) { Text("16-Band Mode") }

                    Button(
                        onClick = { viewModel.currentMode.value = EqViewModel.EngineMode.BIQUAD_8_MIC },
                        colors = if (currentMode == EqViewModel.EngineMode.BIQUAD_8_MIC) activeColor else inactiveColor
                    ) { Text("8-Band Mode") }
                }
                Spacer(modifier = Modifier.height(8.dp))
                // Master switch
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("主開關")
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = isEngineOn,
                        onCheckedChange = onEngineStateChange,
                        enabled = isPermissionGranted
                    )
                }
                if (!isPermissionGranted) {
                    Text("請授予麥克風權限以啟用", color = MaterialTheme.colorScheme.error)
                }
                Text(text = statusText, style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(8.dp))
                // Reset button
                Button(onClick = {
                    viewModel.resetCurrentModeBands()
                    // The LaunchedEffect above will automatically sync the changes to JNI
                }) {
                    Text("重設等化器")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Equalizer UI
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .horizontalScroll(scrollState),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.Start
            ) {
                Column(
                    modifier = Modifier
                        .width(totalBandContainerWidth) // 使用計算出的總寬度
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Frequency labels
                    Row(modifier = Modifier.fillMaxWidth()) {
                        centerFrequencies.forEach { freq ->
                            Text(
                                text = formatFrequencyLabel(freq),
                                modifier = Modifier.width(UIConstants.BAND_CONTAINER_WIDTH),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    // The interactive curve display
                    EqualizerCurveDisplay(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        bandGains = currentBandGains,
                        centerFrequencies = centerFrequencies,
                        onDragBand = { bandIndex, newGain ->
                            viewModel.updateBandGain(bandIndex, newGain)
                            onSetBandGain(bandIndex, newGain)
                        }
                    )

                    Row(modifier = Modifier.fillMaxWidth()) {
                        // currentBandGains 是 List<MutableState<Float>>，所以直接用它
                        currentBandGains.forEach { gainState ->
                            Text(
                                text = String.format("%.1f dB", gainState.value),
                                modifier = Modifier.width(UIConstants.BAND_CONTAINER_WIDTH),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            audioManager?.let { SystemVolumeSlider(it) }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}


private fun formatFrequencyLabel(freq: Int): String  {
    if (freq < 1000) return freq.toString()
    val kHz = freq / 1000.0
    // Format to one decimal place, then remove ".0" if it's a whole number.
    // e.g., 1000 -> 1.0 -> "1", 1250 -> 1.25 -> "1.3", 1600 -> 1.6 -> "1.6"
    return String.format("%.1f", kHz).removeSuffix(".0") + "k"
}

@Composable
fun SystemVolumeSlider(audioManager: AudioManager) {
    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    var currentVolume by remember { mutableStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    var sliderPosition by remember { mutableStateOf(currentVolume.toFloat() / maxVolume) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text("系統音量")
        Slider(
            value = sliderPosition,
            onValueChange = {
                sliderPosition = it
                val newVolume = (it * maxVolume).toInt()
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                currentVolume = newVolume
            }
        )
        Text(text = "${(sliderPosition * 100).toInt()}%")
    }
}

@Composable
fun EqualizerCurveDisplay(
    modifier: Modifier = Modifier,
    bandGains: List<State<Float>>,
    centerFrequencies: List<Int>,
    onDragBand: (bandIndex: Int, gain: Float) -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val waveColor = primaryColor.copy(alpha = 0.3f)
    val totalWidthDp = UIConstants.BAND_CONTAINER_WIDTH * centerFrequencies.size

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(centerFrequencies) { // key1 ensures re-launch on band count change
                forEachGesture {
                    awaitPointerEventScope {
                        awaitFirstDown(requireUnconsumed = false).also {
                            it.consume()
                            val bandWidthPx = size.width / centerFrequencies.size.toFloat()
                            val bandIndex = (it.position.x / bandWidthPx).toInt().coerceIn(0, centerFrequencies.size - 1)
                            val gainRange = EqViewModel.MAX_GAIN_DB - EqViewModel.MIN_GAIN_DB
                            val calculatedGain = EqViewModel.MAX_GAIN_DB - (it.position.y / size.height) * gainRange
                            onDragBand(bandIndex, calculatedGain.coerceIn(EqViewModel.MIN_GAIN_DB, EqViewModel.MAX_GAIN_DB))
                        }

                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.all { !it.pressed }) break // Exit if all pointers are up

                            event.changes.firstOrNull()?.let {
                                it.consume()
                                val bandWidthPx = size.width / centerFrequencies.size.toFloat()
                                val bandIndex = (it.position.x / bandWidthPx).toInt().coerceIn(0, centerFrequencies.size - 1)
                                val gainRange = EqViewModel.MAX_GAIN_DB - EqViewModel.MIN_GAIN_DB
                                val calculatedGain = EqViewModel.MAX_GAIN_DB - (it.position.y / size.height) * gainRange
                                onDragBand(bandIndex, calculatedGain.coerceIn(EqViewModel.MIN_GAIN_DB, EqViewModel.MAX_GAIN_DB))
                            }
                        }
                    }
                }
            }
    ) {
        val path = Path()
        val bandWidth = size.width / centerFrequencies.size.toFloat()
        val gainRange = EqViewModel.MAX_GAIN_DB - EqViewModel.MIN_GAIN_DB
        if (gainRange <= 0) return@Canvas

        val zeroDbY = size.height * (1f - (0f - EqViewModel.MIN_GAIN_DB) / gainRange)
        drawLine(
            color = Color.Gray,
            start = Offset(0f, zeroDbY),
            end = Offset(size.width, zeroDbY),
            strokeWidth = 1.dp.toPx()
        )

        if (bandGains.isNotEmpty()) {
            val points = bandGains.mapIndexed { index, gainState ->
                val x = (index + 0.5f) * bandWidth
                val y = size.height * (1f - (gainState.value - EqViewModel.MIN_GAIN_DB) / gainRange)
                Offset(x, y.coerceIn(0f, size.height))
            }

            path.moveTo(points.first().x, points.first().y)
            points.forEach { path.lineTo(it.x, it.y) }

            drawPath(path = path, color = primaryColor, style = Stroke(width = 2.dp.toPx()))
            points.forEach { point ->
                drawCircle(color = primaryColor.copy(alpha = 0.7f), radius = 6.dp.toPx(), center = point)
            }
        }
    }
}

// Preview remains the same, but we need to provide mock functions for the new parameters
@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    HarkTheme {
        HarkAppScreen(
            viewModel = EqViewModel(),
            audioManager = null,
            isEngineOn = false,
            onEngineStateChange = {},
            onSetBandGain = { _, _ -> },
            onSetBandQ = { _, _ -> }
        )
    }
}