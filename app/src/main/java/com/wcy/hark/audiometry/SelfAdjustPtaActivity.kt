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
 * 受試者自行把脈衝純音調到「剛好聽得見」：每個頻率做兩步——
 * 步驟1 直接調到剛好聽得見；步驟2 先調到完全聽不見、再調回剛好聽得見
 * （一次下-上折返）。閾值 = 兩步 dBFS 平均。
 *
 * 位準範圍 −100 ~ −60 dBFS、1 dB 解析度；播放走浮點 PCM（16-bit 量化
 * 底線 −90.3 dBFS 以下即靜音，浮點路徑才能呈現正常聽力者的閾值區）。
 * 頻率序列與標準純音測驗相同（8 頻率，1k 起、不重測）。
 * 結果經耳機校正表換算為 dB HL，輸出與標準純音相容的 CSV 供歷史紀錄。
 */
class SelfAdjustPtaActivity : AppCompatActivity() {

    private val frequencies = listOf(1000, 2000, 3000, 4000, 6000, 8000, 500, 250)
    private val ears = listOf("Right", "Left")

    private val minDbfs = -100
    private val maxDbfs = -60

    private var earIndex = 0
    private var freqIndex = 0
    private var phase = 1                    // 1 = 直接調整, 2 = 下-上折返
    private var phase1Dbfs = 0f
    private var currentDbfs = -80

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        lifecycleScope.launch {
            subjectName = repository.getLastSubjectNameFlow().first().ifEmpty { "未填寫" }
            earphoneModel = repository.getSelectedEarphoneFlow().first()
            calibTable = withContext(Dispatchers.IO) { calibRepo.getAllCalibrations(earphoneModel) }
            showIntroDialog()
        }

        seekLevel.max = maxDbfs - minDbfs
        seekLevel.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) setLevel(minDbfs + progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        findViewById<Button>(R.id.buttonSaMinus).setOnClickListener { setLevel(currentDbfs - 1) }
        findViewById<Button>(R.id.buttonSaPlus).setOnClickListener { setLevel(currentDbfs + 1) }
        buttonConfirm.setOnClickListener { onConfirm() }
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

    private fun startCurrentFrequency() {
        phase = 1
        currentDbfs = -75    // 起始位準：多數人聽得到，往下調
        updateUi()
        playTone()
    }

    private fun playTone() {
        val ear = if (currentEar() == "Right") AudiometricToneGenerator.Ear.RIGHT
                  else AudiometricToneGenerator.Ear.LEFT
        toneGen.play(
            frequencies[freqIndex], currentDbfs.toFloat(), ear,
            pulsed = true, durationSec = 1.0f, loop = true, bakeVolume = false
        )
    }

    private fun setLevel(dbfs: Int) {
        currentDbfs = dbfs.coerceIn(minDbfs, maxDbfs)
        toneGen.setVolumeDbfs(currentDbfs.toFloat())
        updateUi()
    }

    private fun updateUi() {
        val earLabel = if (currentEar() == "Right") "右耳" else "左耳"
        textProgress.text = "$earLabel · ${freqIndex + 1} / ${frequencies.size}"
        textFreq.text = "${frequencies[freqIndex]} Hz"
        val hl = calibRepo.estimateOutputDbhlFromTable(calibTable, frequencies[freqIndex], currentDbfs.toFloat())
        textLevel.text = if (hl != null)
            "$currentDbfs dB FS（≈ ${hl.roundToInt()} dB HL）"
        else "$currentDbfs dB FS"
        seekLevel.progress = currentDbfs - minDbfs
        if (phase == 1) {
            textInstruction.text = "步驟 1／2：拖動滑桿或按 +/−，把聲音調到「剛好聽得見」，然後按確認。"
            buttonConfirm.text = "確認：剛好聽得見"
        } else {
            textInstruction.text = "步驟 2／2：請先調到「完全聽不見」，再往上調回「剛好聽得見」，然後按確認。"
            buttonConfirm.text = "確認：折返後剛好聽得見"
        }
    }

    private fun onConfirm() {
        if (phase == 1) {
            phase1Dbfs = currentDbfs.toFloat()
            phase = 2
            updateUi()
            return
        }
        // 閾值 = 兩步平均
        val threshold = (phase1Dbfs + currentDbfs) / 2f
        resultsDbfs[currentEar()]!![frequencies[freqIndex]] = threshold

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
            finishTest()
        }
    }

    private fun finishTest() {
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
        lifecycleScope.launch(Dispatchers.IO) {
            // 存入聽力圖（供 DSL v5 / NAL-R 處方使用）
            rightHl.forEach { (f, t) -> t?.let { repository.saveAudiogramThreshold("right", f, it) } }
            leftHl.forEach { (f, t) -> t?.let { repository.saveAudiogramThreshold("left", f, it) } }
            saveCsv(rightHl, leftHl)
        }

        val intent = Intent(this, AudiogramActivity::class.java)
        intent.putExtra("LEFT_EAR_RESULTS", leftHl as Serializable)
        intent.putExtra("RIGHT_EAR_RESULTS", rightHl as Serializable)
        startActivity(intent)
        finish()
    }

    private fun saveCsv(rightHl: Map<Int, Int?>, leftHl: Map<Int, Int?>) {
        val filename = "${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())}_PureTone_SelfAdjust_Results.csv"
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
        super.onDestroy()
        toneGen.release()
    }
}
