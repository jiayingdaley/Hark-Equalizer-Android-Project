package com.wcy.hark.audiometry
import com.wcy.hark.R
import com.wcy.hark.HarkApplication

import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wcy.hark.data.experiment.EarphoneCalibrationRepository
import com.wcy.hark.data.experiment.FreqCalibration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileWriter
import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * SelfAdjustPtaActivity — 快速純音（自調式，Method of Adjustment）。
 *
 * 測試者自行把脈衝純音調到「剛好聽得見」：每個頻率做兩步——
 * 步驟1 直接調到剛好聽得見；步驟2 先調到完全聽不見、再調回剛好聽得見
 * （一次下-上折返）。閾值 = 兩步 dBFS 平均。
 *
 * 位準範圍 −100 ~ −60 dBFS、1 dB 解析度；−100 dBFS 定義為輸出振幅歸零
 * （真正無聲端點），−99 以上走浮點 PCM（16-bit 量化底線 −90.3 dBFS 以下
 * 即靜音，浮點路徑才能呈現正常聽力者的閾值區）。
 * 頻率序列與標準純音測驗相同（8 頻率，1k 起、不重測）。
 * 結果經耳機校正表換算為 dB HL，輸出與標準純音相容的 CSV 供歷史紀錄。
 */
class SelfAdjustPtaActivity : AppCompatActivity() {

    companion object {
        /** true = 開啟聽損模擬後再測一次（模擬聽損—純音測試 / 操作檢核）。 */
        const val EXTRA_HL_SIM = "EXTRA_HL_SIM"

        /** 完整測驗的 8 個頻率。 */
        val FULL_FREQS = listOf(1000, 2000, 3000, 4000, 6000, 8000, 500, 250)

        /**
         * 模擬聽損—純音測試只跑 4 個頻率（500/1k/2k/4k）。
         * 這一步的目的是「檢核模擬器確實把聽閾抬高了預期的量」，不是重測完整聽力圖；
         * 4 個頻率已足以驗證，卻能省下一半時間——找人幫忙測試，時間是真實成本。
         */
        val CHECK_FREQS = listOf(1000, 2000, 4000, 500)

        /**
         * 聽損模擬模式的滑桿上限（dBFS）。模擬器把聽閾抬高 HL(f) dB，測試者必須把
         * 音量開到「自身聽閾 + HL」才聽得見，故上限必須放寬到接近滿刻度。
         * 主控頁的餘裕檢查以此為依據，兩者不可各自為政。
         */
        const val HL_SIM_MAX_DBFS = -5
    }

    private var hlSimMode = false
    private var hlSim: HearingLossSim = HearingLossSim.none()
    private var prevIsolation = false

    /**
     * true = 由「測試者測驗流程」呼叫（SubjectSessionActivity 的基準純音／
     * HlSimIntroActivity 的操作檢核），false = 使用者從「快速純音」按鈕直接進入。
     * 只用來在匯出的 CSV 檔名加註記，讓一般模式的「查看歷史紀錄」可以濾掉
     * 受試者測驗流程的資料——不影響任何測驗邏輯或量到的數值。
     */
    private var sessionFlow = false

    private var frequencies = FULL_FREQS
    private val ears = listOf("Right", "Left")

