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
    val SLIDER_LENGTH: Dp = 250.dp
    val SLIDER_THICKNESS: Dp = 60.dp
    val BAND_CONTAINER_WIDTH: Dp = 60.dp
}

class MainActivity : ComponentActivity() {

    private val viewModel: EqViewModel by viewModels()
    private lateinit var audioManager: AudioManager
    private var audioDeviceCallback: Any? = null
    private var isEngineRunning by mutableStateOf(false)
    private var isScoAudioConnected by mutableStateOf(false)


    private external fun startEngine()
    private external fun stopEngine()
    private external fun setBandGain(bandIndex: Int, gainDb: Float)
    private external fun setBandQ(bandIndex: Int, q_factor: Float)
    private external fun setAudioInputDeviceId(deviceId: Int)


    // --- Start of Bluetooth SCO Management ---

    private val scoStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
            when (state) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                    isScoAudioConnected = true
                    Log.d("Hark", "Bluetooth SCO Audio connected")
                    // SCO通道已連接！現在可以安全地設定設備並啟動引擎
                    checkAndSetAudioDevice() // 確保 Oboe 使用藍牙設備
                    // 如果引擎是因為等待 SCO 連接而尚未啟動，現在啟動它
                    if (isEngineRunning && !isOboeEngineActuallyRunning()) { // 你需要一個方法來檢查 Oboe 引擎是否真的在跑
                        startEngine()
                        viewModel.statusText.value = "狀態：運作中 (藍牙)"
                    }
                }
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    isScoAudioConnected = false
                    Log.d("Hark", "Bluetooth SCO Audio disconnected")
                    // SCO通道已斷開
                    // 如果引擎正在運行，可能需要停止或切換音訊來源
                    if (isEngineRunning) {
                        // 考慮在這裡停止引擎或嘗試切換到其他麥克風
                        // stopEngine() // 或者讓 checkAndSetAudioDevice 處理切換
                        viewModel.statusText.value = "狀態：藍牙已斷開"
                    }
                    checkAndSetAudioDevice() // 讓 Oboe 知道設備已更改
                    // 你可能需要在這裡處理藍牙斷線後，切回手機麥克風的邏輯
                    // 並且，如果之前是因為等待 SCO 而沒有啟動引擎，現在也不應該啟動
                }
                AudioManager.SCO_AUDIO_STATE_ERROR -> {
                    isScoAudioConnected = false
                    Log.e("Hark", "Bluetooth SCO Audio error")
                    // 通知使用者或記錄錯誤
                    if (isEngineRunning) {
                         // 可能需要停止引擎，因為預期的藍牙音訊無法使用
                        // stopEngine()
                        // onEngineStateChange(false) // 透過 ViewModel 更新狀態，會呼叫 stopEngine 和 stopBluetoothSco
                    }
                }
            }
        }
    }

    private fun registerScoStateReceiver() {
        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        registerReceiver(scoStateReceiver, filter)
    }
    // --- End of Bluetooth SCO Management ---

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        System.loadLibrary("hark")

        setContent {
            HarkTheme {
                HarkAppScreen(
                    viewModel = viewModel,
                    audioManager = audioManager,
                    isEngineOn = isEngineRunning, // isEngineRunning 反映用戶的意圖
                    onEngineStateChange = { userWantsToRunEngine ->
                        if (userWantsToRunEngine) {
                            isEngineRunning = true // 設定用戶意圖
                            viewModel.statusText.value = "狀態：正在連接藍牙..."
                            // 當用戶想啟動引擎時，我們先啟動藍牙SCO
                            if (!audioManager.isBluetoothScoOn && !isScoAudioConnected) {
                                audioManager.startBluetoothSco()
                                // 注意：我們不在這裡直接 startEngine()
                                // 而是等待 scoStateReceiver 通知我們連接成功後，再啟動
                            } else if (isScoAudioConnected) {
                                // 如果 SCO 已經連接，直接嘗試啟動引擎
                                checkAndSetAudioDevice() // 確保 Oboe 設備正確
                                if (!isOboeEngineActuallyRunning()) { // 檢查實際狀態
                                    startEngine()
                                }
                                viewModel.statusText.value = "狀態：運作中 (藍牙)"
                            } else {
                                // isBluetoothScoOn 為 true 但 isScoAudioConnected 為 false，說明正在連接中
                                // 等待廣播即可
                            }
                        } else {
                            isEngineRunning = false // 設定用戶意圖
                            stopEngine()
                            if (audioManager.isBluetoothScoOn || isScoAudioConnected) {
                                audioManager.stopBluetoothSco()
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
        registerAudioDeviceCallback() // 用於有線耳機等設備的插拔
        registerScoStateReceiver()   // 註冊 SCO 狀態廣播接收器

        // 可選：如果應用程式恢復時，藍牙耳機已連接，可以嘗試預先啟動 SCO
        // 但要注意，如果用戶此時並不打算使用麥克風，這可能會讓人困惑
        // 更穩妥的做法是等待用戶明確的操作 (例如點擊錄音按鈕)
        // if (isBluetoothHeadsetConnected() && !audioManager.isBluetoothScoOn && !isScoAudioConnected) {
        //     audioManager.startBluetoothSco()
        // }
    }

    override fun onPause() {
        super.onPause()
        unregisterAudioDeviceCallback()
        unregisterReceiver(scoStateReceiver) // 取消註冊 SCO 狀態廣播接收器

        if (isEngineRunning) { // 如果引擎(用戶意圖)仍在運行
            stopEngine() // 停止 Oboe 引擎
            isEngineRunning = false // 更新意圖狀態
            viewModel.statusText.value = "狀態：已停用"
        }
        if (audioManager.isBluetoothScoOn || isScoAudioConnected) {
            audioManager.stopBluetoothSco() // 確保釋放 SCO 資源
            isScoAudioConnected = false
        }
    }

    private fun isBluetoothHeadsetConnected(): Boolean {
        val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS or AudioManager.GET_DEVICES_INPUTS) // 或者分別獲取再合併
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // Android 6.0 (API 23) to Android 11 (API 30)
            // 分別獲取輸入和輸出設備，然後合併
            // 或者，如果只關心是否有 SCO 設備存在，檢查其中一個列表即可
            // 這裡我們檢查輸入設備，因為麥克風是 SCO 的關鍵部分
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        } else {
            // 對於 API 23 以下的版本，沒有 getDevices() 方法。
            // 你可能需要使用已被棄用的方法，或者接受無法精確檢測的限制。
            // 例如，可以嘗試使用 isBluetoothScoOn()，但它不保證耳機真的連接了。
            // 為了簡化，這裡返回 false，表示在舊版本上無法可靠檢測。
            return false // 或者實現舊版本的邏輯
        }
        return devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    }


    // 你可能需要一個方法來判斷 Oboe 引擎是否真的在運作 (而不是用戶的意圖 isEngineRunning)
    // 這取決於你的 C++ 引擎的實現
    private fun isOboeEngineActuallyRunning(): Boolean {
        // TODO: 實現這個方法，例如，檢查 C++ 層的一個狀態變數
        // external fun isEngineReallyRunning(): Boolean
        // return isEngineReallyRunning()
        return false // 暫時的佔位符
    }

    private fun registerAudioDeviceCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val callback = object : android.media.AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                    checkAndSetAudioDevice()
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    checkAndSetAudioDevice()
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

    private fun checkAndSetAudioDevice() {
        // Find the headset microphone
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        var preferredDeviceId: Int = 0 // 預設為 0 (通常代表主要/內建麥克風)
        val wasEngineActuallyRunning = isOboeEngineActuallyRunning() // 檢查引擎是否真的在跑

        // 優先使用藍牙 SCO 設備 (如果 SCO 已連接)
        if (isScoAudioConnected) { // 使用我們自己維護的 SCO 狀態
            val btDevice = devices.find { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            if (btDevice != null) {
                preferredDeviceId = btDevice.id
                Log.d("Hark", "Using Bluetooth SCO device ID: $preferredDeviceId")
            } else {
                Log.d("Hark", "SCO connected but no SCO device found in input list? Strange.")
                // 這種情況比較奇怪，可能需要進一步調試
            }
        } else {
            // 如果 SCO 未連接，檢查是否有有線耳機
            val wiredDevice = devices.find { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
            if (wiredDevice != null) {
                preferredDeviceId = wiredDevice.id
                Log.d("Hark", "Using Wired Headset device ID: $preferredDeviceId")
            } else {
                // 如果都沒有，則使用預設設備 (可能是手機麥克風)
                // 有些手機上，預設輸入設備的 id 可能不是0，可以查找 TYPE_BUILTIN_MIC
                val builtInMic = devices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                if (builtInMic != null) {
                    preferredDeviceId = builtInMic.id
                }
                Log.d("Hark", "Using Built-in Mic or default device ID: $preferredDeviceId")
            }
        }

        // 如果引擎之前真的在跑，先停止它，因為音訊設備即將改變
        if (wasEngineActuallyRunning) {
            stopEngine()
        }

        // 設定新的設備ID給 Oboe 引擎
        Log.d("Hark", "Setting audio input device ID to: $preferredDeviceId")
        setAudioInputDeviceId(preferredDeviceId) // 呼叫 JNI

        // 如果引擎之前就在跑 (用戶的意圖是啟動，並且 SCO 也允許)，現在把它重新啟動
        // 只有在用戶意圖是啟動引擎 (isEngineRunning)，並且新的首選設備不是預設/回退設備（除非沒有其他選擇）
        // 或者更簡單：如果用戶意圖是啟動，就嘗試用新設備啟動
        if (isEngineRunning && (isScoAudioConnected || preferredDeviceId != 0 /* 避免在 SCO 斷開且無其他設備時自動啟動預設麥克風，除非邏輯允許 */)) {
            // 確保只有在 SCO 連接時，或者有其他有效設備時才因為 checkAndSetAudioDevice 而重新啟動
            if (isScoAudioConnected || devices.any{it.id == preferredDeviceId && it.type != AudioDeviceInfo.TYPE_BUILTIN_MIC && preferredDeviceId !=0} ) {
                 startEngine()
                 // viewModel.statusText.value = "狀態：運作中" // 狀態更新應更精確
            } else if (!isScoAudioConnected && preferredDeviceId == 0 && devices.any{it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC}) {
                 // 如果 SCO 斷開，且切換到了內建麥克風，並且用戶意圖是啟動
                 startEngine() // 允許使用內建麥克風
                 viewModel.statusText.value = "狀態：運作中 (內建麥克風)"
            }
        } else if (isEngineRunning && !isScoAudioConnected && preferredDeviceId == 0) {
            // 用戶意圖是啟動，但 SCO 斷開，且回退到預設麥克風
            // 這裡可能需要提示用戶，或者根據產品邏輯決定是否自動用內建麥克風啟動
            viewModel.statusText.value = "狀態：藍牙已斷開，請檢查設備"
            // stopEngine() // 確保引擎已停止
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

