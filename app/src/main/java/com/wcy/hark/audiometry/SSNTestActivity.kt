package com.wcy.hark.audiometry
import com.wcy.hark.R
import com.wcy.hark.HarkApplication

import android.content.ContentValues
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wcy.hark.audiometry.sqlite.SRTResultContract
import com.wcy.hark.audiometry.sqlite.SRTResultDbHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * SSNTestActivity — 含噪音中文語詞測試 (Speech-in-noise word recognition test)
 *
 * Presents 4AFC Chinese word questions mixed with speech-shaped noise at a
 * series of SNR conditions. Per-SNR percent-correct is plotted as a
 * psychometric function (score % vs SNR) in SSNTestResultActivity.
 */
class SSNTestActivity : AppCompatActivity() {

    private lateinit var textViewQuestionCount: TextView
    private lateinit var textViewSnr: TextView
    private lateinit var buttonOptions: List<Button>
    private lateinit var buttonNotSure: Button
    private lateinit var buttonEndEarly: android.view.View   // 左上角返回箭頭（觸發提早結束確認）
    private lateinit var progressBar: ProgressBar
    private lateinit var textViewPlaying: TextView   // 播放中回饋（按鈕停用期間顯示）

    private lateinit var wordProvider: WordProvider
    private lateinit var mixer: SsnAudioMixer
    private lateinit var dbHelper: SRTResultDbHelper

    // 噪音固定在舒適音量、語音依 SNR 調小，故 SNR 皆為 0 或負值。
    // 無噪音（小聲）模式時，此清單改代表「感覺級 dB SL」（越小越難）。
    private var snrConditions: List<Float> = listOf(0f, -5f, -10f, -15f, -20f, -25f)
    private var questionsPerSnr = 5

    // 無噪音「小聲語詞」模式：語詞呈現位準綁定受試者純音閾值 + dB SL。
    private var noiseless = false
    private var levelAnchorDbfs = SOFT_BASE_DBFS   // = SOFT_BASE_DBFS + 平均純音閾值(dB HL)
    private var subjectName = "未填寫"
    private var earphoneModel: String? = null   // 測試者實驗流程帶入；一般測驗為 null
    private var isExperimentMode = false

    // Flat trial list: (snrDb, question)
    private var trials: List<Pair<Float, WordQuestion>> = emptyList()
    private var trialIndex = 0
    // snr → (correct, total)
    private val results = mutableMapOf<Float, IntArray>()
    private val records = mutableListOf<ContentValues>()
    private var sessionId = System.currentTimeMillis()
    private var isTestOver = false
    private var isTestStarted = false
    private var currentNormGainDb = 0f   // 本題削波防護正規化增益（dB，≤0）
    private var applyDsp = true          // 是否對測驗音訊套用聽力補償（DSP EQ）
    private var nlfc = false             // 語音先經離線移頻（NLFC）處理（實驗模式選項）
    private val handler = Handler(Looper.getMainLooper())

    // A/B 對照模式：由 SSNAbTestActivity 呼叫，結束時直接回傳 SRT50 而非
    // 另開 SSNTestResultActivity，讓外層可合併顯示 OFF/ON 比較結果。
    private var abMode = false
    private var abCondition = ""         // "OFF" 或 "ON"
    private var wordParity = -1          // A/B 互斥詞表分半（0/1；-1 = 整個詞庫）
    private var hlSimCheckErr = Float.NaN  // 步驟③操作檢核最大誤差（NaN = 未檢核）

