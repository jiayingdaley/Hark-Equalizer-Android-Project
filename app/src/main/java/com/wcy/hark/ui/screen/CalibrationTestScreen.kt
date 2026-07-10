package com.wcy.hark.ui.screen

import android.content.Intent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wcy.hark.ui.viewmodel.*
import kotlin.math.roundToInt

// =============================================================================
// Colour Palette — inherits DspTestScreen dark theme
// =============================================================================
private val BgPage      = Color(0xFF0F1015)
private val BgCard      = Color(0xFF1A1C24)
private val BgInput     = Color(0xFF2C2F3C)
private val AccentGreen = Color(0xFF81C784)
private val BgGreen     = Color(0xFF2E6B4E)
private val AccentRed   = Color(0xFFE57373)
private val BgRed       = Color(0xFF6B2E2E)
private val AccentAmber = Color(0xFFFFB74D)
private val AccentBlue  = Color(0xFF64B5F6)
private val TextPrimary = Color.White
private val TextMuted   = Color(0xFFA0A5B5)
private val TextDim     = Color(0xFF8A8F9F)
private val Divider     = Color(0xFF2C2F3C)

// =============================================================================
// Root Screen
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationTestScreen(
    viewModel: ExperimentViewModel,
    onBack: () -> Unit,
    onOpenEarphoneCalib: (() -> Unit)? = null
) {
    val scrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Surface one-shot ViewModel messages (permission / engine guards) as snackbars
    val userMessage by viewModel.userMessage
    LaunchedEffect(userMessage) {
        userMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.userMessage.value = null
        }
    }

    DisposableEffect(Unit) {
        // 1. Record the original engine state before entering experiment mode
        val wasEngineRunning = com.wcy.hark.audio.bridge.HarkAudioBridge.isEngineActuallyRunning()
        
        // 2. Put the audio engine in a silent standby experiment mode (bypasses regular mic/DSP)
        com.wcy.hark.audio.bridge.HarkAudioBridge.setExperimentModeActive(true)
        
        // 3. If the user had not enabled regular hearing aid mode (engine off),
        // boot the audio engine now so that the experiment signal generators can run.
        if (!wasEngineRunning) {
            com.wcy.hark.audio.bridge.HarkAudioBridge.startEngine()
        }
        
        onDispose {
            // 4a. Stop any measurement still running: leaving the screen must not
            // keep a sweep/burst/tone playing into the user's ears, and the state
            // flags must not stay stuck at "running" for the next entry.
            viewModel.stopWdrcSweep()
            viewModel.stopBurst()
            viewModel.stopOspl90()
            if (viewModel.calibToneRunning.value) viewModel.setCalibTone(false)

            // 4b. Deactivate the academic experiment mode state
            com.wcy.hark.audio.bridge.HarkAudioBridge.setExperimentModeActive(false)
            
            // 5. Restore the original engine state: if it was OFF before, stop the engine now.
            if (!wasEngineRunning) {
                com.wcy.hark.audio.bridge.HarkAudioBridge.stopEngine()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("📐 學術實驗測試模式", fontWeight = FontWeight.Bold,
                             color = TextPrimary, fontSize = 16.sp)
                        Text("Researcher-Only Measurement Panel",
                             color = TextMuted, fontSize = 11.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "返回", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF12131A))
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = BgPage
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ⚠️ Researcher disclaimer banner
            ResearcherBanner()

            // Card 1: Post-InputGain tap point monitor
            PostInputGainCard(viewModel)

            // Card 2: Calibration tone + measured calibration readout
            CalibrationSignalCard(viewModel, onOpenEarphoneCalib)

            // Card 3: Dual-mic recording + share
            DualMicRecordCard(viewModel)

            // Card 4: WDRC I/O automated sweep
            WdrcIoSweepCard(viewModel)

            // Card 5: Tone Burst
            ToneBurstCard(viewModel)

            // Card 6: OSPL90 log-swept chirp
            Ospl90Card(viewModel)
        }
    }
}

// =============================================================================
// Researcher Banner
// =============================================================================

@Composable
private fun ResearcherBanner() {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1B10)),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Science, null, tint = AccentAmber, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "研究者專屬模式 — 此頁面產生的測試訊號功率可能對聽力造成影響。" +
                "操作前請確認外部聲學儀器就位，並在耳機未佩戴時先核對輸出電平。",
                color = AccentAmber, fontSize = 11.sp, lineHeight = 16.sp
            )
        }
    }
}

