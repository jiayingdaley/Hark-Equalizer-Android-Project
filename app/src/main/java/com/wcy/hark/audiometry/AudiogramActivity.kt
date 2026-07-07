package com.wcy.hark.audiometry
import com.wcy.hark.R
import com.wcy.hark.HarkApplication

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wcy.hark.data.experiment.EarphoneCalibrationRepository
import com.wcy.hark.data.experiment.FreqCalibration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AudiogramActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audiogram)
        window.statusBarColor = android.graphics.Color.WHITE
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        supportActionBar?.title = "Pure-Tone Test Result"

        val leftEarResults = intent.getSerializableExtra("LEFT_EAR_RESULTS") as? Map<Int, Int?> ?: emptyMap()
        val rightEarResults = intent.getSerializableExtra("RIGHT_EAR_RESULTS") as? Map<Int, Int?> ?: emptyMap()

        val audiogramView = findViewById<AudiogramView>(R.id.audiogram_view)
        audiogramView.setResults(leftEarResults, rightEarResults)

        val toggleButton = findViewById<Button>(R.id.button_toggle_unit)

        // 預設以 dB FS 顯示（聽力圖式：越上方越小聲），可切換回 dB HL。
        // dB FS 換算採耳機校正表（dbfs = refDbfs + (dBHL + RETSPL) − measuredDbSpl）。
        val repository = (application as HarkApplication).eqSettingsRepository
        lifecycleScope.launch {
            val model = repository.getSelectedEarphoneFlow().first()
            val calibRepo = EarphoneCalibrationRepository(this@AudiogramActivity)
            val table: Map<Int, FreqCalibration> =
                withContext(Dispatchers.IO) { calibRepo.getAllCalibrations(model) }

            audiogramView.setDbfsConverter { freqHz, dbHl ->
                // Calibrated conversion; fallback to the same relative mapping the
                // tone playback uses when the earphone is uncalibrated (100 dB HL = 0 dBFS),
                // so dB FS mode always shows the data points.
                calibRepo.dbfsForTargetDbhl(table, freqHz, dbHl.toFloat())
                    ?: (dbHl.toFloat() - 100f)
            }

            var showDbfs = true
            audiogramView.setDisplayDbfs(true)
            toggleButton.text = "顯示 dB HL (Show dB HL)"
            toggleButton.setOnClickListener {
                showDbfs = !showDbfs
                audiogramView.setDisplayDbfs(showDbfs)
                toggleButton.text = if (showDbfs) "顯示 dB HL (Show dB HL)"
                                    else "顯示 dB FS (Show dB FS)"
            }
        }
    }
}
