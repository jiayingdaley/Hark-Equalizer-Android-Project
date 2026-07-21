package com.wcy.hark.audiometry

import android.content.ContentValues
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.wcy.hark.HarkApplication
import com.wcy.hark.audio.bridge.HarkAudioBridge
import com.wcy.hark.audio.manager.SceneManager
import com.wcy.hark.audio.service.HarkAudioService
import com.wcy.hark.audiometry.sqlite.SRTResultContract
import com.wcy.hark.audiometry.sqlite.SRTResultDbHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * v2 比較制情境題：每情境一題「開啟輔聽（ON）相對關閉（OFF）」之 −3～+3 直接比較。
 * scene key 沿用 v1（對應 experiment_scenes/ 之情境音檔），每題對準該情境
 * 最相關的處理機制，anchor 文字為量表兩端之語意。
 */
private data class Scene(
    val key: String, val title: String, val question: String,
    val negLabel: String, val posLabel: String, val note: String? = null
)

private val SCENES = listOf(
    Scene(
        "scene1_babble", "情境 1：多人交談背景",
        "開啟輔聽後，周圍交談聲的吵雜／干擾感？",
        negLabel = "更吵、更干擾", posLabel = "明顯較不吵"
    ),
    Scene(
        "scene2_speech_quiet", "情境 2：安靜下的語音",
        "開啟輔聽後，語音聽起來的清楚程度（輪廓與細節）？",
        negLabel = "更不清楚", posLabel = "明顯更清楚",
        note = "情境音為 ISTS 多語混剪、無法聽懂內容，請純就聲音清楚程度判斷。"
    ),
    Scene(
        "scene3_steady_noise", "情境 3：穩態噪音（冷氣/車流感）",
        "開啟輔聽後，持續噪音的干擾程度？",
        negLabel = "更干擾", posLabel = "明顯較不干擾"
    ),
    Scene(
        "scene4_transients", "情境 4：突發聲（現場拍手）",
        "開啟輔聽後，拍手等突發聲聽起來？",
        negLabel = "更刺耳／有爆音", posLabel = "明顯較不刺耳",
        note = "由實驗者於固定距離拍手數次。"
    ),
    Scene(
        "scene5_speech_in_babble", "情境 5：語音＋交談背景",
        "開啟輔聽後，交談背景中主要語音的突出程度？",
        negLabel = "更難分辨", posLabel = "明顯更突出"
    ),
)

/** 情境題未作答之哨兵值（0 是合法答案「沒差別」，不能拿 0 當未答）。 */
private const val DELTA_UNANSWERED = -99

/**
 * QuestionnaireActivity — 環境輔聽問卷 v2（測試者實驗流程最後一步）。
 *
 * 每個情境以喇叭播放情境音（見 experiment_scenes/；情境 4 為實驗者現場拍手）：
 * 先 OFF 聆聽約 30 秒 → 切 ON 聆聽約 30 秒 → 立即回答該情境「一題」比較題
 * （ON 相對 OFF，−3～+3，0＝沒差別；SSQ-B benefit 邏輯）。v1 的「OFF/ON 各自
 * 絕對評分」對正常聽力測試者有天花板效應（OFF 頂天、ON 音色一變就掉分），
 * v2 直接量差異、且每情境問對準的處理機制。整體題保留 v1 四題（延遲感、
 * 自然度、滿意度、使用意願，跨版可比）並新增自聲悶塞與音量合適度。
 * 全部存入 SQLite questionnaire_responses 表（questionnaire_version=2），
 * 綁定 session_id 供匯出。
 *
 * 畫面頂部提供環境輔聽控制：DSP 開關、收音來源（手機／耳機麥克風）與環境
 * 模式（透明／對話／戶外／影音）。DSP 開關會真正啟動 Hark 的即時麥克風收音
 * 處理引擎（HarkAudioService），OFF 時以 bypass 讓聲音原樣透傳、ON 時套用剛測
 * 得的 DSL v5 增益與所選環境模式的壓縮預設，讓測試者在喇叭播放情境音時實際
 * 比較「有無輔聽」的差異。離開本頁時停止引擎，回復進場前的裝置狀態。
 */
class QuestionnaireActivity : ComponentActivity() {

    // 剛測得、已存入 DataStore 的 DSL v5 16 段增益（雙耳）
    private var dspGainsLeft: List<Float> = emptyList()
    private var dspGainsRight: List<Float> = emptyList()

