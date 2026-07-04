package com.wcy.hark.audiometry.sqlite

import android.provider.BaseColumns


object SRTResultContract {

    // Table for overall test sessions
    object TestSessionEntry : BaseColumns { // BaseColumns provides _ID, though we use a custom session_id as PK here
        const val TABLE_NAME = "test_sessions"
        const val COLUMN_NAME_SESSION_ID = "session_id" // Our primary key for this table
        const val COLUMN_NAME_TEST_TIMESTAMP = "test_timestamp"
        const val COLUMN_NAME_OVERALL_ACCURACY = "overall_accuracy"
        const val COLUMN_NAME_TOTAL_QUESTIONS_ANSWERED = "total_questions_answered"
        const val COLUMN_NAME_PHONE_VOLUME = "phone_volume"
        const val COLUMN_NAME_SUBJECT_NAME = "subject_name"
    }

    // Table for individual question records within a session
    object SRTRecordEntry : BaseColumns { // BaseColumns provides _ID as a primary key
        const val TABLE_NAME = "srt_test_records"
        // _ID will be inherited from BaseColumns and used as INTEGER PRIMARY KEY AUTOINCREMENT

        const val COLUMN_NAME_SESSION_ID_FK = "session_id_fk" // Foreign key to TestSessionEntry
        const val COLUMN_NAME_QUESTION_NUMBER = "question_number"
        const val COLUMN_NAME_CORRECT_WORD = "correct_word"
        const val COLUMN_NAME_USER_ANSWER = "user_answer"
        const val COLUMN_NAME_WAS_CORRECT = "was_correct" // 1 for true, 0 for false
    }

    // SQL statements for creating tables
    // Using direct string concatenation (+) to ensure compile-time constancy for SQL strings.

    const val SQL_CREATE_TEST_SESSIONS_TABLE =
        "CREATE TABLE " + TestSessionEntry.TABLE_NAME + " (" +
                TestSessionEntry.COLUMN_NAME_SESSION_ID + " INTEGER PRIMARY KEY," +
                TestSessionEntry.COLUMN_NAME_TEST_TIMESTAMP + " INTEGER," +
                TestSessionEntry.COLUMN_NAME_OVERALL_ACCURACY + " REAL," +
                TestSessionEntry.COLUMN_NAME_TOTAL_QUESTIONS_ANSWERED + " INTEGER," +
                TestSessionEntry.COLUMN_NAME_PHONE_VOLUME + " INTEGER," +
                TestSessionEntry.COLUMN_NAME_SUBJECT_NAME + " TEXT)"

    const val SQL_CREATE_SRT_RECORDS_TABLE =
        "CREATE TABLE " + SRTRecordEntry.TABLE_NAME + " (" +
                BaseColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT," + // Explicitly using BaseColumns._ID
                SRTRecordEntry.COLUMN_NAME_SESSION_ID_FK + " INTEGER," +
                SRTRecordEntry.COLUMN_NAME_QUESTION_NUMBER + " INTEGER," +
                SRTRecordEntry.COLUMN_NAME_CORRECT_WORD + " TEXT," +
                SRTRecordEntry.COLUMN_NAME_USER_ANSWER + " TEXT," +
                SRTRecordEntry.COLUMN_NAME_WAS_CORRECT + " INTEGER," +
                "FOREIGN KEY(" + SRTRecordEntry.COLUMN_NAME_SESSION_ID_FK + ") REFERENCES " +
                TestSessionEntry.TABLE_NAME + "(" + TestSessionEntry.COLUMN_NAME_SESSION_ID + "))"

    // ── SSN (speech-in-noise) test tables ────────────────────────────────────
    object SSNSessionEntry : BaseColumns {
        const val TABLE_NAME = "ssn_sessions"
        const val COLUMN_NAME_SESSION_ID = "session_id"
        const val COLUMN_NAME_TEST_TIMESTAMP = "test_timestamp"
        const val COLUMN_NAME_SUBJECT_NAME = "subject_name"
        const val COLUMN_NAME_SNR_LIST = "snr_list"            // e.g. "10,5,0,-5,-10"
        const val COLUMN_NAME_QUESTIONS_PER_SNR = "questions_per_snr"
        const val COLUMN_NAME_SRT50 = "srt50"                  // interpolated 50% SNR, nullable
        const val COLUMN_NAME_PHONE_VOLUME = "phone_volume"
    }

    object SSNRecordEntry : BaseColumns {
        const val TABLE_NAME = "ssn_test_records"
        const val COLUMN_NAME_SESSION_ID_FK = "session_id_fk"
        const val COLUMN_NAME_SNR_DB = "snr_db"
        const val COLUMN_NAME_QUESTION_NUMBER = "question_number"
        const val COLUMN_NAME_CORRECT_WORD = "correct_word"
        const val COLUMN_NAME_USER_ANSWER = "user_answer"
        const val COLUMN_NAME_WAS_CORRECT = "was_correct"
    }

    const val SQL_CREATE_SSN_SESSIONS_TABLE =
        "CREATE TABLE IF NOT EXISTS " + SSNSessionEntry.TABLE_NAME + " (" +
                SSNSessionEntry.COLUMN_NAME_SESSION_ID + " INTEGER PRIMARY KEY," +
                SSNSessionEntry.COLUMN_NAME_TEST_TIMESTAMP + " INTEGER," +
                SSNSessionEntry.COLUMN_NAME_SUBJECT_NAME + " TEXT," +
                SSNSessionEntry.COLUMN_NAME_SNR_LIST + " TEXT," +
                SSNSessionEntry.COLUMN_NAME_QUESTIONS_PER_SNR + " INTEGER," +
                SSNSessionEntry.COLUMN_NAME_SRT50 + " REAL," +
                SSNSessionEntry.COLUMN_NAME_PHONE_VOLUME + " INTEGER)"

    const val SQL_CREATE_SSN_RECORDS_TABLE =
        "CREATE TABLE IF NOT EXISTS " + SSNRecordEntry.TABLE_NAME + " (" +
                BaseColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                SSNRecordEntry.COLUMN_NAME_SESSION_ID_FK + " INTEGER," +
                SSNRecordEntry.COLUMN_NAME_SNR_DB + " REAL," +
                SSNRecordEntry.COLUMN_NAME_QUESTION_NUMBER + " INTEGER," +
                SSNRecordEntry.COLUMN_NAME_CORRECT_WORD + " TEXT," +
                SSNRecordEntry.COLUMN_NAME_USER_ANSWER + " TEXT," +
                SSNRecordEntry.COLUMN_NAME_WAS_CORRECT + " INTEGER," +
                "FOREIGN KEY(" + SSNRecordEntry.COLUMN_NAME_SESSION_ID_FK + ") REFERENCES " +
                SSNSessionEntry.TABLE_NAME + "(" + SSNSessionEntry.COLUMN_NAME_SESSION_ID + "))"

    // SQL statements for deleting tables (optional, for upgrades)
    const val SQL_DELETE_TEST_SESSIONS_TABLE = "DROP TABLE IF EXISTS " + TestSessionEntry.TABLE_NAME
    const val SQL_DELETE_SRT_RECORDS_TABLE = "DROP TABLE IF EXISTS " + SRTRecordEntry.TABLE_NAME
}