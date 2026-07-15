package com.wcy.hark.audiometry

import android.content.Context
import android.content.Intent
import android.database.Cursor
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
 * SubjectExportUtil — 打包單一測試者的所有測驗資料（純音 CSV、SSN/SRT/AB
 * SQLite 紀錄、問卷回應）成一個 zip，供匯出到統計軟體分析。
 *
 * 純音 CSV 以檔案內容的「Subject Name,」列比對篩選（而非檔名，因為部分
 * 測驗模式的檔名不含測試者名稱）；SQLite 各表則直接以 subject_name 篩選
 * 匯出成獨立 CSV 放入同一個 zip。
 */
object SubjectExportUtil {

    fun exportSubjectData(context: Context, subjectName: String): android.net.Uri? {
        val exportDir = File(context.getExternalFilesDir(null), "subject_exports").apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val safeName = subjectName.ifBlank { "未填寫" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val zipFile = File(exportDir, "${safeName}_$timestamp.zip")

        ZipOutputStream(zipFile.outputStream()).use { zip ->
            // 1) 純音測驗 CSV（含標準版與自調式）
            context.getExternalFilesDir(null)?.listFiles { f ->
                f.isFile && f.name.contains("PureTone") && f.name.endsWith(".csv")
            }?.forEach { csv ->
                val matches = try {
                    csv.bufferedReader().use { it.readLine()?.contains("Subject Name,$subjectName") == true }
                } catch (e: Exception) { false }
                if (matches) {
                    zip.putNextEntry(ZipEntry("pure_tone/${csv.name}"))
                    csv.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }

            // 2) SQLite 各表篩選匯出
            val db = SRTResultDbHelper(context).readableDatabase
            writeQueryAsCsv(zip, "srt_word_test.csv", db.rawQuery(
                "SELECT s.*, r.question_number, r.correct_word, r.user_answer, r.was_correct " +
                "FROM ${SRTResultContract.TestSessionEntry.TABLE_NAME} s " +
                "LEFT JOIN ${SRTResultContract.SRTRecordEntry.TABLE_NAME} r " +
                "ON s.${SRTResultContract.TestSessionEntry.COLUMN_NAME_SESSION_ID} = r.${SRTResultContract.SRTRecordEntry.COLUMN_NAME_SESSION_ID_FK} " +
                "WHERE s.${SRTResultContract.TestSessionEntry.COLUMN_NAME_SUBJECT_NAME} = ?",
                arrayOf(subjectName)
            ))
            writeQueryAsCsv(zip, "ssn_test.csv", db.rawQuery(
                "SELECT s.*, r.snr_db, r.question_number, r.correct_word, r.user_answer, r.was_correct, r.norm_gain_db " +
                "FROM ${SRTResultContract.SSNSessionEntry.TABLE_NAME} s " +
                "LEFT JOIN ${SRTResultContract.SSNRecordEntry.TABLE_NAME} r " +
                "ON s.${SRTResultContract.SSNSessionEntry.COLUMN_NAME_SESSION_ID} = r.${SRTResultContract.SSNRecordEntry.COLUMN_NAME_SESSION_ID_FK} " +
                "WHERE s.${SRTResultContract.SSNSessionEntry.COLUMN_NAME_SUBJECT_NAME} = ?",
                arrayOf(subjectName)
            ))
            writeQueryAsCsv(zip, "ssn_ab_comparison.csv", db.rawQuery(
                "SELECT * FROM ${SRTResultContract.AbSessionEntry.TABLE_NAME} WHERE ${SRTResultContract.AbSessionEntry.COLUMN_NAME_SUBJECT_NAME} = ?",
                arrayOf(subjectName)
            ))
            writeQueryAsCsv(zip, "questionnaire.csv", db.rawQuery(
                "SELECT * FROM ${SRTResultContract.QuestionnaireEntry.TABLE_NAME} WHERE ${SRTResultContract.QuestionnaireEntry.COLUMN_NAME_SUBJECT_NAME} = ?",
                arrayOf(subjectName)
            ))
        }

        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zipFile)
    }

    private fun writeQueryAsCsv(zip: ZipOutputStream, entryName: String, cursor: Cursor) {
        zip.putNextEntry(ZipEntry(entryName))
        cursor.use { c ->
            val cols = c.columnNames
            val sb = StringBuilder()
            sb.append(cols.joinToString(",")).append("\n")
            while (c.moveToNext()) {
                sb.append(cols.indices.joinToString(",") { i ->
                    val v = c.getString(i) ?: ""
                    "\"${v.replace("\"", "\"\"")}\""
                }).append("\n")
            }
            zip.write(sb.toString().toByteArray())
        }
        zip.closeEntry()
    }

    /**
     * 刪除單一測試者的所有測驗資料（純音 CSV + 四個 SQLite 表）。
     * 用於測試者實驗歷史頁的「刪除」——測錯、測試資料或測試者要求撤回時
     * 清除，避免污染分析資料集。子表（逐題紀錄）需先刪，再刪對應的
     * session 列，順序顛倒會留下孤兒紀錄。此操作不可復原。
     */
    fun deleteSubjectData(context: Context, subjectName: String) {
        val db = SRTResultDbHelper(context).writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "DELETE FROM ${SRTResultContract.SRTRecordEntry.TABLE_NAME} WHERE " +
                "${SRTResultContract.SRTRecordEntry.COLUMN_NAME_SESSION_ID_FK} IN (" +
                "SELECT ${SRTResultContract.TestSessionEntry.COLUMN_NAME_SESSION_ID} FROM ${SRTResultContract.TestSessionEntry.TABLE_NAME} " +
                "WHERE ${SRTResultContract.TestSessionEntry.COLUMN_NAME_SUBJECT_NAME} = ?)",
                arrayOf(subjectName)
            )
            db.delete(
                SRTResultContract.TestSessionEntry.TABLE_NAME,
                "${SRTResultContract.TestSessionEntry.COLUMN_NAME_SUBJECT_NAME} = ?",
                arrayOf(subjectName)
            )

            db.execSQL(
                "DELETE FROM ${SRTResultContract.SSNRecordEntry.TABLE_NAME} WHERE " +
                "${SRTResultContract.SSNRecordEntry.COLUMN_NAME_SESSION_ID_FK} IN (" +
                "SELECT ${SRTResultContract.SSNSessionEntry.COLUMN_NAME_SESSION_ID} FROM ${SRTResultContract.SSNSessionEntry.TABLE_NAME} " +
                "WHERE ${SRTResultContract.SSNSessionEntry.COLUMN_NAME_SUBJECT_NAME} = ?)",
                arrayOf(subjectName)
            )
            db.delete(
                SRTResultContract.SSNSessionEntry.TABLE_NAME,
                "${SRTResultContract.SSNSessionEntry.COLUMN_NAME_SUBJECT_NAME} = ?",
                arrayOf(subjectName)
            )

            db.delete(
                SRTResultContract.AbSessionEntry.TABLE_NAME,
                "${SRTResultContract.AbSessionEntry.COLUMN_NAME_SUBJECT_NAME} = ?",
                arrayOf(subjectName)
            )
            db.delete(
                SRTResultContract.QuestionnaireEntry.TABLE_NAME,
                "${SRTResultContract.QuestionnaireEntry.COLUMN_NAME_SUBJECT_NAME} = ?",
                arrayOf(subjectName)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        // 純音 CSV：比對規則與匯出一致（依內容而非檔名）
        context.getExternalFilesDir(null)?.listFiles { f ->
            f.isFile && f.name.contains("PureTone") && f.name.endsWith(".csv")
        }?.forEach { csv ->
            val matches = try {
                csv.bufferedReader().use { it.readLine()?.contains("Subject Name,$subjectName") == true }
            } catch (e: Exception) { false }
            if (matches) csv.delete()
        }
    }

    fun shareIntent(context: Context, uri: android.net.Uri): Intent {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(intent, "匯出測試者資料")
    }
}
