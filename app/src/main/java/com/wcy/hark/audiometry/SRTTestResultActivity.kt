package com.wcy.hark.audiometry
import com.wcy.hark.R

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale // For String.format

class SRTTestResultActivity : AppCompatActivity() {

    private lateinit var textViewAccuracyValue: TextView
    private lateinit var textViewResultComment: TextView
    private lateinit var buttonFinishTestView: Button
    private lateinit var textViewCorrectCountValue: TextView

    companion object {
        const val TAG = "SRTTestResultActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_srt_test_result)

        // 初始化 UI 元件
        textViewCorrectCountValue = findViewById(R.id.textViewCorrectCountValue) // 新增
        textViewAccuracyValue = findViewById(R.id.textViewAccuracyValue)
        textViewResultComment = findViewById(R.id.textViewResultComment)
        buttonFinishTestView = findViewById(R.id.buttonFinishTestView)

        // 從 Intent 接收資料
        val sessionId = intent.getLongExtra("EXTRA_SESSION_ID", -1L)
        val accuracy = intent.getDoubleExtra("EXTRA_ACCURACY", 0.0)
        val totalAnswered = intent.getIntExtra("EXTRA_TOTAL_ANSWERED", 0)
        val correctCount = intent.getIntExtra("EXTRA_CORRECT_COUNT", 0) // 新增

        Log.d(TAG, "Received results - Session ID: $sessionId, Correct: $correctCount, Total: $totalAnswered, Accuracy: $accuracy%")

        // Display Accuracy
        textViewAccuracyValue.text = String.format(Locale.getDefault(), "%.0f%%", accuracy) // Display as integer percentage

        // 顯示答對題數 (例如 "5 / 10")
        textViewCorrectCountValue.text = "$correctCount / $totalAnswered"

        // Determine and Display Comment
        textViewResultComment.text = getCommentForAccuracy(accuracy)

        buttonFinishTestView.setOnClickListener {
            // Navigate back to the main screen or test selection screen
            // For example, clear activity stack and go to TestSelectActivity
            // val intent = Intent(this, TestSelectActivity::class.java)
            // intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            // startActivity(intent)
            finish() // Or simply finish this activity to go back to the previous one in stack
        }
    }

    private fun getCommentForAccuracy(accuracy: Double): String {
        return when {
            accuracy >= 90.0 -> "辨識得非常好喔，已經很準確，不需要再調整了！"
            accuracy >= 78.0 -> "表現不錯，效果已經蠻好了，不太需要再調整喔！"
            accuracy >= 66.0 -> "聽起來效果不是太理想，建議再重新測一次語詞測試，或是重新調整等化器。"
            accuracy >= 54.0 -> "聽起來效果不是太理想，建議再重新測一次語詞測試，或是重新調整等化器。"
            else -> "目前的效果真的不太好，建議再重新測一次，會比較準喔！"
        }
    }
}