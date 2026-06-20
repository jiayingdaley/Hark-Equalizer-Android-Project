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

        val pureToneTestButton = findViewById<Button>(R.id.button_pure_tone_test)
        val speechAudiometryButton = findViewById<Button>(R.id.button_speech_audiometry)

        // 設定 "Pure-Tone Test" 按鈕的樣式和點擊事件
        pureToneTestButton.apply {
            setBackgroundColor(Color.rgb(70, 147, 211)) // R70 G147 B211
            setTextColor(Color.WHITE)
            setOnClickListener {
                val intent = Intent(this@TestSelectActivity, SelectEarActivity::class.java)
                startActivity(intent)
            }
        }

        // 設定 "Speech Audiometry" 按鈕的樣式和點擊事件
        speechAudiometryButton.apply {
            setBackgroundColor(Color.rgb(234, 135, 69)) // R234 G135 B69
            setTextColor(Color.WHITE)
            setOnClickListener {
                val intent = Intent(this@TestSelectActivity, SpeechAudiometryExplanationActivity::class.java)
                startActivity(intent)
            }
        }
    }
}