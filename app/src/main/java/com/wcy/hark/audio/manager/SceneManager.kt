package com.wcy.hark.audio.manager

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.log10
import com.wcy.hark.audio.bridge.HarkAudioBridge
import com.wcy.hark.audio.router.MediaSessionObserver

/**
 * SceneManager: The intelligent hub for situational mode switching.
 * 
 * Responsibilities:
 * 1. Periodic environmental analysis (using DSP spectral data).
 * 2. Media session monitoring (via MediaSessionObserver).
 * 3. Handling Manual Override (lock mode).
 * 4. Cross-fading between modes.
 */
class SceneManager(private val context: Context) {

    enum class Mode(val id: Int) {
        TRANSPARENCY(0),
        CONVERSATION(1),
        OUTDOOR(2),
        CINEMA(3),
        AUTO(4)
    }

    private val _currentMode = MutableStateFlow(Mode.TRANSPARENCY)
    val currentMode: StateFlow<Mode> = _currentMode

    private val _isAutoLocked = MutableStateFlow(false)
    val isAutoLocked: StateFlow<Boolean> = _isAutoLocked

    private var autoJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private val mediaObserver = MediaSessionObserver(context) { isPlaying ->
        if (!isAutoLocked.value) {
            if (isPlaying) {
                applyMode(Mode.CINEMA)
            } else {
                // Return to auto-analysis if music stops
                runAutoAnalysis()
            }
        }
    }

    fun start() {
        mediaObserver.start()
        startAutoAnalysis()
    }

    fun stop() {
        mediaObserver.stop()
        autoJob?.cancel()
        scope.cancel()
    }

    /**
     * User manually selects a mode. This "locks" the auto-detection.
     */
    fun selectModeManual(mode: Mode) {
        if (mode == Mode.AUTO) {
            _isAutoLocked.value = false
            startAutoAnalysis()
        } else {
            _isAutoLocked.value = true
            autoJob?.cancel()
            applyMode(mode)
        }
    }

    private fun startAutoAnalysis() {
        autoJob?.cancel()
        autoJob = scope.launch {
            while (isActive) {
                if (!_isAutoLocked.value) {
                    runAutoAnalysis()
                }
                delay(5000) // Re-evaluate every 5 seconds
            }
        }
    }

    private fun runAutoAnalysis() {
        val energy = HarkAudioBridge.getEnvironmentEnergy()
        if (energy.size < 5) return

        // Basic classification logic
        // Band 0: 500Hz, Band 1: 1kHz, Band 2: 2kHz, Band 3: 3kHz, Band 4: 4kHz
        val lowFreq = energy[0] + energy[1]
        val highFreq = energy[3] + energy[4]
        val total = energy.sum()

        val detectedMode = when {
            lowFreq > total * 0.7f -> Mode.OUTDOOR      // Heavy low-freq (wind/traffic)
            highFreq > total * 0.4f -> Mode.CONVERSATION // High-freq present (speech clarity)
            total < 0.001f -> Mode.TRANSPARENCY         // Very quiet
            else -> Mode.TRANSPARENCY
        }

        if (detectedMode != currentMode.value) {
            applyMode(detectedMode)
        }
    }

    private fun applyMode(mode: Mode) {
        _currentMode.value = mode
        HarkAudioBridge.setSituationalMode(mode.id)
    }
}
