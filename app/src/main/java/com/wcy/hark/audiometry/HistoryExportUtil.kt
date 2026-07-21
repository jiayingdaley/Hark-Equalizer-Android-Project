package com.wcy.hark.audiometry

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import androidx.core.content.FileProvider
import com.wcy.hark.audiometry.sqlite.SRTResultContract
import com.wcy.hark.audiometry.sqlite.SRTResultDbHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * HistoryExportUtil — 「聽力檢測 → 測試歷史」各分頁的一鍵分享。
 *
 * 語詞辨識(SRT)、噪音語詞(SSN) 只存在 app 內部 SQLite（USB 看不到），此處各匯成
 * 一個 CSV；純音聽力本來就是 CSV 檔，打包成 zip。全部寫入
 * getExternalFilesDir(null)/history_exports/ 後以 FileProvider 分享——分享選單可送
 * Google Drive／Gmail，或選「儲存到檔案 → 下載」讓 USB 直接取用。
 *
 * 匯出「全部場次」（不依測試者姓名篩選），供從「聽力檢測」單獨做測驗的使用者
 * 取回自己的資料（測試者實驗流程另有 SubjectExportUtil 依姓名打包）。
 */
object HistoryExportUtil {

    private fun exportDir(context: Context) =
        File(context.getExternalFilesDir(null), "history_exports").apply { mkdirs() }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

    private fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** Cursor → CSV（表頭取欄名，值一律加雙引號並跳脫，與 SubjectExportUtil 一致）。 */
    private fun writeCsv(file: File, cursor: Cursor) {
        file.bufferedWriter().use { w ->
            cursor.use { c ->
                val cols = c.columnNames
                w.append(cols.joinToString(",")).append("\n")
                while (c.moveToNext()) {
                    w.append(cols.indices.joinToString(",") { i ->
                        val v = c.getString(i) ?: ""
                        "\"${v.replace("\"", "\"\"")}\""
                    }).append("\n")
                }
            }
        }
    }

    private fun hasRows(db: android.database.sqlite.SQLiteDatabase, table: String): Boolean =
        db.rawQuery("SELECT 1 FROM $table LIMIT 1", null).use { it.moveToFirst() }

    /** 語詞辨識（SRT）全部場次逐題 → CSV。無資料回 null。 */
    fun exportSrt(context: Context): Uri? {
        val db = SRTResultDbHelper(context).readableDatabase
        if (!hasRows(db, SRTResultContract.TestSessionEntry.TABLE_NAME)) return null
        val file = File(exportDir(context), "Hark_語詞辨識_${stamp()}.csv")
        writeCsv(file, db.rawQuery(
            "SELECT s.*, r.question_number, r.correct_word, r.user_answer, r.was_correct " +
            "FROM ${SRTResultContract.TestSessionEntry.TABLE_NAME} s " +
            "LEFT JOIN ${SRTResultContract.SRTRecordEntry.TABLE_NAME} r " +
            "ON s.${SRTResultContract.TestSessionEntry.COLUMN_NAME_SESSION_ID} = " +
            "r.${SRTResultContract.SRTRecordEntry.COLUMN_NAME_SESSION_ID_FK} " +
            "ORDER BY s.${SRTResultContract.TestSessionEntry.COLUMN_NAME_SESSION_ID} DESC, r.question_number",
            null
        ))
        return uriFor(context, file)
    }

    /** 噪音語詞（SSN）全部場次逐題 → CSV。無資料回 null。 */
    fun exportSsn(context: Context): Uri? {
        val db = SRTResultDbHelper(context).readableDatabase
        if (!hasRows(db, SRTResultContract.SSNSessionEntry.TABLE_NAME)) return null
        val file = File(exportDir(context), "Hark_噪音語詞_${stamp()}.csv")
        writeCsv(file, db.rawQuery(
            "SELECT s.*, r.snr_db, r.question_number, r.correct_word, r.user_answer, r.was_correct, r.norm_gain_db " +
            "FROM ${SRTResultContract.SSNSessionEntry.TABLE_NAME} s " +
            "LEFT JOIN ${SRTResultContract.SSNRecordEntry.TABLE_NAME} r " +
            "ON s.${SRTResultContract.SSNSessionEntry.COLUMN_NAME_SESSION_ID} = " +
            "r.${SRTResultContract.SSNRecordEntry.COLUMN_NAME_SESSION_ID_FK} " +
            "ORDER BY s.${SRTResultContract.SSNSessionEntry.COLUMN_NAME_SESSION_ID} DESC, r.question_number",
            null
        ))
        return uriFor(context, file)
    }

    /** 純音聽力：打包所有 PureTone CSV 成 zip。無檔案回 null。 */
    fun exportPureTone(context: Context): Uri? {
        val csvs = context.getExternalFilesDir(null)?.listFiles { f ->
            f.isFile && f.name.contains("PureTone") && f.name.endsWith(".csv")
        }?.sortedByDescending { it.name } ?: emptyList()
        if (csvs.isEmpty()) return null
        val zipFile = File(exportDir(context), "Hark_純音聽力_${stamp()}.zip")
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            csvs.forEach { csv ->
                zip.putNextEntry(ZipEntry(csv.name))
                csv.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return uriFor(context, zipFile)
    }

    fun shareIntent(context: Context, uri: Uri, mime: String): Intent {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(intent, "分享測試資料")
    }
}
