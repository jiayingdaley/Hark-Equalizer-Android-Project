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

        val points = snrs.zip(scores.toTypedArray()) { a, b -> a to b }
        findViewById<PsychometricView>(R.id.psychometric_view)
            .setData(points, if (srt50.isNaN()) null else srt50)

        findViewById<TextView>(R.id.textViewSsnSummary).text = buildString {
            append("使用者：$subject\n")
            if (!srt50.isNaN()) {
                append("SRT50（50%% 辨識率之訊噪比）：${String.format("%.1f", srt50)} dB SNR\n".format())
                append("SRT50 越低代表在噪音中的語音理解能力越好。")
            } else {
                append("無法內插出 SRT50（辨識率未跨越 50%），可調整 SNR 範圍後重測。")
            }
        }
    }
}
