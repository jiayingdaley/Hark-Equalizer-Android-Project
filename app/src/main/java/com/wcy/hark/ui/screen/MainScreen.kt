package com.wcy.hark.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.wcy.hark.EqViewModel
import com.wcy.hark.ui.components.EqualizerCurveDisplay
import com.wcy.hark.ui.components.SystemVolumeSlider

// ---------------------------------------------------------------------------
// UI Layout Constants
// ---------------------------------------------------------------------------

/**
 * Shared UI dimension constants for the equalizer layout.
 * Centralised here so all EQ UI components share the same column width.
 */
object EqUiConstants {
    /** Width allocated to each EQ band column (label + curve handle + dB text). */
    val BAND_CONTAINER_WIDTH: Dp = 70.dp
}

// ---------------------------------------------------------------------------
// Main Screen
// ---------------------------------------------------------------------------

/**
 * HarkAppScreen – Top-level composable for the main hearing-aid control screen.
 *
 * Responsibilities:
 *  - Permission request UI flow
 *  - Mode selection (8-band / 16-band)
 *  - Master on/off switch
 *  - Status text display
 *  - Equalizer curve display + drag-to-edit
 *  - System volume slider
 *
 * Design: Stateless composable receiving all state and callbacks from outside,
 * following the Compose Unidirectional Data Flow (UDF) pattern.
 * Ref: https://developer.android.com/jetpack/compose/architecture
 *
 * @param viewModel          Provides EQ state and business logic.
 * @param audioManager       For volume control; nullable for Preview compatibility.
 * @param isEngineOn         Current engine on/off state (user intent).
 * @param onEngineStateChange Callback when the user toggles the master switch.
 * @param onSetBandGain      JNI call-through to set per-band gain.
 * @param onSetBandQ         JNI call-through to set per-band Q factor.
 */
@Composable
fun HarkAppScreen(
    viewModel: EqViewModel,
    audioManager: AudioManager?,
    isEngineOn: Boolean,
    onEngineStateChange: (Boolean) -> Unit,
    onSetBandGain: (bandIndex: Int, gain: Float) -> Unit,
    onSetBandQ: (bandIndex: Int, q: Float) -> Unit
) {
    val context = LocalContext.current

    // --- Permission state ---
    var isPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted -> isPermissionGranted = isGranted }
    )
    LaunchedEffect(context) {
        if (!isPermissionGranted) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // --- EQ state from ViewModel ---
    val scrollState = rememberScrollState()
    val currentMode by viewModel.currentMode
    val statusText by viewModel.statusText
    val centerFrequencies = viewModel.currentCenterFrequencies
    val currentBandGains = viewModel.currentBandGains
    val totalBandContainerWidth = EqUiConstants.BAND_CONTAINER_WIDTH * centerFrequencies.size

    // Extract JNI syncing logic into a lambda so we can call it on reset as well
    val syncJniState = {
        Log.d("HarkAppScreen", "Syncing JNI state for mode: $currentMode")
        // 8-band mode maps to specific positions in the 16-band filter array
        val map8to16 = intArrayOf(0, 3, 6, 8, 9, 12, 14, 15)
        for (i in 0 until 16) {
            if (currentMode == EqViewModel.EngineMode.BIQUAD_16_MIC) {
                val gain = viewModel.bandGains16.getOrNull(i)?.value ?: 0f
                val q = viewModel.bandQs16.getOrNull(i)?.value ?: EqViewModel.DEFAULT_Q
                onSetBandGain(i, gain)
                onSetBandQ(i, q)
            } else {
                val indexOf8 = map8to16.indexOf(i)
                if (indexOf8 != -1) {
                    val gain = viewModel.bandGains8.getOrNull(indexOf8)?.value ?: 0f
                    val q = viewModel.bandQs8.getOrNull(indexOf8)?.value ?: EqViewModel.DEFAULT_Q
                    onSetBandGain(i, gain)
                    onSetBandQ(i, q)
                } else {
                    // Unused 16-band slots in 8-band mode → flat
                    onSetBandGain(i, 0f)
                    onSetBandQ(i, EqViewModel.DEFAULT_Q)
                }
            }
        }
    }

    // Sync ViewModel state → JNI layer whenever mode changes or data finishes loading.
    // Maps 8-band UI indices to the correct 16-band C++ filter slots.
    LaunchedEffect(currentMode, viewModel.isDataLoaded.value) {
        if (!viewModel.isDataLoaded.value) return@LaunchedEffect
        syncJniState()
    }

    // --- Layout ---
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(vertical = 8.dp)
        ) {
            // --- Top control bar ---
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Mode selector buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    val activeColors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                    val inactiveColors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Button(
                        onClick = { viewModel.currentMode.value = EqViewModel.EngineMode.BIQUAD_16_MIC },
                        colors = if (currentMode == EqViewModel.EngineMode.BIQUAD_16_MIC) activeColors else inactiveColors
                    ) { Text("16-Band") }

                    Button(
                        onClick = { viewModel.currentMode.value = EqViewModel.EngineMode.BIQUAD_8_MIC },
                        colors = if (currentMode == EqViewModel.EngineMode.BIQUAD_8_MIC) activeColors else inactiveColors
                    ) { Text("8-Band") }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Master on/off switch
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
                    syncJniState()
                }) {
                    Text("重設等化器")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- Equalizer section (horizontally scrollable) ---
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
                        .width(totalBandContainerWidth)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Frequency labels row
                    Row(modifier = Modifier.fillMaxWidth()) {
                        centerFrequencies.forEach { freq ->
                            Text(
                                text = formatFrequencyLabel(freq),
                                modifier = Modifier.width(EqUiConstants.BAND_CONTAINER_WIDTH),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    // Interactive EQ curve
                    EqualizerCurveDisplay(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        bandGains = currentBandGains,
                        centerFrequencies = centerFrequencies,
                        onDragBand = { bandIndex, newGain ->
                            viewModel.updateBandGain(bandIndex, newGain)
                            // Map 8-band UI index to 16-band JNI index when in 8-band mode
                            val jniIndex = if (currentMode == EqViewModel.EngineMode.BIQUAD_8_MIC) {
                                val map = intArrayOf(0, 3, 6, 8, 9, 12, 14, 15)
                                if (bandIndex in map.indices) map[bandIndex] else bandIndex
                            } else bandIndex
                            onSetBandGain(jniIndex, newGain)
                        }
                    )

                    // dB value labels row
                    Row(modifier = Modifier.fillMaxWidth()) {
                        currentBandGains.forEach { gainState ->
                            Text(
                                text = String.format("%.1f dB", gainState.value),
                                modifier = Modifier.width(EqUiConstants.BAND_CONTAINER_WIDTH),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodySmall,
                                color = androidx.compose.ui.graphics.Color.Gray
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

// ---------------------------------------------------------------------------
// Utilities
// ---------------------------------------------------------------------------

/**
 * Formats a frequency in Hz to a human-readable label.
 * e.g. 250 → "250", 1000 → "1k", 1250 → "1.3k", 6300 → "6.3k"
 */
private fun formatFrequencyLabel(freq: Int): String {
    if (freq < 1000) return freq.toString()
    val kHz = freq / 1000.0
    return String.format("%.1f", kHz).removeSuffix(".0") + "k"
}
