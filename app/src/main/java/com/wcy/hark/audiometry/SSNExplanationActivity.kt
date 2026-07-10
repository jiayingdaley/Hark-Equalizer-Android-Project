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
            // 先讓使用者選測驗類型：有噪音（SSN）或無噪音但小聲
            android.app.AlertDialog.Builder(this)
                .setTitle("選擇語詞測驗類型")
                .setItems(arrayOf("有噪音的語詞（噪音下 SNR 掃描）", "無噪音、小聲的語詞（音量 dB SL 掃描）")) { _, which ->
                    noiseless = (which == 1)
                    // 無噪音模式對「小聲語詞」設定舒適音量，故參考音改用語音樣本
                    val ref = if (noiseless) "adjust_mcl" else "ssn_noise"
                    VolumeAdjustmentDialogFragment.newInstance(ref)
                        .show(supportFragmentManager, VolumeAdjustmentDialogFragment.TAG)
                }
                .show()
        }
    }

    private var noiseless = false

    private fun startSsnTest() {
        val entered = nameInput.text.toString().trim()
        val qps = countInput.text.toString().toIntOrNull()?.coerceIn(1, 20) ?: 5

        val intent = Intent(this, SSNTestActivity::class.java).apply {
            putExtra("EXTRA_APPLY_DSP", switchApplyDsp.isChecked)
            putExtra("EXTRA_SUBJECT", if (entered.isEmpty()) "未填寫" else entered)
            putExtra("EXTRA_QUESTIONS_PER_SNR", qps)
            putExtra("EXTRA_NOISELESS", noiseless)
            // 有噪音模式才傳 SNR 清單；無噪音模式由 SSNTestActivity 用 dB SL 預設。
            if (!noiseless) {
                val snrs = snrInput.text.toString()
                    .split(",").mapNotNull { it.trim().toFloatOrNull() }
                    .distinct().sortedDescending()
                    .ifEmpty { listOf(0f, -5f, -10f, -15f, -20f, -25f) }
                putExtra("EXTRA_SNRS", snrs.toFloatArray())
            }
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
        VolumeAdjustmentDialogFragment.newInstance("ssn_noise").show(supportFragmentManager, VolumeAdjustmentDialogFragment.TAG)
    }

    override fun onStartSrtTestFromInstructions() {
        startSsnTest()
    }
}
