package com.wcy.hark.audiometry

interface DialogNavCallback {
    fun onVolumeAdjustedShowInstructions() // 當音量調整完成，請求顯示測驗說明
    fun onInstructionsDismissedShowVolume() // 當測驗說明被關閉(X)，請求重新顯示音量調整
    fun onStartSrtTestFromInstructions()    // 當測驗說明中點擊開始測試
}