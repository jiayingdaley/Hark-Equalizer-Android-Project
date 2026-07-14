package com.wcy.hark.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "eq_settings")

class EqSettingsRepository(private val context: Context) {

    // We store gains as preferences. For 16 bands, we store 16 floats.
    // For 8 bands, we store 8 floats.
    private fun getBandGainKey(ear: String, mode: Int, index: Int) = floatPreferencesKey("band_gain_${ear}_${mode}_${index}")
    private fun getBandQKey(mode: Int, index: Int) = floatPreferencesKey("band_q_${mode}_${index}")

    fun getBandGainsFlow(ear: String, mode: Int, numBands: Int): Flow<List<Float>> = context.dataStore.data.map { preferences ->
        val gains = mutableListOf<Float>()
        for (i in 0 until numBands) {
            gains.add(preferences[getBandGainKey(ear, mode, i)] ?: 0f)
        }
        gains
    }
    
    fun getBandQsFlow(mode: Int, numBands: Int): Flow<List<Float>> = context.dataStore.data.map { preferences ->
        val qs = mutableListOf<Float>()
        for (i in 0 until numBands) {
            qs.add(preferences[getBandQKey(mode, i)] ?: 1.8f) // DEFAULT_Q is 1.8f
        }
        qs
    }

    suspend fun saveBandGain(ear: String, mode: Int, index: Int, gain: Float) {
        context.dataStore.edit { preferences ->
            preferences[getBandGainKey(ear, mode, index)] = gain
        }
    }

    suspend fun saveBandQ(mode: Int, index: Int, q: Float) {
        context.dataStore.edit { preferences ->
            preferences[getBandQKey(mode, index)] = q
        }
    }
    
    suspend fun resetBands(mode: Int, numBands: Int) {
        context.dataStore.edit { preferences ->
            for (i in 0 until numBands) {
                preferences[getBandGainKey("left", mode, i)] = 0f
                preferences[getBandGainKey("right", mode, i)] = 0f
                preferences[getBandQKey(mode, i)] = 1.8f
            }
        }
    }

    private fun getAudiogramThresholdKey(ear: String, frequency: Int) = intPreferencesKey("audiogram_${ear}_$frequency")

    suspend fun saveAudiogramThreshold(ear: String, frequency: Int, threshold: Int) {
        context.dataStore.edit { preferences ->
            preferences[getAudiogramThresholdKey(ear, frequency)] = threshold
        }
    }

