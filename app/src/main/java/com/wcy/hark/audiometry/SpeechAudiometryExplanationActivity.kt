package com.wcy.hark.audiometry
import com.wcy.hark.R

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class SpeechAudiometryExplanationActivity : AppCompatActivity(), DialogNavCallback { // 在這裡加上 ", DialogNavCallback"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_speech_audiometry_explanation)

        val buttonStartVolumeAdjustment: Button = findViewById(R.id.buttonStartVolumeAdjustment)

        buttonStartVolumeAdjustment.setOnClickListener {
            // 初始呼叫以顯示音量調整 Dialog
            VolumeAdjustmentDialogFragment().show(supportFragmentManager, VolumeAdjustmentDialogFragment.TAG)
        }
    }

    // --- DialogNavCallback 實現 ---
    override fun onVolumeAdjustedShowInstructions() {
        // 從 VolumeAdjustmentDialogFragment 呼叫，顯示測驗說明 Dialog
        TestInstructionsDialogFragment().show(supportFragmentManager, TestInstructionsDialogFragment.TAG)
    }

    override fun onInstructionsDismissedShowVolume() {
        // 從 TestInstructionsDialogFragment 的 "X" 呼叫，重新顯示音量調整 Dialog
        VolumeAdjustmentDialogFragment().show(supportFragmentManager, VolumeAdjustmentDialogFragment.TAG)
    }

    override fun onStartSrtTestFromInstructions() {
        // 從 TestInstructionsDialogFragment 的 "Start Test" 呼叫，啟動 SRT 測試 Activity
        val intent = Intent(this, SRTTestActivity::class.java) // 假設 SRTTestActivity 已/將被建立
        startActivity(intent)
        // 你可以考慮在這裡 finish() 這個 SpeechAudiometryExplanationActivity，如果後續流程不需要再回到它
        // finish()
    }
}