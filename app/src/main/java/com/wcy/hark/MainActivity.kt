package com.wcy.hark

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.wcy.hark.audio.bridge.HarkAudioBridge
import com.wcy.hark.audio.router.HarkAudioRouter
import com.wcy.hark.audio.router.VolumeSyncHelper
import com.wcy.hark.audio.service.HarkAudioService
import com.wcy.hark.audio.service.FloatingEqService
import com.wcy.hark.audio.manager.SystemDspManager
import com.wcy.hark.ui.viewmodel.EqViewModel
import com.wcy.hark.ui.viewmodel.EqViewModelFactory
import com.wcy.hark.ui.viewmodel.AudioSourceMode
import com.wcy.hark.ui.screen.HarkMainScreen
import com.wcy.hark.ui.screen.HarkEqualizerScreen
import com.wcy.hark.ui.screen.HarkAppScreen
import com.wcy.hark.ui.theme.HarkTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch


/**
 * MainActivity – Entry point and lifecycle owner of the Hark hearing-aid app.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: EqViewModel by viewModels {
        EqViewModelFactory((application as HarkApplication).eqSettingsRepository)
    }

    private lateinit var audioManager: AudioManager
    private lateinit var audioRouter: HarkAudioRouter
    private lateinit var volumeSyncHelper: VolumeSyncHelper
    
    // User's *intent*: does the user want the engine running?
    private var isEngineRunningByUserIntent by mutableStateOf(false)

    // ── Screen Navigation State Machine ──────────────────────────────────────
    // "main"      → HarkMainScreen (PSAP 主控制面板, default)
    // "equalizer" → HarkEqualizerScreen (EQUALIZER 等化器微調)
    // "debug"     → HarkAppScreen (實驗調試面板, original UI)
    private var currentScreen by mutableStateOf("main")

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Load the native "hark" library before any HarkAudioBridge calls.
        System.loadLibrary("hark")

        // Initialize decoupled helper components
        audioRouter = HarkAudioRouter(
            context = this,
            scope = lifecycleScope,
            useHeadsetMicProvider = { viewModel.useHeadsetMic.value },
            onRoutingChanged = { isHeadphoneConnected, deviceLabel ->
                controlAudioEngineService(isHeadphoneConnected, deviceLabel)
            },
            onScoStateChanged = { statusText ->
                viewModel.statusText.value = statusText
            }
        )

        volumeSyncHelper = VolumeSyncHelper(this)

        // Restore last hearing-aid enabled state from DataStore (KNOWN-ISSUE-006 fix).
        // This runs asynchronously so the UI may momentarily show "已停用" before updating.
        lifecycleScope.launch {
            val savedEnabled = (application as HarkApplication).eqSettingsRepository
                .getHearingAidEnabledFlow().first()
            if (savedEnabled && !isEngineRunningByUserIntent) {
                isEngineRunningByUserIntent = true
                viewModel.statusText.value = "狀態：正在偵測音訊裝置..."
                audioRouter.triggerRecheck()
            }
        }

        // Request RECORD_AUDIO and notification permissions atomically on startup
        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
            viewModel.isMicrophonePermissionGranted.value = audioGranted
            if (audioGranted) {
                Log.d(TAG, "RECORD_AUDIO permission granted atomically.")
                lifecycleScope.launch { audioRouter.triggerRecheck() }
            } else {
                Log.w(TAG, "RECORD_AUDIO permission denied.")
                viewModel.statusText.value = "狀態：麥克風權限被拒絕"
            }
        }

        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            viewModel.isMicrophonePermissionGranted.value = true
        }

        // Observe SystemDspManager enabled flow to reactively update overlay window and DSP state
        lifecycleScope.launch {
            SystemDspManager.isEnabledFlow.collect { enabled ->
                viewModel.isSystemDspOn.value = enabled
                audioRouter.triggerRecheck()
            }
        }

        setContent {
            HarkTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    when (currentScreen) {
                        "equalizer" -> {
                            // Screen 2: EQUALIZER — 等化器微調
                            HarkEqualizerScreen(
                                viewModel = viewModel,
                                onBack = { currentScreen = "main" }
                            )
                        }
                        "debug" -> {
                            // Debug Screen: 實驗調試面板 (original HarkAppScreen, untouched)
                            HarkAppScreen(
                                viewModel = viewModel,
                                audioManager = audioManager,
                                isEngineOn = isEngineRunningByUserIntent,
                                onEngineStateChange = { userWantsOn ->
                                    isEngineRunningByUserIntent = userWantsOn
                                    // Persist the state so the App restores it after being killed
                                    lifecycleScope.launch {
                                        (application as HarkApplication).eqSettingsRepository
                                            .saveHearingAidEnabled(userWantsOn)
                                    }
                                    viewModel.statusText.value = if (userWantsOn)
                                        "狀態：正在偵測音訊裝置..." else "狀態：已停用"
                                    lifecycleScope.launch { audioRouter.triggerRecheck() }
                                },
                                onSourceModeChanged = handleSourceModeChanged(),
                                onSetBandGain = { index, gain ->
                                    HarkAudioBridge.setBandGain(0, index, gain)
                                    HarkAudioBridge.setBandGain(1, index, gain)
                                    SystemDspManager.updateBandGain(0, index, gain)
                                    SystemDspManager.updateBandGain(1, index, gain)
                                },
                                onSetBandQ = HarkAudioBridge::setBandQ,
                                onBack = { currentScreen = "main" }
                            )
                        }
                        else -> {
                            // Screen 1: PSAP — HarkMainScreen (default)
                            HarkMainScreen(
                                viewModel = viewModel,
                                audioManager = audioManager,
                                isEngineOn = isEngineRunningByUserIntent,
                                onEngineStateChange = { userWantsOn ->
                                    isEngineRunningByUserIntent = userWantsOn
                                    // Persist the state so the App restores it after being killed
                                    lifecycleScope.launch {
                                        (application as HarkApplication).eqSettingsRepository
                                            .saveHearingAidEnabled(userWantsOn)
                                    }
                                    viewModel.statusText.value = if (userWantsOn)
                                        "狀態：正在偵測音訊裝置..." else "狀態：已停用"
                                    lifecycleScope.launch { audioRouter.triggerRecheck() }
                                },
                                onSourceModeChanged = handleSourceModeChanged(),
                                onNavigateToEq = { currentScreen = "equalizer" },
                                onNavigateToDebug = { currentScreen = "debug" }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun handleSourceModeChanged(): (AudioSourceMode) -> Unit = { newMode ->
        if (newMode == AudioSourceMode.INTERNAL_MEDIA) {
            if (!android.provider.Settings.canDrawOverlays(this@MainActivity)) {
                val intent = Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivity(intent)
                viewModel.currentSourceMode.value = AudioSourceMode.MICROPHONE
            } else {
                viewModel.statusText.value = "狀態：手機影音 DSP 啟動中"
                viewModel.setSystemDspEnabled(true) // 自動啟用 System DSP 與懸浮球
                lifecycleScope.launch { audioRouter.triggerRecheck() }
            }
        } else {
            viewModel.setSystemDspEnabled(false) // 安全關閉 System DSP
            lifecycleScope.launch { audioRouter.triggerRecheck() }
        }
    }

    override fun onResume() {

        super.onResume()
        
        val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        viewModel.isMicrophonePermissionGranted.value = hasPermission
        
        audioRouter.start()
        volumeSyncHelper.start()

        if (isEngineRunningByUserIntent) {
            Log.d(TAG, "onResume: user intent ON, re-checking audio device.")
            lifecycleScope.launch { audioRouter.triggerRecheck() }
        } else {
            viewModel.statusText.value = "狀態：已停用"
        }
    }

    override fun onPause() {
        super.onPause()
        audioRouter.stop()
        volumeSyncHelper.stop()

        viewModel.statusText.value = if (isEngineRunningByUserIntent)
            "狀態：已切換至背景" else "狀態：已停用"
    }

    private fun controlAudioEngineService(isHeadphoneConnected: Boolean, deviceLabel: String) {
        updateAudioStates(isHeadphoneConnected, deviceLabel)
    }

    private fun updateAudioStates(isHeadphoneConnected: Boolean, deviceLabel: String) {
        val sourceMode = viewModel.currentSourceMode.value

        if (sourceMode == AudioSourceMode.MICROPHONE) {
            // Stop system DSP since we are in Mic mode
            SystemDspManager.setEnabled(false)
            SystemDspManager.clearAllEffects()
            stopService(Intent(this, FloatingEqService::class.java))

            // 1. Oboe Environment Hearing Aid (Microphone mode)
            if (isEngineRunningByUserIntent && isHeadphoneConnected) {
                val serviceIntent = Intent(this, HarkAudioService::class.java).apply {
                    action = HarkAudioService.ACTION_START
                }
                ContextCompat.startForegroundService(this, serviceIntent)

                lifecycleScope.launch {
                    viewModel.statusText.value = "狀態：正在啟動..."
                    kotlinx.coroutines.delay(1000)
                    updateEngineStatus(deviceLabel)
                }
            } else {
                val serviceIntent = Intent(this, HarkAudioService::class.java).apply {
                    action = HarkAudioService.ACTION_STOP
                }
                startService(serviceIntent)

                viewModel.statusText.value = if (isEngineRunningByUserIntent && !isHeadphoneConnected) {
                    "狀態：請連接耳機"
                } else {
                    "狀態：已停用"
                }
            }
        } else {
            // Stop environmental hearing aid service since we are in Media mode
            val serviceIntent = Intent(this, HarkAudioService::class.java).apply {
                action = HarkAudioService.ACTION_STOP
            }
            startService(serviceIntent)

            // 2. System-wide Media Hearing Aid (Internal Media mode)
            val shouldSystemDspBeOn = isEngineRunningByUserIntent && isHeadphoneConnected && viewModel.isSystemDspOn.value

            SystemDspManager.setEnabled(shouldSystemDspBeOn)
            if (shouldSystemDspBeOn) {
                SystemDspManager.attachToSession(0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && android.provider.Settings.canDrawOverlays(this)) {
                    startService(Intent(this, FloatingEqService::class.java))
                }
            } else {
                SystemDspManager.clearAllEffects()
                stopService(Intent(this, FloatingEqService::class.java))
            }

            // Update status text for media mode
            viewModel.statusText.value = if (isEngineRunningByUserIntent) {
                if (!isHeadphoneConnected) {
                    "狀態：請連接耳機"
                } else {
                    if (viewModel.isSystemDspOn.value) "狀態：手機影音 DSP 已啟用" else "狀態：手機影音 DSP 已暫停"
                }
            } else {
                "狀態：已停用"
            }
        }
    }

    private fun updateEngineStatus(deviceLabel: String = "") {
        viewModel.statusText.value = if (HarkAudioBridge.isEngineActuallyRunning()) {
            "狀態：運作中${if (deviceLabel.isNotEmpty()) " ($deviceLabel)" else ""}"
        } else {
            Log.e(TAG, "Engine failed to start. Device: $deviceLabel")
            "狀態：引擎啟動失敗"
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}