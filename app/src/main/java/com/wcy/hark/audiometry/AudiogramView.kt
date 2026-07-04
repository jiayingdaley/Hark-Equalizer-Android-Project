package com.wcy.hark.audiometry

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.ln

/**
 * AudiogramView — publication-quality clinical audiogram.
 *
 * Follows audiological plotting conventions (ISO 8253-1 / ASHA):
 *  - X axis: Frequency (Hz), octave-proportional (log2) spacing; 3k/6k as inter-octave ticks
 *  - Y axis: Hearing Level (dB HL), inverted (−10 at top, 120 at bottom)
 *  - Symbols: right ear ○ red, left ear × blue
 *  - Shaded normal-hearing band (≤ 25 dB HL)
 *
 * Experiment mode can switch to dB FS display via [setDbfsConverter] + [displayDbfs]:
 * the Y axis becomes Output Level (dB FS), 0 at top, −100 at bottom.
 */
class AudiogramView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    // Data frequencies (tested) vs axis frequencies (clinical layout incl. 125 Hz)
    private val frequencies = listOf(250, 500, 1000, 2000, 3000, 4000, 6000, 8000)
    private val axisFrequencies = listOf(125, 250, 500, 1000, 2000, 3000, 4000, 6000, 8000)
    private var leftEarResults: Map<Int, Int?> = emptyMap()
    private var rightEarResults: Map<Int, Int?> = emptyMap()

    // dB FS display support (experiment mode)
    private var dbfsConverter: ((freqHz: Int, dbHl: Int) -> Float?)? = null
    private var displayDbfs = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textColor = Color.rgb(33, 33, 33)
    private val gridColor = Color.rgb(200, 200, 200)
    private val minorGridColor = Color.rgb(230, 230, 230)
    private val leftEarColor = Color.rgb(33, 102, 204)   // audiological blue
    private val rightEarColor = Color.rgb(211, 47, 47)   // audiological red
    private val padding = 110f

    fun setResults(leftEar: Map<Int, Int?>, rightEar: Map<Int, Int?>) {
        this.leftEarResults = leftEar
        this.rightEarResults = rightEar
        invalidate()
    }

    /** Provide the dB HL → dB FS conversion (from the earphone calibration table). */
    fun setDbfsConverter(converter: ((freqHz: Int, dbHl: Int) -> Float?)?) {
        dbfsConverter = converter
        invalidate()
    }

    /** Toggle between dB HL (false) and dB FS (true) display. */
    fun setDisplayDbfs(enabled: Boolean) {
        displayDbfs = enabled
        invalidate()
    }

    // Y-axis ranges
    private val hlMin = -10f
    private val hlMax = 120f
    private val fsMin = -100f  // bottom
    private val fsMax = 0f     // top

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()

        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawRect(0f, 0f, width, height, paint)

        // ── Axis titles (English, publication format) ──
        paint.color = textColor
        paint.textSize = 30f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("Frequency (Hz)", width / 2, height - padding / 3, paint)

        canvas.save()
        canvas.rotate(-90f, padding / 3, height / 2)
        canvas.drawText(
            if (displayDbfs) "Output Level (dB FS)" else "Hearing Level (dB HL)",
            height / 2, padding / 3, paint
        )
        canvas.restore()

        // ── Vertical grid: log2 frequency positions ──
        paint.strokeWidth = 1.5f
        paint.textSize = 24f
        paint.textAlign = Paint.Align.CENTER
        for (f in axisFrequencies) {
            val x = mapFrequencyToX(f, width)
            val isOctave = f in listOf(125, 250, 500, 1000, 2000, 4000, 8000)
            paint.style = Paint.Style.STROKE
            paint.color = if (isOctave) gridColor else minorGridColor
            paint.pathEffect = if (isOctave) null else DashPathEffect(floatArrayOf(6f, 6f), 0f)
            canvas.drawLine(x, padding, x, height - padding, paint)
            paint.pathEffect = null
            paint.style = Paint.Style.FILL
            paint.color = textColor
            val freqText = when {
                f >= 1000 && f % 1000 == 0 -> "${f / 1000}k"
                f >= 1000 -> "${f / 1000.0}k".removeSuffix(".0k") + "k"
                else -> "$f"
            }
            canvas.drawText(freqText, x, height - padding + 34f, paint)
        }

        // ── Horizontal grid ──
        val yLevels: List<Float> = if (displayDbfs)
            generateSequence(fsMax) { it - 10f }.takeWhile { it >= fsMin }.toList()
        else
            generateSequence(hlMin) { it + 10f }.takeWhile { it <= hlMax }.toList()

        paint.textAlign = Paint.Align.RIGHT
        for (lv in yLevels) {
            val y = mapLevelToY(lv, height)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (lv == 0f) 3f else 1.5f
            paint.color = if (lv == 0f) Color.rgb(150, 150, 150) else gridColor
            canvas.drawLine(padding, y, width - padding, y, paint)
            paint.style = Paint.Style.FILL
            paint.color = textColor
            canvas.drawText(lv.toInt().toString(), padding - 12f, y + 8f, paint)
        }

        // ── Normal hearing band (dB HL mode only): −10 to 25 dB HL ──
        if (!displayDbfs) {
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(26, 76, 175, 80)
            canvas.drawRect(
                padding, mapLevelToY(hlMin, height),
                width - padding, mapLevelToY(25f, height), paint
            )
        }

        // ── Plot data (right first per convention, so left draws on top when overlapping) ──
        drawEarResults(canvas, rightEarResults, rightEarColor, isRight = true, height = height, width = width)
        drawEarResults(canvas, leftEarResults, leftEarColor, isRight = false, height = height, width = width)

        // ── Legend ──
        paint.textSize = 28f
        paint.textAlign = Paint.Align.LEFT
        val legendY = padding / 2
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = rightEarColor
        canvas.drawCircle(padding + 10f, legendY - 8f, 10f, paint)
        paint.style = Paint.Style.FILL
        paint.color = textColor
        canvas.drawText("Right Ear", padding + 32f, legendY, paint)

        paint.style = Paint.Style.STROKE
        paint.color = leftEarColor
        val lx = padding + 220f
        canvas.drawLine(lx - 10f, legendY - 18f, lx + 10f, legendY + 2f, paint)
        canvas.drawLine(lx - 10f, legendY + 2f, lx + 10f, legendY - 18f, paint)
        paint.style = Paint.Style.FILL
        paint.color = textColor
        canvas.drawText("Left Ear", lx + 22f, legendY, paint)
    }

    private fun drawEarResults(
        canvas: Canvas,
        results: Map<Int, Int?>,
        color: Int,
        isRight: Boolean,
        width: Float,
        height: Float
    ) {
        paint.color = color
        paint.strokeCap = Paint.Cap.ROUND

        val points = mutableListOf<Pair<Float, Float>>()
        for (frequency in frequencies) {
            val hl = results[frequency] ?: continue
            if (hl == -1) continue
            val plotted: Float = if (displayDbfs) {
                dbfsConverter?.invoke(frequency, hl) ?: continue
            } else hl.toFloat()

            val x = mapFrequencyToX(frequency, width)
            val y = mapLevelToY(plotted, height)
            points.add(x to y)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 5f
            if (isRight) {
                canvas.drawCircle(x, y, 12f, paint)
            } else {
                canvas.drawLine(x - 12f, y - 12f, x + 12f, y + 12f, paint)
                canvas.drawLine(x - 12f, y + 12f, x + 12f, y - 12f, paint)
            }
        }

        if (points.size > 1) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 5f
            val path = Path()
            path.moveTo(points[0].first, points[0].second)
            for (i in 1 until points.size) path.lineTo(points[i].first, points[i].second)
            canvas.drawPath(path, paint)
        }
    }

    /**
     * Octave-proportional (log2) x position. Clinical audiogram layout:
     * axis spans 125–8000 Hz with one blank half-octave margin on each side.
     */
    private fun mapFrequencyToX(freqHz: Int, width: Float): Float {
        val halfOctave = ln(2.0) * 0.5
        val logMin = ln(125.0) - halfOctave
        val logMax = ln(8000.0) + halfOctave
        val t = ((ln(freqHz.toDouble()) - logMin) / (logMax - logMin)).toFloat()
        return padding + t * (width - 2 * padding)
    }

    private fun mapLevelToY(level: Float, height: Float): Float {
        val availableHeight = height - 2 * padding
        return if (displayDbfs) {
            // 0 dBFS at top, −100 at bottom
            padding + ((fsMax - level) / (fsMax - fsMin)) * availableHeight
        } else {
            // −10 dB HL at top, 120 at bottom
            padding + ((level - hlMin) / (hlMax - hlMin)) * availableHeight
        }
    }
}
