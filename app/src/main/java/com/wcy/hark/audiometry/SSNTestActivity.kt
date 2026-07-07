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
    private lateinit var buttonEndEarly: Button
    private lateinit var progressBar: ProgressBar

    private lateinit var wordProvider: WordProvider
    private lateinit var mixer: SsnAudioMixer
    private lateinit var dbHelper: SRTResultDbHelper

    private var snrConditions: List<Float> = listOf(10f, 5f, 0f, -5f, -10f)
    private var questionsPerSnr = 5
    private var subjectName = "未填寫"
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

    companion object { private const val TAG = "SSNTestActivity" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ssn_test)
        window.statusBarColor = android.graphics.Color.parseColor("#F5F7FA")
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        // Same isolation as SRT test: mute + bypass the hearing-aid engine
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
        buttonEndEarly = findViewById(R.id.buttonSsnEndEarly)
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
        intent.getFloatArrayExtra("EXTRA_SNRS")?.toList()?.takeIf { it.isNotEmpty() }?.let {
            snrConditions = it
        }
        questionsPerSnr = intent.getIntExtra("EXTRA_QUESTIONS_PER_SNR", questionsPerSnr)

        val repository = (application as HarkApplication).eqSettingsRepository
        lifecycleScope.launch {
            isExperimentMode = repository.getExperimentModeFlow().first()
            startTest()
        }
    }

    /** 與 SRT 測驗一致的 DSP 狀態標示。 */
    private fun updateDspStatusBadge() {
        val badge = findViewById<TextView>(R.id.textViewSsnDspStatus)
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
        textViewSnr.text = if (isExperimentMode) "SNR: ${fmtSnr(snr)} dB" else "" // 使用者模式不顯示 SNR，避免暗示
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
            val result = mixer.playWordInNoise(resId, R.raw.ssn_noise, snr)
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

        // Result screen
        val intent = Intent(this, SSNTestResultActivity::class.java).apply {
            putExtra("EXTRA_SNRS", points.map { it.first }.toFloatArray())
            putExtra("EXTRA_SCORES", points.map { it.second }.toFloatArray())
            putExtra("EXTRA_SRT50", srt50 ?: Float.NaN)
            putExtra("EXTRA_SUBJECT", subjectName)
            putExtra("EXTRA_ATTENUATED_COUNT", attenuatedCount)
            putExtra("EXTRA_MAX_ATTENUATION_DB", maxAttenuationDb)
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
        try {
            com.wcy.hark.audio.bridge.HarkAudioBridge.setBypassMode(false)
            com.wcy.hark.audio.bridge.HarkAudioBridge.setMuted(false)
        } catch (e: Exception) { /* engine may not be running */ }
        super.onDestroy()
    }
}
