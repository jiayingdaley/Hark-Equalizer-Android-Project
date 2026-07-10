package com.wcy.hark.audiometry
import com.wcy.hark.R

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * SSNTestResultActivity — psychometric function result screen.
 * Chart axes are English (thesis figure format); explanatory text is
 * Traditional Chinese for the user.
 */
class SSNTestResultActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ssn_result)
        window.statusBarColor = android.graphics.Color.WHITE
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        supportActionBar?.title = "Speech-in-Noise Result"

        val snrs = intent.getFloatArrayExtra("EXTRA_SNRS") ?: floatArrayOf()
        val scores = intent.getFloatArrayExtra("EXTRA_SCORES") ?: floatArrayOf()
        val srt50 = intent.getFloatExtra("EXTRA_SRT50", Float.NaN)
        val subject = intent.getStringExtra("EXTRA_SUBJECT") ?: "未填寫"
        val noiseless = intent.getBooleanExtra("EXTRA_NOISELESS", false)
        val unit = if (noiseless) "dB SL" else "dB SNR"
        if (noiseless) supportActionBar?.title = "Quiet-Speech Result"

        val points = snrs.zip(scores.toTypedArray()) { a, b -> a to b }

        // Peak-End Rule：最難的條件必然挫折，結束畫面先給正向、白話的摘要，
        // 專業圖表與數據放在其後供研究者/進階使用者查閱。
        findViewById<TextView>(R.id.textViewSsnHeadline).text = buildString {
            append("測驗完成，辛苦了！\n")
            val best = points.maxByOrNull { it.second }
            if (best != null) {
                val cond = if (noiseless) "${fmt(best.first)} dB SL 的音量" else "SNR ${fmt(best.first)} dB 的條件"
                append("你在${cond}下答對了 ${best.second.toInt()}%")
                append(if (best.second >= 80f) "，表現很穩定。" else "。")
            }
            if (!noiseless) append("越吵的條件本來就越難，答不出來是正常的。")
            else append("越小聲的條件本來就越難，聽不到是正常的。")
        }
        findViewById<PsychometricView>(R.id.psychometric_view)
            .setData(points, if (srt50.isNaN()) null else srt50)

        val attenuatedCount = intent.getIntExtra("EXTRA_ATTENUATED_COUNT", 0)
        val maxAttenuationDb = intent.getFloatExtra("EXTRA_MAX_ATTENUATION_DB", 0f)

        findViewById<TextView>(R.id.textViewSsnSummary).text = buildString {
            append("使用者：$subject\n")
            if (!srt50.isNaN()) {
                val label = if (noiseless) "50% 辨識率之呈現音量" else "50% 辨識率之訊噪比"
                append("SRT50（$label）：${String.format("%.1f", srt50)} $unit\n")
                append(if (noiseless) "數值越低代表可辨識更小聲的語音、聽力越靈敏。"
                       else "SRT50 越低代表在噪音中的語音理解能力越好。")
            } else {
                append("無法內插出 SRT50（辨識率未跨越 50%），可調整範圍後重測。")
            }
            if (attenuatedCount > 0) {
                append("\n\n⚠️ 削波防護記錄：$attenuatedCount 題觸發輸出正規化，" +
                       "最大額外衰減 ${String.format("%.1f", maxAttenuationDb)} dB" +
                       "（SNR 不受影響，但該些題目的絕對呈現級別較低，已逐題存入資料庫）。")
            }
        }
    }

    private fun fmt(v: Float) = if (v == v.toInt().toFloat()) "${v.toInt()}" else "$v"
}
