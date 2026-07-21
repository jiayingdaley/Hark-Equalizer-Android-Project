package com.wcy.hark.audiometry

import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wcy.hark.HarkApplication
import com.wcy.hark.R
import com.wcy.hark.audio.bridge.HarkAudioBridge
import com.wcy.hark.audio.fitting.Prescriptions
import com.wcy.hark.audio.manager.SystemDspManager
import com.wcy.hark.audiometry.sqlite.SRTResultContract
import com.wcy.hark.audiometry.sqlite.SRTResultDbHelper
import com.wcy.hark.data.experiment.EarphoneCalibrationRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * SubjectSessionActivity — 找人測試用的測試者實驗流程主控頁（實驗模式）。
 *
 * 依序引導：① 基準純音（不模擬）→ ② 設定模擬聽損條件（同時自動補償處方）
 * → ③ 模擬聽損—純音測試（含聽損體驗）→ ④ 語詞測驗 A/B → ⑤ 環境輔聽問卷／匯出。
 * 同一個測試者 ID 與耳機型號貫穿全程；換耳機重測時保留測試者 ID、重新走一次
 * 流程即可（支援「同一人戴多副耳機」的設計）。
 *
 * ★ 設定的可編輯時機 ★
 * 步驟①是「基準」——量的是測試者本人的真實聽閾，模擬器必須關閉，否則量到的是
 * 模擬後的閾值，整個 dB SL 尺度的零點就歪了。因此模擬聽力圖在步驟①期間不可編輯
 * （也用不到）。量完基準之後才輪到步驟②選條件；一旦步驟③的模擬器檢核跑過，
 * 條件就鎖死——之後的 A/B 對照必須跟檢核時是同一個模擬條件，否則檢核不算數。
 *
 * 任一步驟測得不理想都可用底下的「重測①/③/④」按鈕單獨重做，不會影響
 * 已完成的其他步驟（重測①會自動重新套用 DSL v5，因為處方本就取決於
 * 剛測得的聽力圖）。
 */
class SubjectSessionActivity : AppCompatActivity() {

    companion object {
        /**
         * 模擬聽閾的可用上限（dBFS）：滑桿上限是 −5 dBFS，這裡再留 10 dB，
         * 讓測試者在模擬聽閾之上還有調整空間（否則閾值卡在滑桿頂端，量不出來）。
         */
        private const val USABLE_CEILING_DBFS = -15f
    }

    private var stage = 0
    private var subjectName = "未填寫"

    /** stage 一律經此變更：同步持久化，app 關掉、當機、隔天續測都不用從①重來。 */
    private fun setStage(v: Int) {
        stage = v
        val repo = (application as HarkApplication).eqSettingsRepository
        lifecycleScope.launch { repo.saveSessionProgress(v, sessionId) }
    }
    private var earphoneModel = "其他"
    private var sessionId = System.currentTimeMillis()

    private lateinit var editSubject: EditText
    private lateinit var spinnerEarphone: Spinner
    private lateinit var spinnerHlProfile: Spinner
    private lateinit var switchSmearing: android.widget.Switch
    private lateinit var textHlHint: TextView
    private lateinit var textStatus: TextView
    private lateinit var buttonNext: Button
    private lateinit var buttonExport: Button
    private lateinit var buttonRetestPta: Button
    private lateinit var buttonRetestHlSim: Button
    private lateinit var buttonRetestAb: Button
    private lateinit var buttonRetestQuestionnaire: Button
    private lateinit var buttonRetestAbc: Button
    private lateinit var stepViews: List<TextView>
    private var earphoneCallback: android.media.AudioDeviceCallback? = null
    private var lastEarphoneInfo: String? = null

