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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.forEachGesture
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.hark.ui.theme.HarkTheme
import kotlin.math.abs

// Object to hold all tunable UI parameters
object UIConstants {
    val SLIDER_LENGTH: Dp = 250.dp
    val SLIDER_THICKNESS: Dp = 60.dp
    val BAND_CONTAINER_WIDTH: Dp = 60.dp
}

class MainActivity : ComponentActivity() {

    private val viewModel: EqViewModel by viewModels()
    private lateinit var audioManager: AudioManager

    private external fun startEngine()
    private external fun stopEngine()
    private external fun setBandGain(bandIndex: Int, gainDb: Float)
    private external fun setBandQ(bandIndex: Int, q_factor: Float)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
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
    audioManager: AudioManager?,
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
    val totalWidth = UIConstants.BAND_CONTAINER_WIDTH * centerFrequencies.size

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
                        .width(totalWidth)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        centerFrequencies.forEach {
                            Text(
                                text = if (it >= 1000) "${it / 1000}k" else it.toString(),
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
                        viewModel = viewModel,
                        centerFrequencies = centerFrequencies,
                        onSetBandGain = onSetBandGain
                    )

                    Row(modifier = Modifier.fillMaxWidth()) {
                        viewModel.bandGains.take(centerFrequencies.size).forEach {
                            Text(
                                text = String.format("%.1f dB", it.value),
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
    viewModel: EqViewModel,
    centerFrequencies: List<Int>,
    onSetBandGain: (Int, Float) -> Unit
) {
    val bandGains = viewModel.bandGains
    val primaryColor = MaterialTheme.colorScheme.primary
    val waveColor = primaryColor.copy(alpha = 0.3f)

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    forEachGesture {
                        awaitPointerEventScope {
                            val down = awaitFirstDown(requireUnconsumed = false)

                            val canvasWidth = size.width.toFloat()
                            if (centerFrequencies.isEmpty()) return@awaitPointerEventScope
                            val bandWidth = canvasWidth / centerFrequencies.size
                            val bandIndex = (down.position.x / bandWidth).toInt()

                            if (bandIndex !in centerFrequencies.indices) {
                                return@awaitPointerEventScope
                            }

                            // Wait for the drag to start, distinguishing between vertical and horizontal
                            val drag = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                                if (abs(change.position.y - down.position.y) > abs(change.position.x - down.position.x)) {
                                    change.consume()
                                } // Don't consume for horizontal drag
                            }

                            if (drag != null && drag.isConsumed) {
                                val minGain = -15f
                                val maxGain = 15f
                                val gainRange = maxGain - minGain
                                val canvasHeight = size.height.toFloat()

                                // Apply the initial over-slop drag
                                val currentGain = viewModel.bandGains[bandIndex].value
                                val dragDelta = drag.position - drag.previousPosition
                                val gainDelta = (-dragDelta.y / canvasHeight) * gainRange
                                val newGain = (currentGain + gainDelta).coerceIn(minGain, maxGain)
                                viewModel.bandGains[bandIndex].value = newGain
                                onSetBandGain(bandIndex, newGain)

                                // Continue dragging vertically
                                drag(drag.id) {
                                    val innerDragDelta = it.position - it.previousPosition
                                    val innerGainDelta = (-innerDragDelta.y / canvasHeight) * gainRange
                                    val innerCurrentGain = viewModel.bandGains[bandIndex].value
                                    val innerNewGain = (innerCurrentGain + innerGainDelta).coerceIn(minGain, maxGain)
                                    viewModel.bandGains[bandIndex].value = innerNewGain
                                    onSetBandGain(bandIndex, innerNewGain)
                                    it.consume()
                                }
                            }
                        }
                    }
                }
        ) { // onDraw scope starts here
            if (centerFrequencies.isEmpty()) return@Canvas

            val canvasWidth = size.width
            val canvasHeight = size.height
            val bandWidth = canvasWidth / centerFrequencies.size

            val minGain = -15f
            val maxGain = 15f
            val gainRange = maxGain - minGain

            val points = centerFrequencies.mapIndexed { index, _ ->
                val gain = bandGains[index].value
                val x = (index * bandWidth) + (bandWidth / 2f)
                val y = canvasHeight - (canvasHeight * ((gain - minGain) / gainRange))
                Offset(x, y)
            }

            val zeroGainY = canvasHeight - (canvasHeight * ((0f - minGain) / gainRange))

            val fillPath = Path().apply {
                moveTo(0f, zeroGainY)
                if (points.isNotEmpty()) {
                    lineTo(points.first().x, points.first().y)
                    for (i in 0 until points.size - 1) {
                        val p0 = points.getOrElse(i - 1) { points[i] }
                        val p1 = points[i]
                        val p2 = points[i + 1]
                        val p3 = points.getOrElse(i + 2) { p2 }

                        val cp1x = p1.x + (p2.x - p0.x) / 6f
                        val cp1y = p1.y + (p2.y - p0.y) / 6f
                        val cp2x = p2.x - (p3.x - p1.x) / 6f
                        val cp2y = p2.y - (p3.y - p1.y) / 6f

                        cubicTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y)
                    }
                    lineTo(points.last().x, zeroGainY)
                }
                lineTo(canvasWidth, zeroGainY)
                close()
            }

            val strokePath = Path().apply {
                if (points.isNotEmpty()) {
                    moveTo(points.first().x, points.first().y)
                    for (i in 0 until points.size - 1) {
                        val p0 = points.getOrElse(i - 1) { points[i] }
                        val p1 = points[i]
                        val p2 = points[i + 1]
                        val p3 = points.getOrElse(i + 2) { p2 }

                        val cp1x = p1.x + (p2.x - p0.x) / 6f
                        val cp1y = p1.y + (p2.y - p0.y) / 6f
                        val cp2x = p2.x - (p3.x - p1.x) / 6f
                        val cp2y = p2.y - (p3.y - p1.y) / 6f

                        cubicTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y)
                    }
                }
            }

            // Drawing starts here
            drawPath(path = fillPath, color = waveColor)
            drawLine(color = Color.Gray, start = Offset(0f, zeroGainY), end = Offset(canvasWidth, zeroGainY), strokeWidth = 2f)
            points.forEach { drawLine(color = primaryColor.copy(alpha = 0.5f), start = Offset(it.x, zeroGainY), end = it, strokeWidth = 2f) }
            drawPath(path = strokePath, color = primaryColor, style = Stroke(width = 5f))

            points.forEach { point ->
                drawCircle(color = primaryColor, radius = 12f, center = point)
                drawCircle(color = Color.White, radius = 8f, center = point)
            }
        }
    }
}

private fun resetEqualizerBands(viewModel: EqViewModel, numBands: Int, onSetBandGain: (Int, Float) -> Unit) {
    for (i in 0 until numBands) {
        viewModel.bandGains[i].value = 0f
        onSetBandGain(i, 0f)
    }
    viewModel.statusText.value = "狀態：等化器已重設"
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    HarkTheme {
        HarkAppScreen(viewModel = EqViewModel(), audioManager = null)
    }
}