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
    private var isExperimentMode = false

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
            isExperimentMode = repository.getExperimentModeFlow().first()
            if (isExperimentMode) {
                findViewById<View>(R.id.layoutSsnExperimentConfig).visibility = View.VISIBLE
            }
        }

        findViewById<Button>(R.id.buttonSsnStartVolumeAdjustment).setOnClickListener {
            val entered = nameInput.text.toString().trim()
            if (entered.isNotEmpty()) {
                lifecycleScope.launch { repository.saveLastSubjectName(entered) }
            }
            // 不再做「舒適音量」調整：本測驗的呈現位準一律以 dB SL（測試者純音閾值
            // 為零點）於數位域決定，測驗開始時系統音量鎖到最大（見 SSNTestActivity
            // 與 AudiometryVolume）。舊的系統音量拉桿會在測驗一開始被 lockToMax 覆蓋，
            // 造成「調的音量」與「測驗音量」不一致，且破壞 dB SL 基準——故直接進測驗。
            // 使用者模式（非實驗模式）只提供「噪音下語詞」；「無噪音、小聲的語詞」
            // 為實驗模式限定測項。
            if (!isExperimentMode) {
                noiseless = false
                startSsnTest()
            } else {
                // 先讓使用者選測驗類型：有噪音（SSN）或無噪音但小聲
                android.app.AlertDialog.Builder(this)
                    .setTitle("選擇語詞測驗類型")
                    .setItems(arrayOf("有噪音的語詞（噪音下 SNR 掃描）", "無噪音、小聲的語詞（音量 dB SL 掃描）")) { _, which ->
                        noiseless = (which == 1)
                        startSsnTest()
                    }
                    .show()
            }
        }
    }

    private var noiseless = false

    private fun startSsnTest() {
        val entered = nameInput.text.toString().trim()
        val qps = countInput.text.toString().toIntOrNull()?.coerceIn(1, 20) ?: 7

        val intent = Intent(this, SSNTestActivity::class.java).apply {
            putExtra("EXTRA_APPLY_DSP", switchApplyDsp.isChecked)
            putExtra("EXTRA_SUBJECT", if (entered.isEmpty()) "未填寫" else entered)
            putExtra("EXTRA_QUESTIONS_PER_SNR", qps)
            putExtra("EXTRA_NOISELESS", noiseless)
            // 實驗模式選項：語音先經離線 NLFC（一般模式下核取框隱藏、恆為 false）
            putExtra("EXTRA_NLFC",
                findViewById<android.widget.CheckBox>(R.id.checkBoxSsnNlfc).isChecked)
            // 有噪音模式才傳 SNR 清單；無噪音模式由 SSNTestActivity 用 dB SL 預設。
            if (!noiseless) {
                val snrs = snrInput.text.toString()
                    .split(",").mapNotNull { it.trim().toFloatOrNull() }
                    .distinct().sortedDescending()
                    .ifEmpty { listOf(0f, -3f, -6f, -9f, -12f, -15f, -18f) }
                putExtra("EXTRA_SNRS", snrs.toFloatArray())
            }
        }
        startActivity(intent)
        finish()
    }

    // --- DialogNavCallback ---
    // 保留介面實作以滿足 DialogNavCallback，但本流程已不再做舒適音量調整
    // （會破壞 dB SL 基準，見上方 onClick 註解與 AudiometryVolume）。
    override fun onVolumeAdjustedShowInstructions() { startSsnTest() }

    override fun onInstructionsDismissedShowVolume() { /* 不再調整音量 */ }

    override fun onStartSrtTestFromInstructions() { startSsnTest() }
}
