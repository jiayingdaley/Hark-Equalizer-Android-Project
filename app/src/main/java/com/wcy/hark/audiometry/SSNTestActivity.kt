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
class SSNTestActivity : AppCompatActivity(), DialogNavCallback {

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
    private var volumeConfirmed = false   // 音量對話框按 OK（而非 X）離開
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

        // 音量對話框按「X」（未按 OK）關閉時，回到說明頁
        supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentDestroyed(
                    fm: androidx.fragment.app.FragmentManager,
                    f: androidx.fragment.app.Fragment
                ) {
                    if (f is VolumeAdjustmentDialogFragment && !volumeConfirmed &&
                        !isTestStarted && !isTestOver && !isFinishing) {
                        showInstructionsDialog()
                    }
                }
            }, false
        )

        val repository = (application as HarkApplication).eqSettingsRepository
        lifecycleScope.launch {
            isExperimentMode = repository.getExperimentModeFlow().first()
            val prefill = repository.getLastSubjectNameFlow().first()
            showSetupDialog(prefill)
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

    private fun showSetupDialog(prefillName: String) {
        val density = resources.displayMetrics.density
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (16 * density).toInt(), (24 * density).toInt(), (8 * density).toInt())
        }
        val nameInput = android.widget.EditText(this).apply {
            hint = "使用者姓名（可不填）"
            setSingleLine(true)
            setText(prefillName)
        }
        layout.addView(nameInput)

        var snrInput: android.widget.EditText? = null
        var countInput: android.widget.EditText? = null
        if (isExperimentMode) {
            layout.addView(android.widget.TextView(this).apply {
                text = "SNR 條件 SNR conditions (dB, comma-separated):"
                textSize = 13f
            })
            snrInput = android.widget.EditText(this).apply {
                setText(snrConditions.joinToString(",") { if (it == it.toInt().toFloat()) "${it.toInt()}" else "$it" })
                setSingleLine(true)
            }
            layout.addView(snrInput)
            layout.addView(android.widget.TextView(this).apply {
                text = "每個 SNR 題數 Questions per SNR:"
                textSize = 13f
            })
            countInput = android.widget.EditText(this).apply {
                setText(questionsPerSnr.toString())
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setSingleLine(true)
            }
            layout.addView(countInput)
        }

        AlertDialog.Builder(this)
            .setTitle("噪音下語詞測驗設置")
            .setMessage("將在不同訊噪比 (SNR) 的語音噪音下播放語詞，請選出聽到的詞。")
            .setView(layout)
            .setPositiveButton("開始測驗") { _, _ ->
                val entered = nameInput.text.toString().trim()
                subjectName = if (entered.isEmpty()) "未填寫" else entered
                if (entered.isNotEmpty()) {
                    lifecycleScope.launch {
                        (application as HarkApplication).eqSettingsRepository.saveLastSubjectName(entered)
                    }
                }
                snrInput?.text?.toString()?.let { txt ->
                    val parsed = txt.split(",").mapNotNull { it.trim().toFloatOrNull() }
                    if (parsed.isNotEmpty()) snrConditions = parsed.distinct().sortedDescending()
                }
                countInput?.text?.toString()?.toIntOrNull()?.let { if (it in 1..20) questionsPerSnr = it }
                showVolumeAdjustDialog()
            }
            .setNegativeButton("取消") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    /**
     * 舒適音量調整：沿用語詞測試（SRT）相同的 VolumeAdjustmentDialogFragment
     * （循環播放示範語音 + 系統音量拉桿）。SNR 由數位混音精確控制，與播放音量無關。
     * 按 OK → 說明頁；按 X → 也回到說明頁（透過 fragment 銷毀偵測）。
     */
    private fun showVolumeAdjustDialog() {
        volumeConfirmed = false
        VolumeAdjustmentDialogFragment().show(supportFragmentManager, VolumeAdjustmentDialogFragment.TAG)
    }

    /** 測驗說明頁（SSN 專用）。 */
    private fun showInstructionsDialog() {
        AlertDialog.Builder(this)
            .setTitle("噪音下語詞測驗說明")
            .setMessage(
                "1. 每一題會先聽到背景噪音，接著在噪音中播放一個中文語詞。\n" +
                "2. 請從四個選項中選出您聽到的詞；聽不清楚可按「不確定」。\n" +
                "3. 測驗過程中噪音大小會改變，這是正常的測驗設計。\n" +
                "4. 測驗開始後音量將被鎖定，無法再以音量鍵調整。"
            )
            .setPositiveButton("開始測驗") { _, _ -> startTest() }
            .setNegativeButton("重新調整音量") { _, _ -> showVolumeAdjustDialog() }
            .setCancelable(false)
            .show()
    }

    // DialogNavCallback：音量對話框按「OK」→ 顯示說明頁
    override fun onVolumeAdjustedShowInstructions() {
        volumeConfirmed = true
        showInstructionsDialog()
    }

    override fun onInstructionsDismissedShowVolume() {
        showVolumeAdjustDialog()
    }

    override fun onStartSrtTestFromInstructions() {
        startTest()
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
            val durMs = mixer.playWordInNoise(resId, R.raw.ssn_noise, snr)
            if (durMs > 0) {
                handler.postDelayed({ enableButtons(true) }, durMs / 2) // enable mid-playback
            } else {
                enableButtons(true)
            }
        }, 1200)
    }

    private fun processAnswer(answer: String) {
        val (snr, q) = trials.getOrNull(trialIndex) ?: return
        mixer.stop()
        val correct = answer == q.correctWord
        results[snr]!!.let { r -> if (correct) r[0]++; r[1]++ }
        records.add(ContentValues().apply {
            put(SRTResultContract.SSNRecordEntry.COLUMN_NAME_SESSION_ID_FK, sessionId)
            put(SRTResultContract.SSNRecordEntry.COLUMN_NAME_SNR_DB, snr)
            put(SRTResultContract.SSNRecordEntry.COLUMN_NAME_QUESTION_NUMBER, trialIndex + 1)
            put(SRTResultContract.SSNRecordEntry.COLUMN_NAME_CORRECT_WORD, q.correctWord)
            put(SRTResultContract.SSNRecordEntry.COLUMN_NAME_USER_ANSWER, answer)
            put(SRTResultContract.SSNRecordEntry.COLUMN_NAME_WAS_CORRECT, if (correct) 1 else 0)
        })
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
        mixer.stop()

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

        // Result screen
        val intent = Intent(this, SSNTestResultActivity::class.java).apply {
            putExtra("EXTRA_SNRS", points.map { it.first }.toFloatArray())
            putExtra("EXTRA_SCORES", points.map { it.second }.toFloatArray())
            putExtra("EXTRA_SRT50", srt50 ?: Float.NaN)
            putExtra("EXTRA_SUBJECT", subjectName)
        }
        startActivity(intent)
        finish()
    }

    private fun fmtSnr(v: Float) = if (v == v.toInt().toFloat()) "${v.toInt()}" else "$v"

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        mixer.release()
        try {
            com.wcy.hark.audio.bridge.HarkAudioBridge.setBypassMode(false)
            com.wcy.hark.audio.bridge.HarkAudioBridge.setMuted(false)
        } catch (e: Exception) { /* engine may not be running */ }
        super.onDestroy()
    }
}
