package com.wcy.hark.audiometry.sqlite

// Data class for an individual question's record
data class SRTTestRecord(
    val recordId: Long? = null, // Nullable if not yet inserted (for _ID from BaseColumns)
    val sessionIdFk: Long,
    val questionNumber: Int,
    val correctWord: String,
    val userAnswer: String,
    val wasCorrect: Boolean
)

// Data class for an overall test session
data class SRTSession(
    val sessionId: Long, // Typically System.currentTimeMillis() at the start of the test
    val testTimestamp: Long, // Typically System.currentTimeMillis() at the end of the test
    val overallAccuracy: Double,
    val totalQuestionsAnswered: Int
)