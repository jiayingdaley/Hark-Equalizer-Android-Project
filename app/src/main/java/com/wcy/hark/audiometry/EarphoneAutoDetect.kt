package com.wcy.hark.audiometry

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.widget.Spinner

/**
 * 耳機型號自動偵測（官方 API：AudioDeviceInfo.getProductName()，API 23+）。
 *
 * 目的：測驗設置時自動把「使用耳機」下拉選單帶到目前實際連接的耳機，
 * 減少選錯耳機導致校正表套錯的人為失誤。自動帶入僅在「偵測到的名稱
 * 能對上校正表型號」時發生；對不上就不動選單（保持原值），一律仍可
 * 手動改選——自動只是預填，不是鎖定。
 *
 * 注意：藍牙耳機的 productName 由裝置自行回報（多半等於使用者在藍牙
 * 設定看到的名稱）；有線 3.5mm 耳機無數位識別，回報不到型號。
 */
object EarphoneAutoDetect {

    /** 外接耳機偵測結果：connected = 是否有外接輸出裝置；name = 可用的型號名（識別不到為 null）。 */
    data class Detection(val connected: Boolean, val name: String?)

    private val HEADPHONE_TYPES = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        26,  // TYPE_BLE_HEADSET（API 31+；直接用數值，舊系統只是比對不到）
        30   // TYPE_BLE_BROADCAST（API 33+）
    )

    /** 偵測目前連接的外接耳機（藍牙 / BLE Audio / USB / 有線）。 */
    fun detect(context: Context): Detection {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val device = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type in HEADPHONE_TYPES }
            ?: return Detection(false, null)
        val name = device.productName?.toString()?.trim()
        // 有線耳機（含 USB-C 轉 3.5mm 轉接器）常回報手機本身型號，等於沒有識別資訊
        val usable = if (name.isNullOrEmpty() || name == android.os.Build.MODEL) null else name
        return Detection(true, usable)
    }

    /** 目前連接的外部耳機名稱（藍牙 / USB / 有線），無外部耳機或識別不到回 null。 */
    fun detectedHeadphoneName(context: Context): String? = detect(context).name

    private fun norm(s: String) = s.lowercase().replace(Regex("[\\s\\-_]"), "")

    /** 回報名稱對校正表型號的比對（雙向包含，忽略大小寫/空白）；無匹配回 -1。 */
    fun matchIndex(productName: String?, models: List<String>): Int {
        if (productName == null) return -1
        val p = norm(productName)
        if (p.isEmpty()) return -1
        // 先找完全相等，再找互相包含（例如回報 "ATH-CKS330NC BK" vs 型號 "ATH-CKS330NC"）
        models.forEachIndexed { i, m -> if (m != "其他" && norm(m) == p) return i }
        models.forEachIndexed { i, m ->
            if (m == "其他") return@forEachIndexed
            val n = norm(m)
            if (n.isNotEmpty() && (p.contains(n) || n.contains(p))) return i
        }
        return -1
    }

    /**
     * 一次性偵測並預選 spinner。回傳給 UI 的提示訊息（null = 無外部耳機）：
     * 對上型號時回「已偵測到耳機：X」；對不上回「偵測到耳機：X（無校正資料，請手動選擇）」。
     */
    fun autoSelect(context: Context, spinner: Spinner, models: List<String>): String? {
        val det = detect(context)
        if (!det.connected) return null
        val name = det.name
            // 有裝置但讀不到型號（典型：USB-C 轉 3.5mm 轉接器＋類比耳機）——
            // 以前這裡默默回 null，使用者以為自動帶入功能不存在；改成明講。
            ?: return "偵測到有線耳機（轉接器無法回報型號，請手動確認選單）"
        val idx = matchIndex(name, models)
        return if (idx >= 0) {
            spinner.setSelection(idx)
            "已偵測到耳機：$name"
        } else {
            "偵測到耳機：$name（無校正資料，請手動選擇）"
        }
    }

    /**
     * 註冊連線監聽：耳機接上/拔除時重新偵測並預選。回傳 callback，
     * 呼叫端須在適當時機 [unregister]（Activity onDestroy 或 dialog dismiss）。
     */
    fun register(
        context: Context,
        spinner: Spinner,
        models: List<String>,
        onInfo: ((String?) -> Unit)? = null
    ): AudioDeviceCallback {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                onInfo?.invoke(autoSelect(context, spinner, models))
            }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                onInfo?.invoke(autoSelect(context, spinner, models))
            }
        }
        am.registerAudioDeviceCallback(callback, null)
        // 立即做一次初始偵測
        onInfo?.invoke(autoSelect(context, spinner, models))
        return callback
    }

    fun unregister(context: Context, callback: AudioDeviceCallback) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.unregisterAudioDeviceCallback(callback)
    }
}
