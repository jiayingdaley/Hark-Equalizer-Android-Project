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

        // 設定 "Pure-Tone Test" 按鈕的樣式和點擊事件（tint 保留圓角背景）
        pureToneTestButton.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(70, 147, 211))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val intent = Intent(this@TestSelectActivity, SelectEarActivity::class.java)
                startActivity(intent)
            }
        }

        // 設定 "Speech Audiometry" 按鈕的樣式和點擊事件
        speechAudiometryButton.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(234, 135, 69))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val intent = Intent(this@TestSelectActivity, SpeechAudiometryExplanationActivity::class.java)
                startActivity(intent)
            }
        }

        // 噪音下語詞測驗 (SSN speech-in-noise)
        findViewById<Button>(R.id.button_ssn_test).apply {
            setTextColor(Color.WHITE)
            setOnClickListener {
                startActivity(Intent(this@TestSelectActivity, SSNTestActivity::class.java))
            }
        }

        // 設定 "View History" 按鈕的樣式和點擊事件
        findViewById<Button>(R.id.button_view_history).apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(103, 58, 183))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val intent = Intent(this@TestSelectActivity, TestHistoryActivity::class.java)
                startActivity(intent)
            }
        }
    }
}