    // 量程下端 −115 dBFS 定義為「輸出振幅歸零」（真正無聲），這樣「完全聽不見」
    // 有明確端點，不會殘留微弱波形誘發幻聽。
    //
    // 為什麼下端要到 −115：位準帳（系統音量鎖最大，0 dBFS ≈ 95–105 dB SPL）下，
    // −99 dBFS ≈ 0–6 dB SPL——年輕的好耳朵在 3–6 kHz「真的聽得到」這個位準
    // （聽覺最敏感區的閾值可低於 0 dB HL）。實測就發生了：多個頻率在舊量程
    // 底端 −99 仍可聽，閾值被地板截斷。截斷值當 dB SL 零點會把後續位準系統性
    // 高估。取樣值烘入 float（24 bit 尾數）在 −115 仍精確可表示；再往下已低於
    // 硬體底噪與聽覺極限，無意義。
    //
    // 基準測驗上限為 −45 dBFS。聽力正常者的聽閾約落在 −105 ~ −75 dBFS，
    // 25 dB HL 約 −68 dBFS；−45 dBFS ≈ 55 dB SPL 仍屬舒適，天花板餘裕充足。
    // 頂到上限會示警（見 ceilingHits）。
    //
    // 聽損模擬模式下上限再放寬到 HL_SIM_MAX_DBFS：模擬器把聽閾抬高了 HL(f) dB，
    // 測試者需要把音量開到「自身閾值 + HL」才聽得見。
    private val minDbfs = -115
    private var maxDbfs = -45
    private val silentSentinel = minDbfs

    /** 頂到滑桿上限的頻率（該次量測不是真正的聽閾，須提醒實驗者）。 */
    private val ceilingHits = mutableListOf<String>()

    private var earIndex = 0
    private var freqIndex = 0
    private var phase = 1                    // 1 = 直接調整, 2 = 下-上折返
    private var phase1Dbfs = 0f
    private var currentDbfs = -70
    private var silent = false              // true = 目前為完全靜音

    // 每耳: freq → 閾值 dBFS（兩步平均）
    private val resultsDbfs = mutableMapOf<String, MutableMap<Int, Float>>(
        "Right" to mutableMapOf(), "Left" to mutableMapOf()
    )

    private val toneGen = AudiometricToneGenerator()
    private var calibTable: Map<Int, FreqCalibration> = emptyMap()
    private var earphoneModel = "其他"
    private var subjectName = "未填寫"
    private lateinit var calibRepo: EarphoneCalibrationRepository

    private lateinit var textProgress: TextView
    private lateinit var textFreq: TextView
    private lateinit var textLevel: TextView
    private lateinit var textInstruction: TextView
    private lateinit var seekLevel: SeekBar
    private lateinit var buttonConfirm: Button
    private var originalMediaVolume = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        disableSystemBackNavigation()
        setContentView(R.layout.activity_self_adjust_pta)
        window.statusBarColor = android.graphics.Color.parseColor("#F5F7FA")
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        textProgress = findViewById(R.id.textSaProgress)
        textFreq = findViewById(R.id.textSaFreq)
        textLevel = findViewById(R.id.textSaLevel)
        textInstruction = findViewById(R.id.textSaInstruction)
        seekLevel = findViewById(R.id.seekSaLevel)
        buttonConfirm = findViewById(R.id.buttonSaConfirm)

        calibRepo = EarphoneCalibrationRepository(this)
        val repository = (application as HarkApplication).eqSettingsRepository
        // 測試者測驗流程會直接帶入姓名/耳機型號 —— 必須優先採用這兩個值，
        // 不能回頭讀 DataStore：呼叫端（SubjectSessionActivity）寫入
        // DataStore 是非同步的，若在此改讀 Flow 會有競態，可能讀到「上一輪
        // 選的耳機」而非「這一輪剛選的耳機」，導致說明頁顯示錯誤型號。
        val extraSubject = intent.getStringExtra("EXTRA_SUBJECT")
        val extraEarphone = intent.getStringExtra("EXTRA_EARPHONE_MODEL")
        hlSimMode = intent.getBooleanExtra(EXTRA_HL_SIM, false)
        sessionFlow = intent.getBooleanExtra("EXTRA_SESSION_FLOW", false)
        // 隔離即時輔聽引擎：若使用者進流程前開著輔聽，麥克風透傳的環境音會
        // 疊在測試音上，聽閾會被底噪墊高。稽核時發現本頁是全流程唯一漏掉
        // 隔離的測驗頁。離場時還原成進場前的狀態（本頁可能被步驟③嵌套呼叫，
        // 不可一律解除——否則會提前打開上一層刻意關閉的引擎）。
        prevIsolation = com.wcy.hark.audio.service.HarkAudioService.audiometryIsolationActive
        com.wcy.hark.audio.service.HarkAudioService.audiometryIsolationActive = true
        try {
            com.wcy.hark.audio.bridge.HarkAudioBridge.setMuted(true)
            com.wcy.hark.audio.bridge.HarkAudioBridge.setBypassMode(true)
        } catch (e: Exception) { /* 引擎未載入時忽略 */ }

