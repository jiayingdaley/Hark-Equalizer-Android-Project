package com.wcy.hark.audiometry

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * PsychometricView — publication-quality speech-in-noise psychometric function.
 *
 * X axis: SNR (dB); Y axis: Speech Recognition Score (%).
 * English axis labels (thesis figure format). Marks the interpolated
 * SRT50 (SNR at 50% correct) with a dashed guide when available.
 */
class PsychometricView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** snrDb → percent correct (0–100) */
    private var dataPoints: List<Pair<Float, Float>> = emptyList()
    private var srt50: Float? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textColor = Color.rgb(33, 33, 33)
    private val gridColor = Color.rgb(210, 210, 210)
    private val curveColor = Color.rgb(25, 118, 210)
    private val srtColor = Color.rgb(211, 47, 47)
    private val padding = 110f

    fun setData(points: List<Pair<Float, Float>>, srt50Db: Float?) {
        dataPoints = points.sortedBy { it.first }
        srt50 = srt50Db
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()

        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawRect(0f, 0f, width, height, paint)

        if (dataPoints.isEmpty()) return

        val xMin = dataPoints.first().first - 2.5f
        val xMax = dataPoints.last().first + 2.5f

        fun mapX(snr: Float) = padding + (snr - xMin) / (xMax - xMin) * (width - 2 * padding)
        fun mapY(pct: Float) = height - padding - (pct / 100f) * (height - 2 * padding)

        // ── Grid ──
        paint.textSize = 24f
        for (pct in 0..100 step 20) {
            val y = mapY(pct.toFloat())
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f
            paint.color = gridColor
            canvas.drawLine(padding, y, width - padding, y, paint)
            paint.style = Paint.Style.FILL
            paint.color = textColor
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText("$pct", padding - 12f, y + 8f, paint)
        }
        for (p in dataPoints) {
            val x = mapX(p.first)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f
            paint.color = gridColor
            canvas.drawLine(x, padding, x, height - padding, paint)
            paint.style = Paint.Style.FILL
            paint.color = textColor
            paint.textAlign = Paint.Align.CENTER
            val snrLabel = if (p.first == p.first.toInt().toFloat()) "${p.first.toInt()}" else "${p.first}"
            canvas.drawText(snrLabel, x, height - padding + 34f, paint)
        }

        // ── Axis titles (English, thesis format) ──
        paint.color = textColor
        paint.textSize = 30f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("SNR (dB)", width / 2, height - padding / 3, paint)
        canvas.save()
        canvas.rotate(-90f, padding / 3, height / 2)
        canvas.drawText("Speech Recognition Score (%)", height / 2, padding / 3, paint)
        canvas.restore()

        // ── SRT50 guides ──
        srt50?.let { s ->
            if (s in xMin..xMax) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3f
                paint.color = srtColor
                paint.pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
                canvas.drawLine(mapX(s), padding, mapX(s), height - padding, paint)
                canvas.drawLine(padding, mapY(50f), width - padding, mapY(50f), paint)
                paint.pathEffect = null
                paint.style = Paint.Style.FILL
                paint.textAlign = Paint.Align.LEFT
                paint.textSize = 26f
                canvas.drawText("SRT50 = ${String.format("%.1f", s)} dB", mapX(s) + 10f, padding + 30f, paint)
            }
        }

        // ── Curve + points ──
        paint.color = curveColor
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        val path = Path()
        dataPoints.forEachIndexed { i, p ->
            val x = mapX(p.first); val y = mapY(p.second)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)

        paint.style = Paint.Style.FILL
        for (p in dataPoints) {
            canvas.drawCircle(mapX(p.first), mapY(p.second), 10f, paint)
        }
    }
}