    // 預設手機收音：本機 USB 耳機麥只有 Legacy 慢路徑（burst 960），順暢與
    // 低延遲不可兼得；內建麥 MMAP 低延遲、實測零 stall。施測全員一致即可。
    private var useHeadsetMic = false
    private var currentMode = SceneManager.Mode.CONVERSATION
    private var engineStarted = false
    private var dspOn = false

    /**
     * 麥克風權限請求。重灌 app 後權限會被系統重置，而本頁是測試者流程中
     * 第一個（也是唯一）需要麥克風的步驟——先前只檢查＋Toast 不請求，
     * 症狀是「輔聽 DSP 切開完全無聲」（實測踩到）。改為主動請求，核准
     * 後接著啟動引擎。
     */
    private val micPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startLiveEngine()
        else android.widget.Toast.makeText(
            this, "未取得麥克風權限，輔聽開關將無作用（問卷仍可作答）",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        disableSystemBackNavigation()
        val subjectName = intent.getStringExtra("EXTRA_SUBJECT") ?: "未填寫"
        val sessionId = intent.getLongExtra("EXTRA_SESSION_ID", System.currentTimeMillis())
        val earphoneModel = intent.getStringExtra("EXTRA_EARPHONE_MODEL")

        val repository = (application as HarkApplication).eqSettingsRepository
        lifecycleScope.launch {
            dspGainsLeft = repository.getBandGainsFlow("left", 0, 16).first()
            dspGainsRight = repository.getBandGainsFlow("right", 0, 16).first()
            startLiveEngine()   // 引擎起來後預設 OFF（bypass），符合先聽 OFF 的施測順序
        }

        setContent {
            QuestionnaireScreen(
                subjectName = subjectName,
                onDspToggle = { on -> applyDsp(on) },
                onMicToggle = { headset -> setMic(headset) },
                onModeSelect = { mode -> setMode(mode) },
                onSubmit = { sceneDeltas, overall ->
                    saveToDb(sessionId, subjectName, earphoneModel, sceneDeltas, overall)
                    setResult(RESULT_OK)
                    finish()
                },
                onSkip = { finish() }
            )
        }
    }

    /** 啟動即時環境輔聽引擎（需麥克風權限）；預設 bypass（OFF）。 */
    private fun startLiveEngine() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        // ★ 主動解除測驗隔離 ★
        // 前面的測驗步驟會設 audiometryIsolationActive=true + setMuted(true)，
        // 而服務端的耳機偵測（HarkAudioService）只要看到這個旗標就會把引擎
        // 重新靜音——所以本頁不能只被動假設「別人已還原」，必須自己斷言
        // 「現在要出聲」。實測漏掉這段的症狀：問卷的輔聽怎麼切都完全無聲。
        HarkAudioService.audiometryIsolationActive = false
        // ★ 宣告本頁全權掌控 ★ 服務端的耳機偵測/AudioFocus 不得改動靜音、
        // HarkAudioRouter 的裝置重設全部跳過（背景 MainActivity 會反覆觸發，
        // 實測造成嚴重斷續）。離頁於 onDestroy 還原。
        HarkAudioService.experimentManualControl = true

