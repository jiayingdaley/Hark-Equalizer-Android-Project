package com.wcy.hark.ui.screen

import android.media.AudioManager
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wcy.hark.audiometry.AudiometricToneGenerator
import com.wcy.hark.data.EqSettingsRepository
import com.wcy.hark.data.experiment.EarphoneCalibrationRepository
import com.wcy.hark.data.experiment.FreqCalibration
import com.wcy.hark.ui.viewmodel.ExperimentViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Dark palette consistent with CalibrationTestScreen
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

/**
 * EarphoneCalibrationScreen — 逐頻率耳機校準 Per-frequency Earphone Calibration
 *
 * Researcher flow: pick earphone model → step through the 8 audiometric
 * frequencies → play the calibration tone at refDbfs via the SAME AudioTrack
 * path used by the pure-tone test → enter the SPL read from an external
 * sound level meter / coupler → save. The pure-tone test then converts
 * target dB HL to dBFS through this table (+ RETSPL, ISO 389-1).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EarphoneCalibrationScreen(
    viewModel: ExperimentViewModel,
    repository: EqSettingsRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val calibRepo = remember { EarphoneCalibrationRepository(context.applicationContext) }
    val toneGen = remember { AudiometricToneGenerator() }
    val audioManager = remember { context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager }

    val frequencies = EarphoneCalibrationRepository.TEST_FREQUENCIES

    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedModel by remember { mutableStateOf("其他") }
    var table by remember { mutableStateOf<Map<Int, FreqCalibration>>(emptyMap()) }
    var freqIndex by remember { mutableStateOf(2) } // start at 1000 Hz
    var refDbfsText by remember { mutableStateOf("-20.0") }
    var measuredText by remember { mutableStateOf("") }
    var isPlaying by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var originalVolume by remember { mutableStateOf(-1) }

    val currentFreq = frequencies[freqIndex]

    fun reloadTable() {
        scope.launch(Dispatchers.IO) {
            val t = calibRepo.getAllCalibrations(selectedModel)
            withContext(Dispatchers.Main) { table = t }
        }
    }

    LaunchedEffect(Unit) {
        selectedModel = repository.getSelectedEarphoneFlow().first()
        withContext(Dispatchers.IO) {
            models = calibRepo.getEarphoneModels()
        }
        reloadTable()
    }

    fun stopTone() {
        toneGen.stop()
        isPlaying = false
        if (originalVolume >= 0) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            originalVolume = -1
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            toneGen.stop()
            toneGen.release()
            if (originalVolume >= 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("🎧 耳機校準", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
                        Text("Per-frequency Earphone Calibration", color = TextMuted, fontSize = 11.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { stopTone(); onBack() }) {
                        Icon(Icons.Default.ArrowBack, "返回 Back", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF12131A))
            )
        },
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
            // ── Instructions ──
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1B10)),
                 shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Text(
                    "校準流程 Procedure：播放校準音（與純音測驗相同的 AudioTrack 路徑、系統音量鎖定最大）→ " +
                    "以外部聲壓計 / 耦合器讀取 dBSPL → 輸入並儲存。完成全部 8 個頻率後，" +
                    "純音測驗結果即為校正後之 dB HL（RETSPL: ISO 389-1）。",
                    color = AccentAmber, fontSize = 11.sp, lineHeight = 16.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }

            // ── Earphone model ──
            CalCard(title = "耳機型號 Earphone Model") {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(BgInput)
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    TextButton(onClick = { modelExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(selectedModel, color = TextPrimary, fontSize = 13.sp)
                            Icon(Icons.Default.ArrowDropDown, null, tint = TextMuted)
                        }
                    }
                    DropdownMenu(
                        expanded = modelExpanded,
                        onDismissRequest = { modelExpanded = false },
                        modifier = Modifier.background(BgCard)
                    ) {
                        models.forEach { name ->
                            DropdownMenuItem(
                                text = { Text(name, color = TextPrimary, fontSize = 13.sp) },
                                onClick = {
                                    modelExpanded = false
                                    if (name != selectedModel) {
                                        stopTone()
                                        selectedModel = name
                                        viewModel.selectEarphone(name)
                                        scope.launch { repository.saveSelectedEarphone(name) }
                                        reloadTable()
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // ── Frequency stepper + measurement ──
            CalCard(title = "頻率校準 Frequency Calibration") {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = { if (freqIndex > 0) { stopTone(); freqIndex--; measuredText = "" } },
                        enabled = freqIndex > 0
                    ) { Icon(Icons.Default.ChevronLeft, "上一頻率 Prev", tint = if (freqIndex > 0) TextPrimary else TextDim) }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$currentFreq Hz", color = TextPrimary, fontSize = 26.sp,
                             fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        val cal = table[currentFreq]
                        Text(
                            cal?.measuredDbSpl?.let { "✓ 已校準 ${String.format("%.1f", it)} dBSPL @ ${String.format("%.0f", cal.refDbfs)} dBFS" }
                                ?: "未校準 Uncalibrated",
                            color = if (cal?.measuredDbSpl != null) AccentGreen else AccentRed,
                            fontSize = 11.sp
                        )
                    }

                    IconButton(
                        onClick = { if (freqIndex < frequencies.size - 1) { stopTone(); freqIndex++; measuredText = "" } },
                        enabled = freqIndex < frequencies.size - 1
                    ) { Icon(Icons.Default.ChevronRight, "下一頻率 Next", tint = if (freqIndex < frequencies.size - 1) TextPrimary else TextDim) }
                }

                Spacer(Modifier.height(12.dp))

                // refDbfs field
                OutlinedTextField(
                    value = refDbfsText,
                    onValueChange = { refDbfsText = it },
                    label = { Text("播放電平 Ref Level (dBFS)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = calFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isPlaying
                )

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = {
                        if (isPlaying) {
                            stopTone()
                        } else {
                            val refDbfs = refDbfsText.toFloatOrNull() ?: -20.0f
                            // Lock media volume to max — same clinical condition as the pure-tone test
                            originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            audioManager.setStreamVolume(
                                AudioManager.STREAM_MUSIC,
                                audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0
                            )
                            toneGen.play(
                                frequencyHz = currentFreq,
                                dbfs = refDbfs.coerceIn(-80f, 0f),
                                ear = AudiometricToneGenerator.Ear.BOTH,
                                pulsed = false,
                                durationSec = 2.0f,
                                loop = true
                            )
                            isPlaying = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (isPlaying) BgRed else BgGreen),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (isPlaying) "停止 Stop" else "播放校準音 Play Calibration Tone", color = TextPrimary)
                }

                Spacer(Modifier.height(12.dp))

                // Measured SPL entry
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = measuredText,
                        onValueChange = { measuredText = it },
                        label = { Text("量測 Measured SPL (dBSPL)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = calFieldColors(),
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val measured = measuredText.toFloatOrNull() ?: return@Button
                            val refDbfs = refDbfsText.toFloatOrNull() ?: -20.0f
                            stopTone()
                            scope.launch(Dispatchers.IO) {
                                calibRepo.saveMeasurement(selectedModel, currentFreq, refDbfs, measured)
                                val t = calibRepo.getAllCalibrations(selectedModel)
                                withContext(Dispatchers.Main) {
                                    table = t
                                    measuredText = ""
                                    // auto-advance to next uncalibrated / next frequency
                                    if (freqIndex < frequencies.size - 1) freqIndex++
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BgGreen),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.align(Alignment.CenterVertically)
                    ) { Text("儲存 Save", color = TextPrimary) }
                }
            }

            // ── Summary table ──
            CalCard(title = "校準總表 Calibration Summary") {
                val doneCount = frequencies.count { table[it]?.measuredDbSpl != null }
                Text(
                    if (doneCount == frequencies.size) "✓ 全部完成 All calibrated ($doneCount/8)"
                    else "進度 Progress: $doneCount / 8",
                    color = if (doneCount == frequencies.size) AccentGreen else AccentAmber,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text("Freq (Hz)", color = TextDim, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("SPL @ref", color = TextDim, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("RETSPL", color = TextDim, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("0 dB HL @", color = TextDim, fontSize = 11.sp, modifier = Modifier.weight(1f))
                }
                HorizontalDivider(color = BgInput)
                frequencies.forEach { f ->
                    val cal = table[f]
                    val retspl = EarphoneCalibrationRepository.RETSPL[f] ?: 0f
                    // dBFS needed for 0 dB HL: refDbfs + retspl − measured
                    val zeroHlDbfs = cal?.measuredDbSpl?.let { cal.refDbfs + retspl - it }
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text("$f", color = TextPrimary, fontSize = 11.sp,
                             fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                        Text(cal?.measuredDbSpl?.let { String.format("%.1f", it) } ?: "—",
                             color = if (cal?.measuredDbSpl != null) AccentBlue else AccentRed,
                             fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                        Text(String.format("%.1f", retspl), color = TextMuted, fontSize = 11.sp,
                             fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                        Text(zeroHlDbfs?.let { String.format("%.1f dBFS", it) } ?: "—",
                             color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                             modifier = Modifier.weight(1f))
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CalCard(title: String, content: @Composable ColumnScope.() -> Unit) {
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
private fun calFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedLabelColor = AccentBlue,
    unfocusedLabelColor = TextDim,
    focusedBorderColor = AccentBlue,
    unfocusedBorderColor = TextDim,
    cursorColor = AccentBlue
)
