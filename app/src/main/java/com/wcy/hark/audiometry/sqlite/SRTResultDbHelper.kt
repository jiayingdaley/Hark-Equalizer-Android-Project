package com.wcy.hark.audiometry.sqlite

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SRTResultDbHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_VERSION = 5 // v5: SSN (speech-in-noise) test tables
        const val DATABASE_NAME = "SRTResults.db"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SRTResultContract.SQL_CREATE_TEST_SESSIONS_TABLE)
        db.execSQL(SRTResultContract.SQL_CREATE_SRT_RECORDS_TABLE)
        db.execSQL(SRTResultContract.SQL_CREATE_SSN_SESSIONS_TABLE)
        db.execSQL(SRTResultContract.SQL_CREATE_SSN_RECORDS_TABLE)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Non-destructive migration to preserve clinical testing results
        if (oldVersion < 3) {
            try {
                // Ensure experiment_log table exists (added in version 3)
                db.execSQL("CREATE TABLE IF NOT EXISTS experiment_log (id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp TEXT, test_type TEXT, earphone TEXT, dsp_bypass TEXT, params TEXT, note TEXT)")
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