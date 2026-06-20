package com.wcy.hark.audiometry
import com.wcy.hark.R

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity


class AudiogramActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audiogram)

        supportActionBar?.title = "Pure-Tone Test Result"

        val leftEarResults = intent.getSerializableExtra("LEFT_EAR_RESULTS") as? Map<Int, Int?> ?: emptyMap()
        val rightEarResults = intent.getSerializableExtra("RIGHT_EAR_RESULTS") as? Map<Int, Int?> ?: emptyMap()

        val audiogramView = findViewById<AudiogramView>(R.id.audiogram_view)
        audiogramView.setResults(leftEarResults, rightEarResults)
    }
}