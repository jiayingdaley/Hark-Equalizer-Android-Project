package com.wcy.hark.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * HarkColors — 全 app 的語意色票單一來源（Compose 端）。
 * View 系（XML/Canvas）對應值維護於 res/values/colors.xml 與 AudiogramView 等
 * 自繪元件，修改時兩邊必須同步。
 *
 * 配色鐵則：
 * 1. 聽力學慣例：右耳紅、左耳藍（聽力圖符號 ○紅 / ×藍），所有左右耳相關
 *    UI（slider、按鈕、曲線、標籤）一律取用 EarRight / EarLeft，不得另造紅藍。
 * 2. 主色（品牌/強調）刻意選 teal——不可用紅或藍系，避免與耳別色搶語意。
 * 3. 使用者模式亮色、實驗模式深色，兩者一眼可辨。
 */
object HarkColors {

    // ── 耳別（聽力學慣例；對比度 ≥4.5:1 於白底） ──────────────────────────
    val EarRight = Color(0xFFD32F2F)      // 右耳紅（audiological red）
    val EarLeft = Color(0xFF1976D2)       // 左耳藍（audiological blue）
    val EarBoth = Color(0xFF7E57C2)       // 雙耳連動（紫，僅 EQ 連動模式使用）

    // ── 品牌主色（teal；醫療感、不與耳別色衝突） ─────────────────────────
    val Primary = Color(0xFF00796B)       // teal 700
    val PrimaryDark = Color(0xFF004D40)   // teal 900（漸層深端）
    val PrimaryLight = Color(0xFF4DB6AC)  // teal 300（深色主題主色）
    val PrimaryContainer = Color(0xFFB2DFDB)
    val OnPrimaryContainer = Color(0xFF00332C)

    // ── 使用者模式背景（亮） ─────────────────────────────────────────────
    val UserBgTop = Color(0xFFF5F7FA)
    val UserBgBottom = Color(0xFFE9F0F8)

    // ── 實驗模式（深；與 DspTestScreen / CalibrationTestScreen 一致） ────
    val ExperimentBg = Color(0xFF0F1015)
    val ExperimentCard = Color(0xFF1A1C24)
    val ExperimentBanner = Color(0xFFFFB74D)   // 常駐「實驗模式」色帶（amber）
    val ExperimentBannerText = Color(0xFF3E2723)

    // ── 狀態色 ───────────────────────────────────────────────────────────
    val Success = Color(0xFF2E7D32)
    val Warning = Color(0xFFF9A825)
    val Error = Color(0xFFC62828)
}
