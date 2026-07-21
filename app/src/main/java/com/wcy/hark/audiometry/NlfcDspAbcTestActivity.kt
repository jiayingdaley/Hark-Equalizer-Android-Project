package com.wcy.hark.audiometry

import android.content.ContentValues
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.wcy.hark.R
import com.wcy.hark.audiometry.sqlite.SRTResultContract
import com.wcy.hark.audiometry.sqlite.SRTResultDbHelper

/**
 * NlfcDspAbcTestActivity — 步驟⑥ NLFC/DSP 效益驗證（測試者實驗流程專用）。
 *
 * 三個固定條件，皆套用與④相同的模擬聽損、皆固定同一個呈現位準（dB SL），
 * 依序 A→B→C（只做使用者勾選的子集，用於補測缺漏的單一格）：
 *   A：純聽損（不移頻、不補償）——基準線
 *   B：聽損 + NLFC（僅移頻）
 *   C：聽損 + NLFC + Hark 完整 DSP（處方增益／WDRC）
 *
 * 三格輸出皆為固定位準下的整體正確率（%），不是 SL50 內插——單一位準本來就
 * 無法內插 50% 交叉點。B−A＝NLFC 本身的貢獻；C−B＝疊加 Hark 完整 DSP 後的
 * 額外貢獻。
 *
 * 底層仍各自呼叫 SSNTestActivity（EXTRA_AB_MODE=true），沿用其既有的 nlfc/dsp_on
 * 欄位與聽損模擬邏輯；本 Activity 只負責跑三格、把結果彙總進
 * nlfc_dsp_abc_sessions。詞表沿用既有全詞庫（word_parity=-1），與④的互斥分半
 * 不衝突（④已各自消耗奇偶半，本步驟允許同詞重複，經使用者確認）。
 */
class NlfcDspAbcTestActivity : AppCompatActivity() {

    private var subjectName = "未填寫"
    private var earphoneModel = "其他"
    private var levelSlDb = 30f
    private var questionsPerCondition = 10
    private lateinit var conditionsToRun: List<String>   // 子集 of ["A","B","C"]，依序執行

    private var sessionIdA: Long? = null
    private var sessionIdB: Long? = null
    private var sessionIdC: Long? = null
    private var accuracyA: Float? = null
    private var accuracyB: Float? = null
    private var accuracyC: Float? = null

    private var runIndex = 0
    private val groupId = System.currentTimeMillis()

