package com.wcy.hark.audiometry

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat

class AudiogramView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    // Include frequencies from standard audiogram testing (evenly spaced log-scale grids)
    private val axisFrequencies = listOf(125, 250, 500, 1000, 2000, 4000, 8000)
    private val hearingLevels = (-10..120 step 10).toList()
    private var leftEarResults: Map<Int, Int?> = emptyMap()
    private var rightEarResults: Map<Int, Int?> = emptyMap()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textColor = Color.BLACK
    private val gridColor = Color.LTGRAY
    private val leftEarColor = Color.BLUE
    private val rightEarColor = Color.RED
    private val symbolSize = 45f
    private val padding = 100f // Increased padding for better visibility

    fun setResults(leftEar: Map<Int, Int?>, rightEar: Map<Int, Int?>) {
        this.leftEarResults = leftEar
        this.rightEarResults = rightEar
        invalidate() // Request redraw
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()

        // Fill background
        paint.color = Color.WHITE
        canvas.drawRect(0f, 0f, width, height, paint)

        // Draw axis labels
        paint.color = textColor
        paint.textSize = 30f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("Frequency in Hertz (Hz)", width / 2, height - padding / 3, paint)

        // Draw vertical axis label
        paint.textAlign = Paint.Align.CENTER
        canvas.save()
        canvas.rotate(-90f, padding / 3, height / 2)
        canvas.drawText("Hearing Level in dB (ANSI 1996)", height / 2, padding / 3, paint)
        canvas.restore()

        // Draw grid lines and labels
        paint.color = gridColor
        paint.strokeWidth = 1f
        paint.textSize = 24f

        // Draw vertical lines (frequencies)
        paint.textAlign = Paint.Align.CENTER
        for (freq in axisFrequencies) {
            val x = mapFrequencyToX(freq, width)
            canvas.drawLine(x, padding, x, height - padding, paint)

            // Format frequencies for display (e.g., 1000 -> 1k, 1500 -> 1.5k)
            val freqText = when {
                freq >= 1000 -> if (freq % 1000 == 0) "${freq/1000}k" else "${freq/1000f}k"
                else -> "$freq"
            }
            canvas.drawText(freqText, x, height - padding + 30f, paint)
        }

        // Draw horizontal lines (hearing levels)
        val availableHeight = height - 2 * padding
        val levelSpacing = availableHeight / (hearingLevels.size - 1)

        paint.textAlign = Paint.Align.RIGHT
        for (i in hearingLevels.indices) {
            val y = padding + i * levelSpacing
            canvas.drawLine(padding, y, width - padding, y, paint)
            canvas.drawText(hearingLevels[i].toString(), padding - 10f, y + 8f, paint)
        }

        // Draw normal hearing range zone (0-25 dB)
        paint.color = Color.argb(30, 0, 255, 0)  // Light green with transparency
        val y0dB = mapHearingLevelToY(0, height)
        val y25dB = mapHearingLevelToY(25, height)
        canvas.drawRect(padding, y0dB, width - padding, y25dB, paint)

        // Draw results
        drawEarResults(canvas, leftEarResults, leftEarColor, "X", width, height)
        drawEarResults(canvas, rightEarResults, rightEarColor, "O", width, height)

        // Draw legend
        paint.textSize = 30f
        paint.textAlign = Paint.Align.LEFT

        // Right ear legend
        paint.color = rightEarColor
        canvas.drawText("O", padding, padding / 2, paint)
        paint.color = textColor
        canvas.drawText(" - Right Ear", padding + 30f, padding / 2, paint)

        // Left ear legend
        paint.color = leftEarColor
        canvas.drawText("X", padding + 300f, padding / 2, paint)
        paint.color = textColor
        canvas.drawText(" - Left Ear", padding + 330f, padding / 2, paint)
    }

    private fun drawEarResults(
        canvas: Canvas,
        results: Map<Int, Int?>,
        color: Int,
        symbol: String,
        width: Float,
        height: Float
    ) {
        paint.color = color
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 6f // 加粗線條
        paint.textAlign = Paint.Align.CENTER
        // 將文字加粗稍微調整
        paint.textSize = symbolSize
        paint.isFakeBoldText = true

        // First pass: Draw points
        val points = mutableListOf<Pair<Float, Float>>()

        // Sort tested frequencies for line drawing connection
        val testedFrequencies = results.keys.sorted()

        for (frequency in testedFrequencies) {
            val level = results[frequency]
            if (level != null && level != -1) {
                val x = mapFrequencyToX(frequency, width)
                val y = mapHearingLevelToY(level, height)

                // Draw symbol
                canvas.drawText(symbol, x, y + (symbolSize / 3f), paint) // 微調 Y 座標置中
                points.add(Pair(x, y))

                Log.d("AudiogramView", "$symbol at Freq: $frequency Hz, Level: $level dB, X: $x, Y: $y")
            }
        }

        // 恢復正常文字設定，避免影響其他圖表元素
        paint.isFakeBoldText = false

        // Second pass: Connect points with lines
        if (points.size > 1) {
            paint.style = Paint.Style.STROKE
            val path = Path()
            path.moveTo(points[0].first, points[0].second)

            for (i in 1 until points.size) {
                path.lineTo(points[i].first, points[i].second)
            }

            canvas.drawPath(path, paint)
        }
    }

    private fun mapHearingLevelToY(level: Int, height: Float): Float {
        val minLevel = hearingLevels.first() // -10
        val maxLevel = hearingLevels.last() // 120
        val levelRange = maxLevel - minLevel
        val availableHeight = height - 2 * padding

        // Calculate position - note we don't apply the yAxisShift anymore
        // since we want accurate positioning
        return padding + ((level - minLevel) / levelRange.toFloat()) * availableHeight
    }

    private fun mapFrequencyToX(frequency: Int, width: Float): Float {
        val minFreq = 125f
        val maxFreq = 8000f
        val availableWidth = width - 2 * padding
        val logRatio = kotlin.math.log2(frequency.toFloat() / minFreq) / kotlin.math.log2(maxFreq / minFreq)
        return padding + logRatio * availableWidth
    }
}