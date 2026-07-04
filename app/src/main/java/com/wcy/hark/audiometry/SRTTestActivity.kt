package com.wcy.hark.audiometry
import com.wcy.hark.R

import com.wcy.hark.audiometry.sqlite.SRTResultContract
import android.content.ContentValues
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.wcy.hark.audiometry.sqlite.SRTResultDbHelper
import com.wcy.hark.audiometry.sqlite.SRTTestRecord
import java.io.IOException


class SRTTestActivity : AppCompatActivity() {

    private lateinit var textViewQuestionCount: TextView
    private lateinit var textViewInstruction: TextView
    private lateinit var gridLayoutOptions: GridLayout
    private lateinit var buttonOption1: Button
    private lateinit var buttonOption2: Button
    private lateinit var buttonOption3: Button
    private lateinit var buttonOption4: Button
    private lateinit var buttonNotSure: Button
    private lateinit var buttonPauseResume: Button
    private lateinit var buttonEndEarly: Button
    private lateinit var progressBarAudioLoading: ProgressBar
    private lateinit var textViewDspStatus: TextView

    private lateinit var wordProvider: WordProvider
    private var allQuestions: List<WordQuestion> = listOf()
    private var currentQuestionIndex: Int = 0
    private var currentQuestion: WordQuestion? = null
    private var score: Int = 0

    private var mediaPlayer: MediaPlayer? = null
    private val audioPlaybackDelayMs: Long = 2000 // 2秒延遲
    private val handler = Handler(Looper.getMainLooper())
    private var audioPlaybackRunnable: Runnable? = null

    private var isPaused: Boolean = false
    private var isTestOver: Boolean = false

    // --- SQLite ---
    private lateinit var dbHelper: SRTResultDbHelper
    private var currentSessionId: Long = -1
    private val answeredRecords: MutableList<SRTTestRecord> = mutableListOf()
    private var subjectName: String = "未填寫"

    companion object {
        const val TOTAL_QUESTIONS_TO_ASK = 25
        const val TAG = "SRTTestActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_srt_test)
        window.statusBarColor = android.graphics.Color.parseColor("#F5F7FA")
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        // --- Critical isolation step (KNOWN-ISSUE-004 fix) ---
        // Instead of stopping the engine (which requires the user to re-enable manually),
        // we mute the output and enable bypass mode so the Oboe engine keeps running silently.
        // This way the Service stays alive and the engine resumes automatically when the test ends.
        // Ref: KNOWN-ISSUE-004, HarkAudioBridge.setMuted() / setBypassMode()
        try {
            com.wcy.hark.audio.bridge.HarkAudioBridge.setMuted(true)
            com.wcy.hark.audio.bridge.HarkAudioBridge.setBypassMode(true)
            com.wcy.hark.audio.manager.SystemDspManager.setEnabled(false)
            Log.d(TAG, "Engine muted and bypassed for clean test isolation (service stays alive).")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to mute engine (acceptable if not running): ${e.message}")
        }

        // Initialize UI
        textViewQuestionCount = findViewById(R.id.textViewQuestionCount)
        textViewInstruction = findViewById(R.id.textViewInstruction)
        gridLayoutOptions = findViewById(R.id.gridLayoutOptions)
        buttonOption1 = findViewById(R.id.buttonOption1)
        buttonOption2 = findViewById(R.id.buttonOption2)
        buttonOption3 = findViewById(R.id.buttonOption3)
        buttonOption4 = findViewById(R.id.buttonOption4)
        buttonNotSure = findViewById(R.id.buttonNotSure)
        buttonPauseResume = findViewById(R.id.buttonPauseResume)
        buttonEndEarly = findViewById(R.id.buttonEndEarly)
        progressBarAudioLoading = findViewById(R.id.progressBarAudioLoading)
        textViewDspStatus = findViewById(R.id.textViewDspStatus)

        // Read DSP preference from the explanation screen; update status badge (read-only)
        val applyDsp = intent.getBooleanExtra("EXTRA_APPLY_DSP", true)
        updateDspStatusBadge(applyDsp)

        // Initialize helpers
        wordProvider = WordProvider(this)
        dbHelper = SRTResultDbHelper(this)
        currentSessionId = System.currentTimeMillis() // Use timestamp as session ID

        setupButtonListeners()
        showSubjectNameInputDialog()
    }