    // 各步驟被「提早結束/離開」（RESULT_CANCELED）時不可視為完成：
    // ① 純音取消時若照樣套用 DSL v5，會拿到「上一輪測試者」的舊聽力圖，
    //    處方就套錯人；③ 取消時若照樣前進，會在沒有 A/B 結果的狀態下
    //    開放問卷。取消一律退回該步驟的起點，讓實驗者重新開始該步驟。
    private val ptaLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            // 基準純音完成 → 前進到步驟②，讓實驗者「現在」選模擬聽損條件。
            // 處方不在這裡算：它取決於還沒選的那張聽力圖，現在算一定是舊的。
            //
            // 例外：若這是流程後段的「重測①」（條件早已鎖定），聽力圖沒得改，
            // 就必須立刻用鎖定的條件重算處方——否則後面的 A/B 會沿用上一次
            // 基準聽閾算出的舊增益，處方與實測聽閾對不上。
            // 聽閾在 DataStore 不分人存——記下這份聽閾屬於誰，
            // 換 ID 時 inferProgressFromExistingData 才不會張冠李戴
            lifecycleScope.launch {
                (application as HarkApplication).eqSettingsRepository
                    .saveRawThresholdsOwner(subjectName)
            }
            if (stage >= 3) {
                lifecycleScope.launch { applyPrescription() }
            } else {
                setStage(maxOf(stage, 2))
            }
            updateUi()
        } else {
            if (stage == 1) setStage(0)
            updateUi()
        }
    }

    /** 「中文語詞＋噪音語詞」被選時：安靜 A/B 完成後自動接噪音 A/B。 */
    private var pendingNoiseAb = false

    /** 模擬聽損—純音測試（含聽損體驗）的檢核結果。 */
    private var hlSimCheckPassed: Boolean? = null
    private var hlSimCheckMaxErr: Float = 0f
    // 步驟③操作檢核的逐頻原始資料（500/1000/2000/4000 Hz 固定順序，逗號分隔）。
    private var hlSimCheckMeasuredDbfs: String = ""
    private var hlSimCheckTargetDb: String = ""
    private var hlSimCheckErrorDb: String = ""

    private val hlSimLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            hlSimCheckPassed = result.data?.getBooleanExtra("EXTRA_HLSIM_PASSED", false)
            hlSimCheckMaxErr = result.data?.getFloatExtra("EXTRA_HLSIM_MAX_ERR", 0f) ?: 0f
            hlSimCheckMeasuredDbfs = result.data?.getStringExtra("EXTRA_HLSIM_MEASURED_DBFS") ?: ""
            hlSimCheckTargetDb = result.data?.getStringExtra("EXTRA_HLSIM_TARGET_DB") ?: ""
            hlSimCheckErrorDb = result.data?.getStringExtra("EXTRA_HLSIM_ERROR_DB") ?: ""
            // 持久化：語詞場次要把這個誤差寫進 hl_sim_check_err 欄；只放記憶體
            // 的話 app 重啟續測後就遺失（實測：檢核做了、匯出欄位卻空白）
            lifecycleScope.launch {
                (application as HarkApplication).eqSettingsRepository
                    .saveHlSimCheckErr(hlSimCheckMaxErr)
                (application as HarkApplication).eqSettingsRepository
                    .saveHlSimCheckDetail(hlSimCheckMeasuredDbfs, hlSimCheckTargetDb, hlSimCheckErrorDb)
            }
            setStage(maxOf(stage, 3))
        } else if (stage == 2) {
            setStage(2)   // 取消 → 留在原步驟，可重來
        }
        updateUi()
    }

    private val abLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            if (pendingNoiseAb) {
                // 「中文＋噪音」組合：安靜段完成 → 直接接噪音段（同一步驟內）
                pendingNoiseAb = false
                launchAb(noiseless = false)
                return@registerForActivityResult
            }
            setStage(maxOf(stage, 4))
        } else {
            pendingNoiseAb = false   // 中途取消 → 放棄後續的噪音段
            if (stage == 3) setStage(3)
        }
        updateUi()
    }
    private val questionnaireLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // 問卷「略過」屬於測試者的正當選擇，略過與送出皆視為本步驟完成
        setStage(maxOf(stage, 5))
        updateUi()
    }

    private val abcLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        // 部分完成（提早結束）已完成的格子仍會存檔，但只有「全部跑完」才推進到 stage 6，
        // 避免尚缺格子時被當成⑥已完成而不再提醒補測。
        if (result.resultCode == RESULT_OK) setStage(maxOf(stage, 6))
        updateUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subject_session)
        // ★ 進實驗流程即關閉即時輔聽 ★ 開著輔聽進測驗時，測驗 Activity 之間的
        // 生命週期交錯會讓隔離旗標被前一頁的收尾清掉、引擎中途復聲——語詞
        // 第二區塊開始時直接嘯叫（實測踩到）。與其在每個測驗頁補隔離時序，
        // 進流程就把引擎與服務整個停掉最保險；步驟⑤問卷需要輔聽時會自行
        // 重新啟動服務，不受影響。
        try {
            com.wcy.hark.audio.bridge.HarkAudioBridge.setMuted(true)
            com.wcy.hark.audio.bridge.HarkAudioBridge.stopEngine()
            startService(Intent(this, com.wcy.hark.audio.service.HarkAudioService::class.java).apply {
                action = com.wcy.hark.audio.service.HarkAudioService.ACTION_STOP
            })
        } catch (e: Exception) {
            Log.w("SubjectSession", "stop live engine skipped: ${e.message}")
        }
        window.statusBarColor = android.graphics.Color.parseColor("#F5F7FA")
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        editSubject = findViewById(R.id.editSessionSubjectName)
        spinnerEarphone = findViewById(R.id.spinnerSessionEarphone)
        spinnerHlProfile = findViewById(R.id.spinnerSessionHlProfile)
        switchSmearing = findViewById(R.id.switchSessionSmearing)
        textHlHint = findViewById(R.id.textSessionHlHint)
        textStatus = findViewById(R.id.textSessionStatus)
        buttonNext = findViewById(R.id.buttonSessionNext)
        buttonExport = findViewById(R.id.buttonSessionExport)
        buttonRetestPta = findViewById(R.id.buttonRetestPta)
        buttonRetestHlSim = findViewById(R.id.buttonRetestHlSim)
        buttonRetestAb = findViewById(R.id.buttonRetestAb)
        buttonRetestQuestionnaire = findViewById(R.id.buttonRetestQuestionnaire)
        buttonRetestAbc = findViewById(R.id.buttonRetestAbc)
        stepViews = listOf(
            findViewById(R.id.stepText1), findViewById(R.id.stepText2), findViewById(R.id.stepText3),
            findViewById(R.id.stepText4), findViewById(R.id.stepText5), findViewById(R.id.stepText6)
        )

        val calibRepo = EarphoneCalibrationRepository(this)
        val models = calibRepo.getEarphoneModels()
        spinnerEarphone.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, models)

        // 模擬聽力圖選單：測試者是聽力正常的人，不模擬聽損的話補償沒有對象，
        // A/B 對照的期望效果是零。預設 S1（很輕度陡降）——陡降型才會讓高頻子音
        // 真的掉到聽閾以下，補償把它救回來的效果才乾淨可測。
        val profiles = HearingLossProfile.ALL
        spinnerHlProfile.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, profiles.map { it.label }
        )

        val repository = (application as HarkApplication).eqSettingsRepository
        lifecycleScope.launch {
            editSubject.setText(repository.getLastSubjectNameFlow().first())
            val savedModel = repository.getSelectedEarphoneFlow().first()
            val idx = models.indexOf(savedModel)
            if (idx >= 0) spinnerEarphone.setSelection(idx)

            // ★ 開頁一律預設 S1、頻譜模糊關閉 ★ 不讀 DataStore 目前存的值——
            // 那是全域單一個槽，內容是「上一次不論誰、不論做什麼」留下的殘留值
            // （實測：曾顯示頻譜模糊「開」，但本研究從頭到尾沒有任何一位測試者
            // 真的用過這個選項，畫面顯示只會誤導實驗者以為那是這位測試者的設定）。
            // 本研究的模擬條件協定固定是 S1、不開頻譜模糊，這裡直接寫死預設，
            // 需要偏離協定時（步驟②可編輯視窗）再由實驗者手動改。
            resetHlProfileToDefault()

            // 續測：上次流程走到哪就從哪繼續（聽閾/處方/模擬條件本就各自持久化）。
            // 換人請按「重新開始流程」。
            val savedStage = repository.getSessionStageFlow().first().coerceIn(0, 6)
            val savedSession = repository.getSessionIdFlow().first()
            if (savedStage > 0 && savedSession > 0L && stage == 0) {
                stage = savedStage
                sessionId = savedSession
                val savedErr = repository.getHlSimCheckErrFlow().first()
                if (!savedErr.isNaN()) {
                    hlSimCheckMaxErr = savedErr
                    hlSimCheckPassed = savedErr <= 5f
                    hlSimCheckMeasuredDbfs = repository.getHlSimCheckMeasuredFlow().first()
                    hlSimCheckTargetDb = repository.getHlSimCheckTargetFlow().first()
                    hlSimCheckErrorDb = repository.getHlSimCheckErrorFlow().first()
                }
                android.widget.Toast.makeText(
                    this@SubjectSessionActivity,
                    "已還原上次進度（第 $savedStage 步完成）；換人請按「重新開始流程」",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                updateUi()
            }
            // 註：沒存過進度時的「從既有資料反推」不在開頁時做——此刻 ID 欄
            // 只是上次的預填值，測試者還沒確認是誰。改在按「開始」之後觸發。
            // 先套上次選擇，再依實際連接的耳機自動預選（官方 API 回報型號；
            // 對不上校正表就不動選單，仍可手動改選）
            earphoneCallback = EarphoneAutoDetect.register(
                this@SubjectSessionActivity, spinnerEarphone, models
            ) { info ->
                if (info != null && info != lastEarphoneInfo) {
                    lastEarphoneInfo = info
                    android.widget.Toast.makeText(this@SubjectSessionActivity, info, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }

        buttonNext.setOnClickListener { onNextClicked() }
        buttonExport.setOnClickListener { doExport() }
        findViewById<Button>(R.id.buttonSessionHistory).setOnClickListener {
            startActivity(Intent(this, SubjectTestHistoryActivity::class.java))
        }
        buttonRetestPta.setOnClickListener { launchPta() }
        buttonRetestHlSim.setOnClickListener { launchHlSim() }
        buttonRetestAb.setOnClickListener { askOverwriteThenRetestAb() }
        buttonRetestQuestionnaire.setOnClickListener { launchQuestionnaire() }
        buttonRetestAbc.setOnClickListener { askAbcThenLaunch() }
        findViewById<Button>(R.id.buttonSessionRestart).setOnClickListener { confirmRestart() }
        updateUi()
    }

    private fun updateUi() {
        stepViews.forEachIndexed { i, tv -> tv.alpha = if (i == stage) 1.0f else if (i < stage) 0.7f else 0.4f }
        // 整體進度（Goal-Gradient）：stage 0..6 對應已完成步數
        val done = stage.coerceIn(0, 6)
        findViewById<android.widget.ProgressBar>(R.id.progressSessionOverall).progress = done
        findViewById<TextView>(R.id.textSessionProgress).text = "已完成 $done / 6 步"
        val checkNote = hlSimCheckPassed?.let {
            if (it) "（模擬器檢核 ✅ 通過，最大誤差 %.1f dB）".format(hlSimCheckMaxErr)
            else "（⚠️ 模擬器檢核未通過，最大誤差 %.1f dB — 建議重測步驟②）".format(hlSimCheckMaxErr)
        } ?: ""

        textStatus.text = when (stage) {
            0 -> "請輸入測試者 ID、選擇耳機型號，按開始進行基準純音。\n" +
                    "這一步量的是測試者「本人」的真實聽閾，模擬器全程關閉——" +
                    "它是後面所有 dB SL 位準的零點。模擬聽損等測完基準再設定。"
            1 -> "基準純音進行中…"
            2 -> "已測得測試者本人聽閾（dB SL 零點已建立）。\n" +
                    "現在請選擇模擬聽力圖與頻譜模糊——按下一步時會依「實測聽閾 + 模擬損失」" +
                    "自動補償處方，接著進入模擬聽損—純音測試。"
            3 -> "模擬器檢核完成 $checkNote\n模擬條件已鎖定，準備開始語詞測驗 A/B 對照。"
            4 -> "準備填寫環境輔聽問卷（請先用喇叭播放情境音，見 experiment_scenes/）。\n" +
                    "註：此步驟走即時音訊路徑，未套用聽損模擬。"
            5 -> "準備進行⑥ NLFC/DSP 效益驗證：固定同一音量，依序測 A（純聽損）／" +
                    "B（聽損＋NLFC）／C（聽損＋NLFC＋DSP）三格，或只補測缺漏的格子。"
            else -> "流程已完成 $checkNote\n若同一位測試者要換另一副耳機再測一次，" +
                    "耳機選單改選新型號後按「開始下一輪」即可（ID 不用重打）；" +
                    "也可用下方按鈕單獨重測任一步驟。"
        }
        buttonNext.text = when (stage) {
            0 -> "開始：基準純音（不模擬）"
            2 -> "確認條件並處方 → 模擬聽損—純音測試"
            3 -> "開始：語詞測驗 A/B"
            4 -> "開始：填寫問卷"
            5 -> "開始：⑥ NLFC/DSP 效益驗證"
            6 -> "開始下一輪（可換耳機／新測試者）"
            else -> "進行中…"
        }
        buttonNext.isEnabled = stage != 1
        buttonExport.visibility = if (stage >= 6) android.view.View.VISIBLE else android.view.View.GONE

        // 測試者 ID／耳機：整輪流程的識別，開跑後不可改
        val idEnabled = stage == 0
        spinnerEarphone.isEnabled = idEnabled
        editSubject.isEnabled = idEnabled

        // 模擬聽損條件：步驟②才是設定它的時機。
        //   stage 0/1 —— 基準純音刻意不模擬，這裡設了也不會用到，開放只會誤導；
        //   stage 2   —— 唯一可編輯的視窗；
        //   stage ≥3  —— 檢核已通過，鎖死。A/B 若用了跟檢核不同的模擬條件，
        //                那份檢核就不能拿來背書 A/B 的結果。
        val hlEditable = stage == 2
        spinnerHlProfile.isEnabled = hlEditable
        switchSmearing.isEnabled = hlEditable
        textHlHint.text = when {
            stage < 2 -> "基準純音不模擬聽損 —— 測完①之後才會在此設定。"
            hlEditable -> "測試者是聽力正常的人：不模擬聽損，補償就沒有對象，A/B 對照必然無效果。"
            else -> "🔒 已鎖定（模擬器檢核通過後不可更改，否則檢核無法背書 A/B 的結果）。"
        }

        // 重測按鈕：只要該步驟做過一次（stage 已走到之後）就能單獨重做，
        // 不受目前處於哪一步的限制（進行中的活動全螢幕遮擋，不會誤觸）。
        //
        // 歷史紀錄注意：重測不會刪除先前的紀錄——每次都寫入一筆新的 session
        // （匯出檔裡同一測試者會有多筆）。分析時取「最後一組完整的」即可；
        // 時間戳與 session_id 足以分辨先後。
        buttonRetestPta.isEnabled = stage >= 2
        buttonRetestHlSim.isEnabled = stage >= 3
        buttonRetestAb.isEnabled = stage >= 4
        buttonRetestQuestionnaire.isEnabled = stage >= 5
        buttonRetestAbc.isEnabled = stage >= 6
    }

    private fun onNextClicked() {
        when (stage) {
            0 -> {
                captureSubjectInputs()
                // ID 確認後才檢查此人是否已有舊資料（開頁時 ID 只是預填值，還不算數）
                lifecycleScope.launch { inferProgressFromExistingData() }
            }
            2 -> launchHlSim()
            3 -> askNoiseConditionThenLaunchAb()
            4 -> { updateUi(); launchQuestionnaire() }
            5 -> askAbcThenLaunch()
            6 -> {
                // 重新開始：保留測試者 ID 輸入框內容，方便同一人換耳機重測；
                // 換人時請自行清空/修改姓名欄後再按開始。
                setStage(0); hlSimCheckPassed = null; clearHlSimCheckErr(); resetHlProfileToDefault(); updateUi()
            }
        }
    }

    /**
     * 步驟⑥：固定音量（延續步驟①調好、後續全程未變動的舒適音量基準），依序測
     * A（純聽損）／B（聽損＋NLFC）／C（聽損＋NLFC＋DSP）三格。可選「測全部」
     * 或只補測缺漏的單一格（沿用既有詞庫、允許三格間重複用詞，經確認）。
     */
    private fun askAbcThenLaunch() {
        val ctx = this
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("⑥ NLFC/DSP 效益驗證")
            .setView(container)
            .setNegativeButton("取消", null)
            .create()
        fun bigButton(text: String, conditions: List<String>) = Button(ctx).apply {
            this.text = text
            textSize = 16f
            isAllCaps = false
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 140
            ).apply { topMargin = 16 }
            setOnClickListener { dialog.dismiss(); launchAbc(conditions) }
        }
        container.addView(bigButton("測全部（A→B→C）", listOf("A", "B", "C")))
        container.addView(bigButton("補測 A（純聽損）", listOf("A")))
        container.addView(bigButton("補測 B（聽損＋NLFC）", listOf("B")))
        container.addView(bigButton("補測 C（聽損＋NLFC＋DSP）", listOf("C")))
        dialog.show()
    }

    /**
     * ⑥固定呈現位準（dB SL）——三格套同一個值，唯一變因是移頻/DSP 開關。
     * 30 dB SL 沿用④ OFF 區塊的協定上限（見 SSNAbTestActivity 的 quietConditionsOff），
     * 7 位測試者的 SL50 平均落在 20.5±2.6 dB SL，30 是已驗證過、大多數人在此位準
     * 開始聽得吃力但還沒到完全聽不懂的上緣——超過這個值，純聽損條件也會趴天花板
     * （實測：55 dB SL 時純聽損條件仍有 90% 正確率，測不出補償差異）。
     */
    private val abcLevelSlDb = 30f
    private val abcQuestionsPerCondition = 16

    private fun launchAbc(conditions: List<String>) {
        // 與④/問卷相同：進⑥前無條件以本人聽力圖重算處方，避免沿用他人殘留增益。
        //
        // ★ 模擬條件（聽力圖／頻譜模糊）強制寫回協定值，不信任畫面顯示 ★
        // ⑥ 底層的 SSNTestActivity 是「當場即時」讀 DataStore 目前的
        // hl_sim_profile／hl_sim_smearing 來套用模擬（見 loadHearingLossSim），
        // 不是讀畫面上的 spinner/switch。對於進度已跳過步驟②（如續測⑥）的
        // 測試者，畫面即使顯示「S1、頻譜模糊關」也只是預設顯示，DataStore
        // 裡實際的值可能是別的時間點殘留的——必須在此明確覆寫，才能保證
        // ⑥ 套用的模擬條件與本研究協定（S1、不開頻譜模糊）一致。
        val repository = (application as HarkApplication).eqSettingsRepository
        lifecycleScope.launch {
            repository.saveHlSimProfile(HearingLossProfile.DEFAULT.key)
            repository.saveHlSimSmearing(false)
            ensureAudiogramLoaded()
            applyPrescription()
            abcLauncher.launch(Intent(this@SubjectSessionActivity, NlfcDspAbcTestActivity::class.java).apply {
                putExtra("EXTRA_SUBJECT", subjectName)
                putExtra("EXTRA_EARPHONE_MODEL", earphoneModel)
                putExtra("EXTRA_LEVEL_SL_DB", abcLevelSlDb)
                putExtra("EXTRA_QUESTIONS_PER_CONDITION", abcQuestionsPerCondition)
                putExtra("EXTRA_CONDITIONS", conditions.toTypedArray())
                putExtra("EXTRA_HL_SIM_CHECK_ERR",
                    if (hlSimCheckPassed != null) hlSimCheckMaxErr else Float.NaN)
                if (hlSimCheckPassed != null) {
                    putExtra("EXTRA_HLSIM_MEASURED_DBFS", hlSimCheckMeasuredDbfs)
                    putExtra("EXTRA_HLSIM_TARGET_DB", hlSimCheckTargetDb)
                    putExtra("EXTRA_HLSIM_ERROR_DB", hlSimCheckErrorDb)
                }
            })
        }
    }

    /**
     * 噪音情境是選用測項——由實驗者當場決定要不要做。
     *
     * 安靜情境是主要測項：聽損模擬以擴展器層為主，補償的機制就是「把落在模擬
     * 聽閾下的語音線索拉回可聽」，安靜情境正可直接檢驗，效果大且可解釋。
     * 噪音情境下增益同時作用於語音與噪音、不改變訊噪比，補償效益小得多，
     * 在少量測試者身上很可能被個體變異蓋掉——但它才是「聽得到不等於聽得懂」
     * 的對照觀察，時間與測試者的疲勞度允許時值得做。
     */
    private fun askNoiseConditionThenLaunchAb() {
        // 兩顆真正的大按鈕（實測回饋：setItems 的文字列「不像可以按的東西」）。
        val ctx = this
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("語詞測驗 A/B（兩階段：無補償/有補償，皆套用模擬聽損）")
            .setView(container)
            .setNegativeButton("取消", null)
            .create()
        fun bigButton(text: String, onClick: () -> Unit) = Button(ctx).apply {
            this.text = text
            textSize = 16f
            isAllCaps = false
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 160
            ).apply { topMargin = 16 }
            setOnClickListener { dialog.dismiss(); onClick() }
        }
        container.addView(bigButton("中文語詞測驗") { launchAb(noiseless = true) })
        container.addView(bigButton("中文語詞測驗 ＋ 噪音語詞測驗") {
            pendingNoiseAb = true          // 安靜做完自動接噪音
            launchAb(noiseless = true)
        })
        // 補測入口：安靜段已完成、只缺噪音段時用（完整資料＝安靜＋噪音各一筆
        // A/B 對照），不必整個④重來
        container.addView(bigButton("噪音語詞測驗（單獨補測）") { launchAb(noiseless = false) })
        dialog.show()
    }

    /**
     * 進度持久化上線前的舊測驗沒有存 stage，但證據都在：基準聽閾在 DataStore、
     * 語詞 A/B 與問卷在 SQLite。反推出最遠進度後「詢問」是否續測——推斷可能
     * 跨到別的測試者（聽閾不分人存），所以必須由實驗者確認，不能默默套用。
     */
    private fun startBaseline() {
        sessionId = System.currentTimeMillis() // 新測試者流程 → 新 session_id
        hlSimCheckPassed = null
        clearHlSimCheckErr()
        setStage(1); updateUi()
        launchPta()
    }

    private suspend fun inferProgressFromExistingData() {
        val repository = (application as HarkApplication).eqSettingsRepository
        val thresholds = repository.getBinauralRawThresholdsFlow(
            HearingLossProfile.AUDIOGRAM_FREQS.toList()
        ).first()

        val name = subjectName
        // 聽閾不分人存：只有「主人 ID 相符」的聽閾才算此人的舊資料。
        // 不相符（換了測試者）時，先嘗試從裝置上既有的基準純音 CSV（RawDbfs 列）
        // 還原——DataStore 只留最後一位的聽閾，前面測試者的基準只剩 CSV 有；
        // 沒有這條還原路徑，回訪重測的測試者都得整段重做步驟①。
        val owner = repository.getRawThresholdsOwnerFlow().first()
        if (thresholds.isEmpty() || owner != name) {
            val csv = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                findBaselineCsv(name)
            }
            if (csv == null) {
                android.widget.Toast.makeText(
                    this, "「$name」沒有可續用的基準聽閾（現存聽閾屬「${owner.ifEmpty { "未知" }}」），從步驟①開始",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                startBaseline(); return
            }
            val fileDate = java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm", java.util.Locale.getDefault()
            ).format(java.util.Date(csv.file.lastModified()))
            AlertDialog.Builder(this)
                .setTitle("找到既有的基準純音")
                .setMessage(
                    "在裝置檔案中找到「$name」的基準純音聽閾：\n${csv.file.name}\n（$fileDate，右耳 ${csv.right.size} 頻率、左耳 ${csv.left.size} 頻率）\n\n" +
                            "要還原此基準並續用嗎？\n（若已更換耳機，位準基準會失準，請選擇重新測①）"
                )
                .setPositiveButton("還原並續用") { _, _ ->
                    lifecycleScope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            csv.right.forEach { (f, v) -> repository.saveRawThresholdDbfs("right", f, v) }
                            csv.left.forEach { (f, v) -> repository.saveRawThresholdDbfs("left", f, v) }
                            csv.rightHl.forEach { (f, v) -> repository.saveAudiogramThreshold("right", f, v) }
                            csv.leftHl.forEach { (f, v) -> repository.saveAudiogramThreshold("left", f, v) }
                            repository.saveRawThresholdsOwner(name)
                        }
                        // ★ 立刻以「本人聽力圖＋當前模擬條件」重算處方 ★
                        // DataStore 的處方增益也是單一份覆蓋儲存，不重算的話，
                        // 續測跳到步驟④時 ON 條件會套到上一位測試者的增益。
                        applyPrescription()
                        // owner 已相符，重跑一次即可走正常的「從步驟繼續」推斷
                        inferProgressFromExistingData()
                    }
                }
                .setNegativeButton("重新測①") { _, _ -> startBaseline() }
                .setCancelable(false)
                .show()
            return
        }
        val inferred = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val db = SRTResultDbHelper(this@SubjectSessionActivity).readableDatabase
            fun hasRows(table: String, subjectCol: String): Boolean =
                db.rawQuery(
                    "SELECT 1 FROM $table WHERE $subjectCol = ? LIMIT 1", arrayOf(name)
                ).use { it.moveToFirst() }
            when {
                hasRows(SRTResultContract.AbcSessionEntry.TABLE_NAME,
                    SRTResultContract.AbcSessionEntry.COLUMN_NAME_SUBJECT_NAME) -> 6
                hasRows(SRTResultContract.QuestionnaireEntry.TABLE_NAME,
                    SRTResultContract.QuestionnaireEntry.COLUMN_NAME_SUBJECT_NAME) -> 5
                hasRows(SRTResultContract.AbSessionEntry.TABLE_NAME,
                    SRTResultContract.AbSessionEntry.COLUMN_NAME_SUBJECT_NAME) -> 4
                else -> 2   // 有基準聽閾 → ①已完成；②選條件很快，從②開始最安全
            }
        }

        AlertDialog.Builder(this)
            .setTitle("偵測到先前的測驗資料")
            .setMessage(
                "「$name」已有" + when (inferred) {
                    6 -> "⑥ NLFC/DSP 效益驗證紀錄（可能尚缺格子，請用重測按鈕個別補測）"
                    5 -> "問卷紀錄（全流程大致完成）"
                    4 -> "語詞 A/B 紀錄"
                    else -> "基準純音聽閾"
                } + "。\n\n要從步驟${listOf("②", "④", "⑤", "⑥")[when (inferred) { 6 -> 3; 5 -> 2; 4 -> 1; else -> 0 }]}繼續，還是從頭開始？\n" +
                        "（若換了測試者或換了耳機，請選從頭開始）"
            )
            .setPositiveButton("從步驟繼續") { _, _ -> setStage(inferred); updateUi() }
            .setNegativeButton("從頭開始") { _, _ -> startBaseline() }
            .setCancelable(false)
            .show()
    }

    /** 基準純音 CSV 的還原資料：檔案、左右耳 RawDbfs 聽閾與 dB HL 聽力圖。 */
    private data class BaselineCsv(
        val file: java.io.File,
        val right: Map<Int, Float>,
        val left: Map<Int, Float>,
        val rightHl: Map<Int, Int>,
        val leftHl: Map<Int, Int>
    )

    /**
     * 在 app 外部檔案目錄中尋找「這位測試者」最新的基準純音 CSV。
     * 依 SelfAdjustPtaActivity.saveCsv() 的格式：首列 "Subject Name,<代號>"、
     * 溯源列 "RawDbfs,<Right|Left>,<freq>,<dbfs>"。新舊檔名（含/不含
     * _SubjectFlow 註記）都接受——回訪測試者的舊檔正是還原的對象。
     * 左右耳各需至少 4 個頻率才視為完整可還原。
     */
    private fun findBaselineCsv(name: String): BaselineCsv? {
        val dir = getExternalFilesDir(null) ?: return null
        val files = dir.listFiles { f: java.io.File ->
            f.isFile && f.name.contains("_PureTone_SelfAdjust") && f.name.endsWith("Results.csv")
        } ?: return null
        for (f in files.sortedByDescending { it.lastModified() }) {
            try {
                val lines = f.readLines()
                val subj = lines.firstOrNull { it.startsWith("Subject Name,") }
                    ?.substringAfter("Subject Name,")?.trim()
                if (subj != name) continue
                val right = mutableMapOf<Int, Float>()
                val left = mutableMapOf<Int, Float>()
                val rightHl = mutableMapOf<Int, Int>()
                val leftHl = mutableMapOf<Int, Int>()
                for (ln in lines) {
                    val p = ln.split(",")
                    if (ln.startsWith("RawDbfs,")) {
                        if (p.size < 4) continue
                        val freq = p[2].trim().toIntOrNull() ?: continue
                        val v = p[3].trim().toFloatOrNull() ?: continue
                        when (p[1].trim()) {
                            "Right" -> right[freq] = v
                            "Left" -> left[freq] = v
                        }
                    } else if (p.size == 3 && (p[0] == "Right" || p[0] == "Left")) {
                        // dB HL 聽力圖列（"Right,2000,4"；N/A 略過）——處方計算
                        // （applyPrescription）讀的是這組，不還原它，重測④會套到
                        // 「上一位測試者」的處方
                        val freq = p[1].trim().toIntOrNull() ?: continue
                        val hl = p[2].trim().toIntOrNull() ?: continue
                        if (p[0] == "Right") rightHl[freq] = hl else leftHl[freq] = hl
                    }
                }
                if (right.size >= 4 && left.size >= 4) return BaselineCsv(f, right, left, rightHl, leftHl)
            } catch (e: Exception) {
                Log.w("SubjectSession", "findBaselineCsv: 無法解析 ${f.name}: ${e.message}")
            }
        }
        return null
    }

    /** 重測④前先問要不要覆蓋——覆蓋會刪除「本測試者」所有語詞 A/B 相關紀錄。 */
    private fun askOverwriteThenRetestAb() {
        AlertDialog.Builder(this)
            .setTitle("重測④語詞 A/B")
            .setMessage("要如何處理「$subjectName」先前的語詞測驗紀錄？")
            .setPositiveButton("覆蓋（刪除舊紀錄）") { _, _ ->
                deletePreviousAbResults()
                askNoiseConditionThenLaunchAb()
            }
            .setNeutralButton("保留（新增一筆）") { _, _ -> askNoiseConditionThenLaunchAb() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deletePreviousAbResults() {
        val db = SRTResultDbHelper(this).writableDatabase
        db.beginTransaction()
        try {
            // 只刪「測試者實驗流程」的場次（session_source='subject'）——同名者
            // 在「聽力檢測」單獨做的一般測驗（source 為 NULL）不屬於本流程，不可誤刪。
            db.execSQL(
                "DELETE FROM ${SRTResultContract.SSNRecordEntry.TABLE_NAME} WHERE " +
                        "${SRTResultContract.SSNRecordEntry.COLUMN_NAME_SESSION_ID_FK} IN " +
                        "(SELECT ${SRTResultContract.SSNSessionEntry.COLUMN_NAME_SESSION_ID} FROM " +
                        "${SRTResultContract.SSNSessionEntry.TABLE_NAME} WHERE " +
                        "${SRTResultContract.SSNSessionEntry.COLUMN_NAME_SUBJECT_NAME} = ? AND " +
                        "${SRTResultContract.SSNSessionEntry.COLUMN_NAME_SESSION_SOURCE} = ?)",
                arrayOf(subjectName, SRTResultContract.SSNSessionEntry.SOURCE_SUBJECT)
            )
            db.delete(
                SRTResultContract.SSNSessionEntry.TABLE_NAME,
                "${SRTResultContract.SSNSessionEntry.COLUMN_NAME_SUBJECT_NAME} = ? AND " +
                        "${SRTResultContract.SSNSessionEntry.COLUMN_NAME_SESSION_SOURCE} = ?",
                arrayOf(subjectName, SRTResultContract.SSNSessionEntry.SOURCE_SUBJECT)
            )
            db.delete(
                SRTResultContract.AbSessionEntry.TABLE_NAME,
                "${SRTResultContract.AbSessionEntry.COLUMN_NAME_SUBJECT_NAME} = ?",
                arrayOf(subjectName)
            )
            db.setTransactionSuccessful()
            android.widget.Toast.makeText(this, "已刪除舊語詞紀錄", android.widget.Toast.LENGTH_SHORT).show()
        } finally {
            db.endTransaction()
        }
    }

    private fun confirmRestart() {
        AlertDialog.Builder(this)
            .setTitle("重新開始流程")
            .setMessage("進度將歸零（從①基準純音開始）。已存的測驗紀錄不會被刪除。\n換測試者時請按此，並修改測試者 ID。")
            .setPositiveButton("重新開始") { _, _ ->
                setStage(0); hlSimCheckPassed = null; clearHlSimCheckErr(); resetHlProfileToDefault(); updateUi()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun captureSubjectInputs() {
        subjectName = editSubject.text.toString().trim().ifEmpty { "未填寫" }
        earphoneModel = spinnerEarphone.selectedItem as? String ?: "其他"
        val repository = (application as HarkApplication).eqSettingsRepository
        lifecycleScope.launch {
            repository.saveLastSubjectName(subjectName)
            repository.saveSelectedEarphone(earphoneModel)
        }
    }

    private fun selectedProfile(): HearingLossProfile.Profile =
        HearingLossProfile.ALL.getOrNull(spinnerHlProfile.selectedItemPosition)
            ?: HearingLossProfile.DEFAULT

    /**
     * 步驟②：確認模擬聽損條件 → 依「實測聽閾 + 模擬損失」補償處方 →
     * 進入模擬聽損—純音測試（第一頁是聽損體驗，接著開模擬測 4 個頻率）。
     *
     * 開跑前先做數位餘裕檢查。模擬器只會衰減，要讓測試者在模擬聽閾之上還聽得到
     * SL 分貝的語音，送進模擬器的數位位準必須是「本人聽閾 + 模擬損失 + SL」。
     * 超過 0 dBFS 的頻帶，未輔助聽不見、輔助後仍然聽不見，對 A/B 對照零貢獻——
     * 選了太重的聽力圖（如 S2 在 4 kHz 損失 95 dB）就會整批資料白做。
     */
    private fun launchHlSim() {
        // 還原進度續測時不經過步驟⓪，subjectName 不補抓的話之後所有紀錄
        // 都會掛在「未填寫」名下（實測踩到，歷史紀錄併不回原測試者）。
        captureSubjectInputs()
        val repository = (application as HarkApplication).eqSettingsRepository
        val profile = selectedProfile()
        val smearing = switchSmearing.isChecked

        lifecycleScope.launch {
            repository.saveHlSimProfile(profile.key)
            repository.saveHlSimSmearing(smearing)
            // 重測③（不經步驟①）時 DataStore 可能殘留他人閾值——先還原本人的，
            // 數位餘裕檢查與模擬器零點才會以本人為準。
            ensureAudiogramLoaded()

            if (profile.isNone) {
                AlertDialog.Builder(this@SubjectSessionActivity)
                    .setTitle("未選擇模擬聽力圖")
                    .setMessage(
                        "目前是「不模擬」。測試者聽力正常，補償沒有作用對象，" +
                                "A/B 對照的期望效果為零。\n\n請選一張模擬聽力圖再繼續。"
                    )
                    .setPositiveButton("知道了", null)
                    .show()
                return@launch
            }

            val thresholds = repository.getBinauralRawThresholdsFlow(
                HearingLossProfile.AUDIOGRAM_FREQS.toList()
            ).first()
            val need = HearingLossProfile.requiredLevelDbfs(
                profile, thresholds, SelfAdjustPtaActivity.CHECK_FREQS
            )

            // 需要留出「模擬聽閾之上還能再推高一些」的空間，測試者才調得出閾值
            if (need > USABLE_CEILING_DBFS) {
                AlertDialog.Builder(this@SubjectSessionActivity)
                    .setTitle("⚠️ 數位餘裕不足")
                    .setMessage(
                        "以這位測試者的實測聽閾，${profile.label} 要把音量推到模擬聽閾" +
                                "需要 %.1f dBFS，已逼近數位滿刻度（滑桿上限 %.0f dBFS）。\n\n"
                                    .format(need, SelfAdjustPtaActivity.HL_SIM_MAX_DBFS.toFloat()) +
                                "代表最重的那個頻帶推不上去：未輔助聽不見、輔助後仍然聽不見，" +
                                "對 A/B 對照沒有任何貢獻。\n\n" +
                                "建議改選較輕的聽力圖（S1 或 N2）。"
                    )
                    .setPositiveButton("改選", null)
                    .setNegativeButton("仍要繼續") { _, _ ->
                        lifecycleScope.launch { applyPrescription(); doLaunchHlSim() }
                    }
                    .show()
                return@launch
            }

            applyPrescription()
            doLaunchHlSim()
        }
    }

    private fun doLaunchHlSim() {
        hlSimLauncher.launch(Intent(this, HlSimIntroActivity::class.java).apply {
            putExtra("EXTRA_SUBJECT", subjectName)
            putExtra("EXTRA_EARPHONE_MODEL", earphoneModel)
        })
    }

    private fun launchPta() {
        captureSubjectInputs()
        // 直接帶入姓名/耳機型號（而非讓 PTA 去讀 DataStore）：captureSubjectInputs()
        // 的存檔是非同步的，若 PTA 改讀 Flow 會有競態，可能顯示錯誤的耳機型號。
        ptaLauncher.launch(Intent(this, SelfAdjustPtaActivity::class.java).apply {
            putExtra("EXTRA_SESSION_FLOW", true)
            putExtra("EXTRA_SUBJECT", subjectName)
            putExtra("EXTRA_EARPHONE_MODEL", earphoneModel)
        })
    }

    private fun launchAb(noiseless: Boolean = true) {
        captureSubjectInputs()
        if (!noiseless) {
            // 噪音測驗的適用性檢查放在「進測驗之前」擋一次就好——
            // 放在測驗裡會 A/B 兩階段各跳一次，干擾施測（實測回報）。
            // 檢查前先確保 DataStore 是「本人」的閾值（重測④時可能殘留他人資料）。
            lifecycleScope.launch { ensureAudiogramLoaded(); checkNoiseFeasibilityThenLaunch() }
            return
        }
        doLaunchAb(noiseless = true)
    }

    /**
     * 混音總位準固定在一般交談音量（聽閾+55 dB SL，上限 −35 dBFS）。若模擬損失
     * 重到讓總位準落在「模擬聽閾」以下，未輔助時連噪音都幾乎聽不見，兩條件都會
     * 趴地板。此時給實驗者三個出口：改做安靜、仍要做、取消。
     */
    private suspend fun checkNoiseFeasibilityThenLaunch() {
        val repository = (application as HarkApplication).eqSettingsRepository
        val profile = HearingLossProfile.fromKey(repository.getHlSimProfileFlow().first())
        val thresholds = repository.getBinauralRawThresholdsFlow(
            listOf(500, 1000, 2000)
        ).first()
        val anchor = thresholds.values.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: -60f
        val speechLoss = if (profile.isNone) 0f else listOf(500, 1000, 2000).map { f ->
            val i = HearingLossProfile.AUDIOGRAM_FREQS.indexOf(f)
            if (i >= 0) profile.thresholds[i] else 0f
        }.average().toFloat()

        val totalDbfs = (anchor + 55f).coerceAtMost(-35f)
        val marginDb = totalDbfs - (anchor + speechLoss)
        if (profile.isNone || marginDb >= 10f) { doLaunchAb(noiseless = false); return }

        AlertDialog.Builder(this)
            .setTitle("⚠️ ${profile.label} 不適合噪音測驗")
            .setMessage(
                "呈現總位準固定在一般交談音量（%.0f dBFS），但此模擬條件的聽閾約 %.0f dBFS，"
                    .format(totalDbfs, anchor + speechLoss) +
                        "總位準僅高出 %.0f dB——未輔助時連噪音都幾乎聽不見，".format(marginDb) +
                        "兩條件都會趴地板、測不出差異。\n\n" +
                        "建議改做安靜語詞，或換較輕的模擬聽力圖（如 S1）。"
            )
            .setPositiveButton("改做安靜語詞") { _, _ -> doLaunchAb(noiseless = true) }
            .setNeutralButton("仍要做噪音") { _, _ -> doLaunchAb(noiseless = false) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun clearHlSimCheckErr() {
        lifecycleScope.launch {
            (application as HarkApplication).eqSettingsRepository.saveHlSimCheckErr(Float.NaN)
            (application as HarkApplication).eqSettingsRepository.saveHlSimCheckDetail("", "", "")
        }
    }

    /**
     * 模擬組態選單拉回預設 S1、頻譜模糊關閉（本研究協定），避免沿用 DataStore
     * 全域單槽殘留的組態——開頁、重新開始流程皆呼叫此函式。
     */
    private fun resetHlProfileToDefault() {
        val idx = HearingLossProfile.ALL.indexOf(HearingLossProfile.DEFAULT)
        if (idx >= 0) spinnerHlProfile.setSelection(idx)
        switchSmearing.isChecked = false
    }

    private fun doLaunchAb(noiseless: Boolean) {
        // ★ 每次進④前無條件重算處方 ★ 增益 DataStore 是全域單槽，可能殘留
        // 上一位測試者的處方。原本只在「還原舊資料」時重算，但續測者若
        // DataStore 已有本人閾值就不會再按還原，殘留處方照用（實測：5+3 的
        // 場次套到別人的增益）。applyPrescription 讀本人聽力圖＋目前模擬
        // 組態重算，冪等，多跑無害。
        lifecycleScope.launch {
            ensureAudiogramLoaded()
            applyPrescription()
            doLaunchAbNow(noiseless)
        }
    }

    /**
     * 確保 DataStore 裡有「本人」的 dB HL 聽力圖。舊版還原只寫回 raw dBFS
     * 閾值、沒寫聽力圖——applyPrescription 讀到空聽力圖會靜默跳過、殘留
     * 前一位的增益（實測：5+3 重編譯後增益仍是 11.0 開頭的別人處方）。
     * 這裡在缺漏時自動從本人的基準純音 CSV 補寫 HL 列後再算。
     */
    private suspend fun ensureAudiogramLoaded() {
        // ★ 一律以「本人基線 CSV」的 HL 列為準覆寫 ★ 不能用「DataStore 有值
        // 就跳過」判斷——聽力圖 DataStore 也是全域單槽，很可能存的是上一位
        // 測試者的完整聽力圖（≥4 頻率、看起來「有資料」），applyPrescription
        // 拿它重算，每次都得到同一組別人的增益（實測：5+3 連三場 11.0 開頭）。
        // 本人的 CSV（步驟①產出）才是本人聽力圖的唯一可靠來源。
        val repository = (application as HarkApplication).eqSettingsRepository
        val name = editSubject.text.toString().trim()
        val csv = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            findBaselineCsv(name)
        }
        if (csv != null && csv.rightHl.size >= 4 && csv.leftHl.size >= 4) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                csv.rightHl.forEach { (f, v) -> repository.saveAudiogramThreshold("right", f, v) }
                csv.leftHl.forEach { (f, v) -> repository.saveAudiogramThreshold("left", f, v) }
                // ★ raw dBFS 聽閾也一併覆寫 ★ 它是 dB SL 呈現位準的錨點、聽損
                // 模擬器的零點、噪音可行性檢查的輸入——同為全域單槽，殘留上一位
                // 測試者（或實驗者自測）的閾值時，重測④的絕對位準會整場錯掉。
                csv.right.forEach { (f, v) -> repository.saveRawThresholdDbfs("right", f, v) }
                csv.left.forEach { (f, v) -> repository.saveRawThresholdDbfs("left", f, v) }
                repository.saveRawThresholdsOwner(name)
            }
            Log.i("SubjectSession", "ensureAudiogramLoaded: 以 ${csv.file.name} 的 HL 聽力圖＋raw 閾值覆寫")
            return
        }
        // 找不到 CSV：只剩 DataStore 可用，但無法確認是不是本人的——明確警告
        val freqs = listOf(250, 500, 1000, 2000, 3000, 4000, 6000, 8000)
        suspend fun earCount(ear: String) =
            freqs.count { repository.getAudiogramThresholdFlow(ear, it).first() != -1 }
        val msg = if (earCount("left") >= 4 && earCount("right") >= 4)
            "⚠️ 找不到「$name」的基線 CSV，將沿用裝置內現存聽力圖——若上一位測試者不同人，處方會是錯的！"
        else
            "⚠️ 找不到「$name」的聽力圖（dB HL），處方增益可能不正確——請重測①"
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun doLaunchAbNow(noiseless: Boolean) {
        abLauncher.launch(Intent(this, SSNAbTestActivity::class.java).apply {
            putExtra("EXTRA_SUBJECT", subjectName)
            putExtra("EXTRA_EARPHONE_MODEL", earphoneModel)
            putExtra("EXTRA_NOISELESS", noiseless)
            // 步驟③檢核誤差 → 隨場次寫進資料庫（NaN = 本輪未檢核）
            putExtra("EXTRA_HL_SIM_CHECK_ERR",
                if (hlSimCheckPassed != null) hlSimCheckMaxErr else Float.NaN)
            // 步驟③逐頻原始資料 → 隨場次寫進資料庫（本輪未檢核則為空字串）
            if (hlSimCheckPassed != null) {
                putExtra("EXTRA_HLSIM_MEASURED_DBFS", hlSimCheckMeasuredDbfs)
                putExtra("EXTRA_HLSIM_TARGET_DB", hlSimCheckTargetDb)
                putExtra("EXTRA_HLSIM_ERROR_DB", hlSimCheckErrorDb)
            }
        })
    }

    private fun launchQuestionnaire() {
        captureSubjectInputs()
        // 第二道保險：若這個 sessionId 底下已有「別人」的問卷（換人時沿用了
        // 上一位的持久化 sessionId），為本測試者換發新的 sessionId。搭配
        // QuestionnaireActivity 存檔時 session_id+subject_name 的雙條件刪除，
        // 確保任何情況下都不會動到其他測試者的問卷紀錄。
        val db = SRTResultDbHelper(this).readableDatabase
        val ownedByOther = db.rawQuery(
            "SELECT 1 FROM ${SRTResultContract.QuestionnaireEntry.TABLE_NAME} WHERE " +
            "${SRTResultContract.QuestionnaireEntry.COLUMN_NAME_SESSION_ID} = ? AND " +
            "${SRTResultContract.QuestionnaireEntry.COLUMN_NAME_SUBJECT_NAME} != ? LIMIT 1",
            arrayOf(sessionId.toString(), subjectName)
        ).use { it.moveToFirst() }
        if (ownedByOther) {
            sessionId = System.currentTimeMillis()
            setStage(stage) // 立即持久化新 sessionId，續測時不再沿用舊的
        }
        // ★ 與步驟④（doLaunchAb）相同：進問卷前以「本人聽力圖」無條件重算處方 ★
        // 問卷頁的 DSP ON 讀的是 DataStore 全域單槽的 EQ 增益；單獨「重測問卷」
        // 不經步驟①②，殘留的可能是上一位測試者的處方、或實驗者自用後的近乎
        // 平坦增益——實測：Leo 補測問卷時 ON/OFF 無差異（等同沒有 DSP）。
        // ensureAudiogramLoaded 以本人基準 CSV 的 HL 列覆寫聽力圖後重算，冪等。
        lifecycleScope.launch {
            ensureAudiogramLoaded()
            applyPrescription()
            questionnaireLauncher.launch(Intent(this@SubjectSessionActivity, QuestionnaireActivity::class.java).apply {
                putExtra("EXTRA_SUBJECT", subjectName)
                putExtra("EXTRA_SESSION_ID", sessionId)
                putExtra("EXTRA_EARPHONE_MODEL", earphoneModel)
            })
        }
    }

    /**
     * 讀取剛測得的聽力圖，計算 DSL v5 16 段增益並套用到即時 EQ（雙耳）。
     *
     * ★ 處方的輸入是「測試者實測聽閾 + 模擬損失量」，不是測試者本人的聽力圖 ★
     *
     * 測試者聽力正常，若逕以其本人的聽力圖算處方，DSL v5 對 ≤20 dB HL 給的增益
     * 趨近於零——輔助與未輔助兩條件毫無差異，A/B 對照做出來一定是「沒有效果」。
     * 補償要有可檢驗的對象，處方就必須針對「模擬器所實作的那張聽力圖」來開，
     * 才構成「模擬損失 → 依損失開處方 → 檢驗處方能否還原可聽度」的內部一致閉環。
     */
    private suspend fun applyPrescription() {
        val repository = (application as HarkApplication).eqSettingsRepository
        val testFrequencies = listOf(250, 500, 1000, 2000, 3000, 4000, 6000, 8000)
        val profile = HearingLossProfile.fromKey(repository.getHlSimProfileFlow().first())

        suspend fun loadAudiogram(ear: String): Map<Int, Float> = buildMap {
            for ((i, freq) in testFrequencies.withIndex()) {
                val t = repository.getAudiogramThresholdFlow(ear, freq).first()
                if (t != -1) {
                    // 疊上模擬損失量 → 這才是處方該補償的那張聽力圖
                    put(freq, t.toFloat() + profile.thresholds[i])
                }
            }
        }
        val leftAudiogram = loadAudiogram("left")
        val rightAudiogram = loadAudiogram("right")
        val binaural = leftAudiogram.isNotEmpty() && rightAudiogram.isNotEmpty()
        Log.i("SubjectSession",
            "DSL v5 處方輸入＝實測聽閾＋模擬損失（${profile.key}）: left=$leftAudiogram")

        // 逐頻段「先寫入 DataStore、再更新即時 DSP」皆在同一協程內依序
        // await，確保下一步（A/B 對照快照/還原增益）讀到的是本次結果，
        // 不會被 Activity 生命週期提早取消而遺漏。
        //
        // 同時更新 HarkAudioBridge（真正即時麥克風收音處理引擎）與
        // SystemDspManager（測驗播放音軌用的模擬路徑）——先前只更新
        // 後者，導致問卷情境音環節的「即時輔聽」實際上從未套用過
        // 這裡算出的 DSL v5 增益，是個潛在 bug。
        suspend fun applyEar(earIndex: Int, ear: String, audiogram: Map<Int, Float>) {
            if (audiogram.isEmpty()) return
            Prescriptions.CENTER_FREQUENCIES_16.forEachIndexed { i, freq ->
                val threshold = interpolateThreshold(freq, audiogram) ?: return@forEachIndexed
                val gain = Prescriptions.dslV5Gain(freq, threshold, binaural).coerceIn(0f, 30f)
                repository.saveBandGain(ear, 0, i, gain)
                HarkAudioBridge.setBandGain(earIndex, i, gain)
                SystemDspManager.updateBandGain(earIndex, i, gain)
            }
        }
        applyEar(0, "left", leftAudiogram)
        applyEar(1, "right", rightAudiogram)
    }

    private fun interpolateThreshold(freqHz: Int, audiogram: Map<Int, Float>): Float? {
        if (audiogram.isEmpty()) return null
        audiogram[freqHz]?.let { return it }
        val sorted = audiogram.entries.sortedBy { it.key }
        if (freqHz <= sorted.first().key) return sorted.first().value
        if (freqHz >= sorted.last().key) return sorted.last().value
        val hi = sorted.first { it.key > freqHz }
        val lo = sorted.last { it.key < freqHz }
        val t = (ln(freqHz.toFloat()) - ln(lo.key.toFloat())) / (ln(hi.key.toFloat()) - ln(lo.key.toFloat()))
        return lo.value + (hi.value - lo.value) * t
    }

    private fun doExport() {
        val uri = SubjectExportUtil.exportSubjectData(this, subjectName) ?: return
        startActivity(SubjectExportUtil.shareIntent(this, uri))
    }

    override fun onDestroy() {
        earphoneCallback?.let { EarphoneAutoDetect.unregister(this, it) }
        earphoneCallback = null
        super.onDestroy()
    }
}
