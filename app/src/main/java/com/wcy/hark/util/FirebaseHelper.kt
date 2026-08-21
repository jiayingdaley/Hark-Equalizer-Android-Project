package com.wcy.hark.util

import android.content.Context
import android.os.Bundle
import android.provider.Settings
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import com.wcy.hark.audio.bridge.HarkAudioBridge
import timber.log.Timber

/**
 * FirebaseHelper — Centralized manager for Firebase Analytics logging,
 * Remote Config DSP parameter synchronization, and security stub reservations.
 *
 * NOTE: Strictly zero PII (Personally Identifiable Information) or sensitive audiometry
 * data is collected or uploaded.
 */
object FirebaseHelper {

    private var firebaseAnalytics: FirebaseAnalytics? = null
    private var remoteConfig: FirebaseRemoteConfig? = null

    // Remote Config Keys & Default Values
    private const val KEY_WDRC_EXPANDER_THRESHOLD = "wdrc_expander_threshold"
    private const val KEY_LIMITER_THRESHOLD = "limiter_threshold"
    private const val KEY_LIMITER_RELEASE = "limiter_release"
    private const val KEY_NLFC_CUTOFF_HZ = "nlfc_cutoff_hz"
    private const val KEY_NLFC_RATIO = "nlfc_ratio"

    private const val DEFAULT_WDRC_EXPANDER_THRESHOLD = -72.0f
    private const val DEFAULT_LIMITER_THRESHOLD = -1.5f
    private const val DEFAULT_LIMITER_RELEASE = 30.0f
    private const val DEFAULT_NLFC_CUTOFF_HZ = 3000.0f
    private const val DEFAULT_NLFC_RATIO = 1.5f

    /**
     * Initializes Analytics and Remote Config services.
     */
    fun initialize(context: Context) {
        try {
            firebaseAnalytics = FirebaseAnalytics.getInstance(context)
            remoteConfig = FirebaseRemoteConfig.getInstance()

            val configSettings = FirebaseRemoteConfigSettings.Builder()
                .setMinimumFetchIntervalInSeconds(3600) // 1 hour fetch interval
                .build()
            remoteConfig?.setConfigSettingsAsync(configSettings)

            // Set default parameters
            val defaults = mapOf<String, Any>(
                KEY_WDRC_EXPANDER_THRESHOLD to DEFAULT_WDRC_EXPANDER_THRESHOLD.toDouble(),
                KEY_LIMITER_THRESHOLD to DEFAULT_LIMITER_THRESHOLD.toDouble(),
                KEY_LIMITER_RELEASE to DEFAULT_LIMITER_RELEASE.toDouble(),
                KEY_NLFC_CUTOFF_HZ to DEFAULT_NLFC_CUTOFF_HZ.toDouble(),
                KEY_NLFC_RATIO to DEFAULT_NLFC_RATIO.toDouble()
            )
            remoteConfig?.setDefaultsAsync(defaults)

            fetchAndApplyRemoteConfig()
            Timber.d("FirebaseHelper initialized successfully.")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize FirebaseHelper.")
        }
    }

    /**
     * Fetches the latest Remote Config settings from Firebase cloud
     * and updates the C++ DSP engine via JNI.
     */
    fun fetchAndApplyRemoteConfig() {
        remoteConfig?.fetchAndActivate()
            ?.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Timber.d("Remote Config fetch and activate succeeded.")
                    applyConfigToEngine()
                } else {
                    Timber.w("Remote Config fetch failed. Using default or cached parameters.")
                    applyConfigToEngine()
                }
            }
    }

    /**
     * Applies the current Remote Config values directly to the C++ DSP engine.
     */
    private fun applyConfigToEngine() {
        try {
            val config = remoteConfig ?: return

            val wdrcExpanderThreshold = config.getDouble(KEY_WDRC_EXPANDER_THRESHOLD).toFloat()
            val limiterThreshold = config.getDouble(KEY_LIMITER_THRESHOLD).toFloat()
            val limiterRelease = config.getDouble(KEY_LIMITER_RELEASE).toFloat()
            val nlfcCutoff = config.getDouble(KEY_NLFC_CUTOFF_HZ).toFloat()
            val nlfcRatio = config.getDouble(KEY_NLFC_RATIO).toFloat()

            // Update C++ Native DSP Parameters via HarkAudioBridge
            HarkAudioBridge.setWdrcExpanderThreshold(wdrcExpanderThreshold)
            HarkAudioBridge.setLimiterParameters(limiterThreshold, limiterRelease)
            HarkAudioBridge.setFrequencyLoweringParams(nlfcCutoff, nlfcRatio)

            Timber.d("Applied Remote Config DSP params -> WDRC Expander: $wdrcExpanderThreshold dB, Limiter: $limiterThreshold dB / $limiterRelease ms, NLFC: $nlfcCutoff Hz / $nlfcRatio ratio.")
        } catch (e: Exception) {
            Timber.e(e, "Error applying Remote Config parameters to C++ DSP engine.")
        }
    }

    // ─── Analytics Custom Event Logging ─────────────────────────────────────────

    /** 1. Log Audio Source Mode (MICROPHONE / INTERNAL_MEDIA) */
    fun logAudioSourceSelect(sourceMode: String) {
        val bundle = Bundle().apply {
            putString("source_mode", sourceMode)
        }
        firebaseAnalytics?.logEvent("select_audio_source", bundle)
    }

    /** 2. Log Scene Mode Change (TRANSPARENCY / CLASSROOM / OUTDOOR / INDOOR) */
    fun logSceneModeChange(sceneName: String) {
        val bundle = Bundle().apply {
            putString("scene_name", sceneName)
        }
        firebaseAnalytics?.logEvent("change_scene_mode", bundle)
    }

    /** 3. Log Connected Headphone Device Label */
    fun logHeadphoneConnection(deviceLabel: String) {
        val bundle = Bundle().apply {
            putString("device_label", deviceLabel)
        }
        firebaseAnalytics?.logEvent("headphone_connected", bundle)
    }

    /** 4. Log Hearing Aid Session Duration (Seconds) */
    fun logSessionDuration(durationSeconds: Long) {
        if (durationSeconds <= 0) return
        val bundle = Bundle().apply {
            putLong("duration_seconds", durationSeconds)
        }
        firebaseAnalytics?.logEvent("session_duration", bundle)
    }

    /** 5. Log Manual EQ Adjustment */
    fun logEqAdjustment(bandIndex: Int, gainDb: Float) {
        val bundle = Bundle().apply {
            putInt("band_index", bandIndex)
            putFloat("gain_db", gainDb)
        }
        firebaseAnalytics?.logEvent("adjust_eq", bundle)
    }

    // ─── Security Stubs (Reserved for future anti-theft whitelist & expiration) ─────

    /**
     * STUB: Checks if current device Android ID is authorized via remote whitelist.
     * Currently STUBBED to always return true (unrestricted access).
     */
    fun isDeviceAuthorized(context: Context): Boolean {
        // Reserved for future device binding security check.
        // val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        return true
    }

    /**
     * STUB: Checks if current build has passed hard expiration date.
     * Currently STUBBED to always return false (never expired).
     */
    fun isAppExpired(): Boolean {
        // Reserved for future time-bomb expiration check.
        return false
    }
}
