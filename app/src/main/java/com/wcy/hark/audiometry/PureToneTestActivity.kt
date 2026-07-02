package com.wcy.hark.audiometry
import com.wcy.hark.R
import com.wcy.hark.HarkApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
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
import kotlin.math.PI
import kotlin.math.sin

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
    private var audioTrack: AudioTrack? = null
    private val sampleRate = 44100
    private val durationSeconds = 1.5f // 聲音播放持續時間 (秒)
    private val responseDelayMillis: Long = 1500 // 按下按鈕後到處理回應的延遲 (毫秒)
    private val numSamples = (durationSeconds * sampleRate).toInt()
    private val buffer = ShortArray(numSamples)
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
        environmentalNoise = intent.getDoubleExtra("ENVIRONMENTAL_NOISE", 0.0)

        // 讀取聽力測試校正偏置值
        val repository = (application as HarkApplication).eqSettingsRepository
        CoroutineScope(Dispatchers.Main).launch {
            try {
                repository.getCalibrationOffsetFlow().collect { offset ->
                    calibrationOffset = offset
                }
            } catch (e: Exception) {
                Log.e("PureToneTestActivity", "Error loading calibration offset: ${e.message}")
            }
        }

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

        pauseButton.setOnClickListener {
            isPaused = !isPaused
            if (isPaused) {
                pauseTest()
            } else {
                resumeTest()
            }
        }

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        originalMediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        showSetupDialog()
    }

    private fun showSetupDialog() {
        val context = this
        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val paddingPx = (24 * resources.displayMetrics.density).toInt()
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
        }

        // 姓名輸入框標籤
        val nameLabel = android.widget.TextView(context).apply {
            text = "受試者姓名（可不填）:"
            textSize = 14f
            setTextColor(android.graphics.Color.BLACK)
        }
        layout.addView(nameLabel)

        // 姓名輸入框
        val nameInput = android.widget.EditText(context).apply {
            hint = "請輸入姓名"
            setSingleLine(true)
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
                
                // 更新 CSV 檔案名稱以包含受試者姓名，防重複或覆蓋
                filename = "${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())}_PureTone_${currentTestingEar}_${subjectName}_Results.csv"
                
                // 開始測驗
                startNextFrequency()
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
        // 檢查 audioTrack 是否已初始化
        if (audioTrack != null) {
            audioTrack?.stop()
        } else {
            Log.w("PureToneTestActivity", "AudioTrack was not initialized when stop() was called.")
        }

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
            val dbLevel = amplitude.toFloat() + calibrationOffset
            // 標準聲學對數衰減公式，設定 100 dB HL 為數位滿量程 (0 dBFS / 1.0)，並透過 CoerceIn 進行數位限幅防禦
            val volume = Math.pow(10.0, (dbLevel - 100.0) / 20.0).toFloat().coerceIn(0.0f, 1.0f)

            val isPulsed = isPulsedTone
            for (i in 0 until numSamples) {
                val time = i.toFloat() / sampleRate
                val fade: Float
                if (isPulsed) {
                    // Pulsed tone (300ms ON, 200ms OFF -> Period = 500ms -> 3 beeps in 1.5s)
                    val tMs = time * 1000.0f
                    val periodMs = 500.0f
                    val tMod = tMs % periodMs
                    
                    fade = if (tMod < 10.0f) {
                        tMod / 10.0f  // 10ms Fade-in
                    } else if (tMod >= 10.0f && tMod < 290.0f) {
                        1.0f         // On
                    } else if (tMod >= 290.0f && tMod < 300.0f) {
                        (300.0f - tMod) / 10.0f  // 10ms Fade-out
                    } else {
                        0.0f         // Off
                    }
                } else {
                    fade = 1.0f  // Continuous tone
                }
                buffer[i] = (Short.MAX_VALUE * volume * fade * sin(2 * PI * frequency * time)).toInt().toShort()
            }

            audioTrack?.release()
            // Fix: The static buffer size in bytes for ENCODING_PCM_16BIT stereo (2 channels)
            // is numSamples * 2 (channels) * 2 (bytes per sample) = numSamples * 4.
            // Using numSamples * 2 was truncating the 1.5s playback to 0.75s!
            audioTrack = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO) // 使用立體聲
                    .build(),
                numSamples * 4,
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            val leftVolume = if (ear == "Left") volume else 0f
            val rightVolume = if (ear == "Right") volume else 0f

            // 將 buffer 複製一份用於右聲道
            val rightBuffer = buffer.copyOf()

            // 如果是左耳，將右聲道的 buffer 設為靜音
            if (ear == "Left") {
                for (i in rightBuffer.indices) {
                    rightBuffer[i] = 0
                }
            }
            // 如果是右耳，將左聲道的 buffer 設為靜音
            else if (ear == "Right") {
                for (i in buffer.indices) {
                    buffer[i] = 0
                }
            }

            // 將左右聲道的 buffer 交錯寫入 AudioTrack
            val stereoBuffer = ShortArray(numSamples * 2)
            for (i in 0 until numSamples) {
                stereoBuffer[i * 2] = buffer[i]        // 左聲道
                stereoBuffer[i * 2 + 1] = rightBuffer[i] // 右聲道
            }

            audioTrack?.write(stereoBuffer, 0, stereoBuffer.size)
            audioTrack?.play()
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
        audioTrack?.release()
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
        CoroutineScope(Dispatchers.IO).launch {
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
        audioTrack?.release()
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
        audioTrack?.pause() // 暫停播放
        handler.removeCallbacks(responseRunnable) // 移除未執行的回應處理
        releaseTestAudioFocus() // Release focus while paused
    }

    private fun resumeTest() {
        pauseButton.text = "Pause"
        isPaused = false
        requestTestAudioFocus() // Re-request focus when resumed
        audioTrack?.play() // 恢復播放
        handler.postDelayed(responseRunnable, remainingDelay) // 重新排定回應處理
    }
}