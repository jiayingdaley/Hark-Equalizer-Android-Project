package com.example.hark

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.hark.ui.theme.HarkTheme

// Object to hold all tunable UI parameters
object UIConstants {
    // This now correctly and INDEPENDENTLY controls the SLIDER LENGTH.
    val SLIDER_LENGTH: Dp = 250.dp

    // This now correctly and INDEPENDENTLY controls the SLIDER THICKNESS.
    val SLIDER_THICKNESS: Dp = 60.dp

    // This now correctly and INDEPENDENTLY controls the SPACING between sliders.
    val BAND_CONTAINER_WIDTH: Dp = 65.dp
}

class MainActivity : ComponentActivity() {

    private val viewModel: EqViewModel by viewModels()
    private lateinit  var audioManager: AudioManager

    // Native methods are private to the Activity
    private external fun startEngine()
    private external fun stopEngine()
    private external fun setBandGain(bandIndex: Int, gainDb: Float)
    private external fun setBandQ(bandIndex: Int, q_factor: Float)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Load the native library only when the Activity is actually created
        System.loadLibrary("hark")

        setContent {
            HarkTheme {
                HarkAppScreen(
                    viewModel = viewModel,
                    audioManager = audioManager,
                    onStartEngine = { startEngine() },
                    onStopEngine = { stopEngine() },
                    onSetBandGain = { band, gain -> setBandGain(band, gain) },
                    onSetBandQ = { band, q -> setBandQ(band, q) }
                )
            }
        }
    }
}

@Composable
fun HarkAppScreen(
    viewModel: EqViewModel,
    audioManager: AudioManager,
    onStartEngine: () -> Unit = {},
    onStopEngine: () -> Unit = {},
    onSetBandGain: (Int, Float) -> Unit = { _, _ -> },
    onSetBandQ: (Int, Float) -> Unit = { _, _ -> }
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
    var isEngineOn by remember { mutableStateOf(false) }
    val currentMode by viewModel.currentMode
    val statusText by viewModel.statusText

    val centerFrequencies = if (currentMode == EqViewModel.EngineMode.BIQUAD_16_MIC) viewModel.centerFrequencies16 else viewModel.centerFrequencies8

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Controls
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("主開關")
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = isEngineOn,
                        onCheckedChange = {
                            isEngineOn = it
                            if (isEngineOn) onStartEngine() else onStopEngine()
                            viewModel.statusText.value = if (isEngineOn) "狀態：運作中" else "狀態：已停用"
                        },
                        enabled = isPermissionGranted
                    )
                }
                if (!isPermissionGranted) {
                    Text("請授予麥克風權限以啟用", color = Color.Red)
                }
                Text(text = statusText, style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { resetEqualizerBands(viewModel, centerFrequencies.size, onSetBandGain) }) {
                    Text("重設等化器")
                }
            }

            // EQ Bands
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f) // Let the bands area take the remaining vertical space
                    .horizontalScroll(scrollState),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                for (i in centerFrequencies.indices) {
                    EqBandControl(viewModel = viewModel, bandIndex = i, centerFreq = centerFrequencies[i], onSetBandGain = onSetBandGain)
                }
            }

            // Master Volume Control
            SystemVolumeSlider(audioManager)
        }
    }
}

@Composable
fun EqBandControl(
    viewModel: EqViewModel,
    bandIndex: Int,
    centerFreq: Int,
    onSetBandGain: (Int, Float) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween, // Distribute space
        modifier = Modifier
            .width(UIConstants.BAND_CONTAINER_WIDTH) // This controls the spacing
            .fillMaxHeight() // Fill the height of the parent Row
    ) {
        Text("${if (centerFreq >= 1000) centerFreq / 1000 else centerFreq}${if (centerFreq >= 1000) "k" else ""}", textAlign = TextAlign.Center)

        // This Box now acts as a centered container for the rotated slider
        Box(modifier = Modifier
            .width(UIConstants.SLIDER_THICKNESS) // Box 的寬度是垂直 Slider 最終的厚度 (60dp)
            .height(UIConstants.SLIDER_LENGTH) // Box 的高度是垂直 Slider 最終的長度 (250dp)
            .align(Alignment.CenterHorizontally), // 確保 Box 在 Column 中水平置中
            contentAlignment = Alignment.Center // 讓 Slider 在 Box 中置中
        ) {
            Slider(
                value = viewModel.bandGains[bandIndex].value,
                onValueChange = {
                    viewModel.bandGains[bandIndex].value = it
                    onSetBandGain(bandIndex, it)
                },
                valueRange = -15f..15f,
                steps = 30,
                modifier = Modifier
                    .width(UIConstants.SLIDER_LENGTH)
                    .height(UIConstants.SLIDER_THICKNESS)
                    .graphicsLayer { rotationZ = 270f }
            )
        }
        Text(text = "%.1f dB".format(viewModel.bandGains[bandIndex].value))
    }
}

@Composable
fun SystemVolumeSlider(audioManager: AudioManager) {
    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    var currentVolume by remember { mutableStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    var sliderPosition by remember { mutableStateOf(currentVolume.toFloat() / maxVolume) }

    Column(modifier = Modifier.fillMaxWidth()) {
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

private fun resetEqualizerBands(viewModel: EqViewModel, numBands: Int, onSetBandGain: (Int, Float) -> Unit) {
    for (i in 0 until numBands) {
        viewModel.bandGains[i].value = 0f
        onSetBandGain(i, 0f)
    }
    viewModel.statusText.value = "狀態：等化器已重設"
}

