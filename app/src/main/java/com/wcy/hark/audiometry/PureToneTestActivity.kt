package com.wcy.hark.audiometry
import com.wcy.hark.R

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
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import java.io.FileWriter
import java.io.IOException
import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * PureToneTestActivity — Pure-Tone Audiometry Test Screen
 *
 * Plays pure-tone sine waves at standard audiometric frequencies and hearing levels.
 * Uses the up-5 down-10 method (Hughson-Westlake) to determine hearing thresholds.
 * Results are passed to AudiogramActivity for display.
 *
 * Original: PureToneEqualizer_250223/PureToneTestActivity.kt
 * Migrated: package renamed to com.wcy.hark.audiometry
 *
 * Ref: Audiometric test standard ISO 8253-1
 * Ref: AudioTrack low-latency tone generation — developer.android.com/reference/android/media/AudioTrack
 */
class PureToneTestActivity : ComponentActivity() {

    // ── UI Views ──────────────────────────────────────────────────────────────
    private lateinit var testEarTextView: TextView
    private lateinit var currentFrequencyTextView: TextView
    private lateinit var hearingResponseArea: CardView
    private lateinit var responseView: View
    private lateinit var progressBar: ProgressBar
    private lateinit var pauseButton: Button
    private lateinit var mainConstraintLayout: ConstraintLayout

    // ── Test State ────────────────────────────────────────────────────────────
    /** Current ear being tested: "Left" or "Right" */
    private var testEar: String = "Left"

    /** Current test frequency index in the standard frequency list */
    private var currentFrequencyIndex = 0

    /** Current presentation level in dB HL */
    private var currentLevelDbHL = 40

    /** Whether the test is currently paused */
    private var isPaused = false

    /** Number of consecutive correct responses at current level */
    private var correctResponses = 0

    /** Threshold for the current frequency (null if not determined) */
    private var currentThreshold: Int? = null

    /** Whether a tone is currently playing */
    private var isTonePlaying = false

    /** Whether we are currently waiting for a response after tone played */
    private var waitingForResponse = false

    /** Timestamp when tone started playing (for response window calculation) */
    private var toneStartTime: Long = 0L

    /** AudioTrack for pure tone generation */
    private var audioTrack: AudioTrack? = null

    /** Handler for delayed operations */
    private val handler = Handler(Looper.getMainLooper())

    /** Runnable for response timeout */
    private lateinit var responseRunnable: Runnable

    /** Remaining delay when paused (milliseconds) */
    private var remainingDelay: Long = 0L

    /** Test results: Map<"Left"/"Right", Map<frequencyHz, thresholdDbHL?>> */
    private val testResults: MutableMap<String, MutableMap<Int, Int?>> = mutableMapOf(
        "Left" to mutableMapOf(),
        "Right" to mutableMapOf()
    )

    /** CSV filename for optional export */
    private var filename = ""

    companion object {
        private const val TAG = "PureToneTestActivity"

        /** Standard audiometric frequencies in Hz (ISO 8253-1) */
        val TEST_FREQUENCIES = intArrayOf(1000, 2000, 4000, 8000, 500, 250)

        // Level stepping (up 5 dB down 10 dB Hughson-Westlake method)
        const val LEVEL_STEP_DOWN = 10
        const val LEVEL_STEP_UP = 5
        const val MIN_LEVEL_DB_HL = -10
        const val MAX_LEVEL_DB_HL = 120

        // Tone duration in milliseconds
        const val TONE_DURATION_MS = 1000L

        // Inter-stimulus gap in milliseconds
        const val INTER_STIMULUS_GAP_MS = 1500L

        // Response window in milliseconds
        const val RESPONSE_WINDOW_MS = 2500L

        // Sample rate for pure tone generation
        const val SAMPLE_RATE = 44100
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pure_tone_test)

        // Receive ear selection from SelectEarActivity
        testEar = intent.getStringExtra("TEST_EAR") ?: "Left"

        // Generate filename
        val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
        filename = "${dateFormat.format(Date())}_PureTone_${testEar}.csv"

        // Bind UI
        testEarTextView = findViewById(R.id.textView_test_ear)
        currentFrequencyTextView = findViewById(R.id.textView_frequency)
        hearingResponseArea = findViewById(R.id.cardView_response_area)
        responseView = hearingResponseArea.getChildAt(0)
            ?.let { (it as? ConstraintLayout)?.getChildAt(0) } ?: hearingResponseArea
        progressBar = findViewById(R.id.progressBar_test_progress)
        pauseButton = findViewById(R.id.button_pause)
        mainConstraintLayout = findViewById(R.id.main_constraint_layout)

