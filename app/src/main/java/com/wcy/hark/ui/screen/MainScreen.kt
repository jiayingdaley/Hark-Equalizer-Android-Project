package com.wcy.hark.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.wcy.hark.EqViewModel
import com.wcy.hark.AudioSourceMode
import com.wcy.hark.audio.SceneManager
import com.wcy.hark.ui.components.EqualizerCurveDisplay
import com.wcy.hark.ui.components.SystemVolumeSlider

object EqUiConstants {
    val BAND_CONTAINER_WIDTH: Dp = 70.dp
}

@Composable
fun HarkAppScreen(
    viewModel: EqViewModel,
    audioManager: AudioManager?,
    isEngineOn: Boolean,
    onEngineStateChange: (Boolean) -> Unit,
    onSourceModeChanged: (AudioSourceMode) -> Unit,
    onSetBandGain: (bandIndex: Int, gain: Float) -> Unit,
    onSetBandQ: (bandIndex: Int, q: Float) -> Unit
) {
    val context = LocalContext.current

    // Read dynamic permission state from ViewModel to prevent UI locks
    val isPermissionGranted = viewModel.isMicrophonePermissionGranted.value

    // --- State ---
    val scrollState = rememberScrollState()
    val statusText by viewModel.statusText
    val centerFrequencies = viewModel.centerFrequencies16
    val currentBandGains = viewModel.bandGains16
    val totalBandContainerWidth = EqUiConstants.BAND_CONTAINER_WIDTH * centerFrequencies.size
    
    val situationalMode by viewModel.situationalMode
    val isAutoLocked by viewModel.isAutoLocked

    var currentScreen by remember { mutableStateOf("main") }

    if (currentScreen == "dsp_test") {
        DspTestScreen(
            viewModel = viewModel,
            onBack = { currentScreen = "main" }
        )
    } else {
        // --- Layout ---
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            // --- Top Level Mode Selection ---
            val tabs = listOf("環境助聽 (麥克風)", "手機影音 (內部音訊)")
            val selectedTabIndex = if (viewModel.currentSourceMode.value == AudioSourceMode.MICROPHONE) 0 else 1
            
            TabRow(selectedTabIndex = selectedTabIndex, modifier = Modifier.padding(bottom = 16.dp)) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = {
                            val newMode = if (index == 0) AudioSourceMode.MICROPHONE else AudioSourceMode.INTERNAL_MEDIA
                            if (viewModel.currentSourceMode.value != newMode) {
                                viewModel.currentSourceMode.value = newMode
                                onSourceModeChanged(newMode)
                            }
                        },
                        text = { Text(title, style = MaterialTheme.typography.titleSmall) }
                    )
                }
            }

            if (viewModel.currentSourceMode.value == AudioSourceMode.MICROPHONE) {
                // --- Situational Modes Row ---
            Text("環境模式", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ModeButton(
                    label = "全向",
                    icon = Icons.Default.Hearing,
                    isSelected = situationalMode == SceneManager.Mode.TRANSPARENCY,
                    onClick = { viewModel.selectSituationalMode(SceneManager.Mode.TRANSPARENCY) }
                )
                ModeButton(
                    label = "人聲",
                    icon = Icons.Default.RecordVoiceOver,
                    isSelected = situationalMode == SceneManager.Mode.CONVERSATION,
                    onClick = { viewModel.selectSituationalMode(SceneManager.Mode.CONVERSATION) }
                )
                ModeButton(
                    label = "戶外",
                    icon = Icons.Default.Terrain,
                    isSelected = situationalMode == SceneManager.Mode.OUTDOOR,
                    onClick = { viewModel.selectSituationalMode(SceneManager.Mode.OUTDOOR) }
                )
                ModeButton(
                    label = "影音",
                    icon = Icons.Default.MusicNote,
                    isSelected = situationalMode == SceneManager.Mode.CINEMA,
                    onClick = { viewModel.selectSituationalMode(SceneManager.Mode.CINEMA) }
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Auto Mode Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text("自動切換模式", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = !isAutoLocked,
                    onCheckedChange = { if (it) viewModel.selectSituationalMode(SceneManager.Mode.AUTO) }
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))

            Spacer(modifier = Modifier.height(12.dp))

            // --- Styled Input Source Toggle (Only show in Microphone mode) ---
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("收音來源", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Surface(
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.height(48.dp).width(200.dp) // 加寬至 200dp
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Headset Mic Option (Left)
                        InputSourceIcon(
                            icon = Icons.Default.HeadsetMic,
                            isSelected = viewModel.useHeadsetMic.value,
                            onClick = { 
                                if (!viewModel.useHeadsetMic.value) {
                                    viewModel.toggleHeadsetMic(true)
                                    onEngineStateChange(isEngineOn)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )

                        // Phone Mic Option (Right)
                        InputSourceIcon(
                            icon = Icons.Default.Smartphone,
                            isSelected = !viewModel.useHeadsetMic.value,
                            onClick = { 
                                if (viewModel.useHeadsetMic.value) {
                                    viewModel.toggleHeadsetMic(false)
                                    onEngineStateChange(isEngineOn)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        } else {
                // Placeholder for internal media explanation
                Text(
                    text = "正在處理手機內部影音，麥克風已暫停收音。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    textAlign = TextAlign.Center
                )
            }

            // --- Engine Control (Main Power) ---
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val displayText = if (viewModel.currentSourceMode.value == AudioSourceMode.INTERNAL_MEDIA) {
                    if (viewModel.isSystemDspOn.value) "狀態：手機影音 DSP 已啟用" else "狀態：手機影音 DSP 已暫停"
                } else statusText
                
                Text(displayText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                
                if (viewModel.currentSourceMode.value == AudioSourceMode.MICROPHONE) {
                    Switch(
                        checked = isEngineOn,
                        onCheckedChange = onEngineStateChange
                    )
                } else {
                    Switch(
                        checked = viewModel.isSystemDspOn.value,
                        onCheckedChange = { viewModel.setSystemDspEnabled(it) }
                    )
                }
            }

            // --- EQ Slider Section ---
            Box(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalScroll(scrollState)
                ) {
                    Column(modifier = Modifier.width(totalBandContainerWidth)) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            centerFrequencies.forEach { freq ->
                                Text(
                                    text = formatFrequencyLabel(freq),
                                    modifier = Modifier.width(EqUiConstants.BAND_CONTAINER_WIDTH),
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        
                        EqualizerCurveDisplay(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            bandGains = currentBandGains,
                            centerFrequencies = centerFrequencies,
                            onDragBand = { index, gain -> viewModel.updateBandGain(index, gain) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            audioManager?.let { SystemVolumeSlider(it) }
            
            Button(
                onClick = { viewModel.resetCurrentModeBands() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
            ) {
                Text("重設等化器 (0 dB)")
            }

            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { currentScreen = "dsp_test" },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
            ) {
                Text("進入 DSP 測試與診斷面板")
            }
        }
    }
    }
}

@Composable
fun ModeButton(label: String, icon: ImageVector, isSelected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = Modifier.size(64.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(32.dp))
        }
        Text(text = label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun InputSourceIcon(icon: ImageVector, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxHeight(),
        color = if (isSelected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
        contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = androidx.compose.foundation.shape.CircleShape
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(24.dp))
        }
    }
}

private fun formatFrequencyLabel(freq: Int): String {
    if (freq < 1000) return freq.toString()
    val kHz = freq / 1000.0
    return String.format("%.1f", kHz).removeSuffix(".0") + "k"
}
