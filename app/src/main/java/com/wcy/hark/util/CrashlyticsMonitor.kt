package com.wcy.hark.util

import android.content.Context
import com.google.firebase.crashlytics.FirebaseCrashlytics
import java.util.UUID

object CrashlyticsMonitor {
    private const val PREFS_NAME = "hark_crashlytics_prefs"
    private const val KEY_ANONYMOUS_ID = "anonymous_user_id"

    /**
     * 初始化去識別化的匿名使用者 ID。
     * 若本地已存在則使用舊的，否則自動生成隨機 UUID。
     * 絕不上傳姓名、Email 或聽力數據等敏感個資。
     */
    fun initialize(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var anonymousId = prefs.getString(KEY_ANONYMOUS_ID, null)
        if (anonymousId == null) {
            anonymousId = "usr_" + UUID.randomUUID().toString().substring(0, 8)
            prefs.edit().putString(KEY_ANONYMOUS_ID, anonymousId).apply()
        }
        
        val crashlytics = FirebaseCrashlytics.getInstance()
        crashlytics.setUserId(anonymousId)
        crashlytics.setCustomKey("anonymous_uid", anonymousId)
    }

    /**
     * 取得當前的匿名 UUID
     */
    fun getAnonymousId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ANONYMOUS_ID, "unknown") ?: "unknown"
    }

    /**
     * 記錄當前的耳機路由類型（非個資）。
     * 例如：wired_headphone, bluetooth_sco, builtin_speaker, unknown
     */
    fun setAudioRoute(routeType: String) {
        FirebaseCrashlytics.getInstance().setCustomKey("audio_route", routeType)
    }

    /**
     * 記錄當前 DSP 各模組的啟用狀態（非個資）。
     */
    fun setDspStatus(nsEnabled: Boolean, wdrcEnabled: Boolean, nlfcEnabled: Boolean) {
        val crashlytics = FirebaseCrashlytics.getInstance()
        crashlytics.setCustomKey("dsp_ns_enabled", nsEnabled)
        crashlytics.setCustomKey("dsp_wdrc_enabled", wdrcEnabled)
        crashlytics.setCustomKey("dsp_nlfc_enabled", nlfcEnabled)
    }

    /**
     * 記錄當前音訊硬體設定（非個資）。
     */
    fun setAudioConfig(sampleRate: Int, bufferSize: Int) {
        val crashlytics = FirebaseCrashlytics.getInstance()
        crashlytics.setCustomKey("audio_sample_rate", sampleRate)
        crashlytics.setCustomKey("audio_buffer_size", bufferSize)
    }

    /**
     * 記錄當前場景模式（非個資）。
     */
    fun setSceneMode(sceneName: String) {
        FirebaseCrashlytics.getInstance().setCustomKey("scene_mode", sceneName)
    }
}
