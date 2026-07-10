package com.wcy.hark.audiometry
import com.wcy.hark.R

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import androidx.activity.ComponentActivity
import kotlin.jvm.java

class TestSelectActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test_select)
        window.statusBarColor = android.graphics.Color.parseColor("#F5F7FA")
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        // Stop background environmental hearing aid and system-wide DSP to prevent interference during tests
        val stopServiceIntent = Intent(this, com.wcy.hark.audio.service.HarkAudioService::class.java).apply {
            action = com.wcy.hark.audio.service.HarkAudioService.ACTION_STOP
        }
        startService(stopServiceIntent)
        
        com.wcy.hark.audio.manager.SystemDspManager.setEnabled(false)
        com.wcy.hark.audio.manager.SystemDspManager.clearAllEffects()
        stopService(Intent(this, com.wcy.hark.audio.service.FloatingEqService::class.java))

        val pureToneTestButton = findViewById<Button>(R.id.button_pure_tone_test)
        val speechAudiometryButton = findViewById<Button>(R.id.button_speech_audiometry)

        // 顏色一律由 layout XML 的 backgroundTint 管理。層級規則：
        // 主測驗（純音聽力測試 #00695C、語詞測試 #D84315）＝深色實心白字、最醒目；
        // 輔助測驗（快速純音 #B2DFDB、噪音下語詞 #FFCCBC）＝同家族淺色底深字、次要。
        pureToneTestButton.apply {
            setTextColor(Color.WHITE)
            setOnClickListener {
                val intent = Intent(this@TestSelectActivity, SelectEarActivity::class.java)
                startActivity(intent)
            }
        }

        // 設定 "Speech Audiometry" 按鈕的樣式和點擊事件
        speechAudiometryButton.apply {
            setTextColor(Color.WHITE)
            setOnClickListener {
                val intent = Intent(this@TestSelectActivity, SpeechAudiometryExplanationActivity::class.java)
                startActivity(intent)
            }
        }

        // 快速純音（自調式）：輔助測驗 → 淺 teal 底＋深 teal 字，視覺上退居次要
        findViewById<Button>(R.id.button_self_adjust_pta).apply {
            setTextColor(Color.parseColor("#00695C"))
            setOnClickListener {
                startActivity(Intent(this@TestSelectActivity, SelfAdjustPtaActivity::class.java))
            }
        }

        // 噪音下語詞測驗：輔助測驗 → 淺橘底＋深橘字，視覺上退居次要
        findViewById<Button>(R.id.button_ssn_test).apply {
            setTextColor(Color.parseColor("#BF360C"))
            setOnClickListener {
                startActivity(Intent(this@TestSelectActivity, SSNExplanationActivity::class.java))
            }
        }

        // 設定 "View History" 按鈕的樣式和點擊事件
        findViewById<Button>(R.id.button_view_history).apply {
            setTextColor(Color.WHITE)
            setOnClickListener {
                val intent = Intent(this@TestSelectActivity, TestHistoryActivity::class.java)
                startActivity(intent)
            }
        }
    }
}