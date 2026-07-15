package com.wcy.hark.audiometry

import android.content.ContentValues
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wcy.hark.HarkApplication
import com.wcy.hark.R
import com.wcy.hark.audio.fitting.Prescriptions
import com.wcy.hark.audio.manager.SystemDspManager
import com.wcy.hark.audiometry.sqlite.SRTResultContract
import com.wcy.hark.audiometry.sqlite.SRTResultDbHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * SSNAbTestActivity — 語詞測驗 A/B 對照（測試者實驗流程專用）。
 *
 * 比較「未輔助（OFF）」vs「輔助（ON）」的語詞辨識表現。順序隨機決定並交叉
 * 平衡（記錄 off_first），過程中不告知測試者目前是哪個條件，避免預期心理。
 *
 * ★ 兩個條件都經過聽損模擬器 ★
 *   未輔助：語音 →                  [聽損模擬] → 測試者
 *   輔助：  語音 → [DSP 補償] →     [聽損模擬] → 測試者
 *
 * 若不模擬聽損，補償的對象並不存在——正常耳沒有落在聽閾以下的線索可救，
 * 混音後的語音與噪音一起被放大、訊噪比不變，效果期望值為零。模擬器把測試者
 * 「變成」聽損者，補償才有可檢驗的對象。順序不可對調：真實情境是助聽器先
 * 處理、聲音才進入受損的耳蝸。
 *
 * 主要測項為安靜情境（noiseless，條件值為感覺級 dB SL）；噪音情境（SSN）
 * 為選用測項，由主控頁決定是否施行。
 *
 * ★ 系統音量全程鎖定在最大，不做「舒適音量調整」★
 * 條件值是 dB SL（以測試者自己的純音聽閾為零點），而聽閾是在系統音量最大時量的。
 * 若在此讓測試者把音量調小，「聽閾 + N dB」這個換算在播放端就不成立，刺激會落到
 * 聽閾附近甚至以下，兩個條件通通聽不見。位準一律在數位域（dBFS）控制。
 * 詳見 AudiometryVolume。
 */
class SSNAbTestActivity : AppCompatActivity(), DialogNavCallback {

    private var subjectName = "未填寫"
    private var earphoneModel = "其他"
    // 噪音模式的 SNR 條件。
    //
    // ★ 條件數 × 每條件題數 ≤ 25 ★ 詞庫共 50 詞，A/B 兩階段用互斥的分半
    //（杜絕背答案），每階段只有 25 個詞可用——6 條件 × 5 題 = 30 會直接
    // 「詞庫題目不足」開不了測。5 條件 × 5 題 = 25 恰好用滿。
    //
    // 捨去最難的 −25 dB 而非最簡單的 0 dB：0 dB 是簡單端錨點（確認測試者在
    // 容易條件下接近滿分，排除操作/理解問題）；且套用聽損模擬後整體變難，
    // 50% 交叉點會比正常聽力時出現在更高（較不負）的 SNR，−25 dB 最可能
    // 兩條件都趴地板、對心理測量曲線沒有貢獻。
    private var snrConditions = floatArrayOf(0f, -5f, -10f, -15f, -20f)
    private var questionsPerSnr = 5

    /**
     * true = 安靜情境（主要測項）：不加遮蔽噪音，條件值為感覺級 dB SL。
     *
     * 為什麼安靜情境是主要測項：本研究的聽損模擬以擴展器層為主，補償的作用機制
     * 純粹是「把落在模擬聽閾下的語音線索拉回可聽範圍」——安靜情境正可直接檢驗
     * 這個機制，效果大且可解釋。噪音情境下增益同時作用於語音與噪音、不改變訊噪比，
     * 補償效益遠小得多，在 n = 3–5 的樣本下極可能被個體變異蓋掉。
     */
    private var noiseless = true
    // 20→0 dB SL（原 25→5 有輔助條件全對之天花板效應，SL50 算不出）
    private val quietConditions = floatArrayOf(20f, 15f, 10f, 5f, 0f)

