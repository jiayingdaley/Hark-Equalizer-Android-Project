package com.wcy.hark.audiometry.sqlite

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SRTResultDbHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        // v14: ssn_sessions 新增操作檢核逐頻原始資料（實測/目標/誤差，逗號分隔，
        // 500/1000/2000/4000 Hz 固定順序）。原本只留化約後的 hl_sim_check_err 最大
        // 絕對誤差純量，逐頻細節算完即丟；此三欄補回，供事後檢視每頻誤差方向與大小。
        // v15: questionnaire_responses 新增 v2 比較制問卷欄位（version / scene_delta /
        // own_voice / loudness）。舊資料 version 預設 1，不受影響。
        // v16: 新增⑥ NLFC/DSP 效益驗證彙總表（nlfc_dsp_abc_sessions），非破壞性、
        // 新表不影響既有資料。
        const val DATABASE_VERSION = 16
        const val DATABASE_NAME = "SRTResults.db"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SRTResultContract.SQL_CREATE_TEST_SESSIONS_TABLE)
        db.execSQL(SRTResultContract.SQL_CREATE_SRT_RECORDS_TABLE)
        db.execSQL(SRTResultContract.SQL_CREATE_SSN_SESSIONS_TABLE)
        db.execSQL(SRTResultContract.SQL_CREATE_SSN_RECORDS_TABLE)
        db.execSQL(SRTResultContract.SQL_CREATE_AB_SESSIONS_TABLE)
        db.execSQL(SRTResultContract.SQL_CREATE_QUESTIONNAIRE_TABLE)
        db.execSQL(SRTResultContract.SQL_CREATE_ABC_SESSIONS_TABLE)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Non-destructive migration to preserve clinical testing results
        if (oldVersion < 16) {
            try { db.execSQL(SRTResultContract.SQL_CREATE_ABC_SESSIONS_TABLE) }
            catch (e: Exception) { e.printStackTrace() }
        }
        if (oldVersion < 15) {
            listOf(
                "ALTER TABLE questionnaire_responses ADD COLUMN questionnaire_version INTEGER DEFAULT 1",
                "ALTER TABLE questionnaire_responses ADD COLUMN scene_delta INTEGER",
                "ALTER TABLE questionnaire_responses ADD COLUMN own_voice INTEGER",
                "ALTER TABLE questionnaire_responses ADD COLUMN loudness INTEGER"
            ).forEach { sql -> try { db.execSQL(sql) } catch (e: Exception) { /* 欄位已存在 */ } }
        }
        if (oldVersion < 14) {
            // v14: 操作檢核逐頻原始資料（測量/目標/誤差），取代原本算完即丟的做法。
            for (sql in listOf(
                "ALTER TABLE ssn_sessions ADD COLUMN hl_sim_check_measured_dbfs TEXT",
                "ALTER TABLE ssn_sessions ADD COLUMN hl_sim_check_target_db TEXT",
                "ALTER TABLE ssn_sessions ADD COLUMN hl_sim_check_error_db TEXT"
            )) {
                try { db.execSQL(sql) } catch (e: Exception) { e.printStackTrace() }
            }
        }
        if (oldVersion < 13) {
            // v13：新增資料來源標記欄位，並回填既有資料——凡出現在 ssn_ab_sessions
            // 的 off/on 場次，一律標記為 subject（這類場次本來就只可能由「測試者
            // 測驗流程」(SSNAbTestActivity) 寫入）。回填規則完全基於既有的外鍵
            // 關係，不改動任何既有欄位的數值，已收案資料不受影響，只是補上一個
            // 新欄位的標記值，讓一般模式的「查看歷史紀錄」能正確排除這些場次。
            try { db.execSQL("ALTER TABLE ssn_sessions ADD COLUMN session_source TEXT") }
            catch (e: Exception) { e.printStackTrace() }
            try {
                db.execSQL(
                    "UPDATE ssn_sessions SET session_source='subject' WHERE session_id IN (" +
                        "SELECT session_id_off FROM ssn_ab_sessions WHERE session_id_off IS NOT NULL " +
                        "UNION " +
                        "SELECT session_id_on FROM ssn_ab_sessions WHERE session_id_on IS NOT NULL)"
                )
            } catch (e: Exception) { e.printStackTrace() }
        }
        if (oldVersion < 12) {
            // v12: 呈現條件完整留存——位準錨點、DSP 開關與實際增益、噪音總位準、
            // 詞表分半。讓每場次的 CSV 匯出自足，分析不需回頭拼其他資料來源。
            for (sql in listOf(
                "ALTER TABLE ssn_sessions ADD COLUMN level_anchor_dbfs REAL",
                "ALTER TABLE ssn_sessions ADD COLUMN dsp_on INTEGER",
                "ALTER TABLE ssn_sessions ADD COLUMN dsp_gains_db TEXT",
                "ALTER TABLE ssn_sessions ADD COLUMN total_level_dbfs REAL",
                "ALTER TABLE ssn_sessions ADD COLUMN word_parity INTEGER DEFAULT -1"
            )) {
                try { db.execSQL(sql) } catch (e: Exception) { e.printStackTrace() }
            }
        }
        if (oldVersion < 11) {
            // v11: 記錄本場次模擬了什麼聽損——這是結果可否解釋的前提。
            // 沒有這三欄，事後根本分不出「補償有效」是因為處方好、還是因為
            // 模擬條件不同；必須隨資料留存。
            for (sql in listOf(
                "ALTER TABLE ssn_sessions ADD COLUMN hl_sim_profile TEXT",
                "ALTER TABLE ssn_sessions ADD COLUMN hl_sim_smearing INTEGER DEFAULT 0",
                "ALTER TABLE ssn_sessions ADD COLUMN hl_sim_check_err REAL"
            )) {
                try { db.execSQL(sql) } catch (e: Exception) { e.printStackTrace() }
            }
        }
        if (oldVersion < 10) {
            try {
                // v10: 記錄語音是否先經離線 NLFC 處理（移頻效益之行為驗證）
                db.execSQL("ALTER TABLE ssn_sessions ADD COLUMN nlfc INTEGER DEFAULT 0")
            } catch (e: Exception) { e.printStackTrace() }
        }
        if (oldVersion < 9) {
            try {
                // v9: 區分噪音下（SNR）與無噪音小聲（SL）場次；舊資料 NULL 視為 SNR
                db.execSQL("ALTER TABLE ssn_sessions ADD COLUMN test_mode TEXT")
            } catch (e: Exception) { e.printStackTrace() }
        }
        if (oldVersion < 8) {
            try {
                // v8: 同一測試者可能戴多副耳機分別測驗，加欄位以便匯出時區分
                db.execSQL("ALTER TABLE ssn_sessions ADD COLUMN earphone_model TEXT")
            } catch (e: Exception) { e.printStackTrace() }
            try {
                db.execSQL("ALTER TABLE questionnaire_responses ADD COLUMN earphone_model TEXT")
            } catch (e: Exception) { e.printStackTrace() }
        }
        if (oldVersion < 7) {
            try {
                // v7: 測試者實驗流程新表（非破壞性，IF NOT EXISTS）
                db.execSQL(SRTResultContract.SQL_CREATE_AB_SESSIONS_TABLE)
                db.execSQL(SRTResultContract.SQL_CREATE_QUESTIONNAIRE_TABLE)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (oldVersion < 3) {
            try {
                // Ensure experiment_log table exists (added in version 3)
                db.execSQL("CREATE TABLE IF NOT EXISTS experiment_log (id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp TEXT, test_type TEXT, earphone TEXT, dsp_bypass TEXT, params TEXT, note TEXT)")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (oldVersion in 5 until 6) {
            try {
                // v6: 逐題記錄削波防護正規化增益（非破壞性）
                db.execSQL("ALTER TABLE ssn_test_records ADD COLUMN norm_gain_db REAL DEFAULT 0")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (oldVersion < 5) {
            try {
                // SSN speech-in-noise tables (added in version 5, non-destructive)
                db.execSQL(SRTResultContract.SQL_CREATE_SSN_SESSIONS_TABLE)
                db.execSQL(SRTResultContract.SQL_CREATE_SSN_RECORDS_TABLE)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (oldVersion < 4) {
            try {
                // Add subject_name column to test_sessions table (added in version 4)
                db.execSQL("ALTER TABLE test_sessions ADD COLUMN subject_name TEXT")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onUpgrade(db, oldVersion, newVersion)
    }
}