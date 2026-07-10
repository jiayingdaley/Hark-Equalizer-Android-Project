package com.wcy.hark.audiometry

import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wcy.hark.HarkApplication
import com.wcy.hark.R
import com.wcy.hark.audio.bridge.HarkAudioBridge
import com.wcy.hark.audio.fitting.Prescriptions
import com.wcy.hark.audio.manager.SystemDspManager
import com.wcy.hark.data.experiment.EarphoneCalibrationRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * SubjectSessionActivity — 找人測試用的測試者測驗流程主控頁（實驗模式）。
 *
 * 依序引導：① 快速純音（自調式）→ ② 套用 DSL v5 處方（用剛測得的聽力圖）
 * → ③ 噪音下語詞 A/B 對照（固定模擬中度聽損處方 vs 無補償）→ ④ 環境輔聽
 * 問卷 → ⑤ 一鍵匯出。同一個測試者 ID 與耳機型號貫穿全程；換耳機重測時
 * 保留測試者 ID、重新走一次流程即可（支援「同一人戴多副耳機」的設計）。
 *
 * 任一步驟測得不理想都可用底下的「重測①/③/④」按鈕單獨重做，不會影響
 * 已完成的其他步驟（重測①會自動重新套用 DSL v5，因為處方本就取決於
 * 剛測得的聽力圖）。
 */
class SubjectSessionActivity : AppCompatActivity() {

    private var stage = 0
    private var subjectName = "未填寫"
    private var earphoneModel = "其他"
    private var sessionId = System.currentTimeMillis()

    private lateinit var editSubject: EditText
    private lateinit var spinnerEarphone: Spinner
    private lateinit var textStatus: TextView
    private lateinit var buttonNext: Button
    private lateinit var buttonExport: Button
    private lateinit var buttonRetestPta: Button
    private lateinit var buttonRetestAb: Button
    private lateinit var buttonRetestQuestionnaire: Button
    private lateinit var stepViews: List<TextView>
    private var earphoneCallback: android.media.AudioDeviceCallback? = null
    private var lastEarphoneInfo: String? = null