        // ★ 自行設定耳機旗標 ★ native 端 setupStreams 在 mHeadphonesConnected
        // = false 時直接拒開串流（防喇叭外放回授）。此旗標平時由
        // HarkAudioRouter 設定，但本頁把路由整個跳過（experimentManualControl）
        // 且實驗流程進場已停掉引擎——冷啟動時沒人設旗標，startEngine 被
        // 靜默擋下，整頁無聲（實測踩到，logcat: "setupStreams blocked"）。
        // 這裡只取路由「偵測耳機→設旗標」這一個必要動作。
        run {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            val hp = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type in listOf(
                    android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
                    android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                )
            }
            HarkAudioBridge.setHeadphonesConnected(hp)
            if (!hp) android.widget.Toast.makeText(
                this, "⚠️ 未偵測到耳機——請接上耳機後重進本頁", android.widget.Toast.LENGTH_LONG
            ).show()
        }

        // 問卷頁 UI（Compose 重組）會讓音訊 callback 遲到 20+ ms，
        // 預設 4×burst（≈8 ms）緩衝必然 underrun——實測聲音斷斷續續。
        // 只調大 bursts 不夠：耳機收音時輸出走 Exclusive/MMAP（logcat 可見
        // "setHwVolume (mmap-playback)"），其緩衝容量硬體固定僅 2–3 burst，
        // setBufferSizeInFrames(16×burst) 被靜默鉗到容量上限，斷續依舊
        // （實測回報第二次）。本頁不需要極低延遲，強制 Shared 模式讓
        // 16×burst（≈32 ms）真正生效；離頁還原。
        // ★ 串流組態與主頁一致 ★ 本頁早期疊過強制 Shared／Legacy PS 輸出／
        // ×4 輸入容量等特殊組態，都是在追「斷續」的症狀；真正根因（路由
        // 重入、自動分類搶模式、耳機旗標、服務重啟——見 BUG-012～016）修掉
        // 後全數撤除。與主頁僅存兩個差異：(1) 輸出緩衝請求 32×burst（本頁
        // 有 Compose UI，防偶發重組停頓；MMAP 會鉗到硬體上限 ~30 ms）；
        // (2) 預設手機收音（本機 USB 耳機麥僅有 Legacy 慢路徑，DECISION-001）。
        // 32×burst ≈ 64 ms：實測本頁 callback 最大遲到 32.6 ms
        // （mMaxMeasuredLatenessNanos），16×burst（32 ms）恰好被吃光仍會
        // 斷音，需留一倍餘裕。
        HarkAudioBridge.setOutputBufferBursts(32)

        ContextCompat.startForegroundService(
            this,
            Intent(this, HarkAudioService::class.java).apply { action = HarkAudioService.ACTION_START }
        )
        engineStarted = true
        HarkAudioBridge.setUseHeadsetMic(useHeadsetMic)
        if (!useHeadsetMic) {
            // 與 setMic(false) 相同的手機收音組態：內建麥實體 ID（否則沿用
            // router 上次選的耳機麥 ID）＋內建麥靈敏度補償
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC }
                ?.let { HarkAudioBridge.setAudioInputDeviceId(it.id) }
            HarkAudioBridge.setIsBluetoothInput(false)
            HarkAudioBridge.setInputGainOffset(15.0f)
        }
        HarkAudioBridge.setSituationalMode(currentMode.id)
        applyGains()
        // 初始 OFF = 引擎靜音。OFF 曾實作為 bypass 透傳（有聲音），測試者疑惑
        // 「沒開輔聽怎麼有聲音」——未輔助狀態本來就該是耳機無聲、隔著耳機聽喇叭。
        HarkAudioBridge.setBypassMode(true)
        HarkAudioBridge.setMuted(true)

        // 即時聆聽不是 dB SL 刺激，不需要鎖最大音量；反而必須調回舒適值——
        // 前面測驗全程鎖最大，DSL 增益（高頻可達 +30 dB）疊上去會刺耳傷耳。
        // 音量鍵開放，實驗者可隨時調整。
        try {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (max / 3).coerceAtLeast(1), 0)
        } catch (e: Exception) { /* best effort */ }

        // 鎖定手動環境模式＋搶回 ON/OFF 決定權。兩個服務端動作都是非同步晚到：
        //   (a) SceneManager 就緒後開始「自動環境分類」，安靜室內的判準就是
        //       TRANSPARENCY，每 5 秒窗改寫引擎模式（實測：明明選對話，聽起來
        //       卻是通透；模式被反覆改寫也造成參數跳動的斷續感）。
        //   (b) 服務就緒時的耳機偵測「有耳機就解除靜音」，翻掉本頁 OFF 靜音。
        // 舊版用一次性 postDelayed(800/1200ms) 賭時間，冷啟動（尤其剛授權完
        // 麥克風那次）服務更慢，sceneManager 仍為 null → 鎖永遠沒上。改為
        // 輪詢直到 sceneManager 就緒才鎖，鎖上後再重申兩次以覆蓋 (b)。
        assertQuestionnaireState()
    }

    /** 每 300ms 輪詢服務就緒；就緒後鎖手動模式＋重套 ON/OFF，共成功重申 3 次。 */
    private fun assertQuestionnaireState(attempt: Int = 0, asserted: Int = 0) {
        if (!engineStarted || isDestroyed) return
        // 實驗流程進場會把引擎與服務整個停掉（防測驗嘯叫），本頁是冷啟動；
        // 若服務起來了引擎卻沒跑（前景服務啟動失敗等），直接補啟動，
        // 否則整頁無聲。
        try {
            if (!HarkAudioBridge.isEngineActuallyRunning()) {
                android.util.Log.w("Questionnaire", "engine not running — starting directly")
                HarkAudioBridge.startEngine()
                applyGains()
            }
        } catch (e: Throwable) { /* bridge not ready yet */ }
        val sm = HarkAudioService.sceneManager
        if (sm == null) {
            if (attempt < 40) uiHandler.postDelayed(
                { assertQuestionnaireState(attempt + 1, asserted) }, 300)
            return
        }
        sm.selectModeManual(currentMode)
        applyDsp(dspOn)
        if (asserted < 2) uiHandler.postDelayed(
            { assertQuestionnaireState(attempt, asserted + 1) }, 1000)
    }

    private fun applyGains() {
        if (dspGainsLeft.size < 16 || dspGainsRight.size < 16) return
        dspGainsLeft.forEachIndexed { i, g -> HarkAudioBridge.setBandGain(0, i, g) }
        dspGainsRight.forEachIndexed { i, g -> HarkAudioBridge.setBandGain(1, i, g) }
    }

    /** ON：出聲並套用 DSL v5 增益 + 環境模式；OFF：引擎靜音（耳機無聲）。 */
    private fun applyDsp(on: Boolean) {
        dspOn = on
        if (!engineStarted) return
        if (on) {
            HarkAudioBridge.setMuted(false)
            HarkAudioBridge.setBypassMode(false)
            HarkAudioBridge.setSituationalMode(currentMode.id)
            applyGains()
        } else {
            HarkAudioBridge.setBypassMode(true)
            HarkAudioBridge.setMuted(true)
        }
    }

    /**
     * 切換收音來源。useHeadsetMic 旗標只在「開流」時被讀取（它決定 Oboe 的
     * Exclusive/Shared 共享模式），引擎跑著的時候改旗標毫無作用——這正是
     * 實測「切到手機收音卻還在耳機收音」的原因。必須重啟引擎讓它重新開流。
     */
    private fun setMic(headset: Boolean) {
        useHeadsetMic = headset
        if (!engineStarted) return
        HarkAudioBridge.setMuted(true)          // 重啟瞬間避免爆音
        HarkAudioBridge.stopEngine()
        HarkAudioBridge.setUseHeadsetMic(headset)
        // ★ 光翻旗標不夠 ★ 手機收音模式下，引擎開流用的是 mInputDeviceId
        // （內建麥克風實體 ID）。那個 ID 平時由 HarkAudioRouter 設定，而 router
        // 上次選的是耳機麥——不在這裡塞入內建麥 ID，重啟後照樣是耳機收音。
        if (!headset) {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC }
                ?.let { HarkAudioBridge.setAudioInputDeviceId(it.id) }
            HarkAudioBridge.setIsBluetoothInput(false)
            // 與 router 的手機收音路徑一致：內建麥靈敏度較低，補 +15 dB 輸入增益
            HarkAudioBridge.setInputGainOffset(15.0f)
        } else {
            HarkAudioBridge.setInputGainOffset(0.0f)
        }
        uiHandler.postDelayed({
            HarkAudioBridge.startEngine()
            // 引擎重啟後原生端狀態歸零，把本頁的狀態全部重新套上
            applyGains()
            assertQuestionnaireState()
        }, 200)
    }

    private fun setMode(mode: SceneManager.Mode) {
        currentMode = mode
        if (!engineStarted) return
        // 走 SceneManager 的手動選擇（會鎖住自動分類），不要直接寫 bridge——
        // 直接寫會在下一輪自動分類被蓋掉
        HarkAudioService.sceneManager?.selectModeManual(mode)
            ?: HarkAudioBridge.setSituationalMode(mode.id)
        // 從引擎回讀狀態顯示——模式差異（壓縮參數、降噪開關）在安靜環境下
        // 聽感很細微，實測者會以為「沒有作用」；用回讀值證明有套用。
        uiHandler.postDelayed({
            val nr = try { HarkAudioBridge.isNoiseReductionEnabled() } catch (e: Throwable) { false }
            val desc = when (mode) {
                SceneManager.Mode.TRANSPARENCY -> "透明｜壓縮 1.2:1"
                SceneManager.Mode.CONVERSATION -> "對話｜壓縮 1.5:1＋下擴展降噪"
                SceneManager.Mode.OUTDOOR -> "戶外｜壓縮 1.3:1＋抗風切"
                SceneManager.Mode.CINEMA -> "影音｜壓縮 1.1:1（最接近原音）"
                SceneManager.Mode.AUTO -> "自動判斷"
            }
            android.widget.Toast.makeText(
                this, "已套用：$desc｜降噪 ${if (nr) "開" else "關"}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }, 150)
    }

    override fun onDestroy() {
        HarkAudioService.experimentManualControl = false
        // 還原輔聽的低延遲串流組態（本頁為防斷續改 Shared + Legacy 大緩衝）
        HarkAudioBridge.setStreamOverrides(0, 0)
        HarkAudioBridge.setOutputBufferBursts(4)
        HarkAudioBridge.setOutputPerfModeOverride(0)
        // 離開問卷即停止即時引擎，回復進場前狀態（進場前本流程並未啟用輔聽）。
        if (engineStarted) {
            startService(Intent(this, HarkAudioService::class.java).apply {
                action = HarkAudioService.ACTION_STOP
            })
        }
        super.onDestroy()
    }

    private fun saveToDb(
        sessionId: Long,
        subjectName: String,
        earphoneModel: String?,
        sceneDeltas: Map<String, Int>,
        overall: OverallAnswers
    ) {
        val db = SRTResultDbHelper(this).writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            // 重測問卷＝覆蓋而非疊加：先清掉本 session_id 舊回覆，避免同一
            // 測試者流程留下重複的問卷紀錄混淆分析。
            // ★ 必須同時比對 subject_name：流程換人若沿用了上一位的 sessionId
            //（主控頁會還原持久化的 sessionId），只用 session_id 刪會把「上一位
            // 測試者剛存的問卷」整組刪掉——07/19 阿汝與 Leo 的 v2 問卷即因此遺失。
            db.delete(
                SRTResultContract.QuestionnaireEntry.TABLE_NAME,
                "${SRTResultContract.QuestionnaireEntry.COLUMN_NAME_SESSION_ID} = ? AND " +
                "${SRTResultContract.QuestionnaireEntry.COLUMN_NAME_SUBJECT_NAME} = ?",
                arrayOf(sessionId.toString(), subjectName)
            )
            // v2 情境題：每情境一列，condition="DELTA"，分數存 scene_delta（−3～+3）
            sceneDeltas.forEach { (sceneKey, delta) ->
                if (delta != DELTA_UNANSWERED) {
                    db.insert(SRTResultContract.QuestionnaireEntry.TABLE_NAME, null, ContentValues().apply {
                        put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_SESSION_ID, sessionId)
                        put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_TEST_TIMESTAMP, now)
                        put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_SUBJECT_NAME, subjectName)
                        put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_SCENE_KEY, sceneKey)
                        put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_CONDITION, "DELTA")
                        put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_SCENE_DELTA, delta)
                        put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_VERSION, SRTResultContract.QuestionnaireEntry.CURRENT_VERSION)
                        earphoneModel?.let { put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_EARPHONE_MODEL, it) }
                    })
                }
            }
            db.insert(SRTResultContract.QuestionnaireEntry.TABLE_NAME, null, ContentValues().apply {
                put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_SESSION_ID, sessionId)
                put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_TEST_TIMESTAMP, now)
                put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_SUBJECT_NAME, subjectName)
                put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_SCENE_KEY, "OVERALL")
                put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_CONDITION, "NA")
                put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_VERSION, SRTResultContract.QuestionnaireEntry.CURRENT_VERSION)
                if (overall.delay > 0) put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_DELAY_FEEL, overall.delay)
                put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_ARTIFACT_FLAG, if (overall.artifact) 1 else 0)
                put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_ARTIFACT_NOTE, overall.artifactNote)
                if (overall.naturalness > 0) put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_NATURALNESS, overall.naturalness)
                if (overall.satisfaction > 0) put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_SATISFACTION, overall.satisfaction)
                if (overall.willingness > 0) put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_WILLINGNESS, overall.willingness)
                if (overall.ownVoice > 0) put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_OWN_VOICE, overall.ownVoice)
                if (overall.loudness > 0) put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_LOUDNESS, overall.loudness)
                put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_FREE_TEXT, overall.freeText)
                earphoneModel?.let { put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_EARPHONE_MODEL, it) }
            })
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}