    companion object {
        private const val TAG = "SSNTestActivity"
        // 無噪音小聲模式：正常聽力（0 dB HL）者於 0 dB SL 的呈現位準基準（dBFS）。
        // 位準錨點 = 此值 + 受試者平均純音閾值(dB HL)，故等效以 dB SL 掃描。
        private const val SOFT_BASE_DBFS = -55f
        
        /**
         * 噪音模式的「總位準」：語音＋噪音混音後的整體 RMS，固定於測試者自身
         * 聽閾之上 55 dB SL（≈ 一般交談音量）。每一題聽起來一樣大聲，SNR 只
         * 決定總能量在語音與噪音之間怎麼分配（見 SsnAudioMixer.playWordInNoise）。
         *
         * ★ 不可用「模擬聽閾 + N dB」來推 ★（實測踩到，會炸耳朵）
         * 曾把位準錨在模擬聽閾上（本人聽閾 + 模擬損失 + 30）。重度損失（如 N5 的
         * 70 dB）算出來是 +7 dBFS —— 超出滿刻度，夾限後仍高達 −12 dBFS（≈ 90 dB
         * SPL）。呈現位準固定在交談音量、不隨模擬損失放大；若模擬條件重到總位準
         * 落在模擬聽閾以下，代表該條件不適合噪音測驗——換條件，不是開大聲。
         */
        private const val TOTAL_SL_DB = 55f

        /** 總位準的絕對安全上限（dBFS RMS）≈ 65 dB SPL。任何情況都不得超過。 */
        private const val TOTAL_MAX_DBFS = -35f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        disableSystemBackNavigation()
        setContentView(R.layout.activity_ssn_test)
        window.statusBarColor = android.graphics.Color.parseColor("#F5F7FA")
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        // Same isolation as SRT test: mute + bypass the hearing-aid engine.
        // 旗標讓服務端的自動解除靜音路徑（焦點恢復/耳機重連/音量同步）
        // 在測驗期間維持靜音。
        com.wcy.hark.audio.service.HarkAudioService.audiometryIsolationActive = true
        try {
            com.wcy.hark.audio.bridge.HarkAudioBridge.setMuted(true)
            com.wcy.hark.audio.bridge.HarkAudioBridge.setBypassMode(true)
            com.wcy.hark.audio.manager.SystemDspManager.setEnabled(false)
        } catch (e: Exception) {
            Log.w(TAG, "Engine mute skipped: ${e.message}")
        }

        textViewQuestionCount = findViewById(R.id.textViewSsnQuestionCount)
        textViewSnr = findViewById(R.id.textViewSsnSnr)
        buttonOptions = listOf(
            findViewById(R.id.buttonSsnOption1),
            findViewById(R.id.buttonSsnOption2),
            findViewById(R.id.buttonSsnOption3),
            findViewById(R.id.buttonSsnOption4)
        )
        buttonNotSure = findViewById(R.id.buttonSsnNotSure)
        buttonEndEarly = findViewById(R.id.buttonSsnBack)
        progressBar = findViewById(R.id.progressBarSsn)
        textViewPlaying = findViewById(R.id.textViewSsnPlaying)

        wordProvider = WordProvider(this)
        mixer = SsnAudioMixer(this)
        dbHelper = SRTResultDbHelper(this)

        buttonOptions.forEachIndexed { idx, btn ->
            btn.setOnClickListener { processAnswer(currentQuestion()?.options?.getOrNull(idx) ?: return@setOnClickListener) }
        }
        buttonNotSure.setOnClickListener { processAnswer("not_sure") }
        buttonEndEarly.setOnClickListener { confirmEndEarly() }

        // 設置全部來自 SSNExplanationActivity（說明與設置頁）
        applyDsp = intent.getBooleanExtra("EXTRA_APPLY_DSP", true)
        subjectName = intent.getStringExtra("EXTRA_SUBJECT") ?: "未填寫"
        earphoneModel = intent.getStringExtra("EXTRA_EARPHONE_MODEL")
        noiseless = intent.getBooleanExtra("EXTRA_NOISELESS", false)
        nlfc = intent.getBooleanExtra("EXTRA_NLFC", false)
        // 無噪音模式若未指定條件，預設一組 dB SL sweep（越小越接近閾值、越難）
        // 20→0 dB SL：曾用 25→5，輔助條件（N2/S1 補償後）在 5 dB SL 仍全對，
        // 曲線不跨 50%、SL50 內插不出來（天花板效應，實測兩位測試者皆然）。
        // 加入 0 dB SL（＝模擬聽閾）作為地板端錨點。
        if (noiseless) snrConditions = listOf(20f, 15f, 10f, 5f, 0f)
        intent.getFloatArrayExtra("EXTRA_SNRS")?.toList()?.takeIf { it.isNotEmpty() }?.let {
            snrConditions = it
        }
        questionsPerSnr = intent.getIntExtra("EXTRA_QUESTIONS_PER_SNR", questionsPerSnr)
        abMode = intent.getBooleanExtra("EXTRA_AB_MODE", false)
        abCondition = intent.getStringExtra("EXTRA_AB_CONDITION") ?: ""
        wordParity = intent.getIntExtra("EXTRA_WORD_PARITY", -1)
        hlSimCheckErr = intent.getFloatExtra("EXTRA_HL_SIM_CHECK_ERR", Float.NaN)

        // 標題依模式切換。佈局預設寫「噪音下語詞測驗」，安靜模式進來若不改，
        // 測試者會以為跳錯測驗（實測真的被誤會了）。
        findViewById<TextView>(R.id.textViewSsnTitle).text =
            if (noiseless) "中文語詞測驗（安靜）" else "噪音下語詞測驗（SSN）"

        val repository = (application as HarkApplication).eqSettingsRepository
        lifecycleScope.launch {
            isExperimentMode = repository.getExperimentModeFlow().first()

            // ── 離線處理鏈：DSP 補償 → 聽損模擬 ──────────────────────────
            // 補償改走離線原生處理（不再把 DynamicsProcessing 掛在 AudioTrack
            // session 上），因為系統音效掛上去之後就無從再插入任何處理級，
            // 聽損模擬器接不到它後面——而模擬器「必須」在補償之後。
            // 聽損模擬只屬於測試者實驗流程（A/B）。從「聽力檢測」單獨進來的測驗
            // 量的是這個人的真實聽力——若把 DataStore 殘留的模擬條件也套上去，
            // 資料就被上一場實驗污染了。
            mixer.hearingLossSim =
                if (abMode) loadHearingLossSim(repository) else HearingLossSim.none()
            mixer.dspGainsDb = if (applyDsp) loadDspGains(repository) else null
            Log.i(TAG, "chain: dsp=${if (applyDsp) "ON" else "OFF"} " +
                    "hlSim=${mixer.hearingLossSim.profile.key} " +
                    "smearing=${mixer.hearingLossSim.smearing}")

            // 位準錨點 = 測試者本人語音頻率的原始純音閾值（dBFS）。
            // 安靜模式：條件值直接是 dB SL；噪音模式：混音總位準固定於錨點 + TOTAL_SL_DB。
            // 兩種模式都需要錨點——系統音量鎖最大後，任何「絕對位準」都必須
            // 從測試者自己的聽閾推出來，否則就是亂猜（實測：噪音爆大聲）。
            val rawAnchor = loadSpeechRawThresholdDbfs(repository)
            levelAnchorDbfs = rawAnchor
                ?: (SOFT_BASE_DBFS + loadSpeechPtaHl(repository))  // 舊資料退回原作法
            Log.i(TAG, "level anchor = $levelAnchorDbfs dBFS " +
                    "(${if (rawAnchor != null) "測試者實測閾值" else "退回 dB HL 換算"})")
            startTest()
        }
    }

