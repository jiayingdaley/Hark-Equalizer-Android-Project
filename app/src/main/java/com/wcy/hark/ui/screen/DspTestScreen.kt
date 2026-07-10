package com.wcy.hark.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wcy.hark.ui.viewmodel.EqViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DspTestScreen(
    viewModel: EqViewModel,
    onBack: () -> Unit,
    onNavigateToCalib: () -> Unit = {}   // Entry point to the Calibration Test mode
) {
    DisposableEffect(Unit) {
        viewModel.setDiagnosticsActive(true)
        onDispose {
            viewModel.setDiagnosticsActive(false)
        }
    }

    val scrollState = rememberScrollState()
    val dcBlocker = viewModel.testDcBlockerEnabled.value
    val noiseReduction = viewModel.testNoiseReductionEnabled.value
    val crossoverWdrc = viewModel.testCrossoverWdrcEnabled.value
    val limiter = viewModel.testLimiterEnabled.value
    val transientSuppressor = viewModel.testTransientSuppressorEnabled.value
    val ownVoiceDetector = viewModel.testOwnVoiceDetectorEnabled.value
    val frequencyLowering = viewModel.testFrequencyLoweringEnabled.value

    val masterGain = viewModel.testMasterGain.value
    val inputGainOffset = viewModel.testInputGainOffset.value
    val expanderThresh = viewModel.testWdrcExpanderThreshold.value
    val limiterThresh = viewModel.testLimiterThreshold.value
    val limiterRelease = viewModel.testLimiterRelease.value

    val sharingMode = viewModel.testSharingModeOverride.value
    val inputPreset = viewModel.testInputPresetOverride.value

    val diagIn = viewModel.diagInputLevel.value
    val diagOut = viewModel.diagOutputLevel.value
    val diagWouldBlock = viewModel.diagWouldBlockRate.value
    val diagInXRun = viewModel.diagInputXRun.value
    val diagOutXRun = viewModel.diagOutputXRun.value

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DSP 測試與硬體診斷面板", fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF12131A)
                )
            )
        },
        containerColor = Color(0xFF0F1015)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section 1: Signal Chain Flowchart (訊號鏈流程圖)
            SignalChainFlowchart(
                dcBlocker = dcBlocker,
                noiseReduction = noiseReduction,
                crossoverWdrc = crossoverWdrc,
                limiter = limiter,
                transientSuppressor = transientSuppressor,
                ownVoiceDetector = ownVoiceDetector,
                frequencyLowering = frequencyLowering
            )

            // Section 2: Real-time Diagnostics (即時訊號診斷)
            DiagnosticsPanel(
                diagIn = diagIn,
                diagOut = diagOut,
                wouldBlock = diagWouldBlock,
                inXRun = diagInXRun,
                outXRun = diagOutXRun
            )

            // Section 3: Bypass Controls (濾波器開關)
            BypassControlsCard(
                dcBlocker = dcBlocker,
                noiseReduction = noiseReduction,
                crossoverWdrc = crossoverWdrc,
                limiter = limiter,
                transientSuppressor = transientSuppressor,
                ownVoiceDetector = ownVoiceDetector,
                frequencyLowering = frequencyLowering,
                viewModel = viewModel
            )

            // Section 4: Granular Parameter Tuning (細緻參數微調)
            ParameterTuningCard(
                masterGain = masterGain,
                inputGainOffset = inputGainOffset,
                expanderThresh = expanderThresh,
                limiterThresh = limiterThresh,
                limiterRelease = limiterRelease,
                viewModel = viewModel
            )

            // Section 5: Oboe Stream Overrides (串流硬體配置)
            StreamConfigCard(
                sharingMode = sharingMode,
                inputPreset = inputPreset,
                onApply = { mode, preset ->
                    viewModel.applyStreamOverrides(mode, preset)
                }
            )

            // Section 6: Hardware Troubleshooting Guide (硬體除錯指南)
            TroubleshootingGuideCard()

            // (學術實驗測試模式入口已移至實驗模式主畫面)
        }  // end Column
    }  // end Scaffold
}  // end DspTestScreen