    private var offFirst = true
    private var stage = 0            // 0=尚未開始, 1=第一階段進行中, 2=第二階段進行中, 3=完成
    private var srt50Off: Float? = null
    private var srt50On: Float? = null
    private var sessionIdOff: Long? = null
    private var sessionIdOn: Long? = null
    private val groupId = System.currentTimeMillis()

    // ON 階段前快照的使用者即時 EQ 增益（結束後還原）
    private var savedGainsLeft: List<Float>? = null
    private var savedGainsRight: List<Float>? = null

    // 進場前的系統音量（離場時還原）
    private var originalMediaVolume = 0

    private lateinit var textStatus: TextView
    private lateinit var textResult: TextView
    private lateinit var buttonNext: Button

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val condition = data?.getStringExtra("EXTRA_AB_CONDITION")
        val srt50 = data?.getFloatExtra("EXTRA_SRT50", Float.NaN)
        val sid = data?.getLongExtra("EXTRA_SESSION_ID", -1L)
        val validSrt = srt50?.takeIf { !it.isNaN() }
        if (condition == "OFF") { srt50Off = validSrt; sessionIdOff = sid?.takeIf { it > 0 } }
        else if (condition == "ON") { srt50On = validSrt; sessionIdOn = sid?.takeIf { it > 0 } }

        if (condition == "ON") restoreLiveGains()

        advanceStage()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        disableSystemBackNavigation()
        setContentView(R.layout.activity_ssn_ab_test)
        window.statusBarColor = android.graphics.Color.parseColor("#F5F7FA")
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        subjectName = intent.getStringExtra("EXTRA_SUBJECT") ?: "未填寫"
        earphoneModel = intent.getStringExtra("EXTRA_EARPHONE_MODEL") ?: "其他"
        noiseless = intent.getBooleanExtra("EXTRA_NOISELESS", true)
        if (noiseless) snrConditions = quietConditions
        intent.getFloatArrayExtra("EXTRA_SNRS")?.takeIf { it.isNotEmpty() }?.let { snrConditions = it }
        questionsPerSnr = intent.getIntExtra("EXTRA_QUESTIONS_PER_SNR", questionsPerSnr)
        // ★ 固定「未輔助（OFF）先測」，不再隨機 ★
        // 隨機順序的本意是抵銷練習效應，但若 ON（較容易聽清）先測，測試者會記住
        // 出現過的詞，OFF 階段靠回憶作答——記憶效應遠大於一般練習效應。固定
        // OFF 先測，加上兩階段使用互斥的詞表分半（見 EXTRA_WORD_PARITY），
        // 殘餘的練習效應只會「高估」補償效益，方向已知、可在論文中討論。
        offFirst = true