    /** 建立聽損模擬組態：模擬聽力圖 + 測試者本人聽閾（模擬器的零點）。 */
    private suspend fun loadHearingLossSim(
        repository: com.wcy.hark.data.EqSettingsRepository
    ): HearingLossSim {
        val profile = HearingLossProfile.fromKey(repository.getHlSimProfileFlow().first())
        if (profile.isNone) return HearingLossSim.none()
        val thresholds = repository.getBinauralRawThresholdsFlow(
            HearingLossProfile.AUDIOGRAM_FREQS.toList()
        ).first()
        val smearing = repository.getHlSimSmearingFlow().first()
        return HearingLossSim(profile, thresholds, smearing)
    }

    /** DSP 補償的 16 頻段增益（雙耳平均；語詞素材為單聲道）。 */
    private suspend fun loadDspGains(
        repository: com.wcy.hark.data.EqSettingsRepository
    ): FloatArray {
        val l = repository.getBandGainsFlow("left", 0, 16).first()
        val r = repository.getBandGainsFlow("right", 0, 16).first()
        return FloatArray(16) { i -> ((l.getOrElse(i) { 0f } + r.getOrElse(i) { 0f }) / 2f) }
    }

    /**
     * 測試者本人於語音頻率（500/1k/2k Hz）的原始純音閾值（dBFS，雙耳平均）。
     * 這是 dB SL 的零點。沒有原始閾值資料時回 null。
     */
    private suspend fun loadSpeechRawThresholdDbfs(
        repository: com.wcy.hark.data.EqSettingsRepository
    ): Float? {
        val freqs = listOf(500, 1000, 2000)
        val map = repository.getBinauralRawThresholdsFlow(freqs).first()
        val vals = freqs.mapNotNull { map[it] }
        return if (vals.isEmpty()) null else vals.average().toFloat()
    }