    private lateinit var textStatus: TextView
    private lateinit var textResult: TextView
    private lateinit var buttonNext: Button

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val condition = data?.getStringExtra("EXTRA_AB_CONDITION")
            val sid = data?.getLongExtra("EXTRA_SESSION_ID", -1L)?.takeIf { it > 0 }
            val acc = data?.getFloatExtra("EXTRA_ACCURACY", Float.NaN)?.takeIf { !it.isNaN() }
            val endedEarly = data?.getBooleanExtra("EXTRA_ENDED_EARLY", false) ?: false
            when (condition) {
                "A" -> { sessionIdA = sid; accuracyA = acc }
                "B" -> { sessionIdB = sid; accuracyB = acc }
                "C" -> { sessionIdC = sid; accuracyC = acc }
            }
            runIndex++
            // 使用者是在單一格內按「提早結束」離開，而不是自然做完——不要預設
            // 直接接下一格（原本的行為逼得使用者得連按 3 次才能真正退出整個⑥）。
            // 這裡明確問一次：要繼續下一格，還是到此為止。
            if (endedEarly && runIndex < conditionsToRun.size) {
                askContinueOrStop()
            } else {
                runNextOrFinish()
            }
        } else {
            // 中途取消：已完成的格子仍會存檔，取消只影響尚未進行的格子
            finishFlow(cancelled = true)
        }
    }

    private fun askContinueOrStop() {
        AlertDialog.Builder(this)
            .setTitle("已提早結束這一格")
            .setMessage("要繼續下一格，還是到此為止（已完成的格子會保留）？")
            .setPositiveButton("繼續下一格") { _, _ -> runNextOrFinish() }
            .setNegativeButton("到此為止") { _, _ -> saveAbcSession(); showResult(); finishFlow(cancelled = false) }
            .setCancelable(false)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ssn_ab_test)
        window.statusBarColor = android.graphics.Color.parseColor("#F5F7FA")
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        subjectName = intent.getStringExtra("EXTRA_SUBJECT") ?: "未填寫"
        earphoneModel = intent.getStringExtra("EXTRA_EARPHONE_MODEL") ?: "其他"
        levelSlDb = intent.getFloatExtra("EXTRA_LEVEL_SL_DB", 30f)
        questionsPerCondition = intent.getIntExtra("EXTRA_QUESTIONS_PER_CONDITION", 10)
        val requested = intent.getStringArrayExtra("EXTRA_CONDITIONS")?.toList() ?: listOf("A", "B", "C")
        conditionsToRun = listOf("A", "B", "C").filter { it in requested }

        findViewById<TextView>(R.id.textAbTitle).text = "⑥ NLFC/DSP 效益驗證"
        findViewById<TextView>(R.id.textAbBody).text =
            "接下來測驗 ${conditionsToRun.size} 輪中文語詞辨識，固定同一音量（%.0f dB SL），".format(levelSlDb) +
            "皆套用相同的模擬聽損。依序為：" +
            conditionsToRun.joinToString("、") {
                when (it) {
                    "A" -> "A（純聽損，無處理）"
                    "B" -> "B（聽損＋移頻 NLFC）"
                    else -> "C（聽損＋移頻＋Hark 完整補償）"
                }
            } + "。"
        textStatus = findViewById(R.id.textAbStatus)
        textResult = findViewById(R.id.textAbResult)
        buttonNext = findViewById(R.id.buttonAbNext)
        buttonNext.text = "開始"
        buttonNext.setOnClickListener { runNextOrFinish() }
        findViewById<android.view.View>(R.id.buttonAbBack).setOnClickListener { confirmExitEarly() }
        updateStatusText()
    }

    private fun updateStatusText() {
        textStatus.text = if (runIndex < conditionsToRun.size)
            "第 ${runIndex + 1}/${conditionsToRun.size} 輪：條件 ${conditionsToRun[runIndex]} 進行中…"
        else "已完成"
    }

    private fun runNextOrFinish() {
        updateStatusText()
        if (runIndex >= conditionsToRun.size) {
            saveAbcSession()
            showResult()
            finishFlow(cancelled = false)
            return
        }
        buttonNext.isEnabled = false
        launchCondition(conditionsToRun[runIndex])
    }

    private fun launchCondition(condition: String) {
        val nlfc = condition == "B" || condition == "C"
        val applyDsp = condition == "C"
        launcher.launch(Intent(this, SSNTestActivity::class.java).apply {
            putExtra("EXTRA_APPLY_DSP", applyDsp)
            putExtra("EXTRA_SUBJECT", subjectName)
            putExtra("EXTRA_EARPHONE_MODEL", earphoneModel)
            putExtra("EXTRA_NOISELESS", true)
            putExtra("EXTRA_SNRS", floatArrayOf(levelSlDb))
            putExtra("EXTRA_QUESTIONS_PER_SNR", questionsPerCondition)
            putExtra("EXTRA_AB_MODE", true)
            putExtra("EXTRA_AB_CONDITION", condition)
            putExtra("EXTRA_NLFC", nlfc)
            // 詞表沿用全詞庫（-1），使用者已確認允許三格間重複用詞
            putExtra("EXTRA_WORD_PARITY", -1)
            putExtra("EXTRA_HL_SIM_CHECK_ERR", intent.getFloatExtra("EXTRA_HL_SIM_CHECK_ERR", Float.NaN))
            putExtra("EXTRA_HLSIM_MEASURED_DBFS", intent.getStringExtra("EXTRA_HLSIM_MEASURED_DBFS") ?: "")
            putExtra("EXTRA_HLSIM_TARGET_DB", intent.getStringExtra("EXTRA_HLSIM_TARGET_DB") ?: "")
            putExtra("EXTRA_HLSIM_ERROR_DB", intent.getStringExtra("EXTRA_HLSIM_ERROR_DB") ?: "")
        })
    }

    private fun showResult() {
        textResult.visibility = android.view.View.VISIBLE
        fun fmt(v: Float?) = v?.let { "%.0f%%".format(it) } ?: "—"
        val deltaBa = if (accuracyB != null && accuracyA != null) accuracyB!! - accuracyA!! else null
        val deltaCb = if (accuracyC != null && accuracyB != null) accuracyC!! - accuracyB!! else null
        textResult.text = "A（純聽損）：${fmt(accuracyA)}\n" +
                "B（聽損＋NLFC）：${fmt(accuracyB)}" + (deltaBa?.let { "（NLFC 貢獻 %+.0f%%）".format(it) } ?: "") + "\n" +
                "C（聽損＋NLFC＋DSP）：${fmt(accuracyC)}" + (deltaCb?.let { "（疊加 DSP 額外貢獻 %+.0f%%）".format(it) } ?: "")
    }

    /** 存檔採部分更新：只覆寫本次實際跑過的條件，先前已完成的其他格保留。 */
    private fun saveAbcSession() {
        val db = SRTResultDbHelper(this).writableDatabase
        val existing = db.query(
            SRTResultContract.AbcSessionEntry.TABLE_NAME,
            arrayOf(
                SRTResultContract.AbcSessionEntry.COLUMN_NAME_GROUP_ID,
                SRTResultContract.AbcSessionEntry.COLUMN_NAME_SESSION_ID_A,
                SRTResultContract.AbcSessionEntry.COLUMN_NAME_SESSION_ID_B,
                SRTResultContract.AbcSessionEntry.COLUMN_NAME_SESSION_ID_C,
                SRTResultContract.AbcSessionEntry.COLUMN_NAME_ACCURACY_A,
                SRTResultContract.AbcSessionEntry.COLUMN_NAME_ACCURACY_B,
                SRTResultContract.AbcSessionEntry.COLUMN_NAME_ACCURACY_C
            ),
            "${SRTResultContract.AbcSessionEntry.COLUMN_NAME_SUBJECT_NAME} = ?",
            arrayOf(subjectName), null, null,
            "${SRTResultContract.AbcSessionEntry.COLUMN_NAME_TEST_TIMESTAMP} DESC", "1"
        )
        var existingGroupId: Long? = null
        var keepSidA: Long? = null; var keepSidB: Long? = null; var keepSidC: Long? = null
        var keepAccA: Float? = null; var keepAccB: Float? = null; var keepAccC: Float? = null
        existing.use { c ->
            if (c.moveToFirst()) {
                existingGroupId = c.getLong(0)
                fun idx(i: Int) = if (c.isNull(i)) null else c.getLong(i)
                fun fidx(i: Int) = if (c.isNull(i)) null else c.getFloat(i)
                keepSidA = idx(1); keepSidB = idx(2); keepSidC = idx(3)
                keepAccA = fidx(4); keepAccB = fidx(5); keepAccC = fidx(6)
            }
        }
        val finalSidA = sessionIdA ?: keepSidA
        val finalSidB = sessionIdB ?: keepSidB
        val finalSidC = sessionIdC ?: keepSidC
        val finalAccA = accuracyA ?: keepAccA
        val finalAccB = accuracyB ?: keepAccB
        val finalAccC = accuracyC ?: keepAccC

        val values = ContentValues().apply {
            put(SRTResultContract.AbcSessionEntry.COLUMN_NAME_GROUP_ID, existingGroupId ?: groupId)
            put(SRTResultContract.AbcSessionEntry.COLUMN_NAME_TEST_TIMESTAMP, System.currentTimeMillis())
            put(SRTResultContract.AbcSessionEntry.COLUMN_NAME_SUBJECT_NAME, subjectName)
            put(SRTResultContract.AbcSessionEntry.COLUMN_NAME_EARPHONE_MODEL, earphoneModel)
            put(SRTResultContract.AbcSessionEntry.COLUMN_NAME_LEVEL_SL_DB, levelSlDb)
            finalSidA?.let { put(SRTResultContract.AbcSessionEntry.COLUMN_NAME_SESSION_ID_A, it) }
            finalSidB?.let { put(SRTResultContract.AbcSessionEntry.COLUMN_NAME_SESSION_ID_B, it) }
            finalSidC?.let { put(SRTResultContract.AbcSessionEntry.COLUMN_NAME_SESSION_ID_C, it) }
            finalAccA?.let { put(SRTResultContract.AbcSessionEntry.COLUMN_NAME_ACCURACY_A, it) }
            finalAccB?.let { put(SRTResultContract.AbcSessionEntry.COLUMN_NAME_ACCURACY_B, it) }
            finalAccC?.let { put(SRTResultContract.AbcSessionEntry.COLUMN_NAME_ACCURACY_C, it) }
        }
        try {
            if (existingGroupId != null) {
                db.update(
                    SRTResultContract.AbcSessionEntry.TABLE_NAME, values,
                    "${SRTResultContract.AbcSessionEntry.COLUMN_NAME_GROUP_ID} = ?",
                    arrayOf(existingGroupId.toString())
                )
            } else {
                db.insert(SRTResultContract.AbcSessionEntry.TABLE_NAME, null, values)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun confirmExitEarly() {
        AlertDialog.Builder(this)
            .setTitle("提早結束")
            .setMessage("確定要結束嗎？已完成的格子會保留，尚未進行的格子不會有結果。")
            .setPositiveButton("結束") { _, _ -> finishFlow(cancelled = true) }
            .setNegativeButton("繼續測驗", null)
            .show()
    }

    private fun finishFlow(cancelled: Boolean) {
        if (!cancelled) {
            setResult(RESULT_OK)
        } else {
            if (sessionIdA != null || sessionIdB != null || sessionIdC != null) saveAbcSession()
            setResult(RESULT_CANCELED)
        }
        if (cancelled) { finish(); return }
        buttonNext.text = "完成"
        buttonNext.isEnabled = true
        buttonNext.setOnClickListener { finish() }
    }
}
