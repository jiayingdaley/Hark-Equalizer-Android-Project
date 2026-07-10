package com.wcy.hark.ui.viewmodel

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wcy.hark.audio.bridge.HarkAudioBridge
import com.wcy.hark.data.experiment.EarphoneCalibrationRepository
import com.wcy.hark.data.experiment.ExperimentLog
import com.wcy.hark.data.experiment.ExperimentLogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// =============================================================================
// Supporting State Types
// =============================================================================

enum class RecordingState { IDLE, RECORDING_PHONE, RECORDING_HEADSET, DONE }
enum class WdrcSweepState { IDLE, SETTLING, DONE }
enum class BurstPhase     { IDLE, HIGH, LOW }
enum class Ospl90State    { IDLE, SWEEPING, DONE }

// =============================================================================
// ExperimentViewModel
// =============================================================================

/**
 * ExperimentViewModel
 *
 * Manages all state and coroutine logic for the CalibrationTestScreen.
 * Each experiment mode follows the functional reactive pattern:
 *   1. User taps a button → ViewModel launches a coroutine job.
 *   2. Coroutine updates state periodically.
 *   3. UI reacts to state changes via Compose state.
 *
 * No direct UI references are held; all communication is through MutableState.
 */
class ExperimentViewModel(
    private val appContext: Context,
    private val calibRepo: EarphoneCalibrationRepository,
    private val logRepo: ExperimentLogRepository
) : ViewModel() {

    // =========================================================================
    // 1. Diagnostics & Tap Point
    // =========================================================================

    /** Post-InputGain-Compensation peak level (dBFS), updated by diagnostics polling. */
    val postInputGainLevel = mutableStateOf(-120.0f)

    // =========================================================================
    // 2. Calibration Tone
    // =========================================================================

    val calibFreqHz      = mutableStateOf(1000.0f)
    val calibLevelDbfs   = mutableStateOf(-20.0f)
    val calibToneRunning = mutableStateOf(false)

    /** Selected earphone model (drives calibration table lookup).
     *  與 DataStore selected_earphone_model 雙向同步：實驗面板選的耳機
     *  會帶到純音測驗/受試者流程，反之亦然（單一真相）。 */
    val selectedEarphone = mutableStateOf("ATH-CKS330NC")

    private val eqSettingsRepository =
        (appContext.applicationContext as com.wcy.hark.HarkApplication).eqSettingsRepository

    /** 一律用這個改耳機型號（會寫回 DataStore）；不要直接改 selectedEarphone。 */
    fun selectEarphone(model: String) {
        selectedEarphone.value = model
        updateCalibCorrection()
        viewModelScope.launch { eqSettingsRepository.saveSelectedEarphone(model) }
    }

    /** Measured SPL at refDbfs for the current model + frequency (null = 未校準). */
    val calibMeasuredDbSpl = mutableStateOf<Float?>(null)

    /** Estimated output in dBHL at the current dBFS (null = 未校準，無絕對基準). */
    val estimatedOutputDbhl = mutableStateOf<Float?>(null)

    /** One-shot user-facing message (permission errors, engine-not-running, etc.). */
    val userMessage = mutableStateOf<String?>(null)

    /** List of available earphone models from JSON. */
    val earphoneModels = mutableStateOf<List<String>>(emptyList())

    // =========================================================================
    // 3. Dual-Mic Recording (2A)
    // =========================================================================

    val dualMicState        = mutableStateOf(RecordingState.IDLE)
    val recordingProgressMs = mutableStateOf(0L)
    val lastRecordingFile   = mutableStateOf<File?>(null)
    private var recordingJob: Job? = null

    // =========================================================================
    // 4. WDRC I/O Sweep
    // =========================================================================

    /** Settle time per sweep step (ms). Default 3000ms per ANSI S3.22 analysis. */
    val wdrcSettleTimeMs    = mutableStateOf(3000)
    val wdrcSweepState      = mutableStateOf(WdrcSweepState.IDLE)
    val wdrcCurrentStepIdx  = mutableStateOf(0)   // 0 to 11 (-60 to -5 dBFS, step 5)
    val wdrcCurrentLevelDbfs= mutableStateOf(-60.0f)
    val wdrcTotalSteps      = 12                   // -60, -55, -50, ..., -5 dBFS
    val wdrcElapsedMs       = mutableStateOf(0L)
    private var wdrcJob: Job? = null

    // =========================================================================
    // 5. Tone Burst
    // =========================================================================

    val burstLowDbfs    = mutableStateOf(-40.0f)
    val burstHighDbfs   = mutableStateOf(-20.0f)
    val burstHighMs     = mutableStateOf(300)
    val burstLowMs      = mutableStateOf(500)
    val burstRepeatCount= mutableStateOf(5)
    val burstRunning    = mutableStateOf(false)
    val burstPhase      = mutableStateOf(BurstPhase.IDLE)
    val burstCurrentRep = mutableStateOf(0)
    val burstPhaseRemMs = mutableStateOf(0L)
    private var burstJob: Job? = null

    // =========================================================================
    // 6. OSPL90 Log-Swept Chirp
    // =========================================================================

    val ospl90DurationSec   = mutableStateOf(30)
    val ospl90LevelDbfs     = mutableStateOf(-20.0f)
    val ospl90State         = mutableStateOf(Ospl90State.IDLE)
    val ospl90CurrentFreqHz = mutableStateOf(250.0f)
    val ospl90UsePinkNoise  = mutableStateOf(false)   // false = chirp (default), true = pink noise
    private var ospl90Job: Job? = null

    // =========================================================================
    // Init
    // =========================================================================

    init {
        viewModelScope.launch(Dispatchers.IO) {
            earphoneModels.value = calibRepo.getEarphoneModels()
            // 載入上次選擇的耳機（與純音測驗共用的 DataStore 單一真相）
            val saved = eqSettingsRepository.getSelectedEarphoneFlow().first()
            if (saved.isNotEmpty() && earphoneModels.value.contains(saved)) {
                selectedEarphone.value = saved
            }
        }
        // Update correction whenever earphone or frequency changes
        updateCalibCorrection()
    }

    // =========================================================================
    // 2. Calibration Tone Control
    // =========================================================================

    /** Refreshes the measured SPL and estimated dBHL for the current selection. */
    fun updateCalibCorrection() {
        viewModelScope.launch(Dispatchers.IO) {
            val freq   = calibFreqHz.value.toInt()
            val model  = selectedEarphone.value
            calibMeasuredDbSpl.value  = calibRepo.getCalibration(model, freq)?.measuredDbSpl
            estimatedOutputDbhl.value = calibRepo.estimateOutputDbhl(model, freq, calibLevelDbfs.value)
        }
    }

    /** Starts or stops the calibration tone. */
    fun setCalibTone(enabled: Boolean) {
        if (enabled && !ensureEngineRunning()) return
        calibToneRunning.value = enabled
        HarkAudioBridge.setCalibTone(calibFreqHz.value, calibLevelDbfs.value, enabled)
        updateCalibCorrection()
    }

    /**
     * Saves a measurement from an external sound level meter: the tone was played
     * at calibLevelDbfs, and the meter read [measuredSpl] dBSPL.
     */
    fun saveCalibCorrection(measuredSpl: Float) {
        viewModelScope.launch(Dispatchers.IO) {
            val freq  = calibFreqHz.value.toInt()
            val model = selectedEarphone.value
            calibRepo.saveMeasurement(model, freq, calibLevelDbfs.value, measuredSpl)
            calibMeasuredDbSpl.value  = measuredSpl
            estimatedOutputDbhl.value = calibRepo.estimateOutputDbhl(model, freq, calibLevelDbfs.value)
        }
    }

    /** Guard: experiment signal paths need the Oboe engine running. */
    private fun ensureEngineRunning(): Boolean {
        return if (HarkAudioBridge.isEngineActuallyRunning()) {
            true
        } else {
            userMessage.value = "DSP 引擎未運行，請先進入實驗面板啟動引擎 (DSP engine is not running)"
            false
        }
    }

    // =========================================================================
    // 3. Dual-Mic Recording (2A)
    // =========================================================================

    /**
     * Records 10 seconds of audio using AudioRecord.
     * @param useHeadsetMic true = request VOICE_COMMUNICATION source (headset),
     *                      false = use MIC source (phone mic)
     */
    fun startRecording(useHeadsetMic: Boolean) {
        if (dualMicState.value != RecordingState.IDLE) return

        if (androidx.core.content.ContextCompat.checkSelfPermission(
                appContext, android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            userMessage.value = "需要麥克風權限才能錄音，請至系統設定授權 (RECORD_AUDIO permission required)"
            return
        }

        dualMicState.value = if (useHeadsetMic) RecordingState.RECORDING_HEADSET
                             else               RecordingState.RECORDING_PHONE
        recordingProgressMs.value = 0L

        val audioSource = if (useHeadsetMic) MediaRecorder.AudioSource.VOICE_COMMUNICATION
                          else               MediaRecorder.AudioSource.MIC
        val sampleRate  = 48000
        val channels    = AudioFormat.CHANNEL_IN_MONO
        val encoding    = AudioFormat.ENCODING_PCM_16BIT
        val minBuf      = AudioRecord.getMinBufferSize(sampleRate, channels, encoding)
        val bufSize     = maxOf(minBuf, 4096)
        val durationMs  = 10_000L
        val label       = if (useHeadsetMic) "HeadsetMic" else "PhoneMic"

        recordingJob = viewModelScope.launch(Dispatchers.IO) {
            var recorder: AudioRecord? = null
            try {
                recorder = try {
                    AudioRecord(audioSource, sampleRate, channels, encoding, bufSize)
                } catch (e: SecurityException) {
                    null
                }
                if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                    userMessage.value = "麥克風初始化失敗，請確認裝置與音源可用 (AudioRecord init failed)"
                    dualMicState.value = RecordingState.IDLE
                    return@launch
                }

                // Preallocated PCM buffer — avoids ~480k boxed Shorts over 10 s @ 48 kHz
                val pcmData = ShortArray((sampleRate * durationMs / 1000L).toInt())
                var written = 0
                val buffer  = ShortArray(bufSize / 2)
                recorder.startRecording()

                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < durationMs && written < pcmData.size) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val n = minOf(read, pcmData.size - written)
                        System.arraycopy(buffer, 0, pcmData, written, n)
                        written += n
                    }
                    recordingProgressMs.value = System.currentTimeMillis() - startTime
                }

                // Write WAV file to external files dir
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val dir = File(appContext.getExternalFilesDir(null), "experiment_recordings")
                dir.mkdirs()
                val wavFile = File(dir, "${timestamp}_${label}.wav")
                writeWav(wavFile, pcmData.copyOf(written), sampleRate, 1)
                lastRecordingFile.value = wavFile
                dualMicState.value = RecordingState.DONE

                // Log experiment session
                logRepo.insertLog(ExperimentLog(
                    timestamp = timestamp,
                    testType  = "DUAL_MIC",
                    earphone  = selectedEarphone.value,
                    dspBypass = "{}",
                    params    = JSONObject().apply {
                        put("source", label)
                        put("durationMs", durationMs)
                        put("sampleRate", sampleRate)
                        put("file", wavFile.name)
                    }.toString()
                ))
            } finally {
                try {
                    recorder?.let {
                        if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
                        it.release()
                    }
                } catch (e: Exception) {
                    // release best-effort
                }
            }
        }
    }

    fun resetRecordingState() {
        recordingJob?.cancel()
        dualMicState.value = RecordingState.IDLE
    }

    // =========================================================================
    // 4. WDRC I/O Sweep
    // =========================================================================

    /**
     * Starts the WDRC I/O curve automated sweep.
     * Outputs 1kHz calibration tone from -60 to -5 dBFS, 5dB steps.
     * Each step plays for settleTimeMs milliseconds (default 3000ms).
     * Assumes WDRC is enabled (not bypassed) for accurate I/O characterization.
     */
    fun startWdrcSweep() {
        if (wdrcSweepState.value != WdrcSweepState.IDLE) return
        if (!ensureEngineRunning()) return
        wdrcSweepState.value    = WdrcSweepState.SETTLING
        wdrcCurrentStepIdx.value = 0

        // Route sweep signal through WDRC and Limiter DSP modules
        HarkAudioBridge.setInjectDspMode(true)

        wdrcJob = viewModelScope.launch {
            for (step in 0 until wdrcTotalSteps) {
                val levelDbfs = -60.0f + step * 5.0f   // -60, -55, ..., -5
                wdrcCurrentStepIdx.value  = step
                wdrcCurrentLevelDbfs.value = levelDbfs

                // Output calibration tone at this level
                HarkAudioBridge.setCalibTone(1000.0f, levelDbfs, true)

                // Wait for WDRC to settle (attack: 10ms, release: 600ms → 3×τ = 1800ms + buffer)
                val settleMs = wdrcSettleTimeMs.value.toLong()
                val tickMs   = 100L
                var elapsed  = 0L
                while (elapsed < settleMs) {
                    delay(tickMs)
                    elapsed += tickMs
                    wdrcElapsedMs.value = elapsed
                }
            }
            // Sweep complete — stop tone output and restore bypass mode
            HarkAudioBridge.setCalibTone(1000.0f, -60.0f, false)
            HarkAudioBridge.setInjectDspMode(false)
            wdrcSweepState.value = WdrcSweepState.DONE

            // Log session
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            logRepo.insertLog(ExperimentLog(
                timestamp = ts,
                testType  = "WDRC_IO",
                earphone  = selectedEarphone.value,
                dspBypass = "{}",
                params    = JSONObject().apply {
                    put("startDbfs",    -60)
                    put("endDbfs",      -5)
                    put("stepDb",       5)
                    put("steps",        wdrcTotalSteps)
                    put("settleMs",     wdrcSettleTimeMs.value)
                    put("toneFreqHz",   1000)
                }.toString()
            ))
        }
    }

    fun stopWdrcSweep() {
        wdrcJob?.cancel()
        HarkAudioBridge.setCalibTone(1000.0f, -60.0f, false)
        HarkAudioBridge.setInjectDspMode(false)
        wdrcSweepState.value = WdrcSweepState.IDLE
    }

    // =========================================================================
    // 5. Tone Burst
    // =========================================================================

    /**
     * Plays the configured Tone Burst sequence (1kHz).
     * Alternates between high-level and low-level according to burstHighMs / burstLowMs.
     * Repeats burstRepeatCount times.
     */
    fun startBurst() {
        if (burstRunning.value) return
        if (!ensureEngineRunning()) return
        burstRunning.value    = true
        burstCurrentRep.value = 0

        // Route burst signals through WDRC and Limiter DSP modules to evaluate dynamic response
        HarkAudioBridge.setInjectDspMode(true)

        burstJob = viewModelScope.launch {
            repeat(burstRepeatCount.value) { rep ->
                burstCurrentRep.value = rep + 1

                // High level phase
                burstPhase.value = BurstPhase.HIGH
                HarkAudioBridge.setCalibTone(1000.0f, burstHighDbfs.value, true)
                val highTick = 50L
                var highElapsed = 0L
                while (highElapsed < burstHighMs.value) {
                    delay(highTick)
                    highElapsed += highTick
                    burstPhaseRemMs.value = burstHighMs.value - highElapsed
                }

                // Low level phase
                burstPhase.value = BurstPhase.LOW
                HarkAudioBridge.setCalibTone(1000.0f, burstLowDbfs.value, true)
                val lowTick = 50L
                var lowElapsed = 0L
                while (lowElapsed < burstLowMs.value) {
                    delay(lowTick)
                    lowElapsed += lowTick
                    burstPhaseRemMs.value = burstLowMs.value - lowElapsed
                }
            }
            HarkAudioBridge.setCalibTone(1000.0f, -60.0f, false)
            HarkAudioBridge.setInjectDspMode(false)
            burstRunning.value = false
            burstPhase.value   = BurstPhase.IDLE

            // Log session
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            logRepo.insertLog(ExperimentLog(
                timestamp = ts,
                testType  = "TONE_BURST",
                earphone  = selectedEarphone.value,
                dspBypass = "{}",
                params    = JSONObject().apply {
                    put("toneFreqHz",  1000)
                    put("highDbfs",    burstHighDbfs.value)
                    put("lowDbfs",     burstLowDbfs.value)
                    put("highMs",      burstHighMs.value)
                    put("lowMs",       burstLowMs.value)
                    put("repeatCount", burstRepeatCount.value)
                }.toString()
            ))
        }
    }

    fun stopBurst() {
        burstJob?.cancel()
        HarkAudioBridge.setCalibTone(1000.0f, -60.0f, false)
        HarkAudioBridge.setInjectDspMode(false)
        burstRunning.value = false
        burstPhase.value   = BurstPhase.IDLE
    }

    // =========================================================================
    // 6. OSPL90 Log-Swept Chirp
    // =========================================================================

    /**
     * Starts the OSPL90 log-swept chirp (ANSI S3.22 swept pure tone).
     * The chirp sweeps from 250Hz to 8000Hz over ospl90DurationSec seconds.
     * The UI updates current frequency by interpolating elapsed time.
     * When sweep completes, C++ engine auto-stops; VM detects this and cleans up.
     */
    fun startOspl90() {
        if (ospl90State.value != Ospl90State.IDLE) return
        if (!ensureEngineRunning()) return
        ospl90State.value = Ospl90State.SWEEPING
        ospl90CurrentFreqHz.value = 250.0f

        val durationSec = ospl90DurationSec.value.toFloat()
        val levelDbfs   = ospl90LevelDbfs.value
        val usePink     = ospl90UsePinkNoise.value

        if (usePink) {
            HarkAudioBridge.setPinkNoise(levelDbfs, true)
        } else {
            HarkAudioBridge.setLogChirp(250.0f, 8000.0f, durationSec, levelDbfs, true)
        }

        ospl90Job = viewModelScope.launch {
            val startMs = System.currentTimeMillis()
            val totalMs = (durationSec * 1000).toLong()
            val f0 = 250.0f; val f1 = 8000.0f

            while (true) {
                delay(200)
                val elapsedMs = System.currentTimeMillis() - startMs
                if (elapsedMs >= totalMs) break

                if (!usePink) {
                    // Compute current instantaneous frequency for UI display
                    val t = elapsedMs / 1000.0
                    val K = durationSec / Math.log((f1 / f0).toDouble())
                    ospl90CurrentFreqHz.value = (f0 * Math.exp(t / K)).toFloat()
                        .coerceIn(f0, f1)
                }
            }
            // Sweep done
            HarkAudioBridge.setLogChirp(250.0f, 8000.0f, durationSec, levelDbfs, false)
            HarkAudioBridge.setPinkNoise(levelDbfs, false)
            ospl90State.value = Ospl90State.DONE

            // Log session
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            logRepo.insertLog(ExperimentLog(
                timestamp = ts,
                testType  = "OSPL90",
                earphone  = selectedEarphone.value,
                dspBypass = "{}",
                params    = JSONObject().apply {
                    put("signalType",    if (usePink) "PinkNoise" else "LogChirp")
                    put("startHz",       250)
                    put("endHz",         8000)
                    put("durationSec",   durationSec)
                    put("levelDbfs",     levelDbfs)
                }.toString()
            ))
        }
    }

    fun stopOspl90() {
        ospl90Job?.cancel()
        HarkAudioBridge.setLogChirp(250.0f, 8000.0f, 30.0f, -20.0f, false)
        HarkAudioBridge.setPinkNoise(-20.0f, false)
        ospl90State.value = Ospl90State.IDLE
    }

    // =========================================================================
    // File Share (FileProvider + Android Share Sheet)
    // =========================================================================

    /**
     * Returns the FileProvider URI for the last recorded WAV file.
     * Pass this to an Intent.ACTION_SEND intent to trigger the system Share Sheet.
     * Requires a FileProvider entry in AndroidManifest.xml (authority: com.wcy.hark.fileprovider).
     */
    fun getShareUri(file: File, context: Context) =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    // =========================================================================
    // Cleanup
    // =========================================================================

    override fun onCleared() {
        super.onCleared()
        // Stop all experiment signals when ViewModel is destroyed.
        // Also restore inject-DSP mode: WDRC sweep / tone burst enable it and a
        // mid-run teardown would otherwise leave the engine injecting test
        // signals into the DSP chain permanently.
        HarkAudioBridge.setCalibTone(0f, 0f, false)
        HarkAudioBridge.setLogChirp(250f, 8000f, 30f, 0f, false)
        HarkAudioBridge.setPinkNoise(0f, false)
        HarkAudioBridge.setInjectDspMode(false)
    }

    // =========================================================================
    // WAV File Writing Utility
    // =========================================================================

    /**
     * Writes a PCM ShortArray as a standard WAV file (16-bit mono).
     * Ref: Multimedia Programming Interface and Data Spec. v1.0 (IBM/Microsoft 1991)
     */
    private suspend fun writeWav(
        file: File, pcm: ShortArray, sampleRate: Int, channels: Int
    ) = withContext(Dispatchers.IO) {
        val byteRate   = sampleRate * channels * 2   // 16-bit = 2 bytes
        val dataSize   = pcm.size * 2
        val headerSize = 44

        fun Int.le4(): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(this).array()
        fun Short.le2(): ByteArray = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(this).array()

        FileOutputStream(file).use { fos ->
            // RIFF header
            fos.write("RIFF".toByteArray())
            fos.write((headerSize - 8 + dataSize).le4())
            fos.write("WAVE".toByteArray())
            // fmt chunk
            fos.write("fmt ".toByteArray())
            fos.write(16.le4())                       // chunk size
            fos.write(1.toShort().le2())              // PCM format
            fos.write(channels.toShort().le2())
            fos.write(sampleRate.le4())
            fos.write(byteRate.le4())
            fos.write((channels * 2).toShort().le2()) // block align
            fos.write(16.toShort().le2())             // bits per sample
            // data chunk
            fos.write("data".toByteArray())
            fos.write(dataSize.le4())
            // PCM samples (little-endian)
            val buf = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (s in pcm) buf.putShort(s)
            fos.write(buf.array())
        }
    }
}

// =============================================================================
// Factory
// =============================================================================

class ExperimentViewModelFactory(
    private val context: Context,
    private val calibRepo: EarphoneCalibrationRepository,
    private val logRepo: ExperimentLogRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ExperimentViewModel::class.java))
            return ExperimentViewModel(context, calibRepo, logRepo) as T
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
