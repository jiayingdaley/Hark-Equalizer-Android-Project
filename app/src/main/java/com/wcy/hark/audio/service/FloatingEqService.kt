package com.wcy.hark.audio.service

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.wcy.hark.HarkApplication
import com.wcy.hark.ui.components.EqualizerCurveDisplay
import com.wcy.hark.ui.viewmodel.EqViewModel
import com.wcy.hark.ui.viewmodel.EqViewModelFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class FloatingEqService : Service(), LifecycleOwner, SavedStateRegistryOwner {
    
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    
    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var viewModel: EqViewModel
    private val isExperimentMode = mutableStateOf(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        // Initialize ViewModel to share state
        val repository = (application as HarkApplication).eqSettingsRepository
        // 與 MainActivity 共用同一個 EqViewModel 實例：懸浮窗與 app 內等化器
        // 讀寫同一份 MutableState，參數即時同步。
        viewModel = (application as HarkApplication).sharedEqViewModel
        // 段數規則與主 EQ 畫面一致：使用者模式固定 8 段、實驗模式可切 8/16
        lifecycleScope.launch { isExperimentMode.value = repository.getExperimentModeFlow().first() }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 100
        }

        floatingView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingEqService)
            setViewTreeSavedStateRegistryOwner(this@FloatingEqService)
            setViewTreeViewModelStoreOwner(object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            })
            setContent {
                MaterialTheme {
                    FloatingEqContent(
                        viewModel = viewModel,
                        isExperimentMode = isExperimentMode.value,
                        onClose = { stopSelf() },
                        onDrag = { dx, dy ->
                            params.x += dx.toInt()
                            params.y += dy.toInt()

                            val metrics = resources.displayMetrics
                            val maxX = metrics.widthPixels - floatingView.width
                            val maxY = metrics.heightPixels - floatingView.height

                            params.x = params.x.coerceIn(0, maxX.coerceAtLeast(0))
                            params.y = params.y.coerceIn(0, maxY.coerceAtLeast(0))

                            windowManager.updateViewLayout(floatingView, params)
                        }
                    )
                }
            }
        }

        windowManager.addView(floatingView, params)
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
    }
}

@Composable
fun FloatingEqContent(
    viewModel: EqViewModel,
    isExperimentMode: Boolean,
    onClose: () -> Unit,
    onDrag: (Float, Float) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    // 使用者模式固定 8 段（對齊聽力檢查頻率）；實驗模式可切 16 段完整檢視
    var use16 by remember { mutableStateOf(false) }
    // 拖曳中的「頻段 + dB」標籤：顯示於提示列右側（固定位置、不被手指遮擋）
    var dragLabel by remember { mutableStateOf<String?>(null) }

    if (!expanded) {
        // Collapsed state: just a floating button
        FloatingActionButton(
            onClick = { expanded = true },
            modifier = Modifier
                .size(48.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    }
                },
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
        ) {
            Icon(Icons.Default.GraphicEq, contentDescription = "Open EQ", tint = Color.White, modifier = Modifier.size(24.dp))
        }
    } else {
        // Expanded state: show mini EQ
        Surface(
            modifier = Modifier
                .width(320.dp)
                .height(300.dp) // Fixed height to prevent massive layout shifts in floating window
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    }
                },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            tonalElevation = 8.dp,
            shadowElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("影音聽力補償", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = viewModel.isSystemDspOn.value,
                            onCheckedChange = { viewModel.setSystemDspEnabled(it) },
                            modifier = Modifier.padding(end = 4.dp).scale(0.8f)
                        )
                        IconButton(onClick = { expanded = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "雙耳連動上下調整",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        dragLabel?.let {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                it,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                        }
                    }
                    if (isExperimentMode) {
                        TextButton(onClick = { use16 = !use16 }) {
                            Text(if (use16) "16 段" else "8 段", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                val show16 = isExperimentMode && use16
                // 曲線（可拖曳）＝雙耳平均；拖曳為雙耳連動。
                Box(modifier = Modifier.weight(1f)) {
                    if (show16) {
                        val avg16 = (0 until 16).map { i ->
                            remember("a16-" + i) {
                                derivedStateOf {
                                    (viewModel.bandGainsLeft16[i].value + viewModel.bandGainsRight16[i].value) / 2f
                                }
                            }
                        }
                        EqualizerCurveDisplay(
                            modifier = Modifier.fillMaxSize(),
                            bandGains = avg16,
                            centerFrequencies = viewModel.centerFrequencies16,
                            onDragBand = { index, gain -> viewModel.updateBandGain(index, gain) },
                            onDragLabel = { dragLabel = it }
                        )
                    } else {
                        // 8 段檢視：底層仍是 16 段，一支 slider 驅動其管轄子頻段
                        val avg8 = (0 until 8).map { i ->
                            remember("a8-" + i) {
                                derivedStateOf {
                                    (viewModel.band8Gain(viewModel.bandGainsLeft16, i) +
                                     viewModel.band8Gain(viewModel.bandGainsRight16, i)) / 2f
                                }
                            }
                        }
                        EqualizerCurveDisplay(
                            modifier = Modifier.fillMaxSize(),
                            bandGains = avg8,
                            centerFrequencies = viewModel.centerFrequencies8,
                            onDragBand = { index, gain ->
                                viewModel.updateBand8Gain(
                                    com.wcy.hark.ui.viewmodel.EarType.BOTH, index, gain
                                )
                            },
                            onDragLabel = { dragLabel = it }
                        )
                    }
                }
            }
        }
    }
}
