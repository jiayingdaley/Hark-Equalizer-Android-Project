package com.wcy.hark.ui.screen

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wcy.hark.audio.manager.SceneManager
import com.wcy.hark.audiometry.TestSelectActivity
import com.wcy.hark.ui.components.SystemVolumeSlider
import com.wcy.hark.ui.viewmodel.AudioSourceMode
import com.wcy.hark.ui.viewmodel.EqViewModel

/**
 * HarkMainScreen — 畫面 1 (PSAP 主控制面板)
 *
 * 採用清晰、大字體、高對比的卡片式版面，符合 PureToneEqualizer 的設計風格。
 * 包含三層動態級聯顯示/隱藏邏輯：
 *   Level 1: 助聽總開關（控制所有功能的可見性）
 *   Level 2: 音源選擇 Tab（環境助聽 vs 手機影音）
 *   Level 3: 自動切換模式（停用手動 Preset，顯示呼吸燈動畫）
 *
 * Navigation:
 *   - 「聽力檢測」→ startActivity(TestSelectActivity)
 *   - 「等化器微調」→ onNavigateToEq()
 *   - 「實驗調試面板」→ onNavigateToDebug()
 *
 * Ref: Android ComponentActivity.startActivity pattern
 * Ref: Compose AnimatedVisibility — developer.android.com/jetpack/compose/animation/composables-modifiers
 * Ref: Compose infiniteTransition — developer.android.com/jetpack/compose/animation/value-based
 */
@Composable
fun HarkMainScreen(
    viewModel: EqViewModel,
    audioManager: AudioManager?,
    isEngineOn: Boolean,
    onEngineStateChange: (Boolean) -> Unit,
    onSourceModeChanged: (AudioSourceMode) -> Unit,
    onNavigateToEq: () -> Unit,
    onNavigateToDebug: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // ── State reads from ViewModel ─────────────────────────────────────────
    val situationalMode by viewModel.situationalMode
    val isAutoLocked    by viewModel.isAutoLocked
    val sourceMode       = viewModel.currentSourceMode.value
    val statusText      by viewModel.statusText

    // Background gradient for premium look
    val bgGradient = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(brush = bgGradient)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── App Title ──────────────────────────────────────────────────
            Text(
                text = "Hark",
                style = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary
                )
            )
            Text(
                text = "智慧助聽器",
                style = MaterialTheme.typography.titleMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                modifier = Modifier.offset(y = (-8).dp)
            )

            // ────────────────────────────────────────────────────────────────
            // LEVEL 1: 助聽總開關 (Power Card)
            // ────────────────────────────────────────────────────────────────
            PowerCard(
                isOn = isEngineOn,
                statusText = statusText,
                onToggle = { newState ->
                    onEngineStateChange(newState)
                }
            )

            // ─── OFF State Hint ────────────────────────────────────────────
            AnimatedVisibility(
                visible = !isEngineOn,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = "環境助聽已暫停，請開啟上方開關開始使用",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 22.sp
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    )
                }
            }

            // ─────────────────────────────────────────────────────────────────
            // LEVEL 2: 音源 Tab + 功能區（僅在 ON 時顯示）
            // ─────────────────────────────────────────────────────────────────
            AnimatedVisibility(
                visible = isEngineOn,
                enter = fadeIn() + expandVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

                    // Source Mode Tab
                    val tabs = listOf("環境助聽（麥克風）", "手機影音（內部音訊）")
                    val selectedTabIndex = if (sourceMode == AudioSourceMode.MICROPHONE) 0 else 1

                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        TabRow(
                            selectedTabIndex = selectedTabIndex,
                            containerColor = Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.primary
                        ) {
                            tabs.forEachIndexed { index, title ->
                                Tab(
                                    selected = selectedTabIndex == index,
                                    onClick = {
                                        val newMode = if (index == 0) AudioSourceMode.MICROPHONE else AudioSourceMode.INTERNAL_MEDIA
                                        if (sourceMode != newMode) {
                                            viewModel.currentSourceMode.value = newMode
                                            onSourceModeChanged(newMode)
                                        }
                                    },
                                    text = {
                                        Text(
                                            title,
                                            style = MaterialTheme.typography.labelLarge.copy(
                                                fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Normal
                                            )
                                        )
                                    }
                                )
                            }
                        }
                    }

                    // ── Microphone Mode: Mic Source + Presets ──────────────
                    AnimatedVisibility(
                        visible = sourceMode == AudioSourceMode.MICROPHONE,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

                            // Mic Source Selector Card
                            MicSourceCard(viewModel = viewModel, onEngineStateChange = onEngineStateChange, isEngineOn = isEngineOn)

                            // Environment Presets Card (Level 3 inside)
                            PresetsCard(
                                situationalMode = situationalMode,
                                isAutoLocked = isAutoLocked,
                                onModeSelect = { mode -> viewModel.selectSituationalMode(mode) },
                                onAutoToggle = { enable ->
                                    if (enable) viewModel.selectSituationalMode(SceneManager.Mode.AUTO)
                                    else viewModel.selectSituationalMode(SceneManager.Mode.TRANSPARENCY)
                                }
                            )
                        }
                    }

                    // ── Internal Media Mode Banner ─────────────────────────
                    AnimatedVisibility(
                        visible = sourceMode == AudioSourceMode.INTERNAL_MEDIA,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                                Text(
                                    text = "正在處理手機影音音訊，麥克風已靜音",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        fontWeight = FontWeight.Medium
                                    )
                                )
                            }
                        }
                    }

                    // ── Volume & Balance (always visible when engine ON) ───
                    VolumeBalanceCard(audioManager = audioManager)

                    // ── Navigate to Equalizer ──────────────────────────────
                    LargeNavCard(
                        label = "等化器微調",
                        sublabel = "EQUALIZER",
                        icon = Icons.Default.Tune,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        onClick = onNavigateToEq
                    )
                }
            }

            // ─────────────────────────────────────────────────────────────────
            // Bottom Nav Cards (always visible regardless of power state)
            // ─────────────────────────────────────────────────────────────────
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            LargeNavCard(
                label = "聽力檢測",
                sublabel = "HEARING TEST",
                icon = Icons.Default.HearingDisabled,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                onClick = {
                    context.startActivity(Intent(context, TestSelectActivity::class.java))
                }
            )

            LargeNavCard(
                label = "原 Hark 測試介面",
                sublabel = "HARK TEST PANEL",
                icon = Icons.Default.BugReport,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                onClick = onNavigateToDebug
            )

            // Bottom padding for scroll
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ─── Sub-Components ──────────────────────────────────────────────────────────

