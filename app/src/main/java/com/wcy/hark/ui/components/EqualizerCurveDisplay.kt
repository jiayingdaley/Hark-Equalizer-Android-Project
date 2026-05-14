package com.wcy.hark.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.wcy.hark.EqViewModel

/**
 * Equalizer Curve Display
 *
 * Interactive Canvas that visualizes the EQ frequency response curve and allows
 * users to drag individual band handles to adjust gain.
 *
 * Design pattern: Stateless composable (Unidirectional Data Flow).
 *   - State flows IN via [bandGains].
 *   - Mutations flow OUT via [onDragBand] callback.
 *
 * Ref: Compose Canvas – https://developer.android.com/jetpack/compose/graphics/draw/overview
 *
 * @param modifier          Layout modifier.
 * @param bandGains         Observable gain states, one per EQ band.
 * @param centerFrequencies Center frequency (Hz) list, parallel to [bandGains].
 * @param onDragBand        Callback with (bandIndex, newGainDb) when user drags.
 */
@Composable
fun EqualizerCurveDisplay(
    modifier: Modifier = Modifier,
    bandGains: List<State<Float>>,
    centerFrequencies: List<Int>,
    onDragBand: (bandIndex: Int, gain: Float) -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary

    Canvas(
        modifier = modifier
            .pointerInput(centerFrequencies) {
                // 改用 detectVerticalDragGestures，讓左右滑動事件可以順利傳遞給外層的 horizontalScroll
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        val bandWidthPx = size.width / centerFrequencies.size.toFloat()
                        val bandIndex = (offset.x / bandWidthPx)
                            .toInt().coerceIn(0, centerFrequencies.size - 1)
                        val gainRange = EqViewModel.MAX_GAIN_DB - EqViewModel.MIN_GAIN_DB
                        val gain = EqViewModel.MAX_GAIN_DB -
                                (offset.y / size.height) * gainRange
                        onDragBand(
                            bandIndex,
                            gain.coerceIn(EqViewModel.MIN_GAIN_DB, EqViewModel.MAX_GAIN_DB)
                        )
                    },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        val bandWidthPx = size.width / centerFrequencies.size.toFloat()
                        val bandIndex = (change.position.x / bandWidthPx)
                            .toInt().coerceIn(0, centerFrequencies.size - 1)
                        val gainRange = EqViewModel.MAX_GAIN_DB - EqViewModel.MIN_GAIN_DB
                        val gain = EqViewModel.MAX_GAIN_DB -
                                (change.position.y / size.height) * gainRange
                        onDragBand(
                            bandIndex,
                            gain.coerceIn(EqViewModel.MIN_GAIN_DB, EqViewModel.MAX_GAIN_DB)
                        )
                    }
                )
            }
    ) {
        val path = Path()
        val bandWidth = size.width / centerFrequencies.size.toFloat()
        val gainRange = EqViewModel.MAX_GAIN_DB - EqViewModel.MIN_GAIN_DB
        if (gainRange <= 0) return@Canvas

        // Draw 0 dB reference line
        val zeroDbY = size.height * (1f - (0f - EqViewModel.MIN_GAIN_DB) / gainRange)
        drawLine(
            color = Color.Gray,
            start = Offset(0f, zeroDbY),
            end = Offset(size.width, zeroDbY),
            strokeWidth = 1.dp.toPx()
        )

        if (bandGains.isNotEmpty()) {
            // Map each band to a screen-space point
            val points = bandGains.mapIndexed { index, gainState ->
                val x = (index + 0.5f) * bandWidth
                val y = size.height * (1f - (gainState.value - EqViewModel.MIN_GAIN_DB) / gainRange)
                Offset(x, y.coerceIn(0f, size.height))
            }

            // Draw connecting curve
            path.moveTo(points.first().x, points.first().y)
            points.forEach { path.lineTo(it.x, it.y) }
            drawPath(path = path, color = primaryColor, style = Stroke(width = 2.dp.toPx()))

            // Draw band handle circles
            points.forEach { point ->
                drawCircle(
                    color = primaryColor.copy(alpha = 0.7f),
                    radius = 6.dp.toPx(),
                    center = point
                )
            }
        }
    }
}
