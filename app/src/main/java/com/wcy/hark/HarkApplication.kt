package com.wcy.hark

import android.app.Application
import android.content.pm.ApplicationInfo
import com.wcy.hark.data.EqSettingsRepository
import timber.log.Timber

class HarkApplication : Application() {
    
    lateinit var eqSettingsRepository: EqSettingsRepository

    /**
     * App 層共用的 EqViewModel：MainActivity 與 FloatingEqService（懸浮等化器）
     * 共用同一個實例，slider/曲線改動即時互通，不再依賴 DataStore 去回同步
     * （兩個獨立實例經 200ms debounce 寫入/收集，曾造成懸浮窗與 app 內
     * 等化器參數不同步）。應用程式存活期間不釋放。
     */
    val sharedEqViewModel: com.wcy.hark.ui.viewmodel.EqViewModel by lazy {
        com.wcy.hark.ui.viewmodel.EqViewModel(eqSettingsRepository)
    }

    override fun onCreate() {
        super.onCreate()
        
        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        
        // Initialize Timber for structured and safe logging
        if (isDebuggable) {
            Timber.plant(Timber.DebugTree())
            Timber.d("Timber initialized. Debug environment detected.")
        } else {
            // Can be expanded to forward release logs to Crashlytics
            Timber.plant(CrashReportingTree())
            Timber.d("Timber initialized in Release mode. Forwarding non-trace logs to Crashlytics.")
        }

        // Initialize DataStore Repository
        eqSettingsRepository = EqSettingsRepository(this)
        Timber.d("EqSettingsRepository initialized.")
        
        // Firebase is auto-initialized if google-services.json is present.
        // It's highly recommended to add it!
        // (You can uncomment this when crashlytics is fully setup)
        // com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(!isDebuggable)
    }
}

// Custom Tree to send logs to Crashlytics
class CrashReportingTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority == android.util.Log.VERBOSE || priority == android.util.Log.DEBUG) {
            return
        }

        // Fake FirebaseCrashlytics initialization check. In real code:
        // val crashlytics = com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
        // crashlytics.log(message)
        // if (t != null) {
        //     crashlytics.recordException(t)
        // }
    }
}