        // 標題與說明依模式設定。佈局裡的預設文字是舊設計的殭屍文案
        //（「噪音下語詞測驗」「模擬中度聽損補償」「調整舒適音量」），
        // 安靜模式的測試者看到會以為進錯測驗（實測真的被誤會了）。
        val kindTitle = if (noiseless) "中文語詞測驗（安靜）" else "噪音下語詞測驗（SSN）"
        findViewById<TextView>(R.id.textAbTitle).text = "$kindTitle：A/B 對照"
        findViewById<TextView>(R.id.textAbBody).text =
            (if (noiseless)
                "接下來會測驗兩輪安靜情境的中文語詞辨識（音量由大漸小）"
            else
                "接下來會測驗兩輪噪音下的中文語詞辨識（噪音固定、語詞大小變化）") +
            "：一輪「無 Hark 補償」、一輪「有 Hark 補償」，順序隨機、過程中不告知。" +
            "兩輪皆套用相同的模擬聽損，題目與程序完全相同。音量由程式控制，不需調整。"
        textStatus = findViewById(R.id.textAbStatus)
        textResult = findViewById(R.id.textAbResult)
        buttonNext = findViewById(R.id.buttonAbNext)
        buttonNext.setOnClickListener { onNextClicked() }
        findViewById<android.view.View>(R.id.buttonAbBack).setOnClickListener { confirmExitEarly() }
        updateStatusText()
    }

    override fun onResume() {
        super.onResume()
        // 與純音測驗同一個電聲增益 —— dB SL 基準才成立
        originalMediaVolume = AudiometryVolume.lockToMax(this)
    }

    override fun onPause() {
        super.onPause()
        AudiometryVolume.restore(this, originalMediaVolume)
    }

    // 保留介面實作以滿足 DialogNavCallback，但本流程不再做舒適音量調整
    // （會破壞 dB SL 基準，理由見類別註解與 AudiometryVolume）。
    override fun onVolumeAdjustedShowInstructions() { startStage() }

    override fun onInstructionsDismissedShowVolume() { /* 不再調整音量 */ }

    override fun onStartSrtTestFromInstructions() { /* not used in A/B flow */ }

    // 兩階段之間鎖定音量鍵：OFF/ON 必須在相同呈現級別下比較才有效
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun confirmExitEarly() {
        AlertDialog.Builder(this)
            .setTitle("提早結束")
            .setMessage("確定要結束 A/B 對照測驗嗎？尚未完成的階段將不會有結果。")
            .setPositiveButton("結束") { _, _ ->
                restoreLiveGains()
                setResult(RESULT_CANCELED)
                finish()
            }
            .setNegativeButton("繼續測驗", null)
            .show()
    }

    private fun updateStatusText() {
        val kind = if (noiseless) "安靜語詞測驗" else "噪音語詞測驗（SSN）"
        textStatus.text = when (stage) {
            // 明講兩階段是「未輔助 vs 輔助」——實測有測試者看到「共兩階段」
            // 以為自己選錯成噪音測驗。順序保密（隨機），避免期望效應。
            0 -> "$kind — A/B 對照\n\n共兩階段：一段「無 Hark 補償」、一段「有 Hark 補償」，" +
                    "順序隨機、過程中不告知。兩階段題目與程序完全相同。"
            1 -> "$kind — 第一階段進行中…"
            2 -> "$kind — 第二階段進行中…"
            else -> "已完成"
        }
    }

    private fun onNextClicked() {
        if (stage == 3) { finishFlow(); return }
        startStage()
    }

    private fun startStage() {
        stage += 1
        updateStatusText()
        val isFirstBlock = stage == 1
        val runOffNow = if (isFirstBlock) offFirst else !offFirst
        if (runOffNow) launchOff() else launchOn()
    }

    private fun blockIntent(applyDsp: Boolean, condition: String) =
        Intent(this, SSNTestActivity::class.java).apply {
            putExtra("EXTRA_APPLY_DSP", applyDsp)
            putExtra("EXTRA_SUBJECT", subjectName)
            putExtra("EXTRA_EARPHONE_MODEL", earphoneModel)
            putExtra("EXTRA_NOISELESS", noiseless)
            putExtra("EXTRA_SNRS", snrConditions)
            putExtra("EXTRA_QUESTIONS_PER_SNR", questionsPerSnr)
            putExtra("EXTRA_AB_MODE", true)
            putExtra("EXTRA_AB_CONDITION", condition)
            // 互斥詞表：OFF 用偶數半、ON 用奇數半——同一個詞絕不跨階段重複，
            // 杜絕「背答案」
            putExtra("EXTRA_WORD_PARITY", if (condition == "OFF") 0 else 1)
            putExtra("EXTRA_HL_SIM_CHECK_ERR",
                intent.getFloatExtra("EXTRA_HL_SIM_CHECK_ERR", Float.NaN))
        }

    private fun launchOff() {
        launcher.launch(blockIntent(applyDsp = false, condition = "OFF"))
    }

    /**
     * 輔助條件（ON）。
     *
     * 舊版在此把增益覆寫成 Prescriptions.simulatedModerateLossGains16()，
     * 亦即「對聽力正常的人套用一組模擬中度聽損算出的處方」——但當時並沒有真的
     * 模擬聽損，補償的對象不存在：語音與噪音混音後一起被放大、訊噪比不變，
     * ΔSRT50 的期望值是 0（甚至因壓縮失真為負）。
     *
     * 現在流程改為：先以「測試者實測聽閾 + 模擬損失量」算 DSL v5 處方並寫入
     * DataStore（見 SubjectSessionActivity.applyDslV5ThenAdvance），SSNTestActivity
     * 再從 DataStore 讀出，於離線鏈中先補償、後模擬聽損。故此處不再覆寫任何增益，
     * 也不再經由 SystemDspManager（系統音效掛在 session 之後就接不了模擬器）。
     */
    private fun launchOn() {
        launcher.launch(blockIntent(applyDsp = true, condition = "ON"))
    }

    /**
     * 舊版於 ON 階段覆寫過即時增益，故需還原。現在 A/B 兩條件皆走離線鏈、
     * 不動任何即時 EQ 設定，因此無須還原——保留空實作以維持呼叫點不變。
     */
    private fun restoreLiveGains() {
        savedGainsLeft = null; savedGainsRight = null
    }

    private fun advanceStage() {
        updateStatusText()
        if (stage < 2) {
            buttonNext.text = "開始第二階段"
        } else {
            stage = 3
            updateStatusText()
            showComparisonResult()
            saveAbSession()
            buttonNext.text = "完成"
        }
    }

    private fun showComparisonResult() {
        val off = srt50Off; val on = srt50On
        textResult.visibility = android.view.View.VISIBLE
        // 安靜情境的橫軸是感覺級（dB SL），噪音情境才是訊噪比（dB SNR）
        val unit = if (noiseless) "dB SL" else "dB SNR"
        val metric = if (noiseless) "SL50" else "SRT50"
        if (off != null && on != null) {
            val delta = off - on
            val verdict = when {
                delta > 0.5f ->
                    if (noiseless) "套用補償後，所需音量降低 %.1f dB（可聽度改善）".format(delta)
                    else "套用補償後所需訊噪比降低 %.1f dB（表現改善）".format(delta)
                delta < -0.5f -> "套用補償後所需位準反而升高 %.1f dB".format(-delta)
                else -> "兩條件差異不明顯（Δ%.1f dB）".format(delta)
            }
            textResult.text =
                "未輔助 $metric：%.1f $unit\n輔助 $metric：%.1f $unit\n$verdict".format(off, on)
        } else {
            textResult.text = "部分條件未取得有效 $metric（可能有效資料不足），請參考歷史紀錄個別測驗結果。"
        }
    }

    private fun saveAbSession() {
        val off = srt50Off; val on = srt50On
        val delta = if (off != null && on != null) off - on else null
        val dbHelper = SRTResultDbHelper(this)
        try {
            dbHelper.writableDatabase.insert(SRTResultContract.AbSessionEntry.TABLE_NAME, null, ContentValues().apply {
                put(SRTResultContract.AbSessionEntry.COLUMN_NAME_GROUP_ID, groupId)
                put(SRTResultContract.AbSessionEntry.COLUMN_NAME_TEST_TIMESTAMP, System.currentTimeMillis())
                put(SRTResultContract.AbSessionEntry.COLUMN_NAME_SUBJECT_NAME, subjectName)
                put(SRTResultContract.AbSessionEntry.COLUMN_NAME_EARPHONE_MODEL, earphoneModel)
                put(SRTResultContract.AbSessionEntry.COLUMN_NAME_OFF_FIRST, if (offFirst) 1 else 0)
                sessionIdOff?.let { put(SRTResultContract.AbSessionEntry.COLUMN_NAME_SESSION_ID_OFF, it) }
                sessionIdOn?.let { put(SRTResultContract.AbSessionEntry.COLUMN_NAME_SESSION_ID_ON, it) }
                off?.let { put(SRTResultContract.AbSessionEntry.COLUMN_NAME_SRT50_OFF, it) }
                on?.let { put(SRTResultContract.AbSessionEntry.COLUMN_NAME_SRT50_ON, it) }
                delta?.let { put(SRTResultContract.AbSessionEntry.COLUMN_NAME_DELTA_SRT50, it) }
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun finishFlow() {
        val result = Intent().apply {
            srt50Off?.let { putExtra("EXTRA_SRT50_OFF", it) }
            srt50On?.let { putExtra("EXTRA_SRT50_ON", it) }
        }
        setResult(RESULT_OK, result)
        finish()
    }

    override fun onDestroy() {
        // 若中途離開，確保還原使用者原本的即時增益，不留下污染
        restoreLiveGains()
        super.onDestroy()
    }
}
