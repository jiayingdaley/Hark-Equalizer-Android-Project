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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.wcy.hark.audio.bridge.HarkAudioBridge
import com.wcy.hark.audio.manager.SystemDspManager
import com.wcy.hark.audio.router.HarkAudioRouter
import com.wcy.hark.audio.router.VolumeSyncHelper
import com.wcy.hark.audio.service.FloatingEqService
import com.wcy.hark.audio.service.HarkAudioService
import com.wcy.hark.data.experiment.EarphoneCalibrationRepository
import com.wcy.hark.data.experiment.ExperimentLogRepository
import com.wcy.hark.ui.screen.CalibrationTestScreen
import com.wcy.hark.ui.screen.EarphoneCalibrationScreen
import com.wcy.hark.ui.screen.HarkAppScreen
import com.wcy.hark.ui.screen.HarkEqualizerScreen
import com.wcy.hark.ui.screen.HarkMainScreen
import com.wcy.hark.ui.theme.HarkTheme
import com.wcy.hark.ui.viewmodel.AudioSourceMode
import com.wcy.hark.ui.viewmodel.EqViewModel
import com.wcy.hark.ui.viewmodel.EqViewModelFactory
import com.wcy.hark.ui.viewmodel.ExperimentViewModel
import com.wcy.hark.ui.viewmodel.ExperimentViewModelFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch


/**
 * MainActivity – Entry point and lifecycle owner of the Hark hearing-aid app.
 */
class MainActivity : ComponentActivity() {

    // 共用 Application 層的 EqViewModel（與懸浮等化器同一實例，狀態即時互通）
    private val viewModel: EqViewModel by lazy {
        (application as HarkApplication).sharedEqViewModel
    }

    private lateinit var audioManager: AudioManager
    private lateinit var audioRouter: HarkAudioRouter
    private lateinit var volumeSyncHelper: VolumeSyncHelper
    
    // User's *intent*: does the user want the engine running?
    private var isEngineRunningByUserIntent by mutableStateOf(false)
    private var engineStartTimeMs = 0L

    // ── Screen Navigation State Machine ──────────────────────────────────────
    // "main"           → HarkMainScreen (主控制面板, default)
    // "equalizer"      → HarkEqualizerScreen (EQUALIZER 等化器微調)
    // "debug"          → HarkAppScreen (實驗調試面板, original UI)
    // "experiment"     → CalibrationTestScreen (聲學實驗面板, experiment mode only)
    // "earphone_calib" → EarphoneCalibrationScreen (逐頻率耳機校準, experiment mode only)
    private var currentScreen by mutableStateOf("main")

    // App-level mode: false = 使用者模式, true = 實驗模式 (persisted in DataStore)
    private var isExperimentMode by mutableStateOf(false)

    private val experimentViewModel: ExperimentViewModel by viewModels {
        ExperimentViewModelFactory(
            applicationContext,
            EarphoneCalibrationRepository(applicationContext),
            ExperimentLogRepository(applicationContext)
        )
    }

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

        // Observe app mode (User / Experiment) so toggles propagate from anywhere
        lifecycleScope.launch {
            (application as HarkApplication).eqSettingsRepository
                .getExperimentModeFlow().collect { enabled ->
                    isExperimentMode = enabled
                    // Leaving experiment mode while on an experiment screen → go home
                    if (!enabled && (currentScreen == "experiment" || currentScreen == "earphone_calib")) {
                        currentScreen = "main"
                    }
                }
        }

        // Observe SystemDspManager enabled flow to reactively update overlay window and DSP state
        lifecycleScope.launch {
            SystemDspManager.isEnabledFlow.collect { enabled ->
                viewModel.isSystemDspOn.value = enabled
                audioRouter.triggerRecheck()
            }
        }