@Composable
fun SignalChainFlowchart(
    dcBlocker: Boolean,
    noiseReduction: Boolean,
    crossoverWdrc: Boolean,
    limiter: Boolean,
    transientSuppressor: Boolean,
    ownVoiceDetector: Boolean,
    frequencyLowering: Boolean
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1C24)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("音訊訊號處理鏈流程圖 (Signal Chain)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFA0A5B5))
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FlowBlock("輸入", true)
                FlowArrow()
                FlowBlock("DC濾除", dcBlocker)
                FlowArrow()
                FlowBlock("脈衝降噪", transientSuppressor)
                FlowArrow()
                FlowBlock("降噪", noiseReduction)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FlowArrowVertical()
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FlowBlock("移頻", frequencyLowering)
                FlowArrow()
                FlowBlock("分頻壓縮", crossoverWdrc)
                FlowArrow()
                FlowBlock("自我語音", ownVoiceDetector)
                FlowArrow()
                FlowBlock("安全限幅", limiter)
                FlowArrow()
                FlowBlock("輸出", true)
            }
        }
    }
}

@Composable
fun FlowBlock(name: String, enabled: Boolean) {
    val bgColor by animateColorAsState(if (enabled) Color(0xFF2E6B4E) else Color(0xFF6B2E2E))
    val textColor = Color.White
    Box(
        modifier = Modifier
            .width(56.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = textColor,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun FlowArrow() {
    Text("→", color = Color(0xFF505565), fontSize = 16.sp, fontWeight = FontWeight.Bold)
}

@Composable
fun FlowArrowVertical() {
    Text("↓", color = Color(0xFF505565), fontSize = 16.sp, fontWeight = FontWeight.Bold)
}

@Composable
fun DiagnosticsPanel(
    diagIn: Float,
    diagOut: Float,
    wouldBlock: Float,
    inXRun: Int,
    outXRun: Int
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1C24)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("即時效能與訊號健康度診斷", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFA0A5B5))
            Spacer(modifier = Modifier.height(16.dp))

            // Level Meters
            LevelMeter(label = "麥克風輸入電平 (Mic Raw Input)", valueDb = diagIn)
            Spacer(modifier = Modifier.height(12.dp))
            LevelMeter(label = "耳機輸出電平 (Headphone Output)", valueDb = diagOut)

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = Color(0xFF2C2F3C))
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // WouldBlock Pct
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text("WouldBlock 阻礙率", fontSize = 12.sp, color = Color(0xFF8A8F9F))
                    Spacer(modifier = Modifier.height(4.dp))
                    val color = when {
                        wouldBlock > 5.0f -> Color(0xFFE57373)
                        wouldBlock > 0.1f -> Color(0xFFFFB74D)
                        else -> Color(0xFF81C784)
                    }
                    Text(String.format("%.2f%%", wouldBlock), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color)
                }

                // Input XRuns
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text("輸入端 XRuns", fontSize = 12.sp, color = Color(0xFF8A8F9F))
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(inXRun.toString(), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (inXRun > 0) Color(0xFFFFB74D) else Color.White)
                }

                // Output XRuns
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text("輸出端 XRuns", fontSize = 12.sp, color = Color(0xFF8A8F9F))
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(outXRun.toString(), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (outXRun > 0) Color(0xFFFFB74D) else Color.White)
                }
            }
        }
    }
}

@Composable
fun LevelMeter(label: String, valueDb: Float) {
    val progress = ((valueDb + 100f) / 100f).coerceIn(0f, 1f)
    val meterColor = when {
        valueDb > -6f -> Color(0xFFE57373) // Red zone
        valueDb > -18f -> Color(0xFFFFB74D) // Yellow zone
        else -> Color(0xFF81C784) // Green zone
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, fontSize = 12.sp, color = Color.White)
            Text(String.format("%.1f dBFS", valueDb), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = meterColor)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color(0xFF2C2F3C))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress)
                    .clip(RoundedCornerShape(5.dp))
                    .background(meterColor)
            )
        }
    }
}