        // Initialize UI labels
        testEarTextView.text = if (testEar == "Left") "左耳" else "右耳"
        progressBar.max = TEST_FREQUENCIES.size
        progressBar.progress = 0

        // Response runnable — triggers when no response during window
        responseRunnable = Runnable {
            if (!isPaused && waitingForResponse) {
                handleNoResponse()
            }
        }

        // Tap-anywhere-on-card to register a heard response
        hearingResponseArea.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN && waitingForResponse && !isPaused) {
                handleResponse(heard = true)
            }
            true
        }

        // Pause / Resume button
        pauseButton.setOnClickListener {
            if (isPaused) resumeTest() else pauseTest()
        }

        // Start testing at first frequency
        startFrequencyTest()
    }

    override fun onDestroy() {
        super.onDestroy()
        audioTrack?.release()
        handler.removeCallbacksAndMessages(null)
    }

    // ── Test Logic ────────────────────────────────────────────────────────────

    /**
     * Starts testing the current frequency.
     * Resets level state and plays the first tone.
     */
    private fun startFrequencyTest() {
        if (currentFrequencyIndex >= TEST_FREQUENCIES.size) {
            // All frequencies for current ear done
            checkIfBothEarsDone()
            return
        }

        val freq = TEST_FREQUENCIES[currentFrequencyIndex]
        currentLevelDbHL = 40 // Start at comfortable level
        correctResponses = 0
        currentThreshold = null

        currentFrequencyTextView.text = "$freq Hz"
        playTone()
    }

    /**
     * Plays a pure tone at the current frequency and level.
     * Uses AudioTrack with PCM16 for low-latency generation.
     */
    private fun playTone() {
        if (isPaused) return

        val freq = TEST_FREQUENCIES[currentFrequencyIndex]
        val level = currentLevelDbHL

        Log.d(TAG, "Playing: ${freq}Hz at ${level}dB HL for $testEar")
        isTonePlaying = true
        waitingForResponse = false

        // Generate PCM sine wave on background thread
        Thread {
            val numSamples = (SAMPLE_RATE * TONE_DURATION_MS / 1000).toInt()
            val samples = ShortArray(numSamples)
            val amplitude = levelToAmplitude(level)

            for (i in 0 until numSamples) {
                val angle = 2.0 * PI * freq.toDouble() * i.toDouble() / SAMPLE_RATE.toDouble()
                samples[i] = (sin(angle) * amplitude * Short.MAX_VALUE).toInt().toShort()
            }

            val minBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                if (testEar == "Left") AudioFormat.CHANNEL_OUT_FRONT_LEFT else AudioFormat.CHANNEL_OUT_FRONT_RIGHT,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(numSamples * 2)

            audioTrack?.release()
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(
                            if (testEar == "Left") AudioFormat.CHANNEL_OUT_FRONT_LEFT
                            else AudioFormat.CHANNEL_OUT_FRONT_RIGHT
                        )
                        .build()
                )
                .setBufferSizeInBytes(minBufferSize)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack?.write(samples, 0, numSamples)
            audioTrack?.play()

            toneStartTime = System.currentTimeMillis()

            // After tone ends, open response window
            handler.postDelayed({
                if (!isPaused) {
                    isTonePlaying = false
                    waitingForResponse = true
                    // Start response timeout
                    remainingDelay = RESPONSE_WINDOW_MS
                    handler.postDelayed(responseRunnable, RESPONSE_WINDOW_MS)
                }
            }, TONE_DURATION_MS)
        }.start()
    }

    /**
     * Convert dB HL to a 0..1 amplitude factor for tone generation.
     * Formula: amplitude = 10^(dBHL/20) normalized to audiometric 0 dBHL ≈ -70 dBFS
     */
    private fun levelToAmplitude(dBHL: Int): Double {
        // Map dBHL to dBFS: 0 dBHL ≈ very quiet (threshold). 
        // We use a simplified linear mapping: 0 dBHL → 0.001, 110 dBHL → 0.9
        val clampedDB = dBHL.coerceIn(MIN_LEVEL_DB_HL, MAX_LEVEL_DB_HL)
        return (clampedDB + 10).toDouble() / 130.0 * 0.9
    }

    /**
     * Handles a "heard" response from the user.
     */
    private fun handleResponse(heard: Boolean) {
        handler.removeCallbacks(responseRunnable)
        waitingForResponse = false

        if (heard) {
            correctResponses++
            if (correctResponses >= 2) {
                // Threshold confirmed at current level
                currentThreshold = currentLevelDbHL
                testResults[testEar]?.put(TEST_FREQUENCIES[currentFrequencyIndex], currentThreshold)
                Log.d(TAG, "Threshold for ${TEST_FREQUENCIES[currentFrequencyIndex]}Hz $testEar: ${currentThreshold}dBHL")
                moveToNextFrequency()
            } else {
                // Need one more confirmation: step down
                adjustLevel(down = true)
                scheduleNextTone()
            }
        }
    }

    /**
     * Handles "no response" (timeout) — steps level up.
     */
    private fun handleNoResponse() {
        waitingForResponse = false
        correctResponses = 0

        if (currentLevelDbHL >= MAX_LEVEL_DB_HL) {
            // Can't go higher — no threshold found
            testResults[testEar]?.put(TEST_FREQUENCIES[currentFrequencyIndex], null)
            moveToNextFrequency()
        } else {
            adjustLevel(down = false)
            scheduleNextTone()
        }
    }

    private fun adjustLevel(down: Boolean) {
        if (down) {
            currentLevelDbHL = (currentLevelDbHL - LEVEL_STEP_DOWN).coerceAtLeast(MIN_LEVEL_DB_HL)
        } else {
            currentLevelDbHL = (currentLevelDbHL + LEVEL_STEP_UP).coerceAtMost(MAX_LEVEL_DB_HL)
        }
        Log.d(TAG, "Level adjusted to: ${currentLevelDbHL}dBHL")
    }

    private fun scheduleNextTone() {
        handler.postDelayed({ if (!isPaused) playTone() }, INTER_STIMULUS_GAP_MS)
    }

    private fun moveToNextFrequency() {
        currentFrequencyIndex++
        progressBar.progress = currentFrequencyIndex
        handler.postDelayed({ startFrequencyTest() }, INTER_STIMULUS_GAP_MS)
    }

    /**
     * Checks if both ears have been tested; shows summary dialog and navigates.
     */
    private fun checkIfBothEarsDone() {
        val leftDone  = testResults["Left"]?.size  == TEST_FREQUENCIES.size
        val rightDone = testResults["Right"]?.size == TEST_FREQUENCIES.size

        if (testEar == "Left" && !rightDone) {
            // Switch to right ear
            showEarSwitchDialog()
        } else {
            // All done — show audiogram
            navigateToAudiogram()
        }
    }

    private fun showEarSwitchDialog() {
        AlertDialog.Builder(this)
            .setTitle("換耳測試")
            .setMessage("左耳測試完成！請換到右耳繼續測試。")
            .setPositiveButton("開始右耳") { _, _ ->
                testEar = "Right"
                testEarTextView.text = "右耳"
                currentFrequencyIndex = 0
                progressBar.progress = 0
                startFrequencyTest()
            }
            .setCancelable(false)
            .show()
    }

    private fun navigateToAudiogram() {
        val leftR  = testResults["Left"]  as? Serializable
        val rightR = testResults["Right"] as? Serializable

        saveTestResultsToCSV()

        val intent = Intent(this, AudiogramActivity::class.java).apply {
            putExtra("LEFT_EAR_RESULTS",  leftR)
            putExtra("RIGHT_EAR_RESULTS", rightR)
        }
        startActivity(intent)
        finish()
    }

    private fun saveTestResultsToCSV() {
        val baseDir = getExternalFilesDir(null)?.absolutePath
        val filePath = "$baseDir/$filename"
        try {
            FileWriter(filePath).use { writer ->
                writer.append("Ear,Frequency (Hz),Threshold (dB HL)\n")
                testResults.forEach { (ear, results) ->
                    results.forEach { (frequency, threshold) ->
                        writer.append("$ear,$frequency,${threshold ?: "N/A"}\n")
                    }
                }
                Log.d(TAG, "Test results saved to: $filePath")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error writing test results to CSV: ${e.message}")
        }
    }

    // ── Pause / Resume ────────────────────────────────────────────────────────

    private fun pauseTest() {
        isPaused = true
        pauseButton.text = "繼續"
        audioTrack?.pause()
        handler.removeCallbacks(responseRunnable)
    }

    private fun resumeTest() {
        isPaused = false
        pauseButton.text = "暫停"
        audioTrack?.play()
        handler.postDelayed(responseRunnable, remainingDelay)
    }
}