        setContent {
            // 使用者模式固定亮色主題（亮背景 + 深色文字，與聽力檢測介面一致）；
            // 實驗模式跟隨系統
            HarkTheme(darkTheme = if (isExperimentMode) androidx.compose.foundation.isSystemInDarkTheme() else false) {
                Box(modifier = Modifier.fillMaxSize()) {
                    when (currentScreen) {
                        "equalizer" -> {
                            // Screen 2: EQUALIZER — 等化器微調
                            HarkEqualizerScreen(
                                viewModel = viewModel,
                                isExperimentMode = isExperimentMode,
                                onBack = { currentScreen = "main" }
                            )
                        }
                        "experiment" -> {
                            // 聲學實驗面板 (top-level in experiment mode)
                            CalibrationTestScreen(
                                viewModel = experimentViewModel,
                                onBack = { currentScreen = "main" },
                                onOpenEarphoneCalib = { currentScreen = "earphone_calib" }
                            )
                        }
                        "earphone_calib" -> {
                            // 逐頻率耳機校準畫面
                            EarphoneCalibrationScreen(
                                viewModel = experimentViewModel,
                                repository = (application as HarkApplication).eqSettingsRepository,
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
                                onNavigateToDebug = { currentScreen = "debug" },
                                isExperimentMode = isExperimentMode,
                                onToggleExperimentMode = { enabled ->
                                    lifecycleScope.launch {
                                        (application as HarkApplication).eqSettingsRepository
                                            .saveExperimentMode(enabled)
                                    }
                                },
                                onNavigateToExperiment = { currentScreen = "experiment" },
                                onNavigateToEarphoneCalib = { currentScreen = "earphone_calib" }
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
        // Telemetry & Analytics logs
        com.wcy.hark.util.CrashlyticsMonitor.setAudioRoute(deviceLabel)
        com.wcy.hark.util.CrashlyticsMonitor.setDspStatus(
            nsEnabled = viewModel.testNoiseReductionEnabled.value,
            wdrcEnabled = viewModel.testCrossoverWdrcEnabled.value,
            nlfcEnabled = viewModel.testFrequencyLoweringEnabled.value
        )
        if (isHeadphoneConnected) {
            com.wcy.hark.util.FirebaseHelper.logHeadphoneConnection(deviceLabel)
        }
        com.wcy.hark.util.FirebaseHelper.logAudioSourceSelect(viewModel.currentSourceMode.value.name)

        viewModel.isHeadphoneConnected.value = isHeadphoneConnected
        val sourceMode = viewModel.currentSourceMode.value

        if (sourceMode == AudioSourceMode.MICROPHONE) {
            // Stop system DSP since we are in Mic mode
            SystemDspManager.setEnabled(false)
            SystemDspManager.clearAllEffects()
            stopService(Intent(this, FloatingEqService::class.java))

            // 1. Oboe Environment Hearing Aid (Microphone mode)
            if (isEngineRunningByUserIntent && isHeadphoneConnected) {
                if (engineStartTimeMs == 0L) {
                    engineStartTimeMs = System.currentTimeMillis()
                }
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
                if (engineStartTimeMs > 0L) {
                    val durationSec = (System.currentTimeMillis() - engineStartTimeMs) / 1000L
                    com.wcy.hark.util.FirebaseHelper.logSessionDuration(durationSec)
                    engineStartTimeMs = 0L
                }
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
            // 不要求耳機：媒體路徑處理的是播放音訊、沒有麥克風回授問題，
            // 使用者可用手機喇叭外放聽經補償的影音。
            val shouldSystemDspBeOn = isEngineRunningByUserIntent && viewModel.isSystemDspOn.value

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

            // Update status text for media mode（媒體路徑無回授問題，無耳機時以喇叭外放）
            viewModel.statusText.value = if (isEngineRunningByUserIntent) {
                when {
                    !viewModel.isSystemDspOn.value -> "狀態：影音聽力補償已暫停"
                    !isHeadphoneConnected -> "狀態：影音聽力補償已啟用（喇叭外放）"
                    else -> "狀態：影音聽力補償已啟用"
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