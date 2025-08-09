package com.example.hark

import android.Manifest
import android.content.Context
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

    private external fun startEngine()
    private external fun stopEngine()
    private external fun setBandGain(bandIndex: Int, gainDb: Float)
    private external fun setBandQ(bandIndex: Int, q_factor: Float)
    private external fun setAudioInputDeviceId(deviceId: Int)


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        System.loadLibrary("hark")

        setContent {
            HarkTheme {
                HarkAppScreen(
                    viewModel = viewModel,
                    audioManager = audioManager,
                    isEngineOn = isEngineRunning,
                    onEngineStateChange = { newState ->
                        isEngineRunning = newState
                        if (isEngineRunning) {
                            startEngine()
                        } else {
                            stopEngine()
                        }
                        viewModel.statusText.value = if (isEngineRunning) "狀態：運作中" else "狀態：已停用"
                    },
                    // 將 JNI 函數直接傳遞下去
                    jniSetBandGain = { band, gain -> setBandGain(band, gain) },
                    jniSetBandQ = { band, q -> setBandQ(band, q) }
                )
            }
        }
    }
    override fun onResume() {
        super.onResume()
        registerAudioDeviceCallback()
        checkAndSetAudioDevice()
    }

    override fun onPause() {
        super.onPause()
        unregisterAudioDeviceCallback()
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
        var headsetMicId: Int = 0
        val btDevice = devices.find { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        val wiredDevice = devices.find { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
        val wasRunning = isEngineRunning

        if (btDevice != null) {
            headsetMicId = btDevice.id
            // Log.d("Hark", "Bluetooth SCO Mic detected, ID: $headsetMicId")
        } else if (wiredDevice != null) {
            headsetMicId = wiredDevice.id
            // Log.d("Hark", "Wired Headset Mic detected, ID: $headsetMicId")
        } else {
            // Log.d("Hark", "No headset mic detected, using default.")
        }

        // 如果引擎正在跑，才需要先停止它
        if (wasRunning) {
            stopEngine()
        }

        // 設定新的設備ID (無論引擎是否在跑，都要先設定好)
        setAudioInputDeviceId(headsetMicId)

        // 如果引擎之前就在跑，現在把它重新啟動
        if (wasRunning) {
            startEngine()
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

