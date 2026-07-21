package com.wcy.hark.audiometry

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wcy.hark.R
import com.wcy.hark.audiometry.sqlite.SRTResultContract
import com.wcy.hark.audiometry.sqlite.SRTResultDbHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SubjectTestHistoryActivity — 測試者實驗歷史。
 *
 * 以測試者為單位彙整「測試者實驗流程」產生的所有結果，供在 app 內直接
 * 查看與匯出：
 *  - 快速純音（自調式）與標準純音 CSV（以檔案內容的 Subject Name 列比對）
 *  - 噪音下語詞 A/B 對照（ssn_ab_sessions：SRT50 OFF/ON 與 ΔSRT50）
 *  - 環境輔聽問卷（questionnaire_responses 的 OVERALL 列：滿意度等）
 * 每位測試者卡片附「匯出」按鈕，沿用 SubjectExportUtil 打包 zip 分享。
 */
class SubjectTestHistoryActivity : AppCompatActivity() {

    private data class AbRow(val ts: Long, val earphone: String?, val off: Float?, val on: Float?, val delta: Float?)
    private data class QRow(val ts: Long, val earphone: String?, val satisfaction: Int?, val willingness: Int?)
    private data class PtaRow(val fileName: String, val mode: String, val earphone: String?)
    private data class AbcRow(
        val ts: Long, val earphone: String?, val levelSlDb: Float?,
        val accA: Float?, val accB: Float?, val accC: Float?
    )
    private data class SubjectData(
        val name: String,
        val abRows: MutableList<AbRow> = mutableListOf(),
        val qRows: MutableList<QRow> = mutableListOf(),
        val ptaRows: MutableList<PtaRow> = mutableListOf(),
        val abcRows: MutableList<AbcRow> = mutableListOf()
    )

