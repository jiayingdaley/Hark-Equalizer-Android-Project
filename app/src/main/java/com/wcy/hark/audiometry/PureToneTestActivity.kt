package com.wcy.hark.audiometry
import com.wcy.hark.R
import com.wcy.hark.HarkApplication
import com.wcy.hark.data.experiment.EarphoneCalibrationRepository
import com.wcy.hark.data.experiment.FreqCalibration
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.cardview.widget.CardView
import java.io.FileWriter
import java.io.IOException
import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PureToneTestActivity : ComponentActivity() {

    private lateinit var testEarTextView: TextView
    private lateinit var currentFrequencyTextView: TextView
    private lateinit var hearingResponseArea: CardView
    private lateinit var responseView: View
    private lateinit var progressBar: ProgressBar
    private lateinit var pauseButton: Button

    private var testingEar: String? = null
    private var currentTestingEar: String = "" // 目前正在測試的耳朵
    private val frequencies = listOf(1000, 2000, 3000, 4000, 6000, 8000, 1000, 500, 250)
    private var currentFrequencyIndex = 0
    private var currentFrequency = 0
    private var currentIntensity = 40 // 初始音量 (dB HL)
    private val intensityStepDown = 10
    private val intensityStepUp = 5
    private val minIntensity = 0
    private val maxIntensity = 100
    private var heardTone = false
    private var testResults = mutableMapOf<String, MutableMap<Int, Int?>>() // 耳朵 -> (頻率 -> 聽閾)
    private var testCompletedFlag = false // 使用 flag 避免重複調用
    private var progress = 0
    private val handler = Handler(Looper.getMainLooper())
    private val toneGenerator = AudiometricToneGenerator()
    private val durationSeconds = 1.5f // 聲音播放持續時間 (秒)
    private val responseDelayMillis: Long = 1500 // 按下按鈕後到處理回應的延遲 (毫秒)
    private var filename: String = ""
    private var findingThreshold = false // 標記是否正在尋找特定頻率的聽閾
    private var responses = mutableListOf<Boolean>() // 記錄在特定頻率和強度下的回應次數
    private var responseGiven = false // 記使用者是否已回應
    private var currentPhase: TestPhase = TestPhase.DESCENDING
    private var isPaused = false
    private var remainingDelay: Long = 0
    private var testedEars = mutableSetOf<String>() // 記錄已測試過的耳朵
    private var environmentalNoise = 0.0
    private var calibrationOffset = 0.0f
    private var subjectName: String = "未填寫"
    private var isPulsedTone = false

    // Per-earphone measured calibration (loaded once before the setup dialog;
    // the audio path must never read JSON)
    private lateinit var calibRepo: EarphoneCalibrationRepository
    private var earphoneModel: String = "其他"
    private var calibTable: Map<Int, FreqCalibration> = emptyMap()
    private var isFullyCalibrated = false
    private var isExperimentMode = false
    
    // Hughson-Westlake algorithm state parameters (clinical standards)
    private var first1000Threshold: Int? = null
    private var reliabilityWarnings = mutableMapOf<String, Boolean>() // ear -> warning triggered
    private var ascendingHearCountMap = mutableMapOf<Int, Int>() // intensity -> hit count on ASCENDING path

    // System audio manager for locking volume and anti-interference
    private lateinit var audioManager: AudioManager
    private var originalMediaVolume: Int = 0
    private var hasFocus = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                runOnUiThread {
                    if (!isPaused && findingThreshold) {
                        Log.w("PureToneTestActivity", "Audio focus lost. Automatically pausing pure tone test.")
                        pauseTest()
                    }
                }
            }
        }
    }

    enum class TestPhase {
        DESCENDING, ASCENDING
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        disableSystemBackNavigation()
        setContentView(R.layout.activity_pure_tone_test)
        window.statusBarColor = android.graphics.Color.parseColor("#F5F7FA")
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        testEarTextView = findViewById(R.id.textView_test_ear)
        currentFrequencyTextView = findViewById(R.id.textView_frequency)
        hearingResponseArea = findViewById(R.id.cardView_response_area)
        responseView = findViewById(R.id.view_response_area)
        progressBar = findViewById(R.id.progressBar_test_progress)
        pauseButton = findViewById(R.id.button_pause)

        testingEar = intent.getStringExtra("TESTING_EAR")
        currentTestingEar = testingEar!!
        testEarTextView.text = "$currentTestingEar Ear"
        environmentalNoise = intent.getDoubleExtra("ENVIRONMENTAL_NOISE", 0.0)

        testResults[currentTestingEar] = mutableMapOf()
        filename = "${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())}_PureTone_${currentTestingEar}_Results.csv"

        progressBar.max = frequencies.size // 進度條的最大值設定為頻率的數量

        responseView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    heardTone = true
                    responseGiven = true
                    return@setOnTouchListener true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    return@setOnTouchListener true
                }
            }
            false
        }

        // 已停用手勢/返回鍵，Pause 是測驗中唯一的離開入口 —— 暫停時順帶
        // 提供「結束測驗」選項，避免使用者卡在測驗畫面出不去。
        pauseButton.setOnClickListener {
            isPaused = !isPaused
            if (isPaused) {
                pauseTest()
                AlertDialog.Builder(this)
                    .setTitle("測驗已暫停")
                    .setMessage("要繼續測驗，還是提早結束？")
                    .setPositiveButton("繼續測驗") { _, _ -> resumeTest() }
                    .setNegativeButton("結束測驗") { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            } else {
                resumeTest()
            }
        }

        // 左上角返回箭頭：等同「暫停 + 詢問是否結束」
        findViewById<android.view.View>(R.id.button_pt_back).setOnClickListener {
            if (!isPaused) {
                isPaused = true
                pauseTest()
            }
            AlertDialog.Builder(this)
                .setTitle("測驗已暫停")
                .setMessage("要繼續測驗，還是提早結束？")
                .setPositiveButton("繼續測驗") { _, _ -> isPaused = false; resumeTest() }
                .setNegativeButton("結束測驗") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        }

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        originalMediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        // 先把校正偏置、耳機型號與逐頻率校正表載入完成，才顯示設置對話框並開始測驗，
        // 避免第一個測試音在校正資料到達前播出（校正競態）。
        val repository = (application as HarkApplication).eqSettingsRepository
        calibRepo = EarphoneCalibrationRepository(this)
        lifecycleScope.launch {
            try {
                calibrationOffset = repository.getCalibrationOffsetFlow().first()
                earphoneModel = repository.getSelectedEarphoneFlow().first()
                isExperimentMode = repository.getExperimentModeFlow().first()
                val prefillName = repository.getLastSubjectNameFlow().first()
                withContext(Dispatchers.IO) { loadCalibrationTable() }
                showSetupDialog(prefillName)
            } catch (e: Exception) {
                Log.e("PureToneTestActivity", "Error loading calibration data: ${e.message}")
                showSetupDialog("")
            }
        }
    }

    private fun loadCalibrationTable() {
        calibTable = calibRepo.getAllCalibrations(earphoneModel)
        isFullyCalibrated = EarphoneCalibrationRepository.TEST_FREQUENCIES.all {
            calibTable[it]?.measuredDbSpl != null
        }
    }

    private fun showSetupDialog(prefillName: String = "") {
        val context = this
        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val paddingPx = (24 * resources.displayMetrics.density).toInt()
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
        }

        // 校準狀態提示（論文溯源：未校準時結果僅為相對值）
        val calibLabel = android.widget.TextView(context).apply {
            text = if (isFullyCalibrated) "耳機已校準：$earphoneModel（結果為校正後 dB HL）"
                   else "⚠️ 未經耳機校準（結果為相對值）：$earphoneModel"
            textSize = 12f
            setTextColor(if (isFullyCalibrated) android.graphics.Color.parseColor("#2E7D32")
                         else android.graphics.Color.parseColor("#E65100"))
        }
        layout.addView(calibLabel)

        val calibSpace = android.widget.Space(context).apply {
            minimumHeight = (12 * resources.displayMetrics.density).toInt()
        }
        layout.addView(calibSpace)

        // 耳機型號選擇（使用者/實驗模式皆可選；選擇會存回 DataStore 並重載校正表）
        val earphoneLabel = android.widget.TextView(context).apply {
            text = if (isExperimentMode) "使用耳機 Earphone Model:" else "使用耳機:"
            textSize = 14f
            setTextColor(android.graphics.Color.BLACK)
        }
        layout.addView(earphoneLabel)

        val models: List<String> = calibRepo.getEarphoneModels()
        val earphoneSpinner = android.widget.Spinner(context).apply {
            adapter = android.widget.ArrayAdapter(
                context, android.R.layout.simple_spinner_dropdown_item, models
            )
            val idx = models.indexOf(earphoneModel)
            if (idx >= 0) setSelection(idx)
        }
        layout.addView(earphoneSpinner)

        val space0 = android.widget.Space(context).apply {
            minimumHeight = (12 * resources.displayMetrics.density).toInt()
        }
        layout.addView(space0)

        // 姓名輸入框標籤
        val nameLabel = android.widget.TextView(context).apply {
            text = "使用者姓名（可不填）:"
            textSize = 14f
            setTextColor(android.graphics.Color.BLACK)
        }
        layout.addView(nameLabel)

        // 姓名輸入框
        val nameInput = android.widget.EditText(context).apply {
            hint = "請輸入姓名"
            setSingleLine(true)
            setText(prefillName)
        }
        layout.addView(nameInput)

        // 間距
        val space = android.widget.Space(context).apply {
            minimumHeight = (16 * resources.displayMetrics.density).toInt()
        }
        layout.addView(space)

        // 測試音類型標籤
        val typeLabel = android.widget.TextView(context).apply {
            text = "測試音類型:"
            textSize = 14f
            setTextColor(android.graphics.Color.BLACK)
        }
        layout.addView(typeLabel)

        // 測試音類型 RadioGroup
        val radioGroup = android.widget.RadioGroup(context).apply {
            orientation = android.widget.RadioGroup.HORIZONTAL
        }
        val radioContinuous = android.widget.RadioButton(context).apply {
            text = "連續長音"
            id = View.generateViewId()
            isChecked = true
        }
        val radioPulsed = android.widget.RadioButton(context).apply {
            text = "逼逼逼 (脈衝音)"
            id = View.generateViewId()
        }
        radioGroup.addView(radioContinuous)
        radioGroup.addView(radioPulsed)
        layout.addView(radioGroup)

        AlertDialog.Builder(context)
            .setTitle("純音測驗登錄與設置")
            .setView(layout)
            .setPositiveButton("開始測驗") { _, _ ->
                val enteredName = nameInput.text.toString().trim()
                subjectName = if (enteredName.isEmpty()) "未填寫" else enteredName
                isPulsedTone = radioPulsed.isChecked

                // 更新 CSV 檔案名稱以包含使用者姓名，防重複或覆蓋
                filename = "${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())}_PureTone_${currentTestingEar}_${subjectName}_Results.csv"

                val repository = (application as HarkApplication).eqSettingsRepository
                val chosenModel = earphoneSpinner?.let { models.getOrNull(it.selectedItemPosition) }
                lifecycleScope.launch {
                    if (enteredName.isNotEmpty()) repository.saveLastSubjectName(enteredName)
                    if (chosenModel != null && chosenModel != earphoneModel) {
                        earphoneModel = chosenModel
                        repository.saveSelectedEarphone(chosenModel)
                        withContext(Dispatchers.IO) { loadCalibrationTable() }
                    }
                    // 開始測驗（校正表已就緒）
                    startNextFrequency()
                }
            }
            .setCancelable(false)
            .show()
    }

    private fun startNextFrequency() {
        if (currentFrequencyIndex < frequencies.size && !isPaused) {
            currentFrequency = frequencies[currentFrequencyIndex]
            currentFrequencyTextView.text = "${currentFrequency} Hz"
            
            // Dynamic starting intensity (Iso 8253-1/Hughson-Westlake)
            // Start at (previous_threshold - 10dB), capped at minIntensity. Fallback to 40dB.
            if (currentFrequencyIndex == 0) {
                currentIntensity = 40
            } else {
                val lastFreq = frequencies[currentFrequencyIndex - 1]
                val lastThreshold = testResults[currentTestingEar]!![lastFreq]
                currentIntensity = if (lastThreshold != null && lastThreshold != -1) {
                    (lastThreshold - 10).coerceAtLeast(minIntensity)
                } else {
                    40 // fallback
                }
            }
            
            testResults[currentTestingEar]!![currentFrequency] = null // 初始化該頻率的結果
            findingThreshold = true
            responses.clear()
            ascendingHearCountMap.clear() // Clear hit count for the new frequency
            currentPhase = TestPhase.DESCENDING // 每次開始新頻率都從下降階段開始
            startTestTrial()
        } else if (currentFrequencyIndex >= frequencies.size) {
            // 完成當前耳朵的所有頻率測試
            testCompleted()
        } else if (isPaused) {
            pauseButton.text = "Resume"
        }
    }

    private fun startTestTrial() {
        if (!isPaused) {
            requestTestAudioFocus() // Request transient exclusive audio focus
            playSound(currentFrequency, currentIntensity, currentTestingEar)
            heardTone = false
            responseGiven = false // 重置回應狀態
            remainingDelay = (durationSeconds * 1000).toLong() + responseDelayMillis
            handler.postDelayed(responseRunnable, remainingDelay)
        } else {
            pauseButton.text = "Resume"
        }
    }

    private val responseRunnable = Runnable {
        processResponse()
    }

    private fun processResponse() {

        responses.add(heardTone)
        Log.d("PureToneTestActivity", "Freq: $currentFrequency Hz, Intensity: $currentIntensity dB HL, Heard: $heardTone, Phase: $currentPhase")
        
        if (findingThreshold && !isPaused) {
            when (currentPhase) {
                TestPhase.DESCENDING -> {
                    if (heardTone) {
                        // 聽到聲音，降低 10dB
                        currentIntensity -= intensityStepDown
                        if (currentIntensity < minIntensity) {
                            currentIntensity = minIntensity
                            // Reached absolute minimum with positive response, record as threshold
                            recordThresholdAndGoNext(currentIntensity)
                        } else {
                            startTestTrial()
                        }
                    } else {
                        // 沒有聽到聲音，進入上升階段，提高 5dB
                        currentIntensity += intensityStepUp
                        currentPhase = TestPhase.ASCENDING
                        startTestTrial()
                    }
                }
                TestPhase.ASCENDING -> {
                    if (heardTone) {
                        // 聽到聲音：在上升路徑中，同一個音量下聽到反應次數加 1
                        val count = (ascendingHearCountMap[currentIntensity] ?: 0) + 1
                        ascendingHearCountMap[currentIntensity] = count
                        Log.d("PureToneTestActivity", "Ascending hit at $currentIntensity dB HL, count = $count")
                        
                        if (count >= 2) {
                            // 2 out of 3 (or 2 hits on ascending direction) clinical standard threshold achieved!
                            recordThresholdAndGoNext(currentIntensity)
                        } else {
                            // Re-descend by 10dB and trace back up (clinical Hughson-Westlake)
                            currentIntensity -= intensityStepDown
                            currentIntensity = currentIntensity.coerceAtLeast(minIntensity)
                            currentPhase = TestPhase.DESCENDING
                            startTestTrial()
                        }
                    } else {
                        // 沒有聽到聲音，繼續上升 5dB
                        currentIntensity += intensityStepUp
                        if (currentIntensity > maxIntensity) {
                            recordThresholdAndGoNext(-1) // 未聽到
                        } else {
                            startTestTrial()
                        }
                    }
                }
            }
        } else if (isPaused) {
            pauseButton.text = "Resume"
        }
    }

    private fun recordThresholdAndGoNext(threshold: Int) {
        // Record first 1000 Hz threshold for retest verification
        if (currentFrequencyIndex == 0) {
            first1000Threshold = threshold
        } 
        // Index 6 is the 1000 Hz retest frequency. Perform reliability check.
        else if (currentFrequencyIndex == 6) {
            val t1 = first1000Threshold
            if (t1 != null && t1 != -1 && threshold != -1) {
                val diff = Math.abs(t1 - threshold)
                Log.d("PureToneTestActivity", "1000Hz retest validation: First=$t1, Retest=$threshold, diff=$diff dB")
                if (diff >= 10) {
                    reliabilityWarnings[currentTestingEar] = true
                    Log.w("PureToneTestActivity", "Triggered Reliability Warning for $currentTestingEar ear (diff >= 10dB)")
                }
            }
        }

        testResults[currentTestingEar]!![currentFrequency] = threshold
        findingThreshold = false
        updateProgress() // 更新進度條
        currentFrequencyIndex++
        startNextFrequency()
    }

    private fun playSound(frequency: Int, amplitude: Int, ear: String) {
        if (!isPaused) {
            val targetDbhl = amplitude.toFloat() + calibrationOffset
            // 有實測校正表時：dbfs = refDbfs + (targetDbhl + RETSPL) − measuredDbSpl（真實 dB HL）；
            // 未校準時退回舊有相對映射（100 dB HL == 0 dBFS）。
            var dbfs = calibRepo.dbfsForTargetDbhl(calibTable, frequency, targetDbhl)
                ?: (targetDbhl - 100.0f)
            if (dbfs > 0f) {
                Log.w("PureToneTestActivity",
                    "Requested $targetDbhl dB HL at $frequency Hz exceeds earphone capability (dbfs=$dbfs), clamping to 0 dBFS")
                dbfs = 0f
            }

            val earChannel = if (ear == "Left") AudiometricToneGenerator.Ear.LEFT
                             else AudiometricToneGenerator.Ear.RIGHT
            toneGenerator.play(
                frequencyHz = frequency,
                dbfs = dbfs,
                ear = earChannel,
                pulsed = isPulsedTone,
                durationSec = durationSeconds
            )
        }
    }

    private fun updateProgress() {
        if (!isPaused) {
            progress++
            progressBar.progress = progress
        }
    }

    private fun testCompleted() {
        if (testCompletedFlag) {
            return // 避免重複調用
        }
        testCompletedFlag = true
        toneGenerator.release()
        releaseTestAudioFocus() // Release exclusive focus on test completion
        testedEars.add(currentTestingEar)

        val otherEar = if (currentTestingEar == "Left") "Right" else "Left"

        // 檢查是否已測試過兩隻耳朵
        if (testedEars.size == 2) {
            saveTestResultsToCSV()
            val intent = Intent(this, AudiogramActivity::class.java)
            intent.putExtra("LEFT_EAR_RESULTS", testResults["Left"] as? Serializable)
            intent.putExtra("RIGHT_EAR_RESULTS", testResults["Right"] as? Serializable)
            startActivity(intent)
            finish()
            return
        }

        // 詢問是否要測試另一耳
        val builder = AlertDialog.Builder(this)
            .setTitle("Test Completed")
            .setMessage("Do you want to test your $otherEar ear?")
            .setPositiveButton("Yes") { dialog, which ->
                // 繼續測試另一耳
                currentTestingEar = otherEar
                testEarTextView.text = "$currentTestingEar Ear"
                currentFrequencyIndex = 0
                progress = 0
                progressBar.progress = 0
                testResults[currentTestingEar] = mutableMapOf()
                filename = "${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())}_PureTone_${currentTestingEar}_Results.csv"
                testCompletedFlag = false // 重置 flag
                first1000Threshold = null // Reset first 1000Hz reference for the new ear
                startNextFrequency()
            }
            .setNegativeButton("No") { dialog, which ->
                // 不測試另一耳，輸出結果
                saveTestResultsToCSV()
                val intent = Intent(this, AudiogramActivity::class.java)
                intent.putExtra("LEFT_EAR_RESULTS", testResults["Left"] as? Serializable)
                intent.putExtra("RIGHT_EAR_RESULTS", testResults["Right"] as? Serializable)
                startActivity(intent)
                finish()
            }
            .setCancelable(false) // 禁止點擊視窗外使其消失

        builder.show()
    }

    private fun saveTestResultsToCSV() {
        val baseDir = getExternalFilesDir(null)?.absolutePath
        val filePath = "$baseDir/$filename"
        
        val repository = (application as HarkApplication).eqSettingsRepository
        lifecycleScope.launch(Dispatchers.IO) {
            testResults.forEach { (ear, results) ->
                results.forEach { (frequency, threshold) ->
                    if (threshold != null) {
                        repository.saveAudiogramThreshold(ear.lowercase(), frequency, threshold)
                    }
                }
            }
        }
        try {
            FileWriter(filePath).use { writer ->
                writer.append("Subject Name,$subjectName\n")
                writer.append("Environmental Noise (dB SPL),$environmentalNoise\n")
                // Thesis provenance: which transducer, and whether thresholds are
                // true (calibrated) dB HL or relative values.
                writer.append("Earphone,$earphoneModel\n")
                writer.append("Calibrated,$isFullyCalibrated\n")
                // Write reliability warning flag (Clinical reliability metadata for thesis)
                val hasWarning = reliabilityWarnings[currentTestingEar] == true
                writer.append("Reliability Warning,$hasWarning\n")
                writer.append("Ear,Frequency (Hz),Threshold (dB HL)\n")
                testResults.forEach { (ear, results) ->
                    results.forEach { (frequency, threshold) ->
                        writer.append("$ear,$frequency,${threshold ?: "N/A"}\n")
                    }
                }
                Log.d("PureToneTestActivity", "Test results saved to: $filePath")
            }
        } catch (e: IOException) {
            Log.e("PureToneTestActivity", "Error writing test results to CSV: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        // Lock system media volume to maximum level (clinical calibration standard)
        if (::audioManager.isInitialized) {
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, 0)
        }
    }

    override fun onPause() {
        super.onPause()
        // Auto-pause test and release focus when user navigates away
        if (!isPaused && findingThreshold) {
            pauseTest()
        }
        releaseTestAudioFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Block volume keys to prevent calibration drift and hide volume UI
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        toneGenerator.release()
        releaseTestAudioFocus()
        // Restore original user volume level on finish
        if (::audioManager.isInitialized) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalMediaVolume, 0)
        }
    }

    private fun requestTestAudioFocus(): Boolean {
        if (hasFocus) return true
        val result = audioManager.requestAudioFocus(
            audioFocusChangeListener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
        )
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasFocus
    }

    private fun releaseTestAudioFocus() {
        if (hasFocus) {
            audioManager.abandonAudioFocus(audioFocusChangeListener)
            hasFocus = false
        }
    }

    private fun pauseTest() {
        pauseButton.text = "Resume"
        isPaused = true
        toneGenerator.pause() // 暫停播放
        handler.removeCallbacks(responseRunnable) // 移除未執行的回應處理
        releaseTestAudioFocus() // Release focus while paused
    }

    private fun resumeTest() {
        pauseButton.text = "Pause"
        isPaused = false
        requestTestAudioFocus() // Re-request focus when resumed
        toneGenerator.resume() // 恢復播放
        handler.postDelayed(responseRunnable, remainingDelay) // 重新排定回應處理
    }
}