    /** 語音頻率（500/1k/2k Hz）雙耳平均純音閾值（dB HL）；無資料回 0。 */
    private suspend fun loadSpeechPtaHl(
        repository: com.wcy.hark.data.EqSettingsRepository
    ): Float {
        val freqs = listOf(500, 1000, 2000)
        val vals = mutableListOf<Int>()
        for (ear in listOf("left", "right")) {
            for (f in freqs) {
                val t = repository.getAudiogramThresholdFlow(ear, f).first()
                if (t != 0) vals.add(t)   // 0 視為未測（DataStore 預設）
            }
        }
        return if (vals.isEmpty()) 0f else vals.average().toFloat()
    }

    /**
     * 與 SRT 測驗一致的 DSP 狀態標示。A/B 對照模式下**不可顯示**真實狀態
     * ——否則等於直接告知測試者目前是哪個條件，破壞單盲比較的前提。
     */
    private fun updateDspStatusBadge() {
        val badge = findViewById<TextView>(R.id.textViewSsnDspStatus)
        if (abMode) {
            // 顯示模擬聽損條件（兩階段相同、不破盲）；補償 ON/OFF 保密。
            val sim = mixer.hearingLossSim
            if (sim.profile.isNone) {
                badge.text = "⚠️ 未套用聽損模擬（A/B 對照將無效果）"
                badge.setTextColor(android.graphics.Color.parseColor("#B71C1C"))
            } else {
                badge.text = "模擬聽損：${sim.profile.label}" +
                        (if (sim.smearing) "＋頻譜模糊" else "")
                badge.setTextColor(android.graphics.Color.parseColor("#1A56B0"))
            }
            return
        }
        if (applyDsp) {
            badge.text = "● DSP 聽力補償 已套用"
            badge.setTextColor(android.graphics.Color.parseColor("#2E7D32"))
        } else {
            badge.text = "○ DSP 聽力補償 未套用"
            badge.setTextColor(android.graphics.Color.parseColor("#757575"))
        }
    }

