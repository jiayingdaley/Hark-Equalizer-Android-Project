package com.wcy.hark.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.wcy.hark.ui.components.EqualizerCurveDisplay
import com.wcy.hark.ui.viewmodel.EqViewModel
import com.wcy.hark.ui.viewmodel.EarType
import com.wcy.hark.ui.viewmodel.AudioSourceMode

/**
 * HarkEqualizerScreen — 畫面 2 (EQUALIZER)
 *
 * 專注於 16 頻段左右耳獨立等化器微調。
 * 統一使用與主畫面一致的現代美感設計，支援左耳(藍)、右耳(紅)、雙耳(紫)調節與曲線繪製，
 * 並提供大字體水平 Slider 以及 +/- 微調按鈕，對高齡與聽損使用者極其友善。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HarkEqualizerScreen(
    viewModel: EqViewModel,
    isExperimentMode: Boolean = false,
    onBack: () -> Unit
) {
    // 使用者模式固定 8 段（對齊聽力檢查頻率）；實驗模式可切 8/16 段。
    // 底層儲存與 DSP 永遠是 16 段，8 段只是檢視層。
    var use16Bands by rememberSaveable { mutableStateOf(false) }
    val show16 = isExperimentMode && use16Bands
    val centerFrequencies = if (show16) viewModel.centerFrequencies16 else viewModel.centerFrequencies8
    val currentEar by viewModel.currentEarTab
    
    // Choose active band gains list according to current selection
    val activeBandGains = if (currentEar == EarType.LEFT) {
        viewModel.bandGainsLeft16
    } else {
        viewModel.bandGainsRight16
    }
    
    // Set dynamic theme color
    val themeColor = when (currentEar) {
        EarType.LEFT -> com.wcy.hark.ui.theme.HarkColors.EarLeft   // Clinical Blue
        EarType.RIGHT -> com.wcy.hark.ui.theme.HarkColors.EarRight  // Clinical Red
        EarType.BOTH -> com.wcy.hark.ui.theme.HarkColors.EarBoth   // Clinical Purple
    }

    val themeColorAnimated by animateColorAsState(targetValue = themeColor, label = "themeColor")

    val scrollState = rememberScrollState()

    // ── 頻率預覽音（逼逼聲）────────────────────────────────────────────
    // 調整時按著會播該頻率的脈衝音；放開後播一聲反映最終增益的長音。
    val toneGen = remember { com.wcy.hark.audiometry.AudiometricToneGenerator() }
    var previewingBand by remember { mutableStateOf(-1) }
    var previewingLevel by remember { mutableStateOf(Float.NaN) }
    DisposableEffect(Unit) {
        onDispose { toneGen.release() }
    }
    val previewEar = when (currentEar) {
        EarType.LEFT -> com.wcy.hark.audiometry.AudiometricToneGenerator.Ear.LEFT
        EarType.RIGHT -> com.wcy.hark.audiometry.AudiometricToneGenerator.Ear.RIGHT
        EarType.BOTH -> com.wcy.hark.audiometry.AudiometricToneGenerator.Ear.BOTH
    }
    // 基準電平 −35 dBFS；預覽音即時反映目前增益（±15 dB 內不會削波）。
    // 拖曳中以 1 dB 解析度更新，避免每個觸控事件都重建緩衝。
    val previewBaseDbfs = -35f
    fun startBandPreview(bandIndex: Int, freq: Int, gainDb: Float) {
        val level = (previewBaseDbfs + kotlin.math.round(gainDb)).coerceAtMost(0f)
        if (previewingBand != bandIndex) {
            // 換頻段才重建波形；音量變化只用 setVolume（波形不中斷 → 無爆音）
            previewingBand = bandIndex
            previewingLevel = level
            toneGen.play(freq, level, previewEar, pulsed = true, durationSec = 1.0f, loop = true,
                         pulseOnMs = 150.0f, pulseOffMs = 100.0f, bakeVolume = false)
        } else if (level != previewingLevel) {
            previewingLevel = level
            toneGen.setVolumeDbfs(level)
        }
    }
    fun endBandPreview(freq: Int, gainDb: Float) {
        previewingBand = -1
        previewingLevel = Float.NaN
        toneGen.play(freq, (previewBaseDbfs + gainDb).coerceAtMost(0f), previewEar,
                     pulsed = true, durationSec = 0.5f,
                     pulseOnMs = 150.0f, pulseOffMs = 100.0f)
    }

    // Gradient background matching Hark theme
    val backgroundGradient = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "等化器微調",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.ExtraBold
                            )
                        )
                        val modeText = if (viewModel.currentSourceMode.value == AudioSourceMode.MICROPHONE) {
                            "目前模式：環境輔聽 (麥克風)"
                        } else {
                            "目前模式：手機影音 (內部音訊)"
                        }
                        Text(
                            text = modeText,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回主畫面"
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.resetCurrentModeBands() }
                    ) {
                        Text(
                            text = "重設 0 dB",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(brush = backgroundGradient)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── 1. Ear Switch Tab ──────────────────────────────────────────
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                TabRow(
                    selectedTabIndex = when (currentEar) {
                        EarType.LEFT -> 0
                        EarType.BOTH -> 1
                        EarType.RIGHT -> 2
                    },
                    containerColor = Color.Transparent,
                    contentColor = themeColorAnimated
                ) {
                    val tabs = listOf(
                        Triple(EarType.LEFT, "左耳 (L)", com.wcy.hark.ui.theme.HarkColors.EarLeft),
                        Triple(EarType.BOTH, "雙耳連動", com.wcy.hark.ui.theme.HarkColors.EarBoth),
                        Triple(EarType.RIGHT, "右耳 (R)", com.wcy.hark.ui.theme.HarkColors.EarRight)
                    )

                    tabs.forEachIndexed { index, (earType, title, color) ->
                        val isSelected = currentEar == earType
                        Tab(
                            selected = isSelected,
                            onClick = { viewModel.currentEarTab.value = earType },
                            text = {
                                Text(
                                    title,
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) color else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        )
                    }
                }
            }

            // ── 2. Visual EQ Response Curve Card ──────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                ) {
                    // 曲線點數與下方顯示的頻段數一致（8 段模式只顯示 8 個點，
                    // 對應該組平均增益；16 段模式一對一），不與實際調整的
                    // 頻段數量脫節、造成畫面上多出對不上滑桿的點。
                    val curveBandGains = if (show16) {
                        activeBandGains
                    } else {
                        List(viewModel.centerFrequencies8.size) { i ->
                            derivedStateOf { viewModel.band8Gain(activeBandGains, i) }
                        }
                    }
                    EqualizerCurveDisplay(
                        modifier = Modifier.fillMaxSize(),
                        bandGains = curveBandGains,
                        centerFrequencies = centerFrequencies,
                        lineColor = themeColorAnimated,
                        onDragBand = { index, gain ->
                            if (show16) {
                                viewModel.updateBandGain(currentEar, index, gain)
                            } else {
                                viewModel.updateBand8Gain(currentEar, index, gain)
                            }
                        }
                    )
                }
            }

            // ── 3. Sliders & +/- Adjustments Scroll List ──────────────────
            val context = LocalContext.current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "頻段增益微調 (dB)",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                    if (isExperimentMode) {
                        Spacer(modifier = Modifier.width(8.dp))
                        FilterChip(
                            selected = !use16Bands,
                            onClick = { use16Bands = false },
                            label = { Text("8段", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(28.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        FilterChip(
                            selected = use16Bands,
                            onClick = { use16Bands = true },
                            label = { Text("16段", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }
                // 使用者模式：一鍵套用 DSL v5；實驗模式：下拉選 DSL v5 / NAL-R
                var fittingMenuExpanded by remember { mutableStateOf(false) }
                fun applyFitting(method: com.wcy.hark.audio.fitting.Prescriptions.Method) {
                    viewModel.applyFitting(method) { msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                }
                Box {
                    Button(
                        onClick = {
                            if (isExperimentMode) fittingMenuExpanded = true
                            else applyFitting(com.wcy.hark.audio.fitting.Prescriptions.Method.DSL_V5)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text(
                            text = if (isExperimentMode) "套用聽力圖 ▾" else "套用聽力圖 (DSL v5)",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    DropdownMenu(
                        expanded = fittingMenuExpanded,
                        onDismissRequest = { fittingMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("DSL v5 處方") },
                            onClick = {
                                fittingMenuExpanded = false
                                applyFitting(com.wcy.hark.audio.fitting.Prescriptions.Method.DSL_V5)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("NAL-R 處方") },
                            onClick = {
                                fittingMenuExpanded = false
                                applyFitting(com.wcy.hark.audio.fitting.Prescriptions.Method.NAL_R)
                            }
                        )
                    }
                }
            }

            // ── 高頻移頻（NLFC）：Rule 4 的使用者介面 ─────────────────────
            // 高頻重度損失、增益補不到位時的最後手段；預設關閉。
            // 僅作用於環境輔聽（麥克風）路徑，影音路徑無此處理。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "高頻移頻（NLFC）",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        "將 4.5 kHz 以上的聲音壓縮搬移到較低頻率，適用於高頻重度損失；僅環境輔聽有效",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = viewModel.testFrequencyLoweringEnabled.value,
                    onCheckedChange = { viewModel.setTestFrequencyLoweringEnabled(it) }
                )
            }

            // 套用處方後高頻增益被截斷 → 建議開啟移頻（漸進式補償）
            if (viewModel.suggestNlfc.value) {
                AlertDialog(
                    onDismissRequest = { viewModel.suggestNlfc.value = false },
                    title = { Text("建議開啟高頻移頻") },
                    text = {
                        Text(
                            "你的高頻聽力損失較重，增益補償已達上限、可能仍聽不清 ㄙ/ㄒ/ㄑ 等" +
                            "高頻子音。建議開啟移頻（NLFC），把高頻聲音搬移到你聽得較好的頻率範圍。"
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.setTestFrequencyLoweringEnabled(true)
                            viewModel.suggestNlfc.value = false
                        }) { Text("開啟") }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.suggestNlfc.value = false }) { Text("暫不") }
                    }
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                centerFrequencies.forEachIndexed { index, freq ->
                    // 16 段模式直接一對一；8 段模式顯示該組子頻段平均增益
                    val gainVal = if (show16) activeBandGains[index].value
                                  else viewModel.band8Gain(activeBandGains, index)
                    fun setGain(newVal: Float) {
                        if (show16) viewModel.updateBandGain(currentEar, index, newVal)
                        else viewModel.updateBand8Gain(currentEar, index, newVal)
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Frequency label (e.g. 250 Hz, 1k Hz)
                            Text(
                                text = formatFreqLabel(freq),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                modifier = Modifier.width(48.dp)
                            )

                            // Decrease button
                            IconButton(
                                onClick = {
                                    val newVal = (gainVal - 0.5f).coerceIn(EqViewModel.MIN_GAIN_DB, EqViewModel.MAX_GAIN_DB)
                                    setGain(newVal)
                                    endBandPreview(freq, newVal)
                                },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Remove,
                                    contentDescription = "減少",
                                    tint = themeColorAnimated.copy(alpha = 0.8f)
                                )
                            }

                            // Slider（拖曳時播該頻率脈衝音，放開播最終增益長音）
                            Slider(
                                value = gainVal,
                                onValueChange = { newVal ->
                                    startBandPreview(index, freq, newVal)
                                    setGain(newVal)
                                },
                                onValueChangeFinished = {
                                    val finalVal = if (show16) activeBandGains[index].value
                                                   else viewModel.band8Gain(activeBandGains, index)
                                    endBandPreview(freq, finalVal)
                                },
                                valueRange = EqViewModel.MIN_GAIN_DB..EqViewModel.MAX_GAIN_DB,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 2.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = themeColorAnimated,
                                    activeTrackColor = themeColorAnimated,
                                    inactiveTrackColor = themeColorAnimated.copy(alpha = 0.2f)
                                )
                            )

                            // Increase button
                            IconButton(
                                onClick = {
                                    val newVal = (gainVal + 0.5f).coerceIn(EqViewModel.MIN_GAIN_DB, EqViewModel.MAX_GAIN_DB)
                                    setGain(newVal)
                                    endBandPreview(freq, newVal)
                                },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "增加",
                                    tint = themeColorAnimated.copy(alpha = 0.8f)
                                )
                            }

                            // Gain value text display
                            Text(
                                text = if (gainVal >= 0f) "+%.1f".format(gainVal) else "%.1f".format(gainVal),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = if (gainVal != 0f) themeColorAnimated else MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier
                                    .width(44.dp),
                                textAlign = TextAlign.End
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

private fun formatFreqLabel(freq: Int): String {
    if (freq < 1000) return "${freq}Hz"
    val kHz = freq / 1000.0
    return String.format("%.1f", kHz).removeSuffix(".0") + "kHz"
}
