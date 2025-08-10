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
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.forEachGesture
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.hark.ui.theme.HarkTheme

// Object to hold all tunable UI parameters
object UIConstants {
    //val SLIDER_LENGTH: Dp = 250.dp
    //val SLIDER_THICKNESS: Dp = 60.dp
    val BAND_CONTAINER_WIDTH: Dp = 60.dp
}

class MainActivity : ComponentActivity() {

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
    private external fun isEngineActuallyRunning(): Boolean // 查詢Oboe引擎的真實狀態

    // --- Bluetooth SCO Management ---
    private val scoStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
            when (state) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                    isScoAudioConnected = true
                    Log.d("Hark", "Event: Bluetooth SCO Audio connected.")
                    checkAndSetAudioDevice()
                }
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    isScoAudioConnected = false
                    Log.d("Hark", "Event: Bluetooth SCO Audio disconnected.")
                    checkAndSetAudioDevice()
                }
                AudioManager.SCO_AUDIO_STATE_ERROR -> {
                    isScoAudioConnected = false // 即使錯誤，也更新 SCO 狀態
                    Log.e("Hark", "Event: Bluetooth SCO Audio error.")
                    checkAndSetAudioDevice() // 嘗試切換到其他可用設備
                }
            }
        }
    }

    private fun registerScoStateReceiver() {
        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        // Android 13 (TIRAMISU) and above require specifying receiver export status
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(scoStateReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(scoStateReceiver, filter)
        }
    }
    // --- End of Bluetooth SCO Management ---

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        System.loadLibrary("hark")

        // 檢查並請求 RECORD_AUDIO 權限
        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                Log.d("Hark", "RECORD_AUDIO permission granted.")
                // 權限授予後，可以進行初始化或重新檢查設備
                checkAndSetAudioDevice()
            } else {
                Log.w("Hark", "RECORD_AUDIO permission denied.")
                viewModel.statusText.value = "狀態：麥克風權限被拒絕"
                // 處理權限被拒絕的情況，例如提示用戶或禁用相關功能
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            // 如果權限已授予，可以提前做一次設備檢查
            // 但引擎的啟動仍應由用戶操作觸發
        }

        setContent {
            HarkTheme {
                HarkAppScreen(
                    viewModel = viewModel,
                    audioManager = audioManager,
                    isEngineOn = isEngineRunningByUserIntent, // UI 狀態綁定到用戶意圖
                    onEngineStateChange = { userWantsToRun ->
                        if (userWantsToRun) {
                            isEngineRunningByUserIntent = true
                            viewModel.statusText.value = "狀態：正在偵測音訊裝置..."

                            // 檢查是否需要啟動藍牙SCO
                            // 這裡的 isBluetoothHeadsetConnected() 可以更精確，僅檢查 SCO 兼容設備
                            if (isBluetoothScoHeadsetConnected() && !audioManager.isBluetoothScoOn && !isScoAudioConnected) {
                                viewModel.statusText.value = "狀態：正在連接藍牙..."
                                audioManager.startBluetoothSco()
                                // 等待 scoStateReceiver 回調，它會呼叫 checkAndSetAudioDevice
                            } else {
                                // 如果 SCO 已連線，或不使用藍牙，直接選擇設備並嘗試啟動引擎
                                checkAndSetAudioDevice()
                            }
                        } else {
                            isEngineRunningByUserIntent = false
                            if (isEngineActuallyRunning()) { // 先檢查引擎是否真的在跑
                                stopEngine()
                            }
                            if (audioManager.isBluetoothScoOn || isScoAudioConnected) {
                                audioManager.stopBluetoothSco()
                                // isScoAudioConnected 會在 scoStateReceiver 中被更新為 false
                            }
                            viewModel.statusText.value = "狀態：已停用"
                        }
                    },
                    jniSetBandGain = { band, gain -> setBandGain(band, gain) },
                    jniSetBandQ = { band, q -> setBandQ(band, q) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerAudioDeviceCallback()
        registerScoStateReceiver()
        // 當 App 恢復時，也檢查一次當前音訊設備狀態，以防在背景時發生變化
        // 這確保了 UI 狀態 (如 viewModel.statusText) 和實際設備狀態的一致性
        // 注意：這裡不主動啟動引擎，僅更新設備狀態和可能的 UI 反饋
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            checkAndSetAudioDevice()
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterAudioDeviceCallback()
        unregisterReceiver(scoStateReceiver)

        // 如果 App 退到背景且引擎是由用戶意圖啟動的
        if (isEngineRunningByUserIntent) {
            if (isEngineActuallyRunning()) {
                stopEngine()
            }
            if (audioManager.isBluetoothScoOn || isScoAudioConnected) {
                audioManager.stopBluetoothSco()
            }
            // 不需要在此處設置 isEngineRunningByUserIntent = false
            // 因為用戶的“意圖”並沒有改變，只是App暫停了。
            // 回到 App 時，onResume 中的 checkAndSetAudioDevice 會根據意圖恢復引擎 (如果適用)
            // 但通常更好的做法是明確停止，讓用戶在返回時重新啟動，以節省資源。
            // 為了與你原始的 onPause 邏輯一致，這裡也停止並更新狀態：
            isEngineRunningByUserIntent = false
            isScoAudioConnected = false // 既然 SCO 停了，就更新狀態
            viewModel.statusText.value = "狀態：已暫停 (App 背景)"
        }
    }

    // --- 核心設備選擇與引擎管理邏輯 ---
    private fun checkAndSetAudioDevice() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        var targetDeviceId: Int = 0 // 0 代表 Oboe 的預設/未指定設備 (通常是內建麥克風)
        var deviceTypeName = "內建麥克風"
        var deviceTypeDetail = "Default"

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w("Hark", "checkAndSetAudioDevice: RECORD_AUDIO permission not granted. Aborting.")
            viewModel.statusText.value = "狀態：無麥克風權限"
            // 如果引擎意外運行，則停止
            if (isEngineActuallyRunning()) stopEngine()
            return
        }

        // 1. 優先使用已連接的藍牙 SCO
        if (isScoAudioConnected) { // 依賴我們維護的 isScoAudioConnected 狀態
            devices.find { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }?.let {
                targetDeviceId = it.id
                deviceTypeName = "藍牙"
                deviceTypeDetail = "Bluetooth SCO (ID: ${it.id})"
                Log.d("Hark", "Target device selected: $deviceTypeDetail")
            } ?: run {
                // SCO 連接了，但在設備列表中找不到？這不應該發生，但作為防禦
                Log.w("Hark", "SCO connected but no SCO device found in input list. Forcing SCO off.")
                // 這種情況下，可能 SCO 狀態出錯，嘗試關閉它並重新評估
                audioManager.stopBluetoothSco() // isScoAudioConnected 會在 Receiver 中更新
                // 這裡不立即返回，讓後續邏輯選擇有線或內建
            }
        }

        // 2. 如果沒有藍牙 SCO (或 SCO 查找失敗)，檢查有線耳機
        if (targetDeviceId == 0 || !isScoAudioConnected) { // 確保 SCO 確實沒連上才檢查有線
            devices.find { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }?.let {
                targetDeviceId = it.id
                deviceTypeName = "有線耳機"
                deviceTypeDetail = "Wired Headset (ID: ${it.id})"
                Log.d("Hark", "Target device selected: $deviceTypeDetail")
            }
        }

        // 3. 如果以上都沒有，使用內建麥克風 (Oboe 通常會預設選取，但明確指定ID更好)
        if (targetDeviceId == 0) {
            devices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }?.let {
                targetDeviceId = it.id // 如果能找到內建麥克風的明確 ID
                deviceTypeDetail = "Built-in Mic (ID: ${it.id})"
            } ?: run {
                deviceTypeDetail = "Default/Built-in Mic (ID: 0)"
            }
            Log.d("Hark", "Target device selected: $deviceTypeDetail (fallback)")
        }

        // --- 引擎啟動/停止/重啟邏輯 ---
        val wasEngineActuallyRunning = isEngineActuallyRunning()

        // 如果目標設備改變，或者引擎狀態與用戶意圖不符，需要調整
        // 且 Oboe 引擎需要先停止才能安全地改變輸入設備
        if (wasEngineActuallyRunning) {
            Log.d("Hark", "Engine was running. Stopping it before setting new device or restarting.")
            stopEngine()
        }

        Log.d("Hark", "Setting audio input device ID to: $targetDeviceId ($deviceTypeDetail)")
        setAudioInputDeviceId(targetDeviceId) // 呼叫 JNI 設定 Oboe 輸入裝置

        if (isEngineRunningByUserIntent) {
            Log.d("Hark", "User intent is to run engine. Starting engine with $deviceTypeDetail.")
            startEngine() // 根據用戶意圖啟動引擎
            if (isEngineActuallyRunning()) { // 確認引擎真的啟動了
                viewModel.statusText.value = "狀態：運作中 ($deviceTypeName)"
            } else {
                // 如果 startEngine() 失敗 (雖然比較少見，但 JNI 可能有錯誤)
                viewModel.statusText.value = "狀態：引擎啟動失敗 ($deviceTypeName)"
                Log.e("Hark", "Engine failed to start with $deviceTypeDetail despite user intent.")
                // 這種情況下，可能需要將 isEngineRunningByUserIntent 設回 false 或提供更詳細錯誤
            }
        } else {
            // 用戶意圖是停止，並且我們已經在前面停止了引擎 (如果它在跑)
            // 這裡確保 UI 狀態正確
            if (!isEngineActuallyRunning()) { // 再次確認引擎已停止
                viewModel.statusText.value = "狀態：已停用"
                Log.d("Hark", "User intent is to stop engine. Engine confirmed stopped.")
            } else {
                // 這不應該發生，如果 isEngineRunningByUserIntent 是 false，引擎應該已經停了
                Log.w("Hark", "User intent is to stop, but engine is still running after stopEngine() and setAudioInputDeviceId(). Forcing stop again.")
                stopEngine() // 再次嘗試停止
                viewModel.statusText.value = "狀態：已停用 (強制)"
            }
        }
    }


    // 你可能需要一個方法來判斷 Oboe 引擎是否真的在運作 (而不是用戶的意圖 isEngineRunning)
    // 這取決於你的 C++ 引擎的實現
    private fun isOboeEngineActuallyRunning(): Boolean {
        // TODO: 實現這個方法，例如，檢查 C++ 層的一個狀態變數
        // external fun isEngineReallyRunning(): Boolean
        // return isEngineReallyRunning()
        return false // 暫時的佔位符
    }

    // --- Helper Functions ---
    // 這個函數判斷是否有 SCO *兼容* 的藍牙耳機連接，但不一定代表 SCO *通道* 已開啟
    private fun isBluetoothScoHeadsetConnected(): Boolean {
        // 僅檢查 TYPE_BLUETOOTH_SCO 更為準確，因為 A2DP 是用於高品質音訊輸出的，不適用於輸入。
        // 有些設備可能同時支持 A2DP 和 SCO，但我們關心的是 SCO 輸入能力。
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    }

    private fun registerAudioDeviceCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val callback = object : android.media.AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                    super.onAudioDevicesAdded(addedDevices) // 呼叫父類方法
                    Log.d("Hark", "Audio device added: ${addedDevices?.joinToString { it.productName.toString() + " type " + it.type }}")
                    checkAndSetAudioDevice()
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    super.onAudioDevicesRemoved(removedDevices) // 呼叫父類方法
                    Log.d("Hark", "Audio device removed: ${removedDevices?.joinToString { it.productName.toString() + " type " + it.type }}")
                    checkAndSetAudioDevice()
                }
            }
            audioManager.registerAudioDeviceCallback(callback, null) // Handler 為 null 表示在主線程回呼
            audioDeviceCallback = callback
        }
    }

    private fun unregisterAudioDeviceCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioDeviceCallback != null) {
            try {
                audioManager.unregisterAudioDeviceCallback(audioDeviceCallback as android.media.AudioDeviceCallback)
            } catch (e: Exception) {
                Log.e("Hark", "Error unregistering audio device callback", e)
            } finally {
                audioDeviceCallback = null
            }
        }
    }
}

