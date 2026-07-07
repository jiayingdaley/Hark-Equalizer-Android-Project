package com.wcy.hark.audiometry
import com.wcy.hark.R
import com.wcy.hark.HarkApplication

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * SSNExplanationActivity — 噪音下語詞測驗的說明與設置頁。
 *
 * 與 SpeechAudiometryExplanationActivity（語詞測試）相同的流程與版型：
 * 說明 + 設置（姓名 / DSP 開關 / 實驗模式 SNR 設定）→ OK → 舒適音量調整
 * → 音量確認後啟動 SSNTestActivity。音量對話框按 X 會自然回到本頁。
 */
class SSNExplanationActivity : AppCompatActivity(), DialogNavCallback {

    private lateinit var switchApplyDsp: android.widget.Switch
    private lateinit var nameInput: EditText
    private lateinit var snrInput: EditText
    private lateinit var countInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ssn_explanation)
        window.statusBarColor = android.graphics.Color.parseColor("#F5F7FA")
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        switchApplyDsp = findViewById(R.id.switchSsnApplyDsp)
        nameInput = findViewById(R.id.editTextSsnSubjectName)
        snrInput = findViewById(R.id.editTextSsnSnrList)
        countInput = findViewById(R.id.editTextSsnQuestionsPerSnr)

        val repository = (application as HarkApplication).eqSettingsRepository
        lifecycleScope.launch {
            nameInput.setText(repository.getLastSubjectNameFlow().first())
            // 實驗模式才顯示 SNR 條件與題數設定
            if (repository.getExperimentModeFlow().first()) {
                findViewById<View>(R.id.layoutSsnExperimentConfig).visibility = View.VISIBLE
            }
        }

        findViewById<Button>(R.id.buttonSsnStartVolumeAdjustment).setOnClickListener {
            val entered = nameInput.text.toString().trim()
            if (entered.isNotEmpty()) {
                lifecycleScope.launch { repository.saveLastSubjectName(entered) }
            }
            VolumeAdjustmentDialogFragment().show(supportFragmentManager, VolumeAdjustmentDialogFragment.TAG)
        }
    }

    private fun startSsnTest() {
        val entered = nameInput.text.toString().trim()
        val snrs = snrInput.text.toString()
            .split(",").mapNotNull { it.trim().toFloatOrNull() }
            .distinct().sortedDescending()
            .ifEmpty { listOf(10f, 5f, 0f, -5f, -10f) }
        val qps = countInput.text.toString().toIntOrNull()?.coerceIn(1, 20) ?: 5

        val intent = Intent(this, SSNTestActivity::class.java).apply {
            putExtra("EXTRA_APPLY_DSP", switchApplyDsp.isChecked)
            putExtra("EXTRA_SUBJECT", if (entered.isEmpty()) "未填寫" else entered)
            putExtra("EXTRA_SNRS", snrs.toFloatArray())
            putExtra("EXTRA_QUESTIONS_PER_SNR", qps)
        }
        startActivity(intent)
        finish()
    }

    // --- DialogNavCallback ---
    // 音量對話框按 OK → 直接開始測驗（說明與設置已在本頁完成）
    override fun onVolumeAdjustedShowInstructions() {
        startSsnTest()
    }

    override fun onInstructionsDismissedShowVolume() {
        VolumeAdjustmentDialogFragment().show(supportFragmentManager, VolumeAdjustmentDialogFragment.TAG)
    }

    override fun onStartSrtTestFromInstructions() {
        startSsnTest()
    }
}