/**
 * PowerCard — 助聽總開關大卡片
 * High-contrast card with a large Switch to match PureToneEqualizer style.
 */
@Composable
private fun PowerCard(
    isOn: Boolean,
    statusText: String,
    onToggle: (Boolean) -> Unit
) {
    // Animated card background color
    val cardColor by animateColorAsState(
        targetValue = if (isOn) MaterialTheme.colorScheme.primaryContainer
                      else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(durationMillis = 400),
        label = "powerCardColor"
    )
    val textColor = if (isOn) MaterialTheme.colorScheme.onPrimaryContainer
                   else MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isOn) "助聽已啟用" else "助聽已暫停",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = textColor
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall.copy(color = textColor.copy(alpha = 0.75f))
                )
            }
            Switch(
                checked = isOn,
                onCheckedChange = onToggle,
                modifier = Modifier.scale(1.3f),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    }
}

/**
 * MicSourceCard — 收音來源選擇卡片（耳機麥 / 手機麥）
 */
@Composable
private fun MicSourceCard(
    viewModel: EqViewModel,
    onEngineStateChange: (Boolean) -> Unit,
    isEngineOn: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "收音來源",
                style = MaterialTheme.typography.labelLarge.copy(
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MicSourceButton(
                    label = "耳機麥克風",
                    icon = Icons.Default.HeadsetMic,
                    isSelected = viewModel.useHeadsetMic.value,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (!viewModel.useHeadsetMic.value) {
                            viewModel.toggleHeadsetMic(true)
                            onEngineStateChange(isEngineOn)
                        }
                    }
                )
                MicSourceButton(
                    label = "手機麥克風",
                    icon = Icons.Default.Smartphone,
                    isSelected = !viewModel.useHeadsetMic.value,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (viewModel.useHeadsetMic.value) {
                            viewModel.toggleHeadsetMic(false)
                            onEngineStateChange(isEngineOn)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun MicSourceButton(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = if (isSelected) MaterialTheme.colorScheme.primary
                  else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary
                       else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        onClick = onClick,
        modifier = modifier.height(64.dp),
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        contentColor = contentColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
        }
    }
}

/**
 * PresetsCard — 環境模式卡片（含 Level 3 自動切換邏輯與呼吸燈動畫）
 */
@Composable
private fun PresetsCard(
    situationalMode: SceneManager.Mode,
    isAutoLocked: Boolean,
    onModeSelect: (SceneManager.Mode) -> Unit,
    onAutoToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Auto Mode Toggle Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "環境模式",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "自動切換",
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = MaterialTheme.colorScheme.secondary
                        )
                    )
                    Switch(
                        checked = !isAutoLocked,
                        onCheckedChange = { enabled -> onAutoToggle(enabled) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 4 Preset Mode Buttons (2x2 grid)
            val modes = listOf(
                Triple(SceneManager.Mode.TRANSPARENCY, "全向",  Icons.Default.Hearing),
                Triple(SceneManager.Mode.CONVERSATION, "人聲",  Icons.Default.RecordVoiceOver),
                Triple(SceneManager.Mode.OUTDOOR,      "戶外",  Icons.Default.Terrain),
                Triple(SceneManager.Mode.CINEMA,       "影音",  Icons.Default.MusicNote)
            )

            // Breathing animation for auto-selected mode
            val infiniteTransition = rememberInfiniteTransition(label = "breathe")
            val breatheAlpha by infiniteTransition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(900, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "breatheAlpha"
            )

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                modes.chunked(2).forEach { rowModes ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        rowModes.forEach { (mode, label, icon) ->
                            val isSelected = situationalMode == mode
                            val isAutoActive = !isAutoLocked && isSelected // auto mode highlights this one
                            val isDisabled = isAutoLocked

                            // Border alpha: breathes when auto-selected
                            val borderAlpha = if (isAutoActive) breatheAlpha else 0f
                            val borderColor = MaterialTheme.colorScheme.primary.copy(alpha = borderAlpha)

                            PresetModeCard(
                                label = label,
                                icon = icon,
                                isSelected = isSelected,
                                isDisabled = isDisabled,
                                breatheBorderColor = borderColor,
                                modifier = Modifier.weight(1f),
                                onClick = { if (!isDisabled) onModeSelect(mode) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetModeCard(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    isDisabled: Boolean,
    breatheBorderColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val alpha = if (isDisabled && !isSelected) 0.4f else 1.0f
    val bgColor = when {
        isSelected && !isDisabled -> MaterialTheme.colorScheme.primaryContainer
        isSelected && isDisabled  -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        else                      -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                       else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        onClick = onClick,
        modifier = modifier
            .height(80.dp)
            .border(
                width = 2.dp,
                color = breatheBorderColor,
                shape = RoundedCornerShape(14.dp)
            ),
        shape = RoundedCornerShape(14.dp),
        color = bgColor,
        contentColor = contentColor.copy(alpha = alpha),
        enabled = !isDisabled
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * VolumeBalanceCard — 整體音量 & 左右耳平衡
 */
@Composable
private fun VolumeBalanceCard(audioManager: AudioManager?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "系統音量",
                style = MaterialTheme.typography.labelLarge.copy(
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
            audioManager?.let { SystemVolumeSlider(it) }
        }
    }
}

/**
 * LargeNavCard — 大型導航卡片按鈕
 * Used for primary navigation actions (EQ, Hearing Test).
 */
@Composable
private fun LargeNavCard(
    label: String,
    sublabel: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = contentColor
                    )
                )
                Text(
                    text = sublabel,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = contentColor.copy(alpha = 0.7f),
                        letterSpacing = 2.sp
                    )
                )
            }
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor.copy(alpha = 0.85f),
                modifier = Modifier.size(36.dp)
            )
        }
    }
}
