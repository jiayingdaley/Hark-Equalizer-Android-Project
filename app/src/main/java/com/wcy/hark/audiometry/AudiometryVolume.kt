package com.wcy.hark.audiometry

import android.app.Activity
import android.media.AudioManager

/**
 * AudiometryVolume — 把系統媒體音量鎖到最大，所有位準控制一律在數位域（dBFS）進行。
 *
 * ★ 為什麼一定要鎖，而且要鎖在「最大」★
 *
 * 本實驗的位準基準是 dB SL（感覺級）：以測試者自己的純音聽閾當零點，因此不需要
 * 人工耳或聲級計做絕對聲學校正。但這個基準只有在「量聽閾」與「放刺激」處於
 * 同一個電聲增益下才成立。
 *
 * 曾經的錯誤：純音測驗在系統音量最大時測得聽閾（如 −72 dBFS），語詞測驗卻讓測試者
 * 自行調整「舒適音量」。Android 的音量曲線從最大降到約四成輕易就是 −20～−30 dB，
 * 於是「−72 + 25 = −47 dBFS ＝ 25 dB SL」這個換算在播放端根本不成立——那個詞實際
 * 落在聽閾附近甚至以下。聽損模擬器又照數位位準再衰減一次，結果是輔助與未輔助
 * 兩個條件通通聽不見，正確率全趴在地板上，A/B 對照什麼都測不到。
 *
 * 為什麼是「最大」而不是某個舒適音量：模擬 55 dB 的高頻損失、再把語音呈現在
 * 25 dB SL，需要「聽閾 + 損失 + 呈現級」的數位餘裕。系統音量每調小一格，測得的
 * 聽閾就往 0 dBFS 靠近一格，可用餘裕就少一格。鎖在最大時餘裕最大（聽閾約
 * −70 dBFS → 有 70 dB 可用），調小就不夠模擬中重度損失了。
 *
 * 音量鎖到最大並不代表聲音會很大：刺激的數位位準本來就很低（語詞約 −47 dBFS、
 * 純音約 −70 dBFS），衰減全在數位域完成。這正是聽力計的標準做法——輸出級固定，
 * 靠數位衰減決定呈現級。
 */
object AudiometryVolume {

    /**
     * 鎖到最大並回傳進場前的音量，供離場時還原。
     * 請在 onResume() 呼叫（使用者可能在測驗中途按了實體音量鍵）。
     */
    fun lockToMax(activity: Activity): Int {
        val am = activity.getSystemService(Activity.AUDIO_SERVICE) as AudioManager
        val original = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            0
        )
        return original
    }

    /** 還原進場前的媒體音量。 */
    fun restore(activity: Activity, original: Int) {
        try {
            val am = activity.getSystemService(Activity.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0)
        } catch (e: Exception) { /* best effort */ }
    }
}