    private val dateFmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subject_history)
        window.statusBarColor = Color.parseColor("#F5F7FA")
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        findViewById<android.view.View>(R.id.buttonHistoryBack).setOnClickListener { finish() }

        reload()
    }

    private fun reload() {
        findViewById<LinearLayout>(R.id.layoutHistoryContainer).removeAllViews()
        findViewById<TextView>(R.id.textHistoryEmpty).visibility = android.view.View.GONE
        lifecycleScope.launch {
            val subjects = withContext(Dispatchers.IO) { loadAllSubjects() }
            renderSubjects(subjects)
        }
    }

    private fun loadAllSubjects(): List<SubjectData> {
        val map = linkedMapOf<String, SubjectData>()
        fun of(name: String) = map.getOrPut(name) { SubjectData(name) }

        val db = SRTResultDbHelper(this).readableDatabase

        // A/B 對照
        try {
            db.rawQuery(
                "SELECT ${SRTResultContract.AbSessionEntry.COLUMN_NAME_SUBJECT_NAME}, " +
                "${SRTResultContract.AbSessionEntry.COLUMN_NAME_TEST_TIMESTAMP}, " +
                "${SRTResultContract.AbSessionEntry.COLUMN_NAME_EARPHONE_MODEL}, " +
                "${SRTResultContract.AbSessionEntry.COLUMN_NAME_SRT50_OFF}, " +
                "${SRTResultContract.AbSessionEntry.COLUMN_NAME_SRT50_ON}, " +
                "${SRTResultContract.AbSessionEntry.COLUMN_NAME_DELTA_SRT50} " +
                "FROM ${SRTResultContract.AbSessionEntry.TABLE_NAME} ORDER BY " +
                SRTResultContract.AbSessionEntry.COLUMN_NAME_TEST_TIMESTAMP + " DESC", null
            ).use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    of(name).abRows.add(AbRow(
                        ts = c.getLong(1),
                        earphone = c.getString(2),
                        off = if (c.isNull(3)) null else c.getFloat(3),
                        on = if (c.isNull(4)) null else c.getFloat(4),
                        delta = if (c.isNull(5)) null else c.getFloat(5)
                    ))
                }
            }
        } catch (e: Exception) { /* 舊資料庫可能無此表 */ }

        // 問卷（OVERALL 列）
        try {
            db.rawQuery(
                "SELECT ${SRTResultContract.QuestionnaireEntry.COLUMN_NAME_SUBJECT_NAME}, " +
                "${SRTResultContract.QuestionnaireEntry.COLUMN_NAME_TEST_TIMESTAMP}, " +
                "${SRTResultContract.QuestionnaireEntry.COLUMN_NAME_EARPHONE_MODEL}, " +
                "${SRTResultContract.QuestionnaireEntry.COLUMN_NAME_SATISFACTION}, " +
                "${SRTResultContract.QuestionnaireEntry.COLUMN_NAME_WILLINGNESS} " +
                "FROM ${SRTResultContract.QuestionnaireEntry.TABLE_NAME} " +
                "WHERE ${SRTResultContract.QuestionnaireEntry.COLUMN_NAME_SCENE_KEY} = 'OVERALL' " +
                "ORDER BY ${SRTResultContract.QuestionnaireEntry.COLUMN_NAME_TEST_TIMESTAMP} DESC", null
            ).use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    of(name).qRows.add(QRow(
                        ts = c.getLong(1),
                        earphone = c.getString(2),
                        satisfaction = if (c.isNull(3)) null else c.getInt(3),
                        willingness = if (c.isNull(4)) null else c.getInt(4)
                    ))
                }
            }
        } catch (e: Exception) { /* 舊資料庫可能無此表 */ }

        // ⑥ NLFC/DSP 效益驗證
        try {
            db.rawQuery(
                "SELECT ${SRTResultContract.AbcSessionEntry.COLUMN_NAME_SUBJECT_NAME}, " +
                "${SRTResultContract.AbcSessionEntry.COLUMN_NAME_TEST_TIMESTAMP}, " +
                "${SRTResultContract.AbcSessionEntry.COLUMN_NAME_EARPHONE_MODEL}, " +
                "${SRTResultContract.AbcSessionEntry.COLUMN_NAME_LEVEL_SL_DB}, " +
                "${SRTResultContract.AbcSessionEntry.COLUMN_NAME_ACCURACY_A}, " +
                "${SRTResultContract.AbcSessionEntry.COLUMN_NAME_ACCURACY_B}, " +
                "${SRTResultContract.AbcSessionEntry.COLUMN_NAME_ACCURACY_C} " +
                "FROM ${SRTResultContract.AbcSessionEntry.TABLE_NAME} ORDER BY " +
                SRTResultContract.AbcSessionEntry.COLUMN_NAME_TEST_TIMESTAMP + " DESC", null
            ).use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    of(name).abcRows.add(AbcRow(
                        ts = c.getLong(1),
                        earphone = c.getString(2),
                        levelSlDb = if (c.isNull(3)) null else c.getFloat(3),
                        accA = if (c.isNull(4)) null else c.getFloat(4),
                        accB = if (c.isNull(5)) null else c.getFloat(5),
                        accC = if (c.isNull(6)) null else c.getFloat(6)
                    ))
                }
            }
        } catch (e: Exception) { /* 舊資料庫可能無此表 */ }

        // 純音 CSV（含標準與自調式）：讀前幾列取 Subject Name / Mode / Earphone
        getExternalFilesDir(null)?.listFiles { f ->
            f.isFile && f.name.contains("PureTone") && f.name.endsWith(".csv")
        }?.sortedByDescending { it.name }?.forEach { csv ->
            try {
                val head = csv.bufferedReader().use { r -> (0 until 4).mapNotNull { r.readLine() } }
                val name = head.firstOrNull { it.startsWith("Subject Name,") }
                    ?.substringAfter("Subject Name,")?.trim() ?: return@forEach
                if (name.isEmpty()) return@forEach
                val mode = if (head.any { it.startsWith("Mode,SelfAdjusted") }) "自調式" else "標準"
                val earphone = head.firstOrNull { it.startsWith("Earphone,") }?.substringAfter("Earphone,")?.trim()
                of(name).ptaRows.add(PtaRow(csv.name, mode, earphone))
            } catch (e: Exception) { /* skip unreadable */ }
        }

        // 最新測過的人排最上面：依這位測試者「任何一筆紀錄」的最新時間戳排序，
        // 而不是原本依表格查詢順序（等於是照④/問卷/⑥哪張表先查到就先出現，
        // 跟實際測驗時間無關，找最近測的人時很不直覺）。
        fun latestTs(s: SubjectData): Long = maxOf(
            s.abRows.maxOfOrNull { it.ts } ?: 0L,
            s.qRows.maxOfOrNull { it.ts } ?: 0L,
            s.abcRows.maxOfOrNull { it.ts } ?: 0L
        )
        return map.values.sortedByDescending { latestTs(it) }
    }

    private fun renderSubjects(subjects: List<SubjectData>) {
        val container = findViewById<LinearLayout>(R.id.layoutHistoryContainer)
        if (subjects.isEmpty()) {
            findViewById<TextView>(R.id.textHistoryEmpty).visibility = android.view.View.VISIBLE
            return
        }
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        subjects.forEach { s ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dp(12)
                layoutParams = lp
            }

            card.addView(TextView(this).apply {
                text = "測試者：${s.name}"
                textSize = 17f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.BLACK)
            })

            fun section(title: String) = card.addView(TextView(this).apply {
                text = title
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#1a56b0"))
                setPadding(0, dp(10), 0, dp(2))
            })
            fun line(text: String) = card.addView(TextView(this).apply {
                this.text = text
                textSize = 13f
                setTextColor(Color.parseColor("#444444"))
            })

            if (s.ptaRows.isNotEmpty()) {
                section("純音測驗（${s.ptaRows.size} 筆 CSV）")
                s.ptaRows.take(6).forEach { p ->
                    line("・${p.fileName.substringBefore("_PureTone")}｜${p.mode}" +
                         (p.earphone?.let { "｜$it" } ?: ""))
                }
                if (s.ptaRows.size > 6) line("…共 ${s.ptaRows.size} 筆，匯出可取得全部")
            }

            if (s.abRows.isNotEmpty()) {
                section("噪音下語詞 A/B 對照（${s.abRows.size} 輪）")
                s.abRows.forEach { r ->
                    val off = r.off?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
                    val on = r.on?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
                    val d = r.delta?.let { String.format(Locale.US, "%+.1f", it) } ?: "—"
                    line("・${dateFmt.format(Date(r.ts))}" +
                         (r.earphone?.let { "｜$it" } ?: "") +
                         "\n　OFF $off / ON $on dB SNR｜Δ $d dB")
                }
            }

            if (s.abcRows.isNotEmpty()) {
                section("⑥ NLFC/DSP 效益驗證（${s.abcRows.size} 輪）")
                s.abcRows.forEach { r ->
                    fun fmt(v: Float?) = v?.let { "%.0f%%".format(it) } ?: "缺"
                    line("・${dateFmt.format(Date(r.ts))}" +
                         (r.earphone?.let { "｜$it" } ?: "") +
                         "｜%.0f dB SL".format(r.levelSlDb ?: 0f) +
                         "\n　A(純聽損) ${fmt(r.accA)}／B(＋NLFC) ${fmt(r.accB)}／C(＋NLFC＋DSP) ${fmt(r.accC)}")
                }
            }

            if (s.qRows.isNotEmpty()) {
                section("環境輔聽問卷（${s.qRows.size} 份）")
                s.qRows.forEach { q ->
                    line("・${dateFmt.format(Date(q.ts))}" +
                         (q.earphone?.let { "｜$it" } ?: "") +
                         "｜滿意度 ${q.satisfaction ?: "—"}/5｜使用意願 ${q.willingness ?: "—"}/5")
                }
            }

            val buttonRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = dp(12)
                layoutParams = lp
            }
            buttonRow.addView(Button(this).apply {
                text = "匯出"
                textSize = 14f
                setBackgroundResource(R.drawable.rounded_button)
                val lp = LinearLayout.LayoutParams(0, dp(48), 1f)
                lp.marginEnd = dp(6)
                layoutParams = lp
                setOnClickListener { exportSubject(s.name) }
            })
            buttonRow.addView(Button(this).apply {
                text = "刪除"
                textSize = 14f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#D32F2F"))
                val lp = LinearLayout.LayoutParams(0, dp(48), 1f)
                lp.marginStart = dp(6)
                layoutParams = lp
                setOnClickListener { confirmDelete(s.name) }
            })
            card.addView(buttonRow)

            container.addView(card)
        }
    }

    private fun confirmDelete(name: String) {
        AlertDialog.Builder(this)
            .setTitle("刪除測試者資料")
            .setMessage("確定要刪除「$name」的所有測驗資料嗎？（純音、A/B 對照、問卷）此操作無法復原，建議先匯出備份。")
            .setPositiveButton("刪除") { _, _ -> deleteSubject(name) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteSubject(name: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { SubjectExportUtil.deleteSubjectData(this@SubjectTestHistoryActivity, name) }
            Toast.makeText(this@SubjectTestHistoryActivity, "已刪除「$name」的資料", Toast.LENGTH_SHORT).show()
            reload()
        }
    }

    private fun exportSubject(name: String) {
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) {
                try { SubjectExportUtil.exportSubjectData(this@SubjectTestHistoryActivity, name) }
                catch (e: Exception) { null }
            }
            if (uri != null) {
                startActivity(SubjectExportUtil.shareIntent(this@SubjectTestHistoryActivity, uri))
            } else {
                Toast.makeText(this@SubjectTestHistoryActivity, "匯出失敗", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
