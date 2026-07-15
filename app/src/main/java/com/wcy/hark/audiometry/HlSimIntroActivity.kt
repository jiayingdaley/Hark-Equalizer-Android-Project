package com.wcy.hark.audiometry

import android.app.Activity
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wcy.hark.HarkApplication
import com.wcy.hark.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * HlSimIntroActivity — 「模擬聽損—純音測試」的第一頁：聽損體驗。
 *
 * 為什麼要有這一頁：
 *   測試者是聽力正常的人，他們對「聽損是什麼感覺」毫無概念。讓他們親耳
 *   聽過三種狀態的對比之後，(1) 立刻理解自己接下來在做什麼、(2) 作答動機
 *   明顯提高、(3) 頻譜模糊的效果在這裡最有感——「音量夠大，可是字都糊在
 *   一起」，那個瞬間的體驗勝過任何口頭說明。
 *
 *   這一頁同時是流程的合理性檢查：如果測試者說「補償那個比較清楚」，
 *   就知道模擬器與處方的方向沒有接反。
 *
 * 三個狀態共用同一段語音，差別只在離線處理鏈：
 *   ① 正常聽力      ：語音 → 播放
 *   ② 模擬聽損      ：語音 →              [聽損模擬] → 播放
 *   ③ 聽損 + Hark   ：語音 → [DSP 補償] → [聽損模擬] → 播放
 *
 * ★ 注意順序 ★ 補償在前、模擬在後。真實情境是助聽器先處理、聲音才進入受損
 * 的耳蝸；反過來檢驗的就是「訊號還原」而不是助聽器。
 */
class HlSimIntroActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HlSimIntro"
        /** 體驗用語音：從詞庫取幾個含高頻擦音的詞，最能聽出高頻損失的差別。
         *  ★ 資源列號 r2–r51（r1 是 CSV 表頭，沒有音檔）★ 曾誤填 r1_c1，
         *  該詞每次都靜默跳過，三顆按鈕只播兩個詞。
         *  r10_c1 竹筍(/ʈʂ/、/s/)、r12_c3 稀少(/ɕ/、/ʂ/)、r9_c4 升天(/ʂ/、/tʰ/)。 */
        private val DEMO_WORDS = listOf("hselist4_r10_c1", "hselist4_r12_c3", "hselist4_r9_c4")
        /** 體驗時的呈現位準：相對測試者自身聽閾的感覺級（dB SL）。
         *  ★ 不要調高 ★ 曾設 35 dB SL，S1 低中頻損失僅 10–15 dB，母音幾乎
         *  原樣通過，測試者聽不出①②差別、以為模擬沒套用。25 dB SL 時
         *  2 kHz 以上落於模擬聽閾下，對比才明顯。 */
        private const val DEMO_SL_DB = 25f
    }

    private lateinit var mixer: SsnAudioMixer
    private lateinit var textStatus: TextView

    private var hlSim: HearingLossSim = HearingLossSim.none()
    private var dspGains: FloatArray? = null
    private var anchorDbfs = -55f
    private var ready = false

    private var subjectName = "未填寫"
    private var earphoneModel = "其他"
    private var originalMediaVolume = 0

    private val ptaLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // 把純音檢核的結果原樣往上回傳給測試者實驗流程主控頁
        setResult(result.resultCode, result.data)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hl_sim_intro)

        textStatus = findViewById(R.id.textHlSimStatus)
        mixer = SsnAudioMixer(this)

        subjectName = intent.getStringExtra("EXTRA_SUBJECT") ?: "未填寫"
        earphoneModel = intent.getStringExtra("EXTRA_EARPHONE_MODEL") ?: "其他"

        // 測驗期間隔離即時輔聽引擎，避免麥克風透傳訊號污染刺激
        com.wcy.hark.audio.service.HarkAudioService.audiometryIsolationActive = true
        try {
            com.wcy.hark.audio.bridge.HarkAudioBridge.setMuted(true)
            com.wcy.hark.audio.bridge.HarkAudioBridge.setBypassMode(true)
            com.wcy.hark.audio.manager.SystemDspManager.setEnabled(false)
        } catch (e: Exception) {
            Log.w(TAG, "engine mute skipped: ${e.message}")
        }

        findViewById<Button>(R.id.buttonDemoNormal).setOnClickListener { playDemo(Mode.NORMAL) }
        findViewById<Button>(R.id.buttonDemoLoss).setOnClickListener { playDemo(Mode.LOSS) }
        findViewById<Button>(R.id.buttonDemoAided).setOnClickListener { playDemo(Mode.AIDED) }
        findViewById<Button>(R.id.buttonHlSimStart).setOnClickListener { startPtaCheck() }

        val repository = (application as HarkApplication).eqSettingsRepository
        lifecycleScope.launch {
            val profile = HearingLossProfile.fromKey(repository.getHlSimProfileFlow().first())
            val thresholds = repository.getBinauralRawThresholdsFlow(
                HearingLossProfile.AUDIOGRAM_FREQS.toList()
            ).first()
            val smearing = repository.getHlSimSmearingFlow().first()
            hlSim = HearingLossSim(profile, thresholds, smearing)

            val l = repository.getBandGainsFlow("left", 0, 16).first()
            val r = repository.getBandGainsFlow("right", 0, 16).first()
            dspGains = FloatArray(16) { i ->
                (l.getOrElse(i) { 0f } + r.getOrElse(i) { 0f }) / 2f
            }

            // 體驗音量錨定在測試者自身的語音頻率閾值上（dB SL 尺度）
            val speech = listOf(500, 1000, 2000).mapNotNull { thresholds[it] }
            anchorDbfs = if (speech.isEmpty()) -55f else speech.average().toFloat()

            ready = true
            // 數位餘裕檢核：模擬只靠衰減，重度組態（如 N5）在體驗位準下
            // 「閾值＋損失＋SL」會超過滿刻度——②近乎全靜音、③被 +30 dB
            // 上限與 MPO 截斷，三段音量必然亂掉。這不是 bug，是組態不可行。
            val required = HearingLossProfile.requiredLevelDbfs(
                profile, thresholds, HearingLossProfile.AUDIOGRAM_FREQS.toList()
            ) + DEMO_SL_DB
            val headroomWarn = if (!profile.isNone && required > -3f)
                "\n⚠️ 此組態超出數位餘裕（需 %.0f dBFS），體驗與測驗結果不具意義；請改用 S1／N1／N2。".format(required)
            else ""
            textStatus.text = if (profile.isNone) {
                "⚠️ 目前未設定模擬聽力圖。請回到主控頁選擇後再進行。"
            } else {
                "模擬聽力圖：${profile.label}" +
                        (if (smearing) "　+　頻譜模糊（聽得見但聽不懂）" else "") +
                        "\n請依序按上面三個按鈕，聽聽看差別。" + headroomWarn
            }
        }
    }

    private enum class Mode { NORMAL, LOSS, AIDED }

    /**
     * 三種狀態共用同一段語音，只換離線處理鏈的組態。
     * 呈現位準（dB SL）三者完全相同——差別只來自處理鏈，不來自音量。
     */
    private fun playDemo(mode: Mode) {
        if (!ready) return

        mixer.hearingLossSim = when (mode) {
            Mode.NORMAL -> HearingLossSim.none()
            Mode.LOSS, Mode.AIDED -> hlSim
        }
        mixer.dspGainsDb = if (mode == Mode.AIDED) dspGains else null

        val level = (anchorDbfs + DEMO_SL_DB).coerceAtMost(-3f)

        // 顯示模擬器對各頻率實際施加的衰減量——既是給實驗者的「有套用」證明，
        // 也讓「高頻整個聽不見」有數字可對照。
        val attenNote = if (mode == Mode.LOSS || mode == Mode.AIDED) {
            val per = intArrayOf(500, 1000, 2000, 4000).joinToString("  ") { f ->
                val g = hlSim.toneGainDb(f, level)
                if (g <= -60f) "${f}Hz:✕" else "${f}Hz:%.0fdB".format(g)
            }
            "\n模擬衰減　$per（✕ = 低於模擬聽閾）"
        } else ""

        textStatus.text = when (mode) {
            Mode.NORMAL -> "▶ 正常聽力"
            Mode.LOSS -> "▶ 模擬聽損 —— 這就是聽損者聽到的樣子$attenNote"
            Mode.AIDED -> "▶ 模擬聽損 + Hark 補償$attenNote"
        }
        playSequence(0, level)
    }

    /** 依序播完三個詞（間隔 300 ms），讓差別聽得比較清楚。 */
    private fun playSequence(index: Int, levelDbfs: Float) {
        if (index >= DEMO_WORDS.size) return
        val resId = resources.getIdentifier(DEMO_WORDS[index], "raw", packageName)
        if (resId == 0) {
            Log.e(TAG, "missing demo audio ${DEMO_WORDS[index]}")
            playSequence(index + 1, levelDbfs)
            return
        }
        val r = mixer.playWordQuiet(resId, levelDbfs, nlfc = false)
        if (r.durationMs > 0) {
            textStatus.postDelayed({ playSequence(index + 1, levelDbfs) }, r.durationMs + 300)
        }
    }

    /** 進入「模擬聽損—純音測試」本體：開著模擬器測 4 個頻率，檢核抬升量。 */
    private fun startPtaCheck() {
        mixer.stop()
        ptaLauncher.launch(
            Intent(this, SelfAdjustPtaActivity::class.java).apply {
                putExtra(SelfAdjustPtaActivity.EXTRA_HL_SIM, true)
                putExtra("EXTRA_SESSION_FLOW", true)
                putExtra("EXTRA_SUBJECT", subjectName)
                putExtra("EXTRA_EARPHONE_MODEL", earphoneModel)
            }
        )
    }

    override fun onResume() {
        super.onResume()
        // 聽損體驗的呈現位準是 dB SL（以測試者自身聽閾為零點），而聽閾是在
        // 系統音量最大時量的 —— 此處必須用同一個電聲增益，換算才成立。
        originalMediaVolume = AudiometryVolume.lockToMax(this)
    }

    // 實體音量鍵一併攔下。若讓測試者在這頁把系統音量調小，三個體驗按鈕的
    // dB SL 換算立刻失真（實測發現這個洞：體驗頁可以調音量）。攔鍵之外，
    // onResume 的重新鎖定是第二道保險。
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            android.widget.Toast.makeText(
                this, "測驗期間音量由程式控制（覺得太大聲請告訴實驗者）",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        mixer.stop()
        AudiometryVolume.restore(this, originalMediaVolume)
    }

    override fun onDestroy() {
        // 解除進場時設下的引擎隔離。漏掉這段的後果是實測踩到的：步驟③結束後
        // 引擎一路維持靜音＋旁通，到了問卷步驟「環境輔聽」完全沒聲音、沒作用。
        com.wcy.hark.audio.service.HarkAudioService.audiometryIsolationActive = false
        try {
            com.wcy.hark.audio.bridge.HarkAudioBridge.setBypassMode(false)
            com.wcy.hark.audio.bridge.HarkAudioBridge.setMuted(false)
        } catch (e: Exception) {
            Log.w(TAG, "engine unmute skipped: ${e.message}")
        }
        mixer.release()
        super.onDestroy()
    }
}
