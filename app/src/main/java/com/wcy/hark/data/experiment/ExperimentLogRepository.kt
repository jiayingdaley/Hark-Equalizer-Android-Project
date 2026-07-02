package com.wcy.hark.data.experiment

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.BaseColumns
import com.wcy.hark.audiometry.sqlite.SRTResultContract
import org.json.JSONObject

// =============================================================================
// Data Model
// =============================================================================

/**
 * Represents a single row in the experiment_log table.
 * Each row records one experiment session for later paper-writing reference.
 *
 * @property testType    One of: CALIBRATION, WDRC_IO, TONE_BURST, OSPL90, DUAL_MIC
 * @property earphone    Earphone model name (nullable for tests not involving earphone selection)
 * @property dspBypass   JSON string recording which DSP modules were bypassed (for reproducibility)
 * @property params      JSON string of test-specific parameters (frequency, level, sweep duration, etc.)
 * @property note        Free-form note added by the researcher
 */
data class ExperimentLog(
    val id: Long = 0,
    val timestamp: String,
    val testType: String,
    val earphone: String?,
    val dspBypass: String,   // JSON: {"dcBlocker":true, "wdrc":false, ...}
    val params: String,      // JSON: test-specific configuration snapshot
    val note: String = ""
)

// =============================================================================
// Database Contract
// =============================================================================

object ExperimentLogContract {
    object Entry : BaseColumns {
        const val TABLE_NAME          = "experiment_log"
        const val COL_TIMESTAMP       = "timestamp"
        const val COL_TEST_TYPE       = "test_type"
        const val COL_EARPHONE        = "earphone"
        const val COL_DSP_BYPASS      = "dsp_bypass"
        const val COL_PARAMS          = "params"
        const val COL_NOTE            = "note"
    }

    const val SQL_CREATE_TABLE =
        "CREATE TABLE IF NOT EXISTS ${Entry.TABLE_NAME} (" +
        "${BaseColumns._ID} INTEGER PRIMARY KEY AUTOINCREMENT, " +
        "${Entry.COL_TIMESTAMP} TEXT NOT NULL, " +
        "${Entry.COL_TEST_TYPE} TEXT NOT NULL, " +
        "${Entry.COL_EARPHONE} TEXT, " +
        "${Entry.COL_DSP_BYPASS} TEXT NOT NULL, " +
        "${Entry.COL_PARAMS} TEXT NOT NULL, " +
        "${Entry.COL_NOTE} TEXT)"

    const val SQL_DROP_TABLE = "DROP TABLE IF EXISTS ${Entry.TABLE_NAME}"
}

// =============================================================================
// Upgraded DB Helper — adds experiment_log to the existing SRTResults.db
// Increment DATABASE_VERSION to 3 to trigger onUpgrade migration.
// =============================================================================

/**
 * HarkDbHelper extends the existing SRTResults.db with the experiment_log table.
 * By bumping DATABASE_VERSION to 3, onUpgrade() is called automatically on
 * first launch after the app is updated, adding the new table without destroying
 * existing SRT data.
 *
 * NOTE: If this is a fresh install, onCreate() creates all tables at once.
 */
class HarkDbHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_VERSION = 4          // Bump to 4 to align with subject_name database updates
        const val DATABASE_NAME   = "SRTResults.db"
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Create all tables (fresh install)
        db.execSQL(SRTResultContract.SQL_CREATE_TEST_SESSIONS_TABLE)
        db.execSQL(SRTResultContract.SQL_CREATE_SRT_RECORDS_TABLE)
        db.execSQL(ExperimentLogContract.SQL_CREATE_TABLE)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Non-destructive migration: preserve clinical data and dynamically add columns/tables
        if (oldVersion < 3) {
            try {
                db.execSQL(ExperimentLogContract.SQL_CREATE_TABLE)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (oldVersion < 4) {
            try {
                db.execSQL("ALTER TABLE test_sessions ADD COLUMN subject_name TEXT")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // For downgrade safety, run upgrade to re-sync schema.
        onUpgrade(db, oldVersion, newVersion)
    }
}

// =============================================================================
// Repository
// =============================================================================

/**
 * ExperimentLogRepository
 *
 * Provides INSERT and SELECT operations on the experiment_log table.
 * Intended for use from the experiment UI to log each measurement session,
 * enabling the researcher to accurately reconstruct test conditions when
 * writing the Methods section of the thesis.
 *
 * ⚠️ Warning: Do NOT use this repository for high-frequency continuous data
 * (e.g., logging every 10 ms of WDRC gain change). Use a BufferedWriter
 * to write CSV files instead to avoid I/O blocking on the audio thread.
 */
class ExperimentLogRepository(context: Context) {

    private val dbHelper = HarkDbHelper(context)

    /**
     * Inserts a new experiment log entry and returns the new row ID.
     * Should be called once per experiment session (not per sample).
     */
    fun insertLog(log: ExperimentLog): Long {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(ExperimentLogContract.Entry.COL_TIMESTAMP,  log.timestamp)
            put(ExperimentLogContract.Entry.COL_TEST_TYPE,  log.testType)
            put(ExperimentLogContract.Entry.COL_EARPHONE,   log.earphone)
            put(ExperimentLogContract.Entry.COL_DSP_BYPASS, log.dspBypass)
            put(ExperimentLogContract.Entry.COL_PARAMS,     log.params)
            put(ExperimentLogContract.Entry.COL_NOTE,       log.note)
        }
        return db.insert(ExperimentLogContract.Entry.TABLE_NAME, null, values)
    }

    /**
     * Returns all experiment log entries in reverse chronological order.
     */
    fun queryAll(): List<ExperimentLog> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            ExperimentLogContract.Entry.TABLE_NAME,
            null,
            null, null, null, null,
            "${ExperimentLogContract.Entry.COL_TIMESTAMP} DESC"
        )
        val results = mutableListOf<ExperimentLog>()
        with(cursor) {
            while (moveToNext()) {
                results += ExperimentLog(
                    id        = getLong(getColumnIndexOrThrow(BaseColumns._ID)),
                    timestamp = getString(getColumnIndexOrThrow(ExperimentLogContract.Entry.COL_TIMESTAMP)),
                    testType  = getString(getColumnIndexOrThrow(ExperimentLogContract.Entry.COL_TEST_TYPE)),
                    earphone  = getString(getColumnIndexOrThrow(ExperimentLogContract.Entry.COL_EARPHONE)),
                    dspBypass = getString(getColumnIndexOrThrow(ExperimentLogContract.Entry.COL_DSP_BYPASS)),
                    params    = getString(getColumnIndexOrThrow(ExperimentLogContract.Entry.COL_PARAMS)),
                    note      = getString(getColumnIndexOrThrow(ExperimentLogContract.Entry.COL_NOTE)) ?: ""
                )
            }
        }
        cursor.close()
        return results
    }

    /**
     * Builds a JSON string for the dspBypass field, capturing the current
     * state of all DSP bypass flags for reproducibility documentation.
     */
    fun buildDspBypassJson(
        dcBlocker: Boolean,
        noiseReduction: Boolean,
        crossoverWdrc: Boolean,
        limiter: Boolean,
        transientSuppressor: Boolean,
        ownVoiceDetector: Boolean
    ): String = JSONObject().apply {
        put("dcBlocker",         dcBlocker)
        put("noiseReduction",    noiseReduction)
        put("crossoverWdrc",     crossoverWdrc)
        put("limiter",           limiter)
        put("transientSuppr",    transientSuppressor)
        put("ownVoiceDetector",  ownVoiceDetector)
    }.toString()
}