    // 測驗開始後鎖定音量鍵，維持已調好的舒適音量
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (isTestStarted && !isTestOver &&
            (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
             keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun currentQuestion(): WordQuestion? = trials.getOrNull(trialIndex)?.second

    private var originalMediaVolume = 0

    override fun onResume() {
        super.onResume()
        // 播刺激的人自己鎖音量。A/B 主控頁被本頁蓋住時 onPause 會把音量還原成
        // 進場前的值——若使用者先前把音量調很低，字就完全無聲（實測踩到：
        // 「有幾次連無套用聽損的中文語詞都沒聲音」）。dB SL 換算只在最大音量成立。
        originalMediaVolume = AudiometryVolume.lockToMax(this)
    }

    override fun onPause() {
        super.onPause()
        AudiometryVolume.restore(this, originalMediaVolume)
    }

    /** 停止播放前先解除本題 AudioTrack session 的 DSP 掛載。 */
    private fun stopPlayback() {
        try {
            com.wcy.hark.audio.manager.SystemDspManager.detachFromSession(mixer.audioSessionId)
        } catch (e: Exception) {
            Log.e(TAG, "Error detaching DSP: ${e.message}")
        }
        mixer.stop()
    }

    /** 語音頻率（500/1k/2k Hz）的模擬損失量平均——模擬聽閾相對本人聽閾的抬升量。 */
    private fun simulatedSpeechLossDb(): Float {
        val sim = mixer.hearingLossSim
        if (sim.profile.isNone) return 0f
        return listOf(500, 1000, 2000).map { sim.targetLossDb(it) }.average().toFloat()
    }

    private fun startTest() {
        val totalQuestions = snrConditions.size * questionsPerSnr
        val questions = wordProvider.getRandomQuestions(totalQuestions, wordParity)
        if (questions.size < totalQuestions) {
            Toast.makeText(this, "詞庫題目不足 (${questions.size}/$totalQuestions)", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        // Interleave SNRs randomly across trials so order effects average out
        val trialList = mutableListOf<Pair<Float, WordQuestion>>()
        var qi = 0
        for (snr in snrConditions) {
            repeat(questionsPerSnr) { trialList.add(snr to questions[qi++]) }
        }
        trialList.shuffle()
        trials = trialList
        isTestStarted = true
        updateDspStatusBadge()
        results.clear()
        snrConditions.forEach { results[it] = intArrayOf(0, 0) }
        trialIndex = 0
        sessionId = System.currentTimeMillis()
        progressBar.max = trials.size
        nextTrial()
    }

    private fun nextTrial() {
        if (isTestOver) return
        if (trialIndex >= trials.size) {
            endTest()
            return
        }
        val (snr, q) = trials[trialIndex]
        textViewQuestionCount.text = String.format("%02d / %02d", trialIndex + 1, trials.size)
        textViewSnr.text = when {
            !isExperimentMode -> ""                       // 使用者模式不顯示，避免暗示
            noiseless -> "音量: ${fmtSnr(snr)} dB SL"
            else -> "SNR: ${fmtSnr(snr)} dB"
        }
        progressBar.progress = trialIndex
        q.options.forEachIndexed { i, w -> buttonOptions[i].text = w }
        enableButtons(false)

        handler.postDelayed({
            if (isTestOver) return@postDelayed
            val resId = resources.getIdentifier(q.audioFileName.substringBefore("."), "raw", packageName)
            if (resId == 0) {
                Log.e(TAG, "Missing audio ${q.audioFileName}")
                enableButtons(true)
                return@postDelayed
            }
            val result = if (noiseless) {
                // snr 於無噪音模式代表 dB SL：位準 = 錨點 + SL，夾在 −3 dBFS 以下防削波
                val levelDbfs = (levelAnchorDbfs + snr).coerceAtMost(-3f)
                mixer.playWordQuiet(resId, levelDbfs, nlfc)
            } else {
                // 混音總位準固定於一般交談音量（本人聽閾 + 55 dB SL），受安全上限
                // 保護；SNR 只決定語音/噪音的能量分配，每題聽起來一樣大聲
                val totalDbfs = (levelAnchorDbfs + TOTAL_SL_DB).coerceAtMost(TOTAL_MAX_DBFS)
                mixer.playWordInNoise(resId, R.raw.ssn_noise, snr, totalDbfs, nlfc)
            }
            currentNormGainDb = result.normGainDb
            // 補償與聽損模擬已在 SsnAudioMixer 內以離線原生鏈處理完畢
            // （順序：DSP 補償 → 聽損模擬），此處播出的即為最終刺激，
            // 不再對 AudioTrack session 掛任何系統音效。
            if (result.durationMs > 0) {
                setPlayingIndicator(true)
                handler.postDelayed({ enableButtons(true) }, result.durationMs / 2) // enable mid-playback
                handler.postDelayed({ setPlayingIndicator(false) }, result.durationMs)
            } else {
                enableButtons(true)
            }
        }, 600)
    }

    private fun processAnswer(answer: String) {
        val (snr, q) = trials.getOrNull(trialIndex) ?: return
        stopPlayback()
        val correct = answer == q.correctWord
        results[snr]!!.let { r -> if (correct) r[0]++; r[1]++ }
        records.add(ContentValues().apply {
            put(SRTResultContract.SSNRecordEntry.COLUMN_NAME_SESSION_ID_FK, sessionId)
            put(SRTResultContract.SSNRecordEntry.COLUMN_NAME_SNR_DB, snr)
            put(SRTResultContract.SSNRecordEntry.COLUMN_NAME_QUESTION_NUMBER, trialIndex + 1)
            put(SRTResultContract.SSNRecordEntry.COLUMN_NAME_CORRECT_WORD, q.correctWord)
            put(SRTResultContract.SSNRecordEntry.COLUMN_NAME_USER_ANSWER, answer)
            put(SRTResultContract.SSNRecordEntry.COLUMN_NAME_WAS_CORRECT, if (correct) 1 else 0)
            put(SRTResultContract.SSNRecordEntry.COLUMN_NAME_NORM_GAIN_DB, currentNormGainDb)
        })
        currentNormGainDb = 0f
        trialIndex++
        nextTrial()
    }

    private fun enableButtons(enable: Boolean) {
        buttonOptions.forEach { it.isEnabled = enable }
        buttonNotSure.isEnabled = enable
    }

    /**
     * 「播放中」指示與按鈕解耦：指示只在音檔真正播放的區間顯示。
     * 舊版把它綁在按鈕停用狀態上——準備期（還沒出聲）就亮、播到一半就滅，
     * 測試者的體感是「顯示播放中時沒聲音，消失了才有聲音」。
     */
    private fun setPlayingIndicator(playing: Boolean) {
        textViewPlaying.visibility =
            if (playing && !isTestOver) android.view.View.VISIBLE else android.view.View.INVISIBLE
    }

    private fun confirmEndEarly() {
        AlertDialog.Builder(this)
            .setTitle("提早結束測驗")
            .setMessage("確定要結束目前的噪音下語詞測試嗎？已完成的題目仍會納入結果。")
            .setPositiveButton("是") { _, _ -> endTest() }
            .setNegativeButton("否", null)
            .show()
    }

    /** Percent-correct per SNR, only conditions with ≥1 answered trial. */
    private fun scoreBySnr(): List<Pair<Float, Float>> =
        results.filter { it.value[1] > 0 }
            .map { (snr, r) -> snr to r[0] * 100f / r[1] }
            .sortedBy { it.first }

    /** Linear interpolation of the SNR at 50% correct. */
    private fun computeSrt50(points: List<Pair<Float, Float>>): Float? {
        val sorted = points.sortedBy { it.first }
        for (i in 0 until sorted.size - 1) {
            val (x1, y1) = sorted[i]; val (x2, y2) = sorted[i + 1]
            if ((y1 - 50f) * (y2 - 50f) <= 0f && y1 != y2) {
                return x1 + (50f - y1) * (x2 - x1) / (y2 - y1)
            }
        }
        return null
    }

    private fun endTest() {
        if (isTestOver) return
        isTestOver = true
        handler.removeCallbacksAndMessages(null)
        stopPlayback()

        val points = scoreBySnr()
        val srt50 = computeSrt50(points)

        // Persist
        val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.insert(SRTResultContract.SSNSessionEntry.TABLE_NAME, null, ContentValues().apply {
                put(SRTResultContract.SSNSessionEntry.COLUMN_NAME_SESSION_ID, sessionId)
                put(SRTResultContract.SSNSessionEntry.COLUMN_NAME_TEST_TIMESTAMP, System.currentTimeMillis())
                put(SRTResultContract.SSNSessionEntry.COLUMN_NAME_SUBJECT_NAME, subjectName)
                put(SRTResultContract.SSNSessionEntry.COLUMN_NAME_SNR_LIST, snrConditions.joinToString(",") { fmtSnr(it) })
                put(SRTResultContract.SSNSessionEntry.COLUMN_NAME_QUESTIONS_PER_SNR, questionsPerSnr)
                srt50?.let { put(SRTResultContract.SSNSessionEntry.COLUMN_NAME_SRT50, it) }
                put(SRTResultContract.SSNSessionEntry.COLUMN_NAME_PHONE_VOLUME,
                    audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC))
                earphoneModel?.let { put(SRTResultContract.SSNSessionEntry.COLUMN_NAME_EARPHONE_MODEL, it) }
                put(SRTResultContract.SSNSessionEntry.COLUMN_NAME_TEST_MODE,
                    if (noiseless) SRTResultContract.SSNSessionEntry.MODE_SL
                    else SRTResultContract.SSNSessionEntry.MODE_SNR)
                put(SRTResultContract.SSNSessionEntry.COLUMN_NAME_NLFC, if (nlfc) 1 else 0)
                // 本場次「模擬了什麼聽損」——沒有這個，事後分不出補償有效是因為
                // 處方好還是模擬條件不同，結果無從解釋。
                put(SRTResultContract.SSNSessionEntry.COLUMN_NAME_HL_SIM_PROFILE,
                    mixer.hearingLossSim.profile.key)
                put(SRTResultContract.SSNSessionEntry.COLUMN_NAME_HL_SIM_SMEARING,
                    if (mixer.hearingLossSim.smearing && mixer.hearingLossSim.isActive) 1 else 0)
                // v12：呈現條件完整留存——匯出的每場次 CSV 自足，可直接重建
                // 測試者實際聽到的絕對位準與處理鏈，不需回頭拼純音 CSV 或 A/B 表。
                put(SRTResultContract.SSNSessionEntry.COLUMN_NAME_LEVEL_ANCHOR_DBFS, levelAnchorDbfs)
                val gains = mixer.dspGainsDb
                put(SRTResultContract.SSNSessionEntry.COLUMN_NAME_DSP_ON, if (gains != null) 1 else 0)
                gains?.let {
                    put(SRTResultContract.SSNSessionEntry.COLUMN_NAME_DSP_GAINS_DB,
                        it.joinToString(",") { g -> "%.1f".format(g) })
                }
                if (!noiseless) {
                    put(SRTResultContract.SSNSessionEntry.COLUMN_NAME_TOTAL_LEVEL_DBFS,
                        (levelAnchorDbfs + TOTAL_SL_DB).coerceAtMost(TOTAL_MAX_DBFS))
                }
                put(SRTResultContract.SSNSessionEntry.COLUMN_NAME_WORD_PARITY, wordParity)
                if (!hlSimCheckErr.isNaN()) {
                    put(SRTResultContract.SSNSessionEntry.COLUMN_NAME_HL_SIM_CHECK_ERR, hlSimCheckErr)
                }
                // 只有「測試者實驗流程」(SSNAbTestActivity) 會帶 EXTRA_AB_MODE=true；
                // 一般模式測驗維持 NULL，讓一般模式的歷史紀錄能過濾掉這裡的資料。
                if (abMode) {
                    put(SRTResultContract.SSNSessionEntry.COLUMN_NAME_SESSION_SOURCE,
                        SRTResultContract.SSNSessionEntry.SOURCE_SUBJECT)
                }
            })
            records.forEach { db.insert(SRTResultContract.SSNRecordEntry.TABLE_NAME, null, it) }
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            Log.e(TAG, "Save failed: ${e.message}", e)
        } finally {
            db.endTransaction()
        }

        // 削波正規化統計（學術揭露：受影響題數與最大衰減量）
        val normValues = records.mapNotNull {
            it.getAsFloat(SRTResultContract.SSNRecordEntry.COLUMN_NAME_NORM_GAIN_DB)
        }
        val attenuatedCount = normValues.count { it < 0f }
        val maxAttenuationDb = normValues.minOrNull() ?: 0f

        if (abMode) {
            // A/B 對照模式：不另開結果頁，直接把本條件的 SRT50 回傳給
            // SSNAbTestActivity 合併顯示。
            setResult(RESULT_OK, Intent().apply {
                putExtra("EXTRA_AB_CONDITION", abCondition)
                putExtra("EXTRA_SRT50", srt50 ?: Float.NaN)
                putExtra("EXTRA_SESSION_ID", sessionId)
            })
            finish()
            return
        }

        // Result screen
        val intent = Intent(this, SSNTestResultActivity::class.java).apply {
            putExtra("EXTRA_SNRS", points.map { it.first }.toFloatArray())
            putExtra("EXTRA_SCORES", points.map { it.second }.toFloatArray())
            putExtra("EXTRA_SRT50", srt50 ?: Float.NaN)
            putExtra("EXTRA_SUBJECT", subjectName)
            putExtra("EXTRA_ATTENUATED_COUNT", attenuatedCount)
            putExtra("EXTRA_MAX_ATTENUATION_DB", maxAttenuationDb)
            putExtra("EXTRA_NOISELESS", noiseless)
        }
        startActivity(intent)
        finish()
    }

    private fun fmtSnr(v: Float) = if (v == v.toInt().toFloat()) "${v.toInt()}" else "$v"

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            com.wcy.hark.audio.manager.SystemDspManager.detachFromSession(mixer.audioSessionId)
        } catch (e: Exception) { /* best effort */ }
        mixer.release()
        com.wcy.hark.audio.service.HarkAudioService.audiometryIsolationActive = false
        try {
            com.wcy.hark.audio.bridge.HarkAudioBridge.setBypassMode(false)
            com.wcy.hark.audio.bridge.HarkAudioBridge.setMuted(false)
        } catch (e: Exception) { /* engine may not be running */ }
        super.onDestroy()
    }
}