@Composable
fun BypassControlsCard(
    dcBlocker: Boolean,
    noiseReduction: Boolean,
    crossoverWdrc: Boolean,
    limiter: Boolean,
    transientSuppressor: Boolean,
    ownVoiceDetector: Boolean,
    frequencyLowering: Boolean,
    viewModel: EqViewModel
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1C24)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("訊號處理模組旁路開關 (Bypass)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFA0A5B5))
            Spacer(modifier = Modifier.height(12.dp))

            BypassSwitchRow("直流偏壓濾除 (DC Blocker)", dcBlocker) { viewModel.setTestDcBlockerEnabled(it) }
            BypassSwitchRow("時域脈衝抑制 (Transient Suppressor)", transientSuppressor) { viewModel.setTestTransientSuppressorEnabled(it) }
            BypassSwitchRow("背景降噪 (Noise Suppressor)", noiseReduction) { viewModel.setTestNoiseReductionEnabled(it) }
            BypassSwitchRow("移頻／非線性頻率壓縮 (NLFC)", frequencyLowering) { viewModel.setTestFrequencyLoweringEnabled(it) }
            BypassSwitchRow("多頻段分頻與動態壓縮 (Crossover / WDRC)", crossoverWdrc) { viewModel.setTestCrossoverWdrcEnabled(it) }
            BypassSwitchRow("自我語音堵耳管理 (Own Voice Detector)", ownVoiceDetector) { viewModel.setTestOwnVoiceDetectorEnabled(it) }
            BypassSwitchRow("最大輸出限制器 (MPO Limiter)", limiter) { viewModel.setTestLimiterEnabled(it) }
        }
    }
}

@Composable
fun BypassSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 13.sp)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF81C784),
                checkedTrackColor = Color(0xFF2E6B4E),
                uncheckedThumbColor = Color(0xFFE57373),
                uncheckedTrackColor = Color(0xFF6B2E2E)
            )
        )
    }
}

@Composable
fun ParameterTuningCard(
    masterGain: Float,
    inputGainOffset: Float,
    expanderThresh: Float,
    limiterThresh: Float,
    limiterRelease: Float,
    viewModel: EqViewModel
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1C24)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("DSP 核心參數微調", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFA0A5B5))
            Spacer(modifier = Modifier.height(16.dp))

            // Master Gain
            ParameterSlider(
                label = "主音量增益 (Master Gain)",
                value = masterGain,
                valueRange = 0.0f..2.0f,
                displayValue = String.format("%.2f x", masterGain),
                onValueChange = { viewModel.setTestMasterGain(it) }
            )

            // Input Gain Offset
            ParameterSlider(
                label = "輸入源增益補償 (Input Gain Offset)",
                value = inputGainOffset,
                valueRange = -12.0f..12.0f,
                displayValue = String.format("%+.1f dB", inputGainOffset),
                onValueChange = { viewModel.setTestInputGainOffset(it) }
            )

            // Expander Threshold
            ParameterSlider(
                label = "WDRC 擴展器門檻 (Expander Threshold)",
                value = expanderThresh,
                valueRange = -90.0f..-40.0f,
                displayValue = String.format("%.0f dBFS", expanderThresh),
                onValueChange = { viewModel.setTestWdrcExpanderThreshold(it) }
            )

            // Limiter Threshold
            ParameterSlider(
                label = "MPO 限制門檻 (Limiter Threshold)",
                value = limiterThresh,
                valueRange = -10.0f..0.0f,
                displayValue = String.format("%.1f dBFS", limiterThresh),
                onValueChange = { viewModel.setTestLimiterParameters(it, limiterRelease) }
            )

            // Limiter Release
            ParameterSlider(
                label = "限制器釋放時間 (Limiter Release)",
                value = limiterRelease,
                valueRange = 5.0f..500.0f,
                displayValue = String.format("%.0f ms", limiterRelease),
                onValueChange = { viewModel.setTestLimiterParameters(limiterThresh, it) }
            )
        }
    }
}