        frequencies = if (hlSimMode) CHECK_FREQS else FULL_FREQS
        if (hlSimMode) maxDbfs = HL_SIM_MAX_DBFS   // 模擬損失可達 70 dB，上限必須放寬

        lifecycleScope.launch {
            subjectName = extraSubject ?: repository.getLastSubjectNameFlow().first().ifEmpty { "未填寫" }
            earphoneModel = extraEarphone ?: repository.getSelectedEarphoneFlow().first()
            calibTable = withContext(Dispatchers.IO) { calibRepo.getAllCalibrations(earphoneModel) }

            if (hlSimMode) {
                // 模擬聽損—純音測試：以測試者「未模擬時」剛量到的閾值為零點，
                // 疊上模擬損失量。純音是單頻穩態訊號，擴展器的作用僅是位準映射，
                // 故直接把增益算在音源振幅上即可，不需跑濾波器組。
                val profile = HearingLossProfile.fromKey(repository.getHlSimProfileFlow().first())
                val thresholds = repository.getBinauralRawThresholdsFlow(
                    HearingLossProfile.AUDIOGRAM_FREQS.toList()
                ).first()
                val smearing = repository.getHlSimSmearingFlow().first()
                hlSim = HearingLossSim(profile, thresholds, smearing)
                Log.i("SelfAdjustPta", "HL-sim check: profile=${profile.key} thresholds=$thresholds")
            }
            showIntroDialog()
        }

