package com.wcy.hark.audiometry

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

/**
 * 停用系統返回（含手勢左滑返回與實體/導覽列返回鍵），僅允許以畫面上的
 * 按鈕操作離開。用於測驗進行中的畫面——避免測試者誤觸手勢返回，導致
 * 測驗流程中斷或資料未存檔就跳出。
 *
 * 透過 OnBackPressedDispatcher 攔截，同時涵蓋傳統返回鍵與 Android 13+
 * 的預測式手勢返回（predictive back），無需在 Manifest 額外設定。
 */
fun ComponentActivity.disableSystemBackNavigation(
    message: String = "請使用畫面上的按鈕離開，測驗中無法用手勢/返回鍵返回"
) {
    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            Toast.makeText(this@disableSystemBackNavigation, message, Toast.LENGTH_SHORT).show()
        }
    })
}
