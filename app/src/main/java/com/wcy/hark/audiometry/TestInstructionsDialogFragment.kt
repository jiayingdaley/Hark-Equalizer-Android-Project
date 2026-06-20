package com.wcy.hark.audiometry
import com.wcy.hark.R

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import androidx.fragment.app.DialogFragment

class TestInstructionsDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "TestInstructionsDialog" // 新增 TAG
    }

    private lateinit var callback: DialogNavCallback // 新增 callback 變數

    override fun onAttach(context: Context) { // 新增 onAttach
        super.onAttach(context)
        if (context is DialogNavCallback) {
            callback = context
        } else {
            throw RuntimeException("$context must implement DialogNavCallback")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_test_instructions, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val buttonCloseInstructionsDialog: ImageButton = view.findViewById(R.id.buttonCloseInstructionsDialog)
        val buttonStartSrtTest: Button = view.findViewById(R.id.buttonStartSrtTest)

        // 根據你的要求，此 Dialog 點擊視窗外區域無效
        isCancelable = false

        buttonCloseInstructionsDialog.setOnClickListener {
            // 點擊 "X"，透過 callback 通知 Activity 重新顯示音量調整 Dialog
            callback.onInstructionsDismissedShowVolume()
            dismiss() // 關閉自己
        }

        buttonStartSrtTest.setOnClickListener {
            // 點擊 "Start Test"，透過 callback 通知 Activity 啟動 SRT 測試
            callback.onStartSrtTestFromInstructions()
            dismiss() // 關閉自己
        }
    }

    override fun onStart() {
        super.onStart()
        // 設定 DialogFragment 的寬高
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}