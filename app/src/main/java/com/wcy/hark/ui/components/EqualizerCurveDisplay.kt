package com.wcy.hark.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wcy.hark.ui.viewmodel.EqViewModel

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
    lineColor: Color = MaterialTheme.colorScheme.primary,
    onDragBand: (bandIndex: Int, gain: Float) -> Unit,
    // 選用：拖曳期間把「頻率 ±x.x dB」標籤交給外層版面顯示（結束時傳 null）。
    // 有提供時畫布內不再自行繪製標籤——小視窗（如懸浮等化器）由外層固定位置
    // 呈現，避免手指遮擋。
    onDragLabel: ((String?) -> Unit)? = null,
    // 選用：疊加曲線（僅顯示、不可拖曳），例如懸浮等化器以左藍/右紅細線
    // 呈現左右耳實際增益，主曲線（可拖曳）為雙耳平均。
    overlayCurves: List<Pair<List<State<Float>>, Color>> = emptyList()
) {
    val primaryColor = lineColor
    // 拖曳中的頻段索引（-1 = 未拖曳）；用於即時顯示「頻率 + dB」標籤
    var activeBand by remember { mutableIntStateOf(-1) }
    val textMeasurer = rememberTextMeasurer()

    fun bandLabel(index: Int, gainDb: Float): String {
        val freq = centerFrequencies[index]
        val freqLabel = if (freq >= 1000) {
            val k = freq / 1000f
            if (k == k.toInt().toFloat()) "${k.toInt()} kHz" else "$k kHz"
        } else "$freq Hz"
        val sign = if (gainDb >= 0) "+" else ""
        return "$freqLabel  $sign${"%.1f".format(gainDb)} dB"
    }

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
                        activeBand = bandIndex
                        val g = gain.coerceIn(EqViewModel.MIN_GAIN_DB, EqViewModel.MAX_GAIN_DB)
                        onDragLabel?.invoke(bandLabel(bandIndex, g))
                        onDragBand(bandIndex, g)
                    },
                    onDragEnd = { activeBand = -1; onDragLabel?.invoke(null) },
                    onDragCancel = { activeBand = -1; onDragLabel?.invoke(null) },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        val bandWidthPx = size.width / centerFrequencies.size.toFloat()
                        val bandIndex = (change.position.x / bandWidthPx)
                            .toInt().coerceIn(0, centerFrequencies.size - 1)
                        val gainRange = EqViewModel.MAX_GAIN_DB - EqViewModel.MIN_GAIN_DB
                        val gain = EqViewModel.MAX_GAIN_DB -
                                (change.position.y / size.height) * gainRange
                        activeBand = bandIndex
                        val g = gain.coerceIn(EqViewModel.MIN_GAIN_DB, EqViewModel.MAX_GAIN_DB)
                        onDragLabel?.invoke(bandLabel(bandIndex, g))
                        onDragBand(bandIndex, g)
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

        // 疊加曲線（細線、無把手）：先畫，讓主曲線蓋在上面
        for ((gains, color) in overlayCurves) {
            if (gains.size != centerFrequencies.size) continue
            val p2 = Path()
            gains.forEachIndexed { index, g ->
                val x = (index + 0.5f) * bandWidth
                val y = (size.height * (1f - (g.value - EqViewModel.MIN_GAIN_DB) / gainRange))
                    .coerceIn(0f, size.height)
                if (index == 0) p2.moveTo(x, y) else p2.lineTo(x, y)
            }
            drawPath(path = p2, color = color, style = Stroke(width = 1.5.dp.toPx()))
        }

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

            // Draw band handle circles（拖曳中的頻段放大並實心強調）
            points.forEachIndexed { index, point ->
                val isActive = index == activeBand
                drawCircle(
                    color = if (isActive) primaryColor else primaryColor.copy(alpha = 0.7f),
                    radius = if (isActive) 9.dp.toPx() else 6.dp.toPx(),
                    center = point
                )
            }

            // 拖曳即時標籤：固定顯示於曲線區「頂部中央」而非跟隨拖曳點——
            // 跟隨點會被手指遮擋（occlusion），固定位置可預期、視線不必追蹤。
            if (activeBand in bandGains.indices && onDragLabel == null) {
                val label = bandLabel(activeBand, bandGains[activeBand].value)
                val layout = textMeasurer.measure(
                    label,
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                )
                val pad = 6.dp.toPx()
                val boxW = layout.size.width + pad * 2
                val boxH = layout.size.height + pad
                val boxX = (size.width - boxW) / 2f   // 水平置中
                val boxY = 4.dp.toPx()                // 固定頂部，遠離拖曳手勢
                drawRoundRect(
                    color = Color(0xCC263238),
                    topLeft = Offset(boxX, boxY),
                    size = Size(boxW, boxH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx())
                )
                drawText(layout, topLeft = Offset(boxX + pad, boxY + pad / 2))
            }
        }
    }
}
