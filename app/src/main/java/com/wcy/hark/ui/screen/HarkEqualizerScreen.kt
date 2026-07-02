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
    onBack: () -> Unit
) {
    val centerFrequencies = viewModel.centerFrequencies16
    val currentEar by viewModel.currentEarTab
    
    // Choose active band gains list according to current selection
    val activeBandGains = if (currentEar == EarType.LEFT) {
        viewModel.bandGainsLeft16
    } else {
        viewModel.bandGainsRight16
    }
    
    // Set dynamic theme color
    val themeColor = when (currentEar) {
        EarType.LEFT -> Color(0xFF2F80ED)   // Clinical Blue
        EarType.RIGHT -> Color(0xFFEB5757)  // Clinical Red
        EarType.BOTH -> Color(0xFF9B51E0)   // Clinical Purple
    }

    val themeColorAnimated by animateColorAsState(targetValue = themeColor, label = "themeColor")

    val scrollState = rememberScrollState()

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
                            "目前模式：環境助聽 (麥克風)"
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
                        Triple(EarType.LEFT, "左耳 (L)", Color(0xFF2F80ED)),
                        Triple(EarType.BOTH, "雙耳連動", Color(0xFF9B51E0)),
                        Triple(EarType.RIGHT, "右耳 (R)", Color(0xFFEB5757))
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
                    EqualizerCurveDisplay(
                        modifier = Modifier.fillMaxSize(),
                        bandGains = activeBandGains,
                        centerFrequencies = centerFrequencies,
                        lineColor = themeColorAnimated,
                        onDragBand = { index, gain ->
                            viewModel.updateBandGain(currentEar, index, gain)
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
                Text(
                    text = "頻段增益微調 (dB)",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
                Button(
                    onClick = {
                        viewModel.applyDslV5Fitting()
                        Toast.makeText(context, "已成功套用聽力圖補償 (DSL v5)", Toast.LENGTH_SHORT).show()
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
                        text = "套用聽力圖 (DSL v5)",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                centerFrequencies.forEachIndexed { index, freq ->
                    val gainVal = activeBandGains[index].value
                    
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
                                modifier = Modifier.width(65.dp)
                            )

                            // Decrease button
                            IconButton(
                                onClick = {
                                    val newVal = (gainVal - 0.5f).coerceIn(EqViewModel.MIN_GAIN_DB, EqViewModel.MAX_GAIN_DB)
                                    viewModel.updateBandGain(currentEar, index, newVal)
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Remove,
                                    contentDescription = "減少",
                                    tint = themeColorAnimated.copy(alpha = 0.8f)
                                )
                            }

                            // Slider
                            Slider(
                                value = gainVal,
                                onValueChange = { newVal ->
                                    viewModel.updateBandGain(currentEar, index, newVal)
                                },
                                valueRange = EqViewModel.MIN_GAIN_DB..EqViewModel.MAX_GAIN_DB,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp),
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
                                    viewModel.updateBandGain(currentEar, index, newVal)
                                },
                                modifier = Modifier.size(36.dp)
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
                                    .width(55.dp),
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