// =============================================================================
// Card 1: Post-InputGain Tap Point
// =============================================================================

@Composable
private fun PostInputGainCard(vm: ExperimentViewModel) {
    val postGain by vm.postInputGainLevel
    ExCard(title = "📊 補償後輸入電平監控 (Post-InputGain Tap)") {
        Text(
            "量測點位於 Input Gain Compensation 輸出後、DC Blocker 進入前。\n" +
            "可用於驗證 +15 dB 手機麥克風補償是否正確套用。",
            color = TextDim, fontSize = 11.sp, lineHeight = 15.sp
        )
        Spacer(Modifier.height(12.dp))
        LevelMeterEx(label = "補償後輸入電平 (Post-InputGain)", valueDb = postGain)
    }
}

// =============================================================================
// Card 2: Calibration Tone & ETSPL
// =============================================================================

@Composable
private fun CalibrationSignalCard(vm: ExperimentViewModel, onOpenEarphoneCalib: (() -> Unit)?) {
    val freqHz      by vm.calibFreqHz
    val levelDbfs   by vm.calibLevelDbfs
    val running     by vm.calibToneRunning
    val models      by vm.earphoneModels
    val selModel    by vm.selectedEarphone
    val measuredSpl by vm.calibMeasuredDbSpl
    val estDbhl     by vm.estimatedOutputDbhl

    var showWriteField by remember { mutableStateOf(false) }
    var measuredSplText by remember { mutableStateOf("") }

    val freqOptions = listOf(250f, 500f, 1000f, 2000f, 3000f, 4000f, 6000f, 8000f)
    var freqExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }

    ExCard(title = "🎵 校正純音輸出 (Calibration Signal)") {
        // ── Frequency selector ──
        ExLabel("測試頻率 (Hz)")
        ExDropdown(
            label = "${freqHz.roundToInt()} Hz",
            expanded = freqExpanded,
            onToggle = { freqExpanded = it },
            items = freqOptions.map { "${it.roundToInt()} Hz" },
            onSelect = { idx ->
                vm.calibFreqHz.value = freqOptions[idx]
                vm.updateCalibCorrection()
            }
        )

        Spacer(Modifier.height(12.dp))

        // ── Level slider ──
        ExLabel("輸出電平 (dBFS): ${levelDbfs.roundToInt()} dBFS")
        Slider(
            value = levelDbfs,
            onValueChange = {
                vm.calibLevelDbfs.value = it
                vm.updateCalibCorrection()
                if (running) HarkBridgeHelper.updateCalibTone(vm)
            },
            valueRange = -40f..0f,
            steps = 39,
            colors = sliderColors()
        )

        Spacer(Modifier.height(12.dp))

        // ── Earphone selector ──
        ExLabel("耳機型號")
        ExDropdown(
            label = selModel,
            expanded = modelExpanded,
            onToggle = { modelExpanded = it },
            items = models,
            onSelect = { idx ->
                vm.selectEarphone(models[idx])
                vm.updateCalibCorrection()
            }
        )

        Spacer(Modifier.height(10.dp))

        // ── Measured calibration readout ──
        ExInfoRow(
            "實測 SPL Measured SPL @refDbfs",
            measuredSpl?.let { "${String.format("%.1f", it)} dBSPL" } ?: "未校準 Uncalibrated",
            if (measuredSpl != null) AccentBlue else AccentRed
        )
        ExInfoRow(
            "預估輸出 Est. Output",
            estDbhl?.let { "${String.format("%+.1f", it)} dB HL" } ?: "— (需先校準 needs calibration)",
            AccentAmber
        )

        if (onOpenEarphoneCalib != null) {
            TextButton(onClick = onOpenEarphoneCalib) {
                Icon(Icons.Default.Tune, null, tint = AccentGreen, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("開啟逐頻率耳機校準 Per-frequency Calibration", color = AccentGreen, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Play / Stop ──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.setCalibTone(!running) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (running) BgRed else BgGreen
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(if (running) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(6.dp))
                Text(if (running) "停止輸出" else "連續播放 (5s+)", color = TextPrimary)
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Write correction value ──
        TextButton(onClick = { showWriteField = !showWriteField }) {
            Icon(Icons.Default.Edit, null, tint = AccentBlue, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("寫入新校正值 (外部儀器讀數)", color = AccentBlue, fontSize = 12.sp)
        }

        if (showWriteField) {
            Spacer(Modifier.height(8.dp))
            Text("輸入外部聲壓計量測到的 dBSPL 數值：", color = TextMuted, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = measuredSplText,
                    onValueChange = { measuredSplText = it },
                    label = { Text("量測 SPL (dBSPL)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedLabelColor = AccentBlue,
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = TextDim,
                        cursorColor = AccentBlue
                    ),
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        measuredSplText.toFloatOrNull()?.let { vm.saveCalibCorrection(it) }
                        showWriteField = false
                        measuredSplText = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BgGreen),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("儲存", color = TextPrimary)
                }
            }
        }
    }
}

// =============================================================================
// Card 3: Dual-Mic Recording
// =============================================================================

@Composable
private fun DualMicRecordCard(vm: ExperimentViewModel) {
    val state    by vm.dualMicState
    val progress by vm.recordingProgressMs
    val file     by vm.lastRecordingFile
    val postGain by vm.postInputGainLevel
    val context  = LocalContext.current
    val isIdle   = state == RecordingState.IDLE

    ExCard(title = "🎤 雙收音源錄音比對 (Dual-Mic Recording)") {
        Text(
            "分別以手機麥克風 / 耳機麥克風（SCO）錄製 10 秒 WAV，存入 App 外部目錄。" +
            "錄製完成後可透過 Share Sheet 傳送至工作電腦進行 MATLAB 分析。",
            color = TextDim, fontSize = 11.sp, lineHeight = 15.sp
        )
        Spacer(Modifier.height(10.dp))
        LevelMeterEx("即時輸入電平 (補償後)", postGain)
        Spacer(Modifier.height(12.dp))

        when (state) {
            RecordingState.IDLE, RecordingState.DONE -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.startRecording(false) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A3A6B)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                        enabled = isIdle
                    ) {
                        Icon(Icons.Default.Mic, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("手機麥克風\n(10 秒)", fontSize = 11.sp, textAlign = TextAlign.Center,
                             color = TextPrimary)
                    }
                    Button(
                        onClick = { vm.startRecording(true) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E4A6B)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                        enabled = isIdle
                    ) {
                        Icon(Icons.Default.Headset, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("耳機SCO麥克風\n(10 秒)", fontSize = 11.sp, textAlign = TextAlign.Center,
                             color = TextPrimary)
                    }
                }

                // Share last file
                if (state == RecordingState.DONE && file != null) {
                    Spacer(Modifier.height(10.dp))
                    Text("已完成：${file!!.name}", color = AccentGreen, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = {
                            val uri = vm.getShareUri(file!!, context)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "audio/wav"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "匯出 WAV 錄音檔"))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A3A4A)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("📤 匯出至工作電腦 (Share Sheet)", color = TextPrimary, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { vm.resetRecordingState() }) {
                        Text("重設，準備下一次錄音", color = TextDim, fontSize = 11.sp)
                    }
                }
            }

            RecordingState.RECORDING_PHONE, RecordingState.RECORDING_HEADSET -> {
                val label = if (state == RecordingState.RECORDING_PHONE) "手機麥克風" else "耳機麥克風"
                Text("● 錄製中：$label", color = AccentRed, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (progress / 10000f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = AccentRed
                )
                Spacer(Modifier.height(4.dp))
                Text("${progress / 1000.0f} 秒 / 10 秒", color = TextMuted, fontSize = 12.sp)
            }
        }
    }
}

// =============================================================================
// Card 4: WDRC I/O Sweep
// =============================================================================

@Composable
private fun WdrcIoSweepCard(vm: ExperimentViewModel) {
    val state      by vm.wdrcSweepState
    val stepIdx    by vm.wdrcCurrentStepIdx
    val levelDbfs  by vm.wdrcCurrentLevelDbfs
    val settleMs   by vm.wdrcSettleTimeMs
    val elapsed    by vm.wdrcElapsedMs
    val isIdle     = state == WdrcSweepState.IDLE

    ExCard(title = "📈 WDRC I/O 曲線自動量測") {
        Text(
            "依序輸出 1kHz 純音（-60 ~ -5 dBFS，步進 5dB）。每階等待 WDRC 穩定後，\n" +
            "操作者以外部 HATS + 量測儀記錄輸出 SPL。App 僅提供進度顯示，不自行收音。",
            color = TextDim, fontSize = 11.sp, lineHeight = 15.sp
        )
        Spacer(Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF2C2F3C).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFFFFA000).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(10.dp)
        ) {
            Column {
                Text("⚠️ 量測設置提醒 (DSP 注入模式)", color = Color(0xFFFFB300), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "1. 請確保在「實驗調試面板」中【啟用 WDRC】方能量測到壓縮特性，否則量測結果將為完全線性的基線。\n" +
                    "2. 建議【關閉】降噪 (Noise Suppressor) 與暫態抑制 (Transient Suppressor)，避免非線性降噪模組將測試純音誤判為環境噪音而進行衰減，干擾量測精確度。\n" +
                    "3. 可對比「Bypass WDRC」與「開啟 WDRC」之掃頻曲線，以精確描繪 WDRC 拐點 (Knee-point) 與壓縮比效果。",
                    color = Color(0xFFE0E0E0), fontSize = 10.sp, lineHeight = 14.sp
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Settle time slider
        ExLabel("每階穩定等待時間：$settleMs ms（建議 ≥ 3000 ms）")
        Slider(
            value = settleMs.toFloat(),
            onValueChange = { vm.wdrcSettleTimeMs.value = it.roundToInt() },
            valueRange = 2000f..5000f,
            steps = 29,
            enabled = isIdle,
            colors = sliderColors()
        )

        Spacer(Modifier.height(12.dp))

        when (state) {
            WdrcSweepState.IDLE -> {
                Button(
                    onClick = { vm.startWdrcSweep() },
                    colors = ButtonDefaults.buttonColors(containerColor = BgGreen),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text("▶ 開始自動掃頻量測（共 ${vm.wdrcTotalSteps} 階）", color = TextPrimary)
                }
            }
            WdrcSweepState.SETTLING -> {
                // Progress display
                Box(
                    Modifier.fillMaxWidth().background(BgInput, RoundedCornerShape(8.dp))
                        .padding(16.dp)
                ) {
                    Column {
                        Text("第 ${stepIdx + 1} / ${vm.wdrcTotalSteps} 階",
                             color = AccentAmber, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("當前輸入電平：${levelDbfs.roundToInt()} dBFS",
                             color = TextPrimary, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(4.dp))
                        Text("等待穩定：${elapsed} ms / ${settleMs} ms",
                             color = TextDim, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { (elapsed.toFloat() / settleMs).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = AccentGreen
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { vm.stopWdrcSweep() },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed)
                ) { Text("⏹ 中止量測") }
            }
            WdrcSweepState.DONE -> {
                Text("✅ 量測完成，共 ${vm.wdrcTotalSteps} 階",
                     color = AccentGreen, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { vm.wdrcSweepState.value = WdrcSweepState.IDLE }) {
                    Text("重設，準備下一次量測", color = TextDim, fontSize = 11.sp)
                }
            }
        }
    }
}

// =============================================================================
// Card 5: Tone Burst
// =============================================================================

@Composable
private fun ToneBurstCard(vm: ExperimentViewModel) {
    val running  by vm.burstRunning
    val phase    by vm.burstPhase
    val rep      by vm.burstCurrentRep
    val remMs    by vm.burstPhaseRemMs
    val highDbfs by vm.burstHighDbfs
    val lowDbfs  by vm.burstLowDbfs
    val highMs   by vm.burstHighMs
    val lowMs    by vm.burstLowMs
    val count    by vm.burstRepeatCount

    ExCard(title = "⚡ 暫態響應 Tone Burst 量測") {
        Text("以 1kHz 依序播放高/低電平 Tone Burst，由外部儀器量測包絡線。",
             color = TextDim, fontSize = 11.sp)
        Spacer(Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF2C2F3C).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF64B5F6).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(10.dp)
        ) {
            Column {
                Text("💡 量測設置提醒 (DSP 注入模式)", color = Color(0xFF64B5F6), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "1. 本測試旨在評估 WDRC 的 Attack/Release 暫態響應。請確保在調試面板中【啟用 WDRC】與【Limiter】，以完整觀察包絡線的過渡情形。\n" +
                    "2. 建議【關閉】降噪 (Noise Suppressor)，防止降噪模組將交替的高低電平訊號誤判為突發性環境噪音，並進行非線性修飾，導致量測的時間常數失真。",
                    color = Color(0xFFE0E0E0), fontSize = 10.sp, lineHeight = 14.sp
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Parameters
        ExLabel("高電平：${highDbfs.roundToInt()} dBFS")
        Slider(value = highDbfs, onValueChange = { vm.burstHighDbfs.value = it },
               valueRange = -40f..0f, steps = 39, enabled = !running, colors = sliderColors())

        ExLabel("低電平：${lowDbfs.roundToInt()} dBFS")
        Slider(value = lowDbfs, onValueChange = { vm.burstLowDbfs.value = it },
               valueRange = -60f..-10f, steps = 49, enabled = !running, colors = sliderColors())

        ExLabel("高電平持續：$highMs ms")
        Slider(value = highMs.toFloat(), onValueChange = { vm.burstHighMs.value = it.roundToInt() },
               valueRange = 50f..1000f, steps = 18, enabled = !running, colors = sliderColors())

        ExLabel("低電平持續：$lowMs ms")
        Slider(value = lowMs.toFloat(), onValueChange = { vm.burstLowMs.value = it.roundToInt() },
               valueRange = 100f..2000f, steps = 18, enabled = !running, colors = sliderColors())

        ExLabel("重複次數：$count 次")
        Slider(value = count.toFloat(), onValueChange = { vm.burstRepeatCount.value = it.roundToInt() },
               valueRange = 1f..20f, steps = 18, enabled = !running, colors = sliderColors())

        Spacer(Modifier.height(12.dp))

        if (!running) {
            Button(
                onClick = { vm.startBurst() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5B3A8C)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.FlashOn, null)
                Spacer(Modifier.width(6.dp))
                Text("▶ 播放 Tone Burst", color = TextPrimary)
            }
        } else {
            // Live status
            val phaseLabel = when (phase) {
                BurstPhase.HIGH -> "▲ 高電平"
                BurstPhase.LOW  -> "▼ 低電平"
                else            -> "---"
            }
            val phaseColor = if (phase == BurstPhase.HIGH) AccentRed else AccentBlue
            Box(Modifier.fillMaxWidth().background(BgInput, RoundedCornerShape(8.dp)).padding(16.dp)) {
                Column {
                    Text(phaseLabel, color = phaseColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("第 $rep / $count 次重複 | 剩餘 ${remMs} ms",
                         color = TextMuted, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { vm.stopBurst() },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed)
            ) { Text("⏹ 中止") }
        }
    }
}

// =============================================================================
// Card 6: OSPL90 Log-Swept Chirp
// =============================================================================

@Composable
private fun Ospl90Card(vm: ExperimentViewModel) {
    val state        by vm.ospl90State
    val currentFreq  by vm.ospl90CurrentFreqHz
    val durationSec  by vm.ospl90DurationSec
    val levelDbfs    by vm.ospl90LevelDbfs
    val usePinkNoise by vm.ospl90UsePinkNoise
    val isIdle = state == Ospl90State.IDLE

    ExCard(title = "🔊 OSPL90 掃頻安全量測 (ANSI S3.22)") {
        Text(
            "對數掃頻 Log-swept Sine（符合 ANSI S3.22 swept pure tone 規定）\n" +
            "從 250 Hz 指數掃至 8000 Hz。操作者以外部 HATS + 量測儀在輸出端記錄最大輸出 SPL。",
            color = TextDim, fontSize = 11.sp, lineHeight = 15.sp
        )
        Spacer(Modifier.height(10.dp))

        // Signal type toggle
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("訊號類型：", color = TextPrimary, fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = !usePinkNoise,
                onClick = { vm.ospl90UsePinkNoise.value = false },
                label = { Text("對數掃頻 (Chirp)", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = BgGreen,
                    selectedLabelColor = TextPrimary
                )
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = usePinkNoise,
                onClick = { vm.ospl90UsePinkNoise.value = true },
                label = { Text("粉紅噪音", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFF4A3060),
                    selectedLabelColor = TextPrimary
                )
            )
        }

        Spacer(Modifier.height(10.dp))

        ExLabel("輸出電平：${levelDbfs.roundToInt()} dBFS")
        Slider(
            value = levelDbfs,
            onValueChange = { vm.ospl90LevelDbfs.value = it },
            valueRange = -40f..0f, steps = 39,
            enabled = isIdle, colors = sliderColors()
        )

        if (!usePinkNoise) {
            ExLabel("掃頻時長：$durationSec 秒")
            Slider(
                value = durationSec.toFloat(),
                onValueChange = { vm.ospl90DurationSec.value = it.roundToInt() },
                valueRange = 10f..60f, steps = 49,
                enabled = isIdle, colors = sliderColors()
            )
        }

        Spacer(Modifier.height(12.dp))

        when (state) {
            Ospl90State.IDLE -> {
                Button(
                    onClick = { vm.startOspl90() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3A6B)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.GraphicEq, null)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (usePinkNoise) "▶ 輸出粉紅噪音"
                        else "▶ 開始對數掃頻 ($durationSec 秒)",
                        color = TextPrimary
                    )
                }
            }
            Ospl90State.SWEEPING -> {
                Box(Modifier.fillMaxWidth().background(BgInput, RoundedCornerShape(8.dp)).padding(16.dp)) {
                    Column {
                        if (usePinkNoise) {
                            Text("🟣 粉紅噪音輸出中...", color = Color(0xFFCE93D8),
                                 fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Text("⬛ 掃頻中", color = AccentBlue, fontSize = 13.sp)
                            Text("目前頻率：${currentFreq.roundToInt()} Hz",
                                 color = TextPrimary, fontSize = 22.sp,
                                 fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Text("250 Hz → 8000 Hz | 共 $durationSec 秒", color = TextDim, fontSize = 11.sp)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { vm.stopOspl90() },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed)
                ) { Text("⏹ 停止") }
            }
            Ospl90State.DONE -> {
                Text("✅ 掃頻完成", color = AccentGreen, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { vm.ospl90State.value = Ospl90State.IDLE }) {
                    Text("重設", color = TextDim, fontSize = 11.sp)
                }
            }
        }
    }
}

// =============================================================================
// Shared UI Components
// =============================================================================

/** Card wrapper with consistent dark styling. */
@Composable
private fun ExCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = BgCard),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextMuted)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun ExLabel(text: String) {
    Text(text, color = TextPrimary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 2.dp))
}

@Composable
private fun ExInfoRow(label: String, value: String, valueColor: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextDim, fontSize = 12.sp)
        Text(value, color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.Bold,
             fontFamily = FontFamily.Monospace)
    }
}

/** Generic dropdown backed by a string list. */
@Composable
private fun ExDropdown(
    label: String,
    expanded: Boolean,
    onToggle: (Boolean) -> Unit,
    items: List<String>,
    onSelect: (Int) -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(BgInput)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        TextButton(onClick = { onToggle(true) }, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, color = TextPrimary, fontSize = 13.sp)
                Icon(Icons.Default.ArrowDropDown, null, tint = TextMuted)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onToggle(false) },
            modifier = Modifier.background(BgCard)
        ) {
            items.forEachIndexed { idx, name ->
                DropdownMenuItem(
                    text = { Text(name, color = TextPrimary, fontSize = 13.sp) },
                    onClick = { onSelect(idx); onToggle(false) }
                )
            }
        }
    }
}

/** Level meter matching DspTestScreen style. */
@Composable
private fun LevelMeterEx(label: String, valueDb: Float) {
    val progress = ((valueDb + 100f) / 100f).coerceIn(0f, 1f)
    val meterColor = when {
        valueDb > -6f  -> AccentRed
        valueDb > -18f -> AccentAmber
        else           -> AccentGreen
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = TextPrimary, fontSize = 11.sp)
            Text(String.format("%.1f dBFS", valueDb), color = meterColor, fontSize = 11.sp,
                 fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(BgInput)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress)
                    .clip(RoundedCornerShape(4.dp)).background(meterColor))
        }
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = AccentGreen,
    activeTrackColor = BgGreen,
    inactiveTrackColor = BgInput
)

// Helper object to avoid passing vm directly to lambdas in update callbacks
private object HarkBridgeHelper {
    fun updateCalibTone(vm: ExperimentViewModel) {
        com.wcy.hark.audio.bridge.HarkAudioBridge.setCalibTone(
            vm.calibFreqHz.value, vm.calibLevelDbfs.value, true
        )
    }
}
