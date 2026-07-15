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
        // 測試者可能戴不同耳機分別測驗；此欄位讓匯出/分析可依耳機型號區分
        // 同一測試者的多輪測驗（v8 新增，舊資料為 NULL）。
        const val COLUMN_NAME_EARPHONE_MODEL = "earphone_model"
        // 測驗模式（v9 新增）："SNR" = 噪音下（snr_list 為 dB SNR）、
        // "SL" = 無噪音小聲（snr_list 為 dB SL）。舊資料為 NULL，視為 SNR。
        const val COLUMN_NAME_TEST_MODE = "test_mode"
        const val MODE_SNR = "SNR"
        const val MODE_SL = "SL"
        // 語音是否先經離線移頻（NLFC）處理（v10 新增；0/NULL = 否）
        const val COLUMN_NAME_NLFC = "nlfc"
        // ── v11：聽損模擬（Hearing Loss Simulation）──────────────────────────
        // 測試者是聽力正常的人；不模擬聽損，補償就沒有對象、A/B 對照必然無效果。
        // 這三欄記錄本場次「模擬了什麼」，是結果可否解釋的前提，必須隨資料留存。
        const val COLUMN_NAME_HL_SIM_PROFILE = "hl_sim_profile"    // "NONE"/"S2"/"N4"… (Bisgaard)
        const val COLUMN_NAME_HL_SIM_SMEARING = "hl_sim_smearing"  // 頻譜模糊 0/1
        const val COLUMN_NAME_HL_SIM_CHECK_ERR = "hl_sim_check_err" // 模擬器操作檢核最大誤差 (dB)
        // ── v12：呈現條件完整留存（分析時每場次自足，不需回頭拼其他資料）────
        // 個人位準錨點（dBFS）：本場次所有位準的基準（= 純音平均閾值換算）。
        // 沒有它，事後無法重建「測試者實際聽到的絕對呈現位準」。
        const val COLUMN_NAME_LEVEL_ANCHOR_DBFS = "level_anchor_dbfs"
        // DSP 補償是否開啟（0/1）。A/B 條件原本只能經 ssn_ab_sessions 反查，
        // 場次中斷未配對時便無從判別；此欄讓每場次自帶條件標記。
        const val COLUMN_NAME_DSP_ON = "dsp_on"
        // 實際套用的 16 段處方增益（dB，逗號分隔；dsp_on=0 時為 NULL）。
        const val COLUMN_NAME_DSP_GAINS_DB = "dsp_gains_db"
        // 噪音模式的固定混音總位準（dBFS；SL 模式為 NULL）。
        const val COLUMN_NAME_TOTAL_LEVEL_DBFS = "total_level_dbfs"
        // A/B 互斥詞表分半（0/1；-1 = 整個詞庫）——驗證兩階段詞表不重疊。
        const val COLUMN_NAME_WORD_PARITY = "word_parity"
        // ── v13：資料來源標記 ─────────────────────────────────────────────
        // 一般模式與「測試者實驗流程」共用同一張表，過去無法區分，導致一般模式
        // 的「查看歷史紀錄」會混入受試者測驗流程的資料。此欄由 SSNAbTestActivity
        // （唯一會設定 EXTRA_AB_MODE=true 的呼叫端）寫入；一般模式測驗不寫此欄，
        // 維持 NULL。
        const val COLUMN_NAME_SESSION_SOURCE = "session_source"
        const val SOURCE_SUBJECT = "subject"
    }

    object SSNRecordEntry : BaseColumns {
        const val TABLE_NAME = "ssn_test_records"
        const val COLUMN_NAME_SESSION_ID_FK = "session_id_fk"
        const val COLUMN_NAME_SNR_DB = "snr_db"
        const val COLUMN_NAME_QUESTION_NUMBER = "question_number"
        const val COLUMN_NAME_CORRECT_WORD = "correct_word"
        const val COLUMN_NAME_USER_ANSWER = "user_answer"
        const val COLUMN_NAME_WAS_CORRECT = "was_correct"
        // 削波防護正規化增益（dB，≤0；0 = 未觸發）。非 0 代表該 trial 的
        // 絕對呈現級別相對其他 trial 額外降低，學術報告必須揭露。
        const val COLUMN_NAME_NORM_GAIN_DB = "norm_gain_db"
    }

    const val SQL_CREATE_SSN_SESSIONS_TABLE =
        "CREATE TABLE IF NOT EXISTS " + SSNSessionEntry.TABLE_NAME + " (" +
                SSNSessionEntry.COLUMN_NAME_SESSION_ID + " INTEGER PRIMARY KEY," +
                SSNSessionEntry.COLUMN_NAME_TEST_TIMESTAMP + " INTEGER," +
                SSNSessionEntry.COLUMN_NAME_SUBJECT_NAME + " TEXT," +
                SSNSessionEntry.COLUMN_NAME_SNR_LIST + " TEXT," +
                SSNSessionEntry.COLUMN_NAME_QUESTIONS_PER_SNR + " INTEGER," +
                SSNSessionEntry.COLUMN_NAME_SRT50 + " REAL," +
                SSNSessionEntry.COLUMN_NAME_PHONE_VOLUME + " INTEGER," +
                SSNSessionEntry.COLUMN_NAME_EARPHONE_MODEL + " TEXT," +
                SSNSessionEntry.COLUMN_NAME_TEST_MODE + " TEXT," +
                SSNSessionEntry.COLUMN_NAME_NLFC + " INTEGER DEFAULT 0," +
                SSNSessionEntry.COLUMN_NAME_HL_SIM_PROFILE + " TEXT," +
                SSNSessionEntry.COLUMN_NAME_HL_SIM_SMEARING + " INTEGER DEFAULT 0," +
                SSNSessionEntry.COLUMN_NAME_HL_SIM_CHECK_ERR + " REAL," +
                SSNSessionEntry.COLUMN_NAME_LEVEL_ANCHOR_DBFS + " REAL," +
                SSNSessionEntry.COLUMN_NAME_DSP_ON + " INTEGER," +
                SSNSessionEntry.COLUMN_NAME_DSP_GAINS_DB + " TEXT," +
                SSNSessionEntry.COLUMN_NAME_TOTAL_LEVEL_DBFS + " REAL," +
                SSNSessionEntry.COLUMN_NAME_WORD_PARITY + " INTEGER DEFAULT -1," +
                SSNSessionEntry.COLUMN_NAME_SESSION_SOURCE + " TEXT)"

    const val SQL_CREATE_SSN_RECORDS_TABLE =
        "CREATE TABLE IF NOT EXISTS " + SSNRecordEntry.TABLE_NAME + " (" +
                BaseColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                SSNRecordEntry.COLUMN_NAME_SESSION_ID_FK + " INTEGER," +
                SSNRecordEntry.COLUMN_NAME_SNR_DB + " REAL," +
                SSNRecordEntry.COLUMN_NAME_QUESTION_NUMBER + " INTEGER," +
                SSNRecordEntry.COLUMN_NAME_CORRECT_WORD + " TEXT," +
                SSNRecordEntry.COLUMN_NAME_USER_ANSWER + " TEXT," +
                SSNRecordEntry.COLUMN_NAME_WAS_CORRECT + " INTEGER," +
                SSNRecordEntry.COLUMN_NAME_NORM_GAIN_DB + " REAL DEFAULT 0," +
                "FOREIGN KEY(" + SSNRecordEntry.COLUMN_NAME_SESSION_ID_FK + ") REFERENCES " +
                SSNSessionEntry.TABLE_NAME + "(" + SSNSessionEntry.COLUMN_NAME_SESSION_ID + "))"

    // ── 測試者實驗流程：SSN A/B 對照（固定模擬中度聽損處方 vs 無補償）────────
    object AbSessionEntry : BaseColumns {
        const val TABLE_NAME = "ssn_ab_sessions"
        const val COLUMN_NAME_GROUP_ID = "group_id"           // 本次 A/B 對照的唯一 ID
        const val COLUMN_NAME_TEST_TIMESTAMP = "test_timestamp"
        const val COLUMN_NAME_SUBJECT_NAME = "subject_name"
        const val COLUMN_NAME_EARPHONE_MODEL = "earphone_model"
        const val COLUMN_NAME_OFF_FIRST = "off_first"         // 1 = OFF 先測（順序交叉平衡）
        const val COLUMN_NAME_SESSION_ID_OFF = "session_id_off" // FK → SSNSessionEntry
        const val COLUMN_NAME_SESSION_ID_ON = "session_id_on"   // FK → SSNSessionEntry
        const val COLUMN_NAME_SRT50_OFF = "srt50_off"
        const val COLUMN_NAME_SRT50_ON = "srt50_on"
        const val COLUMN_NAME_DELTA_SRT50 = "delta_srt50"     // OFF − ON；正值代表 ON 改善（所需 SNR 更低）
    }

    const val SQL_CREATE_AB_SESSIONS_TABLE =
        "CREATE TABLE IF NOT EXISTS " + AbSessionEntry.TABLE_NAME + " (" +
                AbSessionEntry.COLUMN_NAME_GROUP_ID + " INTEGER PRIMARY KEY," +
                AbSessionEntry.COLUMN_NAME_TEST_TIMESTAMP + " INTEGER," +
                AbSessionEntry.COLUMN_NAME_SUBJECT_NAME + " TEXT," +
                AbSessionEntry.COLUMN_NAME_EARPHONE_MODEL + " TEXT," +
                AbSessionEntry.COLUMN_NAME_OFF_FIRST + " INTEGER," +
                AbSessionEntry.COLUMN_NAME_SESSION_ID_OFF + " INTEGER," +
                AbSessionEntry.COLUMN_NAME_SESSION_ID_ON + " INTEGER," +
                AbSessionEntry.COLUMN_NAME_SRT50_OFF + " REAL," +
                AbSessionEntry.COLUMN_NAME_SRT50_ON + " REAL," +
                AbSessionEntry.COLUMN_NAME_DELTA_SRT50 + " REAL)"

    // ── 環境輔聽問卷（測試者實驗流程最後一步）────────────────────────────
    object QuestionnaireEntry : BaseColumns {
        const val TABLE_NAME = "questionnaire_responses"
        const val COLUMN_NAME_SESSION_ID = "session_id"       // 本次問卷所屬測試者流程 ID
        const val COLUMN_NAME_TEST_TIMESTAMP = "test_timestamp"
        const val COLUMN_NAME_SUBJECT_NAME = "subject_name"
        const val COLUMN_NAME_SCENE_KEY = "scene_key"         // 情境代碼；整體題填 "OVERALL"
        const val COLUMN_NAME_CONDITION = "condition"         // "OFF" / "ON" / "NA"（整體題）
        const val COLUMN_NAME_CLARITY = "clarity"             // 1-5，可為 NULL（情境不適用）
        const val COLUMN_NAME_COMFORT = "comfort"              // 1-5
        const val COLUMN_NAME_NOISE_INTERFERENCE = "noise_interference" // 1-5（分數越高＝越不受干擾）
        const val COLUMN_NAME_DELAY_FEEL = "delay_feel"        // 1-5，整體題
        const val COLUMN_NAME_ARTIFACT_FLAG = "artifact_flag"  // 0/1，整體題：是否聽到異音
        const val COLUMN_NAME_ARTIFACT_NOTE = "artifact_note"  // 文字說明
        const val COLUMN_NAME_NATURALNESS = "naturalness"      // 1-5，整體題
        const val COLUMN_NAME_SATISFACTION = "satisfaction"    // 1-5，整體題
        const val COLUMN_NAME_WILLINGNESS = "willingness"      // 1-5，整體題
        const val COLUMN_NAME_FREE_TEXT = "free_text"
        const val COLUMN_NAME_EARPHONE_MODEL = "earphone_model" // 同一測試者測多副耳機時區分輪次
    }

    const val SQL_CREATE_QUESTIONNAIRE_TABLE =
        "CREATE TABLE IF NOT EXISTS " + QuestionnaireEntry.TABLE_NAME + " (" +
                BaseColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                QuestionnaireEntry.COLUMN_NAME_SESSION_ID + " INTEGER," +
                QuestionnaireEntry.COLUMN_NAME_TEST_TIMESTAMP + " INTEGER," +
                QuestionnaireEntry.COLUMN_NAME_SUBJECT_NAME + " TEXT," +
                QuestionnaireEntry.COLUMN_NAME_SCENE_KEY + " TEXT," +
                QuestionnaireEntry.COLUMN_NAME_CONDITION + " TEXT," +
                QuestionnaireEntry.COLUMN_NAME_CLARITY + " INTEGER," +
                QuestionnaireEntry.COLUMN_NAME_COMFORT + " INTEGER," +
                QuestionnaireEntry.COLUMN_NAME_NOISE_INTERFERENCE + " INTEGER," +
                QuestionnaireEntry.COLUMN_NAME_DELAY_FEEL + " INTEGER," +
                QuestionnaireEntry.COLUMN_NAME_ARTIFACT_FLAG + " INTEGER," +
                QuestionnaireEntry.COLUMN_NAME_ARTIFACT_NOTE + " TEXT," +
                QuestionnaireEntry.COLUMN_NAME_NATURALNESS + " INTEGER," +
                QuestionnaireEntry.COLUMN_NAME_SATISFACTION + " INTEGER," +
                QuestionnaireEntry.COLUMN_NAME_WILLINGNESS + " INTEGER," +
                QuestionnaireEntry.COLUMN_NAME_FREE_TEXT + " TEXT," +
                QuestionnaireEntry.COLUMN_NAME_EARPHONE_MODEL + " TEXT)"

    // SQL statements for deleting tables (optional, for upgrades)
    const val SQL_DELETE_TEST_SESSIONS_TABLE = "DROP TABLE IF EXISTS " + TestSessionEntry.TABLE_NAME
    const val SQL_DELETE_SRT_RECORDS_TABLE = "DROP TABLE IF EXISTS " + SRTRecordEntry.TABLE_NAME
}