private data class OverallAnswers(
    val delay: Int, val artifact: Boolean, val artifactNote: String,
    val naturalness: Int, val satisfaction: Int, val willingness: Int,
    val ownVoice: Int, val loudness: Int, val freeText: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuestionnaireScreen(
    subjectName: String,
    onDspToggle: (Boolean) -> Unit,
    onMicToggle: (Boolean) -> Unit,
    onModeSelect: (SceneManager.Mode) -> Unit,
    onSubmit: (Map<String, Int>, OverallAnswers) -> Unit,
    onSkip: () -> Unit
) {
    var dspOn by remember { mutableStateOf(false) }   // 依施測順序，預設先 OFF
    var useHeadsetMic by remember { mutableStateOf(false) }   // 預設手機收音（與 Activity 端一致）
    var selectedMode by remember { mutableStateOf(SceneManager.Mode.CONVERSATION) }
    // scene key → v2 比較題分數（−3～+3；DELTA_UNANSWERED = 未作答）
    val sceneDeltas = remember {
        SCENES.associate { it.key to mutableIntStateOf(DELTA_UNANSWERED) }
    }
    var delay by remember { mutableIntStateOf(0) }
    var artifact by remember { mutableStateOf(false) }
    var artifactNote by remember { mutableStateOf("") }
    var naturalness by remember { mutableIntStateOf(0) }
    var satisfaction by remember { mutableIntStateOf(0) }
    var willingness by remember { mutableIntStateOf(0) }
    var ownVoice by remember { mutableIntStateOf(0) }
    var loudness by remember { mutableIntStateOf(0) }
    var freeText by remember { mutableStateOf("") }
    var showExitDialog by remember { mutableStateOf(false) }

    MaterialTheme {
        if (showExitDialog) {
            AlertDialog(
                onDismissRequest = { showExitDialog = false },
                title = { Text("離開問卷") },
                text = { Text("確定要離開嗎？尚未送出的作答不會儲存。") },
                confirmButton = {
                    TextButton(onClick = { showExitDialog = false; onSkip() }) { Text("離開") }
                },
                dismissButton = {
                    TextButton(onClick = { showExitDialog = false }) { Text("繼續填寫") }
                }
            )
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("環境輔聽問卷", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { showExitDialog = true }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "離開問卷"
                            )
                        }
                    }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Text(
                        "測試者：$subjectName\n每個情境：先關閉輔聽（OFF）聆聽約 30 秒 → 開啟輔聽（ON）再聽約 30 秒 → 立刻回答該情境的比較題（0＝兩者沒差別）。情境音由實驗者以喇叭播放；情境 4 為現場拍手。",
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
                item {
                    Card(shape = RoundedCornerShape(14.dp), elevation = CardDefaults.cardElevation(2.dp)) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("輔聽 DSP", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    Text(
                                        "OFF：耳機無聲（隔著耳機聽喇叭）；ON：即時收音 → DSL v5 補償 + 環境模式。" +
                                        "覺得太大聲可用音量鍵調整。",
                                        fontSize = 12.sp,
                                        color = Color(0xFF757575)
                                    )
                                }
                                Switch(checked = dspOn, onCheckedChange = { dspOn = it; onDspToggle(it) })
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("收音來源", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = useHeadsetMic,
                                    onClick = { useHeadsetMic = true; onMicToggle(true) },
                                    label = { Text("耳機麥克風") }
                                )
                                FilterChip(
                                    selected = !useHeadsetMic,
                                    onClick = { useHeadsetMic = false; onMicToggle(false) },
                                    label = { Text("手機麥克風") }
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("環境模式", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(
                                    SceneManager.Mode.TRANSPARENCY to "透明",
                                    SceneManager.Mode.CONVERSATION to "對話",
                                    SceneManager.Mode.OUTDOOR to "戶外",
                                    SceneManager.Mode.CINEMA to "影音",
                                ).forEach { (mode, label) ->
                                    FilterChip(
                                        selected = selectedMode == mode,
                                        onClick = { selectedMode = mode; onModeSelect(mode) },
                                        label = { Text(label) }
                                    )
                                }
                            }
                        }
                    }
                }
                items(SCENES) { scene ->
                    SceneDeltaCard(scene, sceneDeltas[scene.key]!!)
                }
                item {
                    OverallCard(
                        delay, { delay = it },
                        artifact, { artifact = it },
                        artifactNote, { artifactNote = it },
                        naturalness, { naturalness = it },
                        satisfaction, { satisfaction = it },
                        willingness, { willingness = it },
                        ownVoice, { ownVoice = it },
                        loudness, { loudness = it },
                        freeText, { freeText = it }
                    )
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(onClick = { showExitDialog = true }, modifier = Modifier.weight(1f)) {
                            Text("略過")
                        }
                        Button(
                            onClick = {
                                onSubmit(
                                    sceneDeltas.mapValues { (_, st) -> st.intValue },
                                    OverallAnswers(delay, artifact, artifactNote, naturalness, satisfaction, willingness, ownVoice, loudness, freeText)
                                )
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("送出問卷", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SceneDeltaCard(scene: Scene, state: androidx.compose.runtime.MutableIntState) {
    Card(shape = RoundedCornerShape(14.dp), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(scene.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            scene.note?.let { Text(it, fontSize = 12.sp, color = Color(0xFF757575)) }
            Text(scene.question, fontSize = 14.sp)

            val answered = state.intValue != DELTA_UNANSWERED
            // 有刻度的滑桿：−3～+3 共 7 檔（中間 5 個刻度點），snap 到整數。
            // 未作答前滑桿停在 0 但顯示「尚未作答」，第一次拖動即記為作答
            // ——0（沒差別）是合法答案，不能拿 0 當未答哨兵。
            Slider(
                value = if (answered) state.intValue.toFloat() else 0f,
                onValueChange = { state.intValue = kotlin.math.round(it).toInt() },
                valueRange = -3f..3f,
                steps = 5,
                modifier = Modifier.fillMaxWidth()
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("−3\n${scene.negLabel}", fontSize = 11.sp, color = Color(0xFF757575), modifier = Modifier.weight(1f))
                Text("0 沒差別", fontSize = 11.sp, color = Color(0xFF757575),
                    modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text("+3\n${scene.posLabel}", fontSize = 11.sp, color = Color(0xFF757575),
                    modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            Text(
                if (answered) {
                    val v = state.intValue
                    "已選：" + (if (v > 0) "+$v" else "$v") + when {
                        v == 0 -> "（沒差別）"
                        v > 0 -> "（${scene.posLabel.removePrefix("明顯")}方向）"
                        else -> "（${scene.negLabel}方向）"
                    }
                } else "尚未作答——請拖動滑桿（可停在 0）",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (answered) Color(0xFF1a56b0) else Color(0xFFB26A00)
            )
        }
    }
}

@Composable
private fun OverallCard(
    delay: Int, onDelay: (Int) -> Unit,
    artifact: Boolean, onArtifact: (Boolean) -> Unit,
    artifactNote: String, onArtifactNote: (String) -> Unit,
    naturalness: Int, onNaturalness: (Int) -> Unit,
    satisfaction: Int, onSatisfaction: (Int) -> Unit,
    willingness: Int, onWillingness: (Int) -> Unit,
    ownVoice: Int, onOwnVoice: (Int) -> Unit,
    loudness: Int, onLoudness: (Int) -> Unit,
    freeText: String, onFreeText: (String) -> Unit
) {
    Card(shape = RoundedCornerShape(14.dp), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("整體感受（針對輔聽開啟 ON 的體驗）", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            RatingRow("是否感覺延遲、回音或不同步（5＝完全沒有）", delay, onDelay)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("是否聽到嘯叫、爆音等異音？", fontSize = 14.sp, modifier = Modifier.weight(1f))
                Switch(checked = artifact, onCheckedChange = onArtifact)
            }
            if (artifact) {
                OutlinedTextField(
                    value = artifactNote, onValueChange = onArtifactNote,
                    label = { Text("請簡述異音狀況") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            RatingRow("處理後的聲音自然嗎？", naturalness, onNaturalness)
            RatingRow("自己說話的聲音是否悶塞或怪異？（5＝完全正常）", ownVoice, onOwnVoice)
            RatingRow("開啟後的整體音量感覺（1＝太小、3＝剛好、5＝太大）", loudness, onLoudness)
            RatingRow("整體滿意度", satisfaction, onSatisfaction)
            RatingRow("若有需求，願意日常使用嗎？", willingness, onWillingness)
            OutlinedTextField(
                value = freeText, onValueChange = onFreeText,
                label = { Text("其他意見（選填）") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun RatingRow(label: String, value: Int, onChange: (Int) -> Unit) {
    Column {
        Text(label, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            for (i in 1..5) {
                val selected = value == i
                FilterChip(
                    selected = selected,
                    onClick = { onChange(i) },
                    label = { Text("$i") }
                )
            }
        }
    }
}