@Composable
fun HarkAppScreen(
    viewModel: EqViewModel,
    audioManager: AudioManager?,
    isEngineOn: Boolean,
    onEngineStateChange: (Boolean) -> Unit,
    jniSetBandGain: (bandIndex: Int, gainDb: Float) -> Unit, // <--- 接收 JNI 函數
    jniSetBandQ: (bandIndex: Int, q_factor: Float) -> Unit    // <--- 接收 JNI 函數
) {
    val context = LocalContext.current
    var isPermissionGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted -> isPermissionGranted = isGranted }
    )

    LaunchedEffect(Unit) {
        if (!isPermissionGranted) {
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

    // --- 修改：LaunchedEffect 用於在模式切換時同步 JNI ---
    LaunchedEffect(currentMode, currentBandGains) { // 依賴 currentMode 和 currentBandGains
        Log.d("HarkAppScreen", "Mode changed to: $currentMode or gains data changed. Syncing JNI.")
        currentBandGains.forEachIndexed { index, gainState ->
            jniSetBandGain(index, gainState.value)
            // 如果 JNI 層的頻段數量是固定的 (例如總是16個)，
            // 並且當前模式的頻段較少 (例如8個)，您可能需要決定如何處理剩餘的頻段。
            // 一種可能是將它們的增益設置為0。
            // 但如果 JNI 的 setBandGain 只影響指定索引，這裡的遍歷是正確的。
        }
        // 同步 Q 值 (如果需要)
        viewModel.currentBandQs.forEachIndexed { index, qState ->
             jniSetBandQ(index, qState.value)
        }

        // 如果從16切到8，可能需要將8之後的頻段在JNI層重置 (如果JNI不會自動處理)
        if (currentMode == EqViewModel.EngineMode.BIQUAD_8_MIC && viewModel.centerFrequencies16.size > centerFrequencies.size) {
            for (i in centerFrequencies.size until viewModel.centerFrequencies16.size) {
                // 假設 JNI 層最多支持16個頻段
                jniSetBandGain(i, 0f) // 將未使用的頻段增益設為0
                jniSetBandQ(i, EqViewModel.DEFAULT_Q) // 將未使用的頻段Q設為預設  <--- POTENTIAL ISSUE HERE
            }
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
                    ) {
                        Text("16-Band Mode")
                    }
                    Button(
                        onClick = { viewModel.currentMode.value = EqViewModel.EngineMode.BIQUAD_8_MIC },
                        colors = if (currentMode == EqViewModel.EngineMode.BIQUAD_8_MIC) activeColor else inactiveColor
                    ) {
                        Text("8-Band Mode")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("主開關")
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = isEngineOn,
                        onCheckedChange = { newState ->
                            onEngineStateChange(newState)
                            // viewModel.statusText 的更新已經在 MainActivity 的 onEngineStateChange 中處理了
                        },
                        enabled = isPermissionGranted
                    )
                }
                if (!isPermissionGranted) {
                    Text("請授予麥克風權限以啟用", color = Color.Red)
                }
                Text(text = statusText, style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = {
                    viewModel.resetCurrentModeBands() // 重設 ViewModel 中的數據
                    // 將重設後的數據同步到 JNI
                    viewModel.currentBandGains.forEachIndexed { index, gainState ->
                        jniSetBandGain(index, gainState.value)
                    }
                    viewModel.currentBandQs.forEachIndexed { index, qState ->
                        jniSetBandQ(index, qState.value)
                    }
                    Log.d("HarkAppScreen", "Equalizer reset for mode: $currentMode")
                }) {
                    Text("重設等化器")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

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

                    EqualizerCurveDisplay(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        bandGains = currentBandGains, // <--- 傳遞 List<MutableState<Float>>
                        centerFrequencies = centerFrequencies,
                        onDragBand = { bandIndex, newGain ->
                            viewModel.updateBandGain(bandIndex, newGain) // 更新 ViewModel
                            jniSetBandGain(bandIndex, newGain)          // 更新 JNI
                            // Log.d("HarkAppScreen", "Band $bandIndex gain updated to $newGain for mode $currentMode")
                        }
                        // 如果Q值也通過拖拽調整，也需要類似的回調
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

private fun RowScope.formatFrequencyLabel(freq: Int): String  {
    if (freq < 1000) {
        return freq.toString()
    }
    // Perform floating point division
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
    bandGains: List<State<Float>>, // <--- 接收 List<State<Float>> (MutableState 也是 State)
    centerFrequencies: List<Int>,
    onDragBand: (bandIndex: Int, gain: Float) -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val waveColor = primaryColor.copy(alpha = 0.3f)

    // Calculate the total width required by the canvas based on its content
    val totalWidthDp = UIConstants.BAND_CONTAINER_WIDTH * centerFrequencies.size

    Canvas(
        modifier = modifier
            .width(totalWidthDp)
            .fillMaxHeight()
            .pointerInput(centerFrequencies, bandGains) { // 依賴項包含 bandGains
                forEachGesture {
                    awaitPointerEventScope {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val bandWidthPx = size.width / centerFrequencies.size.toFloat() // 確保是浮點數除法

                        var bandIndex = (down.position.x / bandWidthPx).toInt().coerceIn(0, centerFrequencies.size - 1)
                        var initialGainAtPointerDown = bandGains[bandIndex].value // 記錄按下時的增益

                        val change = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                            if (change.pressed) change.consume()
                        }

                        if (change != null && change.pressed) {
                            drag(down.id) { dragChange ->
                                dragChange.consume()

                                // 更新 bandIndex，以防用戶水平拖動到相鄰頻段 (可選，取決於期望的交互)
                                // bandIndex = (dragChange.position.x / bandWidthPx).toInt().coerceIn(0, centerFrequencies.size - 1)

                                // 計算增益變化量，基於垂直拖動距離
                                // 0dB 在畫布中間
                                val newY = dragChange.position.y.coerceIn(0f, size.height.toFloat())
                                val gainRange = EqViewModel.MAX_GAIN_DB - EqViewModel.MIN_GAIN_DB // 總增益範圍
                                val calculatedGain = EqViewModel.MAX_GAIN_DB - (newY / size.height) * gainRange

                                // 或者基於拖動的相對變化 (可能更直觀)
                                // val dragAmountY = dragChange.position.y - down.position.y // 相對於初始按下點的 Y 變化
                                // val gainChangeRatio = -dragAmountY / (size.height / 2f) // 假設畫布一半對應 MAX_GAIN_DB
                                // val calculatedGain = initialGainAtPointerDown + gainChangeRatio * EqViewModel.MAX_GAIN_DB

                                val newGain = calculatedGain.coerceIn(EqViewModel.MIN_GAIN_DB, EqViewModel.MAX_GAIN_DB)

                                onDragBand(bandIndex, newGain)
                            }
                        }
                    }
                }
            }
    ) {
        val path = Path()
        val bandWidth = size.width / centerFrequencies.size.toFloat()

        // 繪製中間的 0dB 線
        val zeroDbY = size.height * ( (EqViewModel.MAX_GAIN_DB - 0f) / (EqViewModel.MAX_GAIN_DB - EqViewModel.MIN_GAIN_DB) )
        drawLine(
            color = Color.Gray,
            start = Offset(0f, zeroDbY),
            end = Offset(size.width, zeroDbY),
            strokeWidth = 1.dp.toPx()
        )


        if (bandGains.isNotEmpty()) {
            val gainRange = EqViewModel.MAX_GAIN_DB - EqViewModel.MIN_GAIN_DB
            if (gainRange <= 0) return@Canvas // 防止除以零或負數

            var yPosition = size.height * ( (EqViewModel.MAX_GAIN_DB - bandGains[0].value) / gainRange )
            yPosition = yPosition.coerceIn(0f, size.height)
            path.moveTo(bandWidth * 0.5f, yPosition)

            bandGains.forEachIndexed { index, gainState ->
                val x = (index + 0.5f) * bandWidth
                var y = size.height * ( (EqViewModel.MAX_GAIN_DB - gainState.value) / gainRange )
                y = y.coerceIn(0f, size.height)

                if (index == 0) { // 對於第一個點，也要 moveTo，或者確保 path 從這裡開始
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
                drawCircle(
                    color = primaryColor.copy(alpha = 0.7f),
                    radius = 6.dp.toPx(),
                    center = Offset(x, y)
                )
            }
            drawPath(
                path = path,
                color = primaryColor,
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}