@Composable
fun ParameterSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    displayValue: String,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = Color.White, fontSize = 13.sp)
            Text(displayValue, color = Color(0xFF81C784), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF81C784),
                activeTrackColor = Color(0xFF2E6B4E),
                inactiveTrackColor = Color(0xFF2C2F3C)
            )
        )
    }
}

@Composable
fun StreamConfigCard(
    sharingMode: Int,
    inputPreset: Int,
    onApply: (Int, Int) -> Unit
) {
    var selectedSharingMode by remember { mutableStateOf(sharingMode) }
    var selectedInputPreset by remember { mutableStateOf(inputPreset) }

    val sharingModes = listOf("系統預設策略", "共享模式 (Shared)", "獨占模式 (Exclusive)")
    val inputPresets = listOf("系統預設策略", "語音通訊 (Voice Comm)", "語音識別 (Voice Rec)", "錄影模式 (Camcorder)", "無處理原始模式 (Unprocessed)")

    var sharingExpanded by remember { mutableStateOf(false) }
    var presetExpanded by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1C24)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Oboe 串流物理配置與重啟 (Stream Config)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFA0A5B5))
            Spacer(modifier = Modifier.height(16.dp))

            // Sharing Mode Dropdown
            Text("共享模式 (Sharing Mode)", color = Color.White, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF2C2F3C))
                    .clickable { sharingExpanded = true }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(sharingModes[selectedSharingMode], color = Color.White, fontSize = 14.sp)
                DropdownMenu(
                    expanded = sharingExpanded,
                    onDismissRequest = { sharingExpanded = false },
                    modifier = Modifier.background(Color(0xFF1A1C24))
                ) {
                    sharingModes.forEachIndexed { index, name ->
                        DropdownMenuItem(
                            text = { Text(name, color = Color.White) },
                            onClick = {
                                selectedSharingMode = index
                                sharingExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Input Preset Dropdown
            Text("輸入預設模式 (Input Preset)", color = Color.White, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF2C2F3C))
                    .clickable { presetExpanded = true }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(inputPresets[selectedInputPreset], color = Color.White, fontSize = 14.sp)
                DropdownMenu(
                    expanded = presetExpanded,
                    onDismissRequest = { presetExpanded = false },
                    modifier = Modifier.background(Color(0xFF1A1C24))
                ) {
                    inputPresets.forEachIndexed { index, name ->
                        DropdownMenuItem(
                            text = { Text(name, color = Color.White) },
                            onClick = {
                                selectedInputPreset = index
                                presetExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = { onApply(selectedSharingMode, selectedInputPreset) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E6B4E)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = "Apply")
                Spacer(modifier = Modifier.width(8.dp))
                Text("安全重啟 Oboe 串流 (Apply & Restart)", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun TroubleshootingGuideCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1B18)),
        border = BorderStroke(1.dp, Color(0xFF4D3A25)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "Info",
                tint = Color(0xFFFFB74D),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text("🎧 鐵三角等 USB 耳機斷續排障指引", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFB74D))
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "當使用鐵三角 ATH-CKS330NC 或其他 USB DAC 耳機時，若聲音斷續，通常是因「獨占模式 (Exclusive)」下 USB 物理時脈與手機內建 Codec 有細微時差，導致 Oboe 來不及寫入/讀取而觸發大量 WouldBlock 阻礙。\n\n" +
                    "💡 解法：\n" +
                    "1. 將上方「共享模式」設為「共享模式 (Shared)」。\n" +
                    "2. 將「輸入預設」設為「語音通訊 (Voice Comm)」或「語音識別 (Voice Rec)」。\n" +
                    "3. 點擊「安全重啟」即可消除斷續，享受平滑穩定的低延遲音訊。",
                    fontSize = 12.sp,
                    color = Color(0xFFD0D5E5),
                    lineHeight = 18.sp
                )
            }
        }
    }
}

