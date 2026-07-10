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
    private var earphoneModel: String? = null   // 測試者測驗流程帶入；一般測驗為 null
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
    private val handler = Handler(Looper.getMainLooper())

    // A/B 對照模式：由 SSNAbTestActivity 呼叫，結束時直接回傳 SRT50 而非
    // 另開 SSNTestResultActivity，讓外層可合併顯示 OFF/ON 比較結果。
    private var abMode = false
    private var abCondition = ""         // "OFF" 或 "ON"

    companion object {
        private const val TAG = "SSNTestActivity"
        // 無噪音小聲模式：正常聽力（0 dB HL）者於 0 dB SL 的呈現位準基準（dBFS）。
        // 位準錨點 = 此值 + 受試者平均純音閾值(dB HL)，故等效以 dB SL 掃描。
        private const val SOFT_BASE_DBFS = -55f
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
        // 無噪音模式若未指定條件，預設一組 dB SL sweep（越小越接近閾值、越難）
        if (noiseless) snrConditions = listOf(25f, 20f, 15f, 10f, 5f)
        intent.getFloatArrayExtra("EXTRA_SNRS")?.toList()?.takeIf { it.isNotEmpty() }?.let {
            snrConditions = it
        }
        questionsPerSnr = intent.getIntExtra("EXTRA_QUESTIONS_PER_SNR", questionsPerSnr)
        abMode = intent.getBooleanExtra("EXTRA_AB_MODE", false)
        abCondition = intent.getStringExtra("EXTRA_AB_CONDITION") ?: ""

        val repository = (application as HarkApplication).eqSettingsRepository
        lifecycleScope.launch {
            isExperimentMode = repository.getExperimentModeFlow().first()
            if (noiseless) {
                // 位準錨點 = 固定軟基準 + 受試者平均純音閾值（dB HL）：
                // 聽力損失越大、呈現越大聲，使掃描維持在「感覺級 dB SL」尺度上。
                val ptaHl = loadSpeechPtaHl(repository)
                levelAnchorDbfs = (SOFT_BASE_DBFS + ptaHl)
            }
            startTest()
        }
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
            badge.text = "測驗進行中"
            badge.setTextColor(android.graphics.Color.parseColor("#757575"))
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

    /** 停止播放前先解除本題 AudioTrack session 的 DSP 掛載。 */
    private fun stopPlayback() {
        try {
            com.wcy.hark.audio.manager.SystemDspManager.detachFromSession(mixer.audioSessionId)
        } catch (e: Exception) {
            Log.e(TAG, "Error detaching DSP: ${e.message}")
        }
        mixer.stop()
    }

    private fun startTest() {
        val totalQuestions = snrConditions.size * questionsPerSnr
        val questions = wordProvider.getRandomQuestions(totalQuestions)
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
                mixer.playWordQuiet(resId, levelDbfs)
            } else {
                mixer.playWordInNoise(resId, R.raw.ssn_noise, snr)
            }
            currentNormGainDb = result.normGainDb
            // 依設置對本題的 AudioTrack session 套用聽力補償（與 SRT 相同機制）
            if (applyDsp && result.durationMs > 0) {
                try {
                    com.wcy.hark.audio.manager.SystemDspManager.attachToSession(
                        mixer.audioSessionId, forceEnabled = true
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to attach DSP: ${e.message}")
                }
            }
            if (result.durationMs > 0) {
                handler.postDelayed({ enableButtons(true) }, result.durationMs / 2) // enable mid-playback
            } else {
                enableButtons(true)
            }
        }, 1200)
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