    fun getAudiogramThresholdFlow(ear: String, frequency: Int): Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[getAudiogramThresholdKey(ear, frequency)] ?: -1
    }

    private val calibrationOffsetKey = floatPreferencesKey("audiogram_calibration_offset")

    suspend fun saveCalibrationOffset(offset: Float) {
        context.dataStore.edit { preferences ->
            preferences[calibrationOffsetKey] = offset
        }
    }

    fun getCalibrationOffsetFlow(): Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[calibrationOffsetKey] ?: 0f
    }

    // ── Hearing Aid Enabled State ──────────────────────────────────────────────
    // Persists the user's last intent (ON/OFF) so the app restores its state
    // after being killed by the system and relaunched (KNOWN-ISSUE-006 fix).
    private val hearingAidEnabledKey = booleanPreferencesKey("is_hearing_aid_enabled")

    suspend fun saveHearingAidEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[hearingAidEnabledKey] = enabled
        }
    }

    fun getHearingAidEnabledFlow(): Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[hearingAidEnabledKey] ?: false
    }

    // ── App Mode (User / Experiment) ──────────────────────────────────────────
    // false = 使用者模式（簡潔介面）；true = 實驗模式（研究人員全功能介面）
    private val appExperimentModeKey = booleanPreferencesKey("app_experiment_mode")

    suspend fun saveExperimentMode(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[appExperimentModeKey] = enabled
        }
    }

    fun getExperimentModeFlow(): Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[appExperimentModeKey] ?: false
    }

    // ── Frequency Lowering (NLFC) ─────────────────────────────────────────────
    // 使用者可選的移頻功能（高頻重度損失、增益補償不足時的最後手段）。
    // 僅作用於原生環境輔聽引擎（影音路徑的 DynamicsProcessing 無此能力）。
    private val frequencyLoweringKey = booleanPreferencesKey("frequency_lowering_enabled")

    suspend fun saveFrequencyLoweringEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[frequencyLoweringKey] = enabled
        }
    }

    fun getFrequencyLoweringFlow(): Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[frequencyLoweringKey] ?: false
    }

    // ── Selected Earphone Model ───────────────────────────────────────────────
    // Single source of truth for the earphone model used by both the experiment
    // panel and the pure-tone test (calibration table lookup).
    private val selectedEarphoneKey = stringPreferencesKey("selected_earphone_model")

    suspend fun saveSelectedEarphone(model: String) {
        context.dataStore.edit { preferences ->
            preferences[selectedEarphoneKey] = model
        }
    }

    fun getSelectedEarphoneFlow(): Flow<String> = context.dataStore.data.map { preferences ->
        val saved = preferences[selectedEarphoneKey] ?: "其他"
        // 型號改名後（對齊系統 API 回報名），舊存值換算成新名稱
        com.wcy.hark.data.experiment.EarphoneCalibrationRepository.MODEL_RENAMES[saved] ?: saved
    }

    // ── Raw Pure-Tone Thresholds (dBFS) ───────────────────────────────────────
    // 自調式純音測驗量到的「原始數位位準」閾值，未經任何絕對聲學校正。
    // 這是聽損模擬器的零點：所有刺激位準以此為基準的感覺級（dB SL）計算，
    // 因此整套行為實驗完全不需要人工耳或聲級計。
    // （getAudiogramThresholdFlow 存的是換算後的 dB HL，未校準時僅為相對值。）
    private fun getRawThresholdKey(ear: String, frequency: Int) =
        floatPreferencesKey("pta_raw_dbfs_${ear}_$frequency")

    suspend fun saveRawThresholdDbfs(ear: String, frequency: Int, dbfs: Float) {
        context.dataStore.edit { it[getRawThresholdKey(ear, frequency)] = dbfs }
    }

    /** 單耳的 freq(Hz) → 閾值 dBFS；未測過的頻率不會出現在 map 中。 */
    fun getRawThresholdsFlow(ear: String, frequencies: List<Int>): Flow<Map<Int, Float>> =
        context.dataStore.data.map { prefs ->
            frequencies.mapNotNull { f ->
                prefs[getRawThresholdKey(ear, f)]?.let { f to it }
            }.toMap()
        }

    /** 雙耳平均的 freq(Hz) → 閾值 dBFS（模擬聽損雙耳對稱套用，取平均即可）。 */
    fun getBinauralRawThresholdsFlow(frequencies: List<Int>): Flow<Map<Int, Float>> =
        context.dataStore.data.map { prefs ->
            frequencies.mapNotNull { f ->
                val l = prefs[getRawThresholdKey("left", f)]
                val r = prefs[getRawThresholdKey("right", f)]
                when {
                    l != null && r != null -> f to (l + r) / 2f
                    l != null -> f to l
                    r != null -> f to r
                    else -> null
                }
            }.toMap()
        }

    // ── Hearing Loss Simulation ───────────────────────────────────────────────
    // 模擬聽損組態：讓聽力正常的測試者體驗聽損，補償演算法才有可檢驗的對象。
    private val hlSimProfileKey = stringPreferencesKey("hl_sim_profile")
    private val hlSimSmearingKey = booleanPreferencesKey("hl_sim_smearing")

    suspend fun saveHlSimProfile(key: String) {
        context.dataStore.edit { it[hlSimProfileKey] = key }
    }

    fun getHlSimProfileFlow(): Flow<String> = context.dataStore.data.map { prefs ->
        prefs[hlSimProfileKey] ?: "NONE"
    }

    suspend fun saveHlSimSmearing(enabled: Boolean) {
        context.dataStore.edit { it[hlSimSmearingKey] = enabled }
    }

    /** 頻譜模糊（耳蝸濾波器變寬）——「聽得見但聽不懂」的來源，預設開啟。 */
    fun getHlSimSmearingFlow(): Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[hlSimSmearingKey] ?: true
    }

    // ── Subject Session Progress（測試者測驗流程續測）──────────────────────────
    // 流程走到哪一步存起來：app 關掉、當機或隔天續測都不用從①重來。
    // 基準聽閾、處方、模擬條件本就各自持久化，stage 是唯一遺失的狀態。
    private val sessionStageKey = intPreferencesKey("subject_session_stage")
    private val sessionIdKey = longPreferencesKey("subject_session_id")

    suspend fun saveSessionProgress(stage: Int, sessionId: Long) {
        context.dataStore.edit {
            it[sessionStageKey] = stage
            it[sessionIdKey] = sessionId
        }
    }

    fun getSessionStageFlow(): Flow<Int> = context.dataStore.data.map { it[sessionStageKey] ?: 0 }
    fun getSessionIdFlow(): Flow<Long> = context.dataStore.data.map { it[sessionIdKey] ?: 0L }

    // 步驟③操作檢核的最大誤差（dB）。原本只放主控頁記憶體變數，app 重啟續測
    // 後遺失，語詞場次的 hl_sim_check_err 就寫不進去（實測踩到：檢核有做、
    // 匯出卻是空欄）。Float.NaN = 本輪尚未檢核。
    private val hlSimCheckErrKey = floatPreferencesKey("hl_sim_check_max_err")

    suspend fun saveHlSimCheckErr(errDb: Float) {
        context.dataStore.edit { it[hlSimCheckErrKey] = errDb }
    }

    fun getHlSimCheckErrFlow(): Flow<Float> =
        context.dataStore.data.map { it[hlSimCheckErrKey] ?: Float.NaN }

    // 基準聽閾（raw thresholds）的「主人」。聽閾在 DataStore 不分人存，
    // 換測試者時若不比對這個欄位，流程主控頁會把上一位的聽閾誤判成
    // 新測試者「已有純音結果」（實測踩到：ID 5+1 改 5+2 仍跳續測詢問）。
    private val rawThresholdsOwnerKey = stringPreferencesKey("raw_thresholds_owner")

    suspend fun saveRawThresholdsOwner(name: String) {
        context.dataStore.edit { it[rawThresholdsOwnerKey] = name }
    }

    fun getRawThresholdsOwnerFlow(): Flow<String> =
        context.dataStore.data.map { it[rawThresholdsOwnerKey] ?: "" }

    // ── Last Subject Name (prefill convenience) ───────────────────────────────
    private val subjectNameKey = stringPreferencesKey("last_subject_name")

    suspend fun saveLastSubjectName(name: String) {
        context.dataStore.edit { preferences ->
            preferences[subjectNameKey] = name
        }
    }

    fun getLastSubjectNameFlow(): Flow<String> = context.dataStore.data.map { preferences ->
        preferences[subjectNameKey] ?: ""
    }
}