        // progress 0 = −100 dBFS（真正靜音），1..N = −99..−60 dBFS
        seekLevel.max = maxDbfs - minDbfs
        seekLevel.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) setLevel(minDbfs + progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        findViewById<Button>(R.id.buttonSaMinus).setOnClickListener { setLevel(effLevel() - 1) }
        findViewById<Button>(R.id.buttonSaPlus).setOnClickListener { setLevel(effLevel() + 1) }
        buttonConfirm.setOnClickListener { onConfirm() }
        findViewById<android.view.View>(R.id.buttonSaBack).setOnClickListener { confirmEndEarly() }

        // 記下進場前的媒體音量：本測驗會把音量鎖到最大（校正表前提），
        // 離開時務必還原，否則接下來的語詞測驗會以最大音量播放（過大聲）。
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        originalMediaVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    private fun showIntroDialog() {
        AlertDialog.Builder(this)
            .setTitle("快速純音（自調式）")
            .setMessage(
                "測驗開始後會持續播放「嘟—嘟—」的音。\n\n" +
                "每個頻率有兩個步驟：\n" +
                "步驟 1：把聲音調到「剛好聽得見」後按確認。\n" +
                "步驟 2：先調到「完全聽不見」，再調回「剛好聽得見」，按確認。\n\n" +
                "先測右耳、再測左耳。測驗期間音量鍵已鎖定。\n" +
                "耳機型號：$earphoneModel" +
                (if (calibTable.values.any { it.measuredDbSpl != null }) "（已校準）" else "（未校準，結果為相對值）")
            )
            .setPositiveButton("開始") { _, _ -> startCurrentFrequency() }
            .setNegativeButton("離開") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun currentEar() = ears[earIndex]

    /** 目前有效位準（靜音時回傳 sentinel，供 +/− 連續調整）。 */
    private fun effLevel() = if (silent) silentSentinel else currentDbfs

    private fun startCurrentFrequency() {
        phase = 1
        silent = false
        // 起始位準：一般模式從 −70 dBFS（多數人聽得到）往下調。
        // 模擬模式下閾值被抬高了 HL(f)，故起點也要跟著上移，否則測試者要從
        // 「完全聽不見」按幾十下才聽得到聲音。
        currentDbfs = if (hlSimMode) {
            val f = frequencies[freqIndex]
            val base = hlSim.thresholdsDbfs[f] ?: -75f
            (base + hlSim.targetLossDb(f) - 10f).toInt().coerceIn(minDbfs + 1, maxDbfs)
        } else {
            -70
        }
        updateUi()
        playTone()
    }

    /**
     * 施加聽損模擬後的實際輸出位準。
     * 純音是單頻穩態訊號，擴展器的作用僅是位準映射，故可直接算在音源振幅上。
     * 未啟用模擬時即原值。
     */
    private fun outputDbfs(dbfs: Int): Float {
        if (!hlSimMode || !hlSim.isActive) return dbfs.toFloat()
        val f = frequencies[freqIndex]
        return dbfs + hlSim.toneGainDb(f, dbfs.toFloat())
    }

    private fun playTone() {
        val ear = if (currentEar() == "Right") AudiometricToneGenerator.Ear.RIGHT
                  else AudiometricToneGenerator.Ear.LEFT
        // 150/250 ms：短促的「嗶、嗶」比 300/200 的長音好辨認，測試者不會
        // 把殘響或耳鳴誤當刺激音（實測回饋：長音會懷疑自己幻聽）。
        // durationSec 必須是脈衝週期（0.4 s）的整數倍，loop 回繞才不會斷拍。
        toneGen.play(
            frequencies[freqIndex], outputDbfs(currentDbfs), ear,
            pulsed = true, durationSec = 0.8f, loop = true,
            pulseOnMs = 150.0f, pulseOffMs = 250.0f
        )
    }

    private fun setLevel(dbfs: Int) {
        if (dbfs <= silentSentinel) {
            silent = true
            currentDbfs = minDbfs
            toneGen.mute()          // −100 dBFS = 輸出振幅歸零（真正無聲）
        } else {
            silent = false
            currentDbfs = dbfs.coerceIn(minDbfs + 1, maxDbfs)
            toneGen.setVolumeDbfs(outputDbfs(currentDbfs))
        }
        updateUi()
    }

    private fun updateUi() {
        val earLabel = if (currentEar() == "Right") "右耳" else "左耳"
        textProgress.text = "$earLabel · ${freqIndex + 1} / ${frequencies.size}"
        textFreq.text = "${frequencies[freqIndex]} Hz"
        if (silent) {
            textLevel.text = "$minDbfs dB FS（靜音）"
            seekLevel.progress = 0
        } else {
            val hl = calibRepo.estimateOutputDbhlFromTable(calibTable, frequencies[freqIndex], currentDbfs.toFloat())
            textLevel.text = if (hl != null)
                "$currentDbfs dB FS（≈ ${hl.roundToInt()} dB HL）"
            else "$currentDbfs dB FS"
            seekLevel.progress = currentDbfs - minDbfs
        }
        if (phase == 1) {
            textInstruction.text = "步驟 1／2：拖動滑桿或按 +/−，把聲音調到「剛好聽得見」，然後按確認。"
            buttonConfirm.text = "確認：剛好聽得見"
        } else {
            textInstruction.text = "步驟 2／2：請先調到「完全聽不見」，再往上調回「剛好聽得見」，然後按確認。"
            buttonConfirm.text = "確認：折返後剛好聽得見"
        }
    }

    private fun confirmEndEarly() {
        AlertDialog.Builder(this)
            .setTitle("提早結束測驗")
            .setMessage("確定要結束目前的快速純音測驗嗎？尚未完成的頻率將不會有結果。")
            .setPositiveButton("結束") { _, _ ->
                toneGen.stop()
                setResult(RESULT_CANCELED)
                finish()
            }
            .setNegativeButton("繼續測驗", null)
            .show()
    }

    private fun onConfirm() {
        if (silent) {
            // 靜音狀態代表「聽不見」，不能作為「剛好聽得見」的確認點。
            android.widget.Toast.makeText(
                this, "目前是完全靜音，請往上調到「剛好聽得見」再確認。",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (phase == 1) {
            phase1Dbfs = currentDbfs.toFloat()
            phase = 2
            updateUi()
            return
        }
        // 閾值 = 兩步平均
        val threshold = (phase1Dbfs + currentDbfs) / 2f
        resultsDbfs[currentEar()]!![frequencies[freqIndex]] = threshold

        // 頂到滑桿上限 → 記下的是被截斷的上限值，不是真正的聽閾。這個值是後面
        // 所有 dB SL 的零點，靜默採用會毀掉整條位準鏈，必須讓實驗者知道。
        if (threshold >= maxDbfs - 5f) {
            ceilingHits += "${currentEar()} ${frequencies[freqIndex]} Hz"
        }

        if (freqIndex < frequencies.size - 1) {
            freqIndex++
            startCurrentFrequency()
        } else if (earIndex < ears.size - 1) {
            toneGen.stop()
            AlertDialog.Builder(this)
                .setTitle("右耳完成")
                .setMessage("接下來測左耳，準備好後按開始。")
                .setPositiveButton("開始") { _, _ ->
                    earIndex++; freqIndex = 0
                    startCurrentFrequency()
                }
                .setCancelable(false)
                .show()
        } else {
            toneGen.stop()
            warnCeilingHitsThen { finishTest() }
        }
    }

    /**
     * 有頻率頂到滑桿上限時先示警。這些頻率記下的是被截斷的上限值而非真正的聽閾，
     * 而聽閾正是後面所有 dB SL 的零點——靜默採用會讓整條位準鏈偏掉且無從察覺。
     * 頂到上限通常代表：該測試者的聽力不在正常範圍（本研究前提是聽力正常者），
     * 或耳機輸出偏低／沒戴好。
     */
    private fun warnCeilingHitsThen(next: () -> Unit) {
        if (ceilingHits.isEmpty()) { next(); return }
        AlertDialog.Builder(this)
            .setTitle("⚠️ 有頻率頂到音量上限")
            .setMessage(
                "下列頻率把音量推到滑桿頂端仍在「剛好聽得見」附近：\n\n" +
                        ceilingHits.joinToString("\n") { "· $it" } +
                        "\n\n這些數值不是真正的聽閾（被上限截斷），而聽閾是後續所有 " +
                        "dB SL 位準的零點。可能原因：該測試者聽力不在正常範圍、" +
                        "耳機沒戴好、或耳機輸出偏低。\n\n建議重測；若仍相同，" +
                        "請考慮更換耳機或排除此測試者。"
            )
            .setPositiveButton("重測") { _, _ ->
                ceilingHits.clear()
                earIndex = 0; freqIndex = 0
                resultsDbfs.values.forEach { it.clear() }
                startCurrentFrequency()
            }
            .setNegativeButton("仍要採用") { _, _ -> next() }
            .setCancelable(false)
            .show()
    }

    private fun finishTest() {
        if (hlSimMode) { finishHlSimCheck(); return }

        // dBFS → dB HL（校正表；未校準退回 dbfs + 100 相對映射）
        fun toHlMap(ear: String): HashMap<Int, Int?> {
            val m = HashMap<Int, Int?>()
            resultsDbfs[ear]!!.forEach { (f, dbfs) ->
                val hl = calibRepo.estimateOutputDbhlFromTable(calibTable, f, dbfs) ?: (dbfs + 100f)
                m[f] = hl.roundToInt()
            }
            return m
        }
        val rightHl = toHlMap("Right")
        val leftHl = toHlMap("Left")

        val repository = (application as HarkApplication).eqSettingsRepository
        // 重要：lifecycleScope 綁定本 Activity 的生命週期，若在寫入完成前就
        // finish()，DataStore/CSV 寫入可能在 onDestroy 時被取消，導致下一步
        // （測試者測驗流程套用 DSL v5）讀到舊資料。因此務必等寫入完成後才
        // finish()，兩個分支皆同。
        lifecycleScope.launch(Dispatchers.IO) {
            // 存入聽力圖（供 DSL v5 / NAL-R 處方使用）
            rightHl.forEach { (f, t) -> t?.let { repository.saveAudiogramThreshold("right", f, it) } }
            leftHl.forEach { (f, t) -> t?.let { repository.saveAudiogramThreshold("left", f, it) } }

            // 同時存原始 dBFS 閾值 —— 這是聽損模擬器的「零點」。
            // 所有刺激位準以此為基準的感覺級（dB SL）計算，故整套行為實驗
            // 完全不需要人工耳或聲級計做絕對聲學校正。
            resultsDbfs["Right"]!!.forEach { (f, v) -> repository.saveRawThresholdDbfs("right", f, v) }
            resultsDbfs["Left"]!!.forEach { (f, v) -> repository.saveRawThresholdDbfs("left", f, v) }

            saveCsv(rightHl, leftHl)

            withContext(Dispatchers.Main) {
                if (intent.getBooleanExtra("EXTRA_SESSION_FLOW", false)) {
                    // 測試者測驗流程呼叫：直接回傳，由流程主控頁接續套用處方，
                    // 不中途跳去看聽力圖畫面。
                    setResult(RESULT_OK)
                    finish()
                } else {
                    val resultIntent = Intent(this@SelfAdjustPtaActivity, AudiogramActivity::class.java)
                    resultIntent.putExtra("LEFT_EAR_RESULTS", leftHl as Serializable)
                    resultIntent.putExtra("RIGHT_EAR_RESULTS", rightHl as Serializable)
                    startActivity(resultIntent)
                    finish()
                }
            }
        }
    }

    /**
     * 模擬聽損—純音測試的收尾（操作檢核 / manipulation check）。
     *
     * 檢核邏輯：模擬器把聽閾抬高了 HL(f)，所以「開了模擬後測得的閾值」減去
     * 「未模擬時測得的閾值」應該恰好等於 HL(f)。
     *   measured(f) − baseline(f) ≈ HL(f)，判準 ±5 dB。
     *
     * 這一步用本系統的純音測驗模組去驗證本系統的聽損模擬器，兩者互為交叉驗證：
     * 既確認「模擬確實造成了預期的損失」，也確認「本系統確實量得出該損失」。
     * 因果鏈就此閉合——如果這步過不了，後面的補償效益數字全部不可信。
     *
     * ★ 本模式不得寫入聽力圖或原始閾值 ★ 那會覆蓋掉未模擬時量到的基準線。
     */
    private fun finishHlSimCheck() {
        val freqs = frequencies.sorted()
        val measured = FloatArray(freqs.size)
        val target = FloatArray(freqs.size)
        val error = FloatArray(freqs.size)

        freqs.forEachIndexed { i, f ->
            val r = resultsDbfs["Right"]!![f]
            val l = resultsDbfs["Left"]!![f]
            val m = listOfNotNull(r, l).average().toFloat()
            val base = hlSim.thresholdsDbfs[f] ?: 0f
            measured[i] = m
            target[i] = hlSim.targetLossDb(f)
            error[i] = (m - base) - target[i]        // 實測抬升量 − 目標損失量
        }

        val maxAbsErr = error.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
        val passed = maxAbsErr <= 5f
        Log.i("SelfAdjustPta",
            "HL-sim check: freqs=$freqs target=${target.toList()} " +
                    "error=${error.toList()} maxAbsErr=$maxAbsErr passed=$passed")

        val summary = buildString {
            append(if (passed) "✅ 模擬器檢核通過" else "⚠️ 模擬器檢核未通過")
            append("（最大誤差 ${"%.1f".format(maxAbsErr)} dB，判準 ±5 dB）\n\n")
            freqs.forEachIndexed { i, f ->
                append("$f Hz：目標 ${"%.0f".format(target[i])} dB / ")
                append("實測抬升 ${"%.0f".format(target[i] + error[i])} dB")
                append("（誤差 ${"%+.1f".format(error[i])}）\n")
            }
            if (!passed) {
                append("\n誤差偏大可能來自：測試者調整不穩、耳機漏音，或模擬器參數有誤。")
                append("建議重測一次；若仍未通過，先不要繼續語詞測驗。")
            }
        }

        AlertDialog.Builder(this)
            .setTitle("模擬聽損—純音測試")
            .setMessage(summary)
            .setCancelable(false)
            .setPositiveButton("完成") { _, _ ->
                setResult(RESULT_OK, Intent().apply {
                    putExtra("EXTRA_HLSIM_PASSED", passed)
                    putExtra("EXTRA_HLSIM_MAX_ERR", maxAbsErr)
                    putExtra("EXTRA_HLSIM_SUMMARY", summary)
                })
                finish()
            }
            .show()
    }

    private fun saveCsv(rightHl: Map<Int, Int?>, leftHl: Map<Int, Int?>) {
        // sessionFlow=true 時檔名加註記，供一般模式「查看歷史紀錄」過濾掉受試者測驗流程資料
        val flowTag = if (sessionFlow) "_SubjectFlow" else ""
        val filename = "${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())}_PureTone_SelfAdjust${flowTag}_Results.csv"
        val filePath = "${getExternalFilesDir(null)?.absolutePath}/$filename"
        try {
            FileWriter(filePath).use { w ->
                w.append("Subject Name,$subjectName\n")
                w.append("Mode,SelfAdjusted\n")
                w.append("Earphone,$earphoneModel\n")
                w.append("Calibrated,${calibTable.values.any { it.measuredDbSpl != null }}\n")
                w.append("Ear,Frequency (Hz),Threshold (dB HL)\n")
                listOf("Right" to rightHl, "Left" to leftHl).forEach { (ear, m) ->
                    m.forEach { (f, t) -> w.append("$ear,$f,${t ?: "N/A"}\n") }
                }
                // 原始 dBFS 閾值（解析器會略過此 4 欄格式，僅供研究溯源）
                resultsDbfs.forEach { (ear, m) ->
                    m.forEach { (f, dbfs) -> w.append("RawDbfs,$ear,$f,$dbfs\n") }
                }
            }
            Log.d("SelfAdjustPta", "saved $filePath")
        } catch (e: Exception) {
            Log.e("SelfAdjustPta", "CSV save failed", e)
        }
    }

    // 鎖定媒體音量至最大（與校準/純音測驗相同前提），並封鎖音量鍵
    override fun onResume() {
        super.onResume()
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        toneGen.resume()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        toneGen.pause()
    }

    override fun onDestroy() {
        // 還原進場前的隔離狀態（嵌套呼叫時交還上一層決定）
        com.wcy.hark.audio.service.HarkAudioService.audiometryIsolationActive = prevIsolation
        if (!prevIsolation) {
            try {
                com.wcy.hark.audio.bridge.HarkAudioBridge.setBypassMode(false)
                com.wcy.hark.audio.bridge.HarkAudioBridge.setMuted(false)
            } catch (e: Exception) { /* no-op */ }
        }
        super.onDestroy()
        toneGen.release()
        // 還原進場前的媒體音量（onResume 曾鎖到最大）
        if (originalMediaVolume >= 0) {
            try {
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                am.setStreamVolume(AudioManager.STREAM_MUSIC, originalMediaVolume, 0)
            } catch (e: Exception) { /* best effort */ }
        }
    }
}