    // 各步驟被「提早結束/離開」（RESULT_CANCELED）時不可視為完成：
    // ① 純音取消時若照樣套用 DSL v5，會拿到「上一輪測試者」的舊聽力圖，
    //    處方就套錯人；③ 取消時若照樣前進，會在沒有 A/B 結果的狀態下
    //    開放問卷。取消一律退回該步驟的起點，讓實驗者重新開始該步驟。
    private val ptaLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            applyDslV5ThenAdvance()
        } else {
            if (stage == 1) stage = 0
            updateUi()
        }
    }
    private val abLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            stage = maxOf(stage, 4)
        } else if (stage == 3) {
            stage = 2
        }
        updateUi()
    }
    private val questionnaireLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // 問卷「略過」屬於測試者的正當選擇，略過與送出皆視為本步驟完成
        stage = maxOf(stage, 5)
        updateUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subject_session)
        window.statusBarColor = android.graphics.Color.parseColor("#F5F7FA")
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        editSubject = findViewById(R.id.editSessionSubjectName)
        spinnerEarphone = findViewById(R.id.spinnerSessionEarphone)
        textStatus = findViewById(R.id.textSessionStatus)
        buttonNext = findViewById(R.id.buttonSessionNext)
        buttonExport = findViewById(R.id.buttonSessionExport)
        buttonRetestPta = findViewById(R.id.buttonRetestPta)
        buttonRetestAb = findViewById(R.id.buttonRetestAb)
        buttonRetestQuestionnaire = findViewById(R.id.buttonRetestQuestionnaire)
        stepViews = listOf(
            findViewById(R.id.stepText1), findViewById(R.id.stepText2), findViewById(R.id.stepText3),
            findViewById(R.id.stepText4), findViewById(R.id.stepText5)
        )

        val calibRepo = EarphoneCalibrationRepository(this)
        val models = calibRepo.getEarphoneModels()
        spinnerEarphone.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, models)

        val repository = (application as HarkApplication).eqSettingsRepository
        lifecycleScope.launch {
            editSubject.setText(repository.getLastSubjectNameFlow().first())
            val savedModel = repository.getSelectedEarphoneFlow().first()
            val idx = models.indexOf(savedModel)
            if (idx >= 0) spinnerEarphone.setSelection(idx)
            // 先套上次選擇，再依實際連接的耳機自動預選（官方 API 回報型號；
            // 對不上校正表就不動選單，仍可手動改選）
            earphoneCallback = EarphoneAutoDetect.register(
                this@SubjectSessionActivity, spinnerEarphone, models
            ) { info ->
                if (info != null && info != lastEarphoneInfo) {
                    lastEarphoneInfo = info
                    android.widget.Toast.makeText(this@SubjectSessionActivity, info, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }

        buttonNext.setOnClickListener { onNextClicked() }
        buttonExport.setOnClickListener { doExport() }
        findViewById<Button>(R.id.buttonSessionHistory).setOnClickListener {
            startActivity(Intent(this, SubjectTestHistoryActivity::class.java))
        }
        buttonRetestPta.setOnClickListener { launchPta() }
        buttonRetestAb.setOnClickListener { launchAb() }
        buttonRetestQuestionnaire.setOnClickListener { launchQuestionnaire() }
        updateUi()
    }

    private fun updateUi() {
        stepViews.forEachIndexed { i, tv -> tv.alpha = if (i == stage) 1.0f else if (i < stage) 0.7f else 0.4f }
        // 整體進度（Goal-Gradient）：stage 0..5 對應已完成步數
        val done = stage.coerceIn(0, 5)
        findViewById<android.widget.ProgressBar>(R.id.progressSessionOverall).progress = done
        findViewById<TextView>(R.id.textSessionProgress).text = "\u5df2\u5b8c\u6210 $done / 5 \u6b65"
        textStatus.text = when (stage) {
            0 -> "請輸入測試者 ID 並選擇耳機型號，按開始進行快速純音（自調式）。"
            1 -> "快速純音進行中…"
            2 -> "已套用 DSL v5 處方，準備開始噪音下語詞 A/B 對照。"
            3 -> "A/B 對照進行中…"
            4 -> "準備填寫環境輔聽問卷（請先用喇叭播放情境音，見 experiment_scenes/）。"
            else -> "流程已完成。若同一位測試者要換另一副耳機再測一次，" +
                    "耳機選單改選新型號後按「開始下一輪」即可（ID 不用重打）；" +
                    "也可用下方按鈕單獨重測任一步驟。"
        }
        buttonNext.text = when (stage) {
            0 -> "開始：快速純音"
            2 -> "開始：A/B 對照"
            4 -> "開始：填寫問卷"
            5 -> "開始下一輪（可換耳機／新測試者）"
            else -> "進行中…"
        }
        buttonNext.isEnabled = stage != 1 && stage != 3
        buttonExport.visibility = if (stage == 5) android.view.View.VISIBLE else android.view.View.GONE

        // 重測按鈕：只要該步驟做過一次（stage 已走到之後）就能單獨重做，
        // 不受目前處於哪一步的限制（進行中的活動全螢幕遮擋，不會誤觸）。
        buttonRetestPta.isEnabled = stage >= 2
        buttonRetestAb.isEnabled = stage >= 4
        buttonRetestQuestionnaire.isEnabled = stage >= 5
    }

    private fun onNextClicked() {
        when (stage) {
            0 -> {
                captureSubjectInputs()
                sessionId = System.currentTimeMillis() // 新測試者流程 → 新 session_id
                setDefaultComfortVolume()
                stage = 1; updateUi()
                launchPta()
            }
            2 -> { stage = 3; updateUi(); launchAb() }
            4 -> { stage = 4; updateUi(); launchQuestionnaire() }
            5 -> {
                // 重新開始：保留測試者 ID 輸入框內容，方便同一人換耳機重測；
                // 換人時請自行清空/修改姓名欄後再按開始。
                stage = 0; updateUi()
            }
        }
    }

    /**
     * 每輪流程開始前把媒體音量重設到最大音量的 1/3，作為統一、不過大聲的
     * 起始點——手機原本的媒體音量可能殘留自其他 app（常偏大），若直接
     * 沿用，快速純音（會鎖到最大音量做校正）結束、還原後仍可能太大聲，
     * 後續 A/B 對照的舒適音量調整也會從一個過大的起點開始。
     */
    private fun setDefaultComfortVolume() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = (max / 3f).roundToInt().coerceAtLeast(1)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        } catch (e: Exception) { /* best effort */ }
    }

    private fun captureSubjectInputs() {
        subjectName = editSubject.text.toString().trim().ifEmpty { "未填寫" }
        earphoneModel = spinnerEarphone.selectedItem as? String ?: "其他"
        val repository = (application as HarkApplication).eqSettingsRepository
        lifecycleScope.launch {
            repository.saveLastSubjectName(subjectName)
            repository.saveSelectedEarphone(earphoneModel)
        }
    }

    private fun launchPta() {
        captureSubjectInputs()
        // 直接帶入姓名/耳機型號（而非讓 PTA 去讀 DataStore）：captureSubjectInputs()
        // 的存檔是非同步的，若 PTA 改讀 Flow 會有競態，可能顯示錯誤的耳機型號。
        ptaLauncher.launch(Intent(this, SelfAdjustPtaActivity::class.java).apply {
            putExtra("EXTRA_SESSION_FLOW", true)
            putExtra("EXTRA_SUBJECT", subjectName)
            putExtra("EXTRA_EARPHONE_MODEL", earphoneModel)
        })
    }

    private fun launchAb() {
        abLauncher.launch(Intent(this, SSNAbTestActivity::class.java).apply {
            putExtra("EXTRA_SUBJECT", subjectName)
            putExtra("EXTRA_EARPHONE_MODEL", earphoneModel)
        })
    }

    private fun launchQuestionnaire() {
        questionnaireLauncher.launch(Intent(this, QuestionnaireActivity::class.java).apply {
            putExtra("EXTRA_SUBJECT", subjectName)
            putExtra("EXTRA_SESSION_ID", sessionId)
            putExtra("EXTRA_EARPHONE_MODEL", earphoneModel)
        })
    }

    /** 讀取剛測得的聽力圖，計算 DSL v5 16 段增益並套用到即時 EQ（雙耳）。 */
    private fun applyDslV5ThenAdvance() {
        val repository = (application as HarkApplication).eqSettingsRepository
        lifecycleScope.launch {
            val testFrequencies = listOf(250, 500, 1000, 2000, 3000, 4000, 6000, 8000)
            suspend fun loadAudiogram(ear: String): Map<Int, Float> = buildMap {
                for (freq in testFrequencies) {
                    val t = repository.getAudiogramThresholdFlow(ear, freq).first()
                    if (t != -1) put(freq, t.toFloat())
                }
            }
            val leftAudiogram = loadAudiogram("left")
            val rightAudiogram = loadAudiogram("right")
            val binaural = leftAudiogram.isNotEmpty() && rightAudiogram.isNotEmpty()

            // 逐頻段「先寫入 DataStore、再更新即時 DSP」皆在同一協程內依序
            // await，確保下一步（A/B 對照快照/還原增益）讀到的是本次結果，
            // 不會被 Activity 生命週期提早取消而遺漏。
            //
            // 同時更新 HarkAudioBridge（真正即時麥克風收音處理引擎）與
            // SystemDspManager（測驗播放音軌用的模擬路徑）——先前只更新
            // 後者，導致問卷情境音環節的「即時輔聽」實際上從未套用過
            // 這裡算出的 DSL v5 增益，是個潛在 bug。
            suspend fun applyEar(earIndex: Int, ear: String, audiogram: Map<Int, Float>) {
                if (audiogram.isEmpty()) return
                Prescriptions.CENTER_FREQUENCIES_16.forEachIndexed { i, freq ->
                    val threshold = interpolateThreshold(freq, audiogram) ?: return@forEachIndexed
                    val gain = Prescriptions.dslV5Gain(freq, threshold, binaural).coerceIn(0f, 30f)
                    repository.saveBandGain(ear, 0, i, gain)
                    HarkAudioBridge.setBandGain(earIndex, i, gain)
                    SystemDspManager.updateBandGain(earIndex, i, gain)
                }
            }
            applyEar(0, "left", leftAudiogram)
            applyEar(1, "right", rightAudiogram)

            stage = maxOf(stage, 2)
            updateUi()
        }
    }

    private fun interpolateThreshold(freqHz: Int, audiogram: Map<Int, Float>): Float? {
        if (audiogram.isEmpty()) return null
        audiogram[freqHz]?.let { return it }
        val sorted = audiogram.entries.sortedBy { it.key }
        if (freqHz <= sorted.first().key) return sorted.first().value
        if (freqHz >= sorted.last().key) return sorted.last().value
        val hi = sorted.first { it.key > freqHz }
        val lo = sorted.last { it.key < freqHz }
        val t = (ln(freqHz.toFloat()) - ln(lo.key.toFloat())) / (ln(hi.key.toFloat()) - ln(lo.key.toFloat()))
        return lo.value + (hi.value - lo.value) * t
    }

    private fun doExport() {
        val uri = SubjectExportUtil.exportSubjectData(this, subjectName) ?: return
        startActivity(SubjectExportUtil.shareIntent(this, uri))
    }

    override fun onDestroy() {
        earphoneCallback?.let { EarphoneAutoDetect.unregister(this, it) }
        earphoneCallback = null
        super.onDestroy()
    }
}
