package com.wcy.hark.audiometry.sqlite

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SRTResultDbHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_VERSION = 10 // v10: ssn_sessions 新增 nlfc（語音是否經離線移頻）
        const val DATABASE_NAME = "SRTResults.db"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SRTResultContract.SQL_CREATE_TEST_SESSIONS_TABLE)
        db.execSQL(SRTResultContract.SQL_CREATE_SRT_RECORDS_TABLE)
        db.execSQL(SRTResultContract.SQL_CREATE_SSN_SESSIONS_TABLE)
        db.execSQL(SRTResultContract.SQL_CREATE_SSN_RECORDS_TABLE)
        db.execSQL(SRTResultContract.SQL_CREATE_AB_SESSIONS_TABLE)
        db.execSQL(SRTResultContract.SQL_CREATE_QUESTIONNAIRE_TABLE)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Non-destructive migration to preserve clinical testing results
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
                // v7: 測試者測驗流程新表（非破壞性，IF NOT EXISTS）
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