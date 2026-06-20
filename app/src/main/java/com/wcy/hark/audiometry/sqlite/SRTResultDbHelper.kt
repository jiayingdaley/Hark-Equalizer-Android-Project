package com.wcy.hark.audiometry.sqlite

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SRTResultDbHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_VERSION = 1 // Increment if schema changes
        const val DATABASE_NAME = "SRTResults.db"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SRTResultContract.SQL_CREATE_TEST_SESSIONS_TABLE)
        db.execSQL(SRTResultContract.SQL_CREATE_SRT_RECORDS_TABLE)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // This database is only a cache for online data, so its upgrade policy is
        // to simply to discard the data and start over.
        // You might want a different policy for real user data.
        db.execSQL(SRTResultContract.SQL_DELETE_SRT_RECORDS_TABLE)
        db.execSQL(SRTResultContract.SQL_DELETE_TEST_SESSIONS_TABLE)
        onCreate(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onUpgrade(db, oldVersion, newVersion)
    }
}