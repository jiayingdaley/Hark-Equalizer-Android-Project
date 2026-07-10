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
 * SSNAbTestActivity — 噪音下語詞測驗 A/B 對照（測試者測驗流程專用）。
 *
 * 比較「無聽力補償（OFF）」vs「套用固定模擬中度聽損 DSL v5 處方（ON）」的
 * 語詞辨識表現（ΔSRT50）。順序隨機決定並交叉平衡（記錄 off_first），
 * 過程中不告知測試者目前是哪個條件，避免預期心理。
 *
 * ON 條件使用 [Prescriptions.simulatedModerateLossGains16]（固定、非個人化），
 * 而非測試者本人的聽力圖處方——因為正常聽力測試者的個人化處方增益本就
 * 接近 0（處方公式對 ≤20 dB HL 給 0 增益），無法呈現「有無補償」的差異。
 * 執行前會快照使用者目前的即時 EQ 增益，ON 階段結束後還原，不影響
 * 使用者原有設定。
 *
 * 開始第一階段前先做「舒適音量調整」（與獨立 SSN 測驗相同的
 * VolumeAdjustmentDialogFragment）——前一步快速純音會把媒體音量鎖到
 * 最大，若不先調整，語詞測驗會過大聲。調整完成後即鎖定音量鍵，
 * 確保 OFF/ON 兩條件在同一呈現級別下比較。
 */
class SSNAbTestActivity : AppCompatActivity(), DialogNavCallback {

    private var subjectName = "未填寫"
    private var earphoneModel = "其他"
    // 預設 SNR 範圍：實測發現近正常聽力受試者在 +10~-10dB 幾乎都維持
    // 80-100% 正確率，SRT50（50% 正確交叉點）落在更負的區間、算不出來。
    // 下移至 0~-25dB 才會實際跨過 50%；保留 0dB 當作簡單端的錨點
    // （確認受試者在容易條件下能拿到接近滿分，排除操作/理解上的問題）。
    private var snrConditions = floatArrayOf(0f, -5f, -10f, -15f, -20f, -25f)
    private var questionsPerSnr = 5

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

    // 舒適音量已調整完成 → 之後鎖定音量鍵，維持 OFF/ON 條件可比性
    private var volumeLocked = false

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
        intent.getFloatArrayExtra("EXTRA_SNRS")?.takeIf { it.isNotEmpty() }?.let { snrConditions = it }
        questionsPerSnr = intent.getIntExtra("EXTRA_QUESTIONS_PER_SNR", questionsPerSnr)
        offFirst = Random.nextBoolean()

        textStatus = findViewById(R.id.textAbStatus)
        textResult = findViewById(R.id.textAbResult)
        buttonNext = findViewById(R.id.buttonAbNext)
        buttonNext.setOnClickListener { onNextClicked() }
        findViewById<android.view.View>(R.id.buttonAbBack).setOnClickListener { confirmExitEarly() }
        updateStatusText()
    }

    // 音量調整完成（VolumeAdjustmentDialogFragment 按 OK）→ 鎖定音量並開始第一階段
    override fun onVolumeAdjustedShowInstructions() {
        volumeLocked = true
        startStage()
    }

    override fun onInstructionsDismissedShowVolume() {
        VolumeAdjustmentDialogFragment.newInstance("ssn_noise").show(supportFragmentManager, VolumeAdjustmentDialogFragment.TAG)
    }

    override fun onStartSrtTestFromInstructions() { /* not used in A/B flow */ }

    // 兩階段之間鎖定音量鍵：OFF/ON 必須在相同呈現級別下比較才有效
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (volumeLocked &&
            (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
             keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) {
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
        textStatus.text = when (stage) {
            0 -> "準備開始（共兩階段）"
            1 -> "第一階段進行中…"
            2 -> "第二階段進行中…"
            else -> "已完成"
        }
    }

    private fun onNextClicked() {
        if (stage == 3) { finishFlow(); return }
        if (stage == 0 && !volumeLocked) {
            // 先做舒適音量調整（前一步純音測驗把媒體音量鎖到最大，
            // 不調整會過大聲），確認後才進入第一階段。
            VolumeAdjustmentDialogFragment.newInstance("ssn_noise").show(supportFragmentManager, VolumeAdjustmentDialogFragment.TAG)
            return
        }
        startStage()
    }

    private fun startStage() {
        stage += 1
        updateStatusText()
        val isFirstBlock = stage == 1
        val runOffNow = if (isFirstBlock) offFirst else !offFirst
        if (runOffNow) launchOff() else launchOn()
    }

    private fun launchOff() {
        val intent = Intent(this, SSNTestActivity::class.java).apply {
            putExtra("EXTRA_APPLY_DSP", false)
            putExtra("EXTRA_SUBJECT", subjectName)
            putExtra("EXTRA_EARPHONE_MODEL", earphoneModel)
            putExtra("EXTRA_SNRS", snrConditions)
            putExtra("EXTRA_QUESTIONS_PER_SNR", questionsPerSnr)
            putExtra("EXTRA_AB_MODE", true)
            putExtra("EXTRA_AB_CONDITION", "OFF")
        }
        launcher.launch(intent)
    }

    private fun launchOn() {
        lifecycleScope.launch {
            val repository = (application as HarkApplication).eqSettingsRepository
            // 快照使用者目前即時增益，套用固定模擬中度聽損處方，測驗結束後還原
            savedGainsLeft = repository.getBandGainsFlow("left", 0, 16).first()
            savedGainsRight = repository.getBandGainsFlow("right", 0, 16).first()
            val fixedGains = Prescriptions.simulatedModerateLossGains16()
            fixedGains.forEachIndexed { i, g ->
                SystemDspManager.updateBandGain(0, i, g)
                SystemDspManager.updateBandGain(1, i, g)
            }

            val intent = Intent(this@SSNAbTestActivity, SSNTestActivity::class.java).apply {
                putExtra("EXTRA_APPLY_DSP", true)
                putExtra("EXTRA_SUBJECT", subjectName)
                putExtra("EXTRA_EARPHONE_MODEL", earphoneModel)
                putExtra("EXTRA_SNRS", snrConditions)
                putExtra("EXTRA_QUESTIONS_PER_SNR", questionsPerSnr)
                putExtra("EXTRA_AB_MODE", true)
                putExtra("EXTRA_AB_CONDITION", "ON")
            }
            launcher.launch(intent)
        }
    }

    private fun restoreLiveGains() {
        val left = savedGainsLeft ?: return
        val right = savedGainsRight ?: return
        left.forEachIndexed { i, g -> SystemDspManager.updateBandGain(0, i, g) }
        right.forEachIndexed { i, g -> SystemDspManager.updateBandGain(1, i, g) }
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
        if (off != null && on != null) {
            val delta = off - on
            val verdict = when {
                delta > 0.5f -> "套用補償後所需訊噪比降低 %.1f dB（表現改善）".format(delta)
                delta < -0.5f -> "套用補償後所需訊噪比反而升高 %.1f dB".format(-delta)
                else -> "兩條件差異不明顯（Δ%.1f dB）".format(delta)
            }
            textResult.text = "無補償 SRT50：%.1f dB SNR\n有補償 SRT50：%.1f dB SNR\n$verdict".format(off, on)
        } else {
            textResult.text = "部分條件未取得有效 SRT50（可能有效資料不足），請參考歷史紀錄個別測驗結果。"
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
