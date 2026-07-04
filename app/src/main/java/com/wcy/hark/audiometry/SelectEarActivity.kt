package com.wcy.hark.audiometry
import com.wcy.hark.R

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.sqrt

class SelectEarActivity : ComponentActivity() {

    private lateinit var leftButton: Button
    private lateinit var rightButton: Button
    private lateinit var noiseTextView: TextView
    private lateinit var noiseLevelText: TextView
    private lateinit var noiseScaleBar: LinearLayout
    private lateinit var noiseIndicator: ImageView
    private var audioRecord: AudioRecord? = null
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    private val handler = Handler(Looper.getMainLooper())
    private var isRecording = false
    private var environmentalNoiseLevel = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_select_ear)
        window.statusBarColor = android.graphics.Color.parseColor("#F5F7FA")
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        leftButton = findViewById(R.id.button_left_ear)
        rightButton = findViewById(R.id.button_right_ear)
        noiseTextView = findViewById(R.id.textView_noise_level)
        noiseLevelText = findViewById(R.id.noise_level_text)
        noiseScaleBar = findViewById(R.id.noise_scale_bar)
        noiseIndicator = findViewById(R.id.noise_indicator)

        // 設定按鈕顏色（聽力學慣例：左耳藍、右耳紅）；用 tint 保留圓角背景
        leftButton.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(33, 102, 204))
        leftButton.setTextColor(Color.WHITE)
        rightButton.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(211, 47, 47))
        rightButton.setTextColor(Color.WHITE)

        // 檢查麥克風權限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO_PERMISSION_CODE)
        } else {
            startRecording()
        }

        leftButton.setOnClickListener {
            checkNoiseAndStartTest("Left")
        }

        rightButton.setOnClickListener {
            checkNoiseAndStartTest("Right")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRecording()
            } else {
                showPermissionDeniedDialog()
            }
        }
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            try {
                audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)
                if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                    audioRecord?.startRecording()
                    isRecording = true
                    handler.post(object : Runnable {
                        override fun run() {
                            if (isRecording) {
                                val buffer = ShortArray(bufferSize / 2)
                                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                                if (read > 0) {
                                    environmentalNoiseLevel = calculateSPL(buffer, read)
                                    noiseTextView.text = String.format(Locale.getDefault(), "%.2f dB SPL", environmentalNoiseLevel)
                                    updateNoiseLevelBar(environmentalNoiseLevel)
                                }
                                handler.postDelayed(this, 100) // 每 100 毫秒更新一次
                            }
                        }
                    })
                } else {
                    Log.e("SelectEarActivity", "AudioRecord initialization failed")
                }
            } catch (e: SecurityException) {
                Log.e("SelectEarActivity", "SecurityException: ${e.message}")
                // 可以提示使用者開啟麥克風權限
            } catch (e: IllegalArgumentException) {
                Log.e("SelectEarActivity", "IllegalArgumentException: ${e.message}")
            }
        } else {
            Log.w("SelectEarActivity", "Audio recording permission not granted")
            showPermissionDeniedDialog()
        }
    }

    /**
     * 麥克風權限被拒：噪音檢測無法進行，但純音測驗本身不需要麥克風，
     * 因此允許使用者「略過噪音檢測」繼續測驗（噪音記為未量測）。
     */
    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要麥克風權限")
            .setMessage("環境噪音檢測需要麥克風權限。您可以前往設定開啟權限，" +
                        "或略過噪音檢測直接進行測驗（結果將標記為噪音未量測）。")
            .setPositiveButton("前往設定") { _, _ ->
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
            .setNeutralButton("略過噪音檢測") { _, _ ->
                environmentalNoiseLevel = 0.0
                noiseTextView.text = "噪音未量測"
                noiseLevelText.text = "噪音未量測"
            }
            .setNegativeButton("離開") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun stopRecording() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun calculateSPL(buffer: ShortArray, readSize: Int): Double {
        var sum = 0.0
        for (i in 0 until readSize) {
            sum += buffer[i] * buffer[i]
        }
        if (readSize > 0) {
            val rms = sqrt(sum / readSize)
            return 20 * log10(rms / 32767.0) + 94 // 假設麥克風靈敏度接近 -6 dBFS for 94 dB SPL
        }
        return 0.0
    }

    private fun updateNoiseLevelBar(spl: Double) {
        noiseLevelText.text = String.format(Locale.getDefault(), "%.2f dB SPL", spl) // 更新數值顯示

        val scaleBarWidth = noiseScaleBar.width
        if (scaleBarWidth > 0) {
            val indicatorLayoutParams = noiseIndicator.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            var horizontalBias = 0.0f

            when {
                spl < 30 -> {
                    horizontalBias = ((spl / 30f) / 3f).toFloat() // 綠色區域佔總寬度的 1/3
                }
                spl in 30.0..50.0 -> {
                    horizontalBias =
                        ((1f / 3f) + ((spl - 30f) / 20f) / 3f).toFloat() // 黃色區域從 1/3 開始，佔總寬度的 1/3
                }
                else -> {
                    horizontalBias =
                        ((2f / 3f) + ((spl - 50f) / 50f) / 3f).toFloat() // 紅色區域從 2/3 開始，佔總寬度的 1/3
                    if (horizontalBias > 1f) {
                        horizontalBias = 1f // 防止超出範圍
                    }
                }
            }

            indicatorLayoutParams.horizontalBias = horizontalBias
            noiseIndicator.layoutParams = indicatorLayoutParams
        } else {
            // 如果 scaleBar 的寬度還沒確定，可以先記錄下來，在佈局完成後再更新
            noiseScaleBar.post { updateNoiseLevelBar(spl) }
        }
    }

    private fun checkNoiseAndStartTest(ear: String) {
        stopRecording() // 停止噪音監測

        when {
            environmentalNoiseLevel < 30 -> {
                AlertDialog.Builder(this)
                    .setMessage("🟢 Quiet Environment")
                    .setPositiveButton("OK") { dialog, which ->
                        startPureToneTest(ear, environmentalNoiseLevel)
                    }
                    .show()
            }
            environmentalNoiseLevel in 30.0..50.0 -> {
                AlertDialog.Builder(this)
                    .setMessage("🟡 Moderate Environment\nMight affect low-frequency results; suggest finding a quieter place.")
                    .setPositiveButton("OK") { dialog, which ->
                        startRecording() // 跳回原頁面，重新監測
                    }
                    .setNegativeButton("Skip") { dialog, which ->
                        startPureToneTest(ear, environmentalNoiseLevel)
                    }
                    .show()
            }
            else -> {
                AlertDialog.Builder(this)
                    .setMessage("🔴 Noisy Environment\nNot suitable; background noise will interfere with the test.")
                    .setPositiveButton("OK") { dialog, which ->
                        startRecording() // 跳回原頁面，重新監測
                    }
                    .setNegativeButton("Skip") { dialog, which ->
                        startPureToneTest(ear, environmentalNoiseLevel)
                    }
                    .show()
            }
        }
    }

    private fun startPureToneTest(ear: String, noiseLevel: Double) {
        val intent = Intent(this, PureToneTestActivity::class.java)
        intent.putExtra("TESTING_EAR", ear)
        intent.putExtra("ENVIRONMENTAL_NOISE", noiseLevel)
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
    }

    companion object {
        private const val REQUEST_AUDIO_PERMISSION_CODE = 200
    }
}