    private fun showSubjectNameInputDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "請輸入姓名"
            setSingleLine(true)
            val paddingPx = (16 * resources.displayMetrics.density).toInt()
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
        }
        
        val container = android.widget.FrameLayout(this).apply {
            addView(input, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = (24 * resources.displayMetrics.density).toInt()
                rightMargin = (24 * resources.displayMetrics.density).toInt()
                topMargin = (8 * resources.displayMetrics.density).toInt()
                bottomMargin = (8 * resources.displayMetrics.density).toInt()
            })
        }

        AlertDialog.Builder(this)
            .setTitle("語詞測驗登錄")
            .setMessage("請輸入使用者姓名（可不填）：")
            .setView(container)
            .setPositiveButton("開始測驗") { _, _ ->
                val enteredName = input.text.toString().trim()
                subjectName = if (enteredName.isEmpty()) "未填寫" else enteredName
                loadTestData()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Updates the DSP status badge colour and text.
     * Green  = DSP 已套用 (applied)
     * Gray   = DSP 未套用 (not applied)
     */
    private fun updateDspStatusBadge(isApplied: Boolean) {
        if (isApplied) {
            textViewDspStatus.text = "● DSP 聽力補償 已套用"
            textViewDspStatus.setTextColor(android.graphics.Color.parseColor("#2E7D32")) // dark green
            textViewDspStatus.setBackgroundResource(android.R.color.transparent)
        } else {
            textViewDspStatus.text = "○ DSP 聽力補償 未套用"
            textViewDspStatus.setTextColor(android.graphics.Color.parseColor("#757575")) // gray
            textViewDspStatus.setBackgroundResource(android.R.color.transparent)
        }
    }

    private fun setupButtonListeners() {
        val optionButtons = listOf(buttonOption1, buttonOption2, buttonOption3, buttonOption4)
        optionButtons.forEachIndexed { index, button ->
            button.setOnClickListener {
                if (!isPaused && currentQuestion != null) {
                    val selectedWord = currentQuestion!!.options[index]
                    processAnswer(selectedWord)
                }
            }
        }

        buttonNotSure.setOnClickListener {
            if (!isPaused && currentQuestion != null) {
                processAnswer("not_sure") // Special string for "I'm not sure"
            }
        }

        buttonPauseResume.setOnClickListener {
            togglePauseResume()
        }

        buttonEndEarly.setOnClickListener {
            if (!isTestOver) {
                confirmEndEarly()
            }
        }
    }

    private fun loadTestData() {
        allQuestions = wordProvider.getRandomQuestions(TOTAL_QUESTIONS_TO_ASK)
        if (allQuestions.size < TOTAL_QUESTIONS_TO_ASK) {
            Log.e(TAG, "Not enough unique questions from WordProvider. Loaded: ${allQuestions.size}")
            Toast.makeText(this, "詞庫題目不足，無法開始測驗 (${allQuestions.size}/$TOTAL_QUESTIONS_TO_ASK)", Toast.LENGTH_LONG).show()
            finish() // Exit activity if not enough questions
            return
        }
        currentQuestionIndex = 0
        score = 0
        answeredRecords.clear()
        isTestOver = false
        isPaused = false // Ensure test starts in unpaused state
        buttonPauseResume.text = "暫停"
        displayNextQuestion()
    }

    private fun displayNextQuestion() {
        if (currentQuestionIndex < allQuestions.size) {
            currentQuestion = allQuestions[currentQuestionIndex]
            textViewQuestionCount.text = String.format("%02d / %02d", currentQuestionIndex + 1, TOTAL_QUESTIONS_TO_ASK)

            currentQuestion?.let { q ->
                buttonOption1.text = q.options[0]
                buttonOption2.text = q.options[1]
                buttonOption3.text = q.options[2]
                buttonOption4.text = q.options[3]

                enableAnswerButtons(false) // Disable buttons until audio is ready/played
                progressBarAudioLoading.visibility = View.VISIBLE

                // Cancel any previous audio playback runnable
                audioPlaybackRunnable?.let { handler.removeCallbacks(it) }
                audioPlaybackRunnable = Runnable {
                    if (!isPaused && !isTestOver) { // Play only if not paused or over
                        playAudio(q.audioFileName)
                    } else if (isPaused) {
                        // If paused right before playback, keep progress bar visible
                        // Audio will play on resume
                    } else {
                        progressBarAudioLoading.visibility = View.GONE
                    }
                }
                handler.postDelayed(audioPlaybackRunnable!!, audioPlaybackDelayMs)
            }
        } else {
            endTest()
        }
    }

    private fun playAudio(audioFileName: String) {
        if (isTestOver) return

        releaseMediaPlayer() // Release any previous instance
        val resId = resources.getIdentifier(audioFileName.substringBefore("."), "raw", packageName)

        if (resId == 0) {
            Log.e(TAG, "Audio file not found: $audioFileName")
            Toast.makeText(this, "音訊檔案 $audioFileName 找不到", Toast.LENGTH_SHORT).show()
            progressBarAudioLoading.visibility = View.GONE
            enableAnswerButtons(true) // Allow user to proceed or guess
            // Or consider this an error and skip question / end test
            return
        }

        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            // Apply per-session DSP EQ only when the user chose to enable it before the test.
            // The DSP state is fixed for the entire session (read from intent, shown as badge).
            val applyDsp = intent.getBooleanExtra("EXTRA_APPLY_DSP", true)
            if (applyDsp) {
                try {
                    com.wcy.hark.audio.manager.SystemDspManager.attachToSession(audioSessionId, forceEnabled = true)
                    Log.d(TAG, "Attached DSP to media player session: $audioSessionId")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to attach DSP: ${e.message}")
                }
            }
            try {
                val afd = resources.openRawResourceFd(resId)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                setOnPreparedListener {
                    progressBarAudioLoading.visibility = View.GONE
                    if (!isPaused && !isTestOver) { // Check again before starting
                        it.start()
                        enableAnswerButtons(true)
                    } else if (isPaused) {
                        // If paused during preparation, don't start.
                        // Will be handled by resume logic.
                        enableAnswerButtons(false) // Keep buttons disabled
                    }
                }
                setOnCompletionListener {
                    // Audio finished playing
                    // Buttons should already be enabled by onPrepared
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer Error: what $what, extra $extra for $audioFileName")
                    Toast.makeText(this@SRTTestActivity, "播放音訊時發生錯誤", Toast.LENGTH_SHORT).show()
                    progressBarAudioLoading.visibility = View.GONE
                    enableAnswerButtons(true) // Allow user to proceed
                    true // Error handled
                }
                prepareAsync()
            } catch (e: IOException) {
                Log.e(TAG, "MediaPlayer IOException: ${e.message} for $audioFileName")
                Toast.makeText(this@SRTTestActivity, "載入音訊時發生錯誤", Toast.LENGTH_SHORT)?.show()
                progressBarAudioLoading.visibility = View.GONE
                enableAnswerButtons(true)
            }
        }
    }

    private fun enableAnswerButtons(enable: Boolean) {
        buttonOption1.isEnabled = enable
        buttonOption2.isEnabled = enable
        buttonOption3.isEnabled = enable
        buttonOption4.isEnabled = enable
        buttonNotSure.isEnabled = enable
    }

    private fun processAnswer(selectedAnswer: String) {
        // Cancel pending audio playback for the current question if an answer is made quickly
        audioPlaybackRunnable?.let { handler.removeCallbacks(it) }
        releaseMediaPlayer() // Stop any currently playing audio
        progressBarAudioLoading.visibility = View.GONE // Hide loading if it was visible

        currentQuestion?.let { q ->
            val isCorrect = (selectedAnswer == q.correctWord)
            if (isCorrect && selectedAnswer != "not_sure") {
                score++
            }

            val record = SRTTestRecord(
                sessionIdFk = currentSessionId,
                questionNumber = currentQuestionIndex + 1,
                correctWord = q.correctWord,
                userAnswer = selectedAnswer,
                wasCorrect = isCorrect
            )
            answeredRecords.add(record)
            Log.d(TAG, "Q${record.questionNumber}: Correct='${record.correctWord}', User='${record.userAnswer}', Correct=${record.wasCorrect}")


            currentQuestionIndex++
            displayNextQuestion()
        }
    }

    private fun togglePauseResume() {
        isPaused = !isPaused
        if (isPaused) {
            buttonPauseResume.text = "繼續測驗"
            // Cancel any scheduled audio playback
            audioPlaybackRunnable?.let { handler.removeCallbacks(it) }
            mediaPlayer?.takeIf { it.isPlaying }?.pause()
            enableAnswerButtons(false)
            progressBarAudioLoading.visibility = View.GONE // Hide if paused before audio started
        } else {
            buttonPauseResume.text = "暫停"
            enableAnswerButtons(false) // Keep disabled until audio plays on resume
            progressBarAudioLoading.visibility = View.VISIBLE

            // Re-schedule current question's audio playback (as per user request: re-play on resume)
            // This means the 2-second delay also applies on resume.
            currentQuestion?.let { q ->
                // Cancel any previous audio playback runnable
                audioPlaybackRunnable?.let { handler.removeCallbacks(it) }
                audioPlaybackRunnable = Runnable {
                    if (!isTestOver) { // Play only if not over
                        playAudio(q.audioFileName)
                    } else {
                        progressBarAudioLoading.visibility = View.GONE
                    }
                }
                handler.postDelayed(audioPlaybackRunnable!!, audioPlaybackDelayMs)
            }
        }
    }

    private fun confirmEndEarly() {
        AlertDialog.Builder(this)
            .setTitle("提早結束測驗")
            .setMessage("確定要結束目前的語詞測試嗎？")
            .setPositiveButton("是") { dialog, _ ->
                dialog.dismiss()
                endTest()
            }
            .setNegativeButton("否") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false) // 視窗外點擊皆失效
            .show()
    }

    private fun endTest() {
        if (isTestOver) return // Prevent multiple calls
        isTestOver = true
        Log.d(TAG, "Test ended. Session ID: $currentSessionId, Score: $score, Answered: ${answeredRecords.size}")


        // Cancel any scheduled audio
        audioPlaybackRunnable?.let { handler.removeCallbacks(it) }
        releaseMediaPlayer()

        val accuracy = if (answeredRecords.isNotEmpty()) {
            score.toDouble() / answeredRecords.size.toDouble() * 100.0
        } else {
            0.0 // Avoid division by zero if no questions were answered (e.g., ended immediately)
        }

        // Prepare data for Result Activity
        // We will pass the session ID and let the ResultActivity query details if needed,
        // or pass the list of records directly. For now, let's pass necessary summary.
        val intent = Intent(this, SRTTestResultActivity::class.java).apply {
            putExtra("EXTRA_SESSION_ID", currentSessionId)
            putExtra("EXTRA_ACCURACY", accuracy)
            putExtra("EXTRA_TOTAL_ANSWERED", answeredRecords.size)
            putExtra("EXTRA_CORRECT_COUNT", score)
            // To pass the list of records, it needs to be Parcelable or Serializable.
            // Or save them here and ResultActivity queries them.
            // For now, we will save them in ResultActivity using the session ID.
        }
        // Pass the records to ResultActivity (SRTTestRecord needs to be Serializable or Parcelable)
        // For simplicity now, SRTTestResultActivity will be responsible for saving these.
        // But it's better to pass data or have ResultActivity query based on session ID.

        // Let's decide that SRTTestResultActivity will receive the records and save them along with the session.
        // So, SRTTestRecord should be made Serializable.
        // (Add @Serializable to SRTTestRecord data class if using kotlinx.serialization,
        // or implement Parcelable, or make it a simple Java Serializable class for Intent passing)

        // For this iteration, we will save the records here before going to result.
        // This might make ResultActivity simpler.
        val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val currentVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        saveTestSessionAndRecords(currentSessionId, accuracy, answeredRecords, currentVolume)


        startActivity(intent)
        finish() // Finish this test activity
    }

    private fun saveTestSessionAndRecords(sessionId: Long, accuracy: Double, records: List<SRTTestRecord>, phoneVolume: Int) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            // Save Session
            val sessionValues = ContentValues().apply {
                put(SRTResultContract.TestSessionEntry.COLUMN_NAME_SESSION_ID, sessionId)
                put(SRTResultContract.TestSessionEntry.COLUMN_NAME_TEST_TIMESTAMP, System.currentTimeMillis())
                put(SRTResultContract.TestSessionEntry.COLUMN_NAME_OVERALL_ACCURACY, accuracy)
                put(SRTResultContract.TestSessionEntry.COLUMN_NAME_TOTAL_QUESTIONS_ANSWERED, records.size)
                put(SRTResultContract.TestSessionEntry.COLUMN_NAME_PHONE_VOLUME, phoneVolume)
                put(SRTResultContract.TestSessionEntry.COLUMN_NAME_SUBJECT_NAME, subjectName)
            }
            val newSessionRowId = db.insert(SRTResultContract.TestSessionEntry.TABLE_NAME, null, sessionValues)
            Log.d(TAG, "Inserted session with ID: $newSessionRowId, accuracy: $accuracy")


            // Save Records
            records.forEach { record ->
                val recordValues = ContentValues().apply {
                    put(SRTResultContract.SRTRecordEntry.COLUMN_NAME_SESSION_ID_FK, record.sessionIdFk) // Should be currentSessionId
                    put(SRTResultContract.SRTRecordEntry.COLUMN_NAME_QUESTION_NUMBER, record.questionNumber)
                    put(SRTResultContract.SRTRecordEntry.COLUMN_NAME_CORRECT_WORD, record.correctWord)
                    put(SRTResultContract.SRTRecordEntry.COLUMN_NAME_USER_ANSWER, record.userAnswer)
                    put(SRTResultContract.SRTRecordEntry.COLUMN_NAME_WAS_CORRECT, if (record.wasCorrect) 1 else 0)
                }
                db.insert(SRTResultContract.SRTRecordEntry.TABLE_NAME, null, recordValues)
            }
            db.setTransactionSuccessful()
            Log.d(TAG, "Saved ${records.size} records for session $sessionId")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving test session and records: ${e.message}", e)
        } finally {
            db.endTransaction()
            // db.close() // dbHelper manages closing
        }
    }


    // 測驗中鎖定音量鍵，維持說明頁調好的舒適音量
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (!isTestOver &&
            (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
             keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun releaseMediaPlayer() {
        mediaPlayer?.let { mp ->
            try {
                com.wcy.hark.audio.manager.SystemDspManager.detachFromSession(mp.audioSessionId)
            } catch (e: Exception) {
                Log.e(TAG, "Error detaching DSP: ${e.message}")
            }
            mp.stop()
            mp.release()
        }
        mediaPlayer = null
    }

    override fun onStop() {
        super.onStop()
        if (!isTestOver) { // If test is not over (e.g. user navigates away or activity is stopped)
            // Consider pausing the test or saving state if appropriate
            // For now, just release media player if it's playing
            if (isFinishing) { // If activity is actually finishing, make sure to release
                audioPlaybackRunnable?.let { handler.removeCallbacks(it) }
                releaseMediaPlayer()
            } else { // Activity is just being stopped, not finished (e.g. home button)
                if (!isPaused) { // If test was running, pause it
                    isPaused = true // Set state
                    buttonPauseResume.text = "繼續測驗"
                    audioPlaybackRunnable?.let { handler.removeCallbacks(it) }
                    mediaPlayer?.takeIf { it.isPlaying }?.pause()
                    enableAnswerButtons(false)
                    progressBarAudioLoading.visibility = View.GONE
                }
            }
        }
    }

    override fun onDestroy() {
        // Ensure all resources are released
        audioPlaybackRunnable?.let { handler.removeCallbacks(it) }
        releaseMediaPlayer()
        // Restore the DSP engine to normal operation when leaving the test.
        // This is the counterpart to the mute/bypass we applied in onCreate.
        // Ref: KNOWN-ISSUE-004 fix — engine stays alive during test
        try {
            com.wcy.hark.audio.bridge.HarkAudioBridge.setBypassMode(false)
            com.wcy.hark.audio.bridge.HarkAudioBridge.setMuted(false)
            Log.d(TAG, "DSP engine restored to normal operation after test.")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore engine state (may not have been running): ${e.message}")
        }
        super.onDestroy()
    }
}