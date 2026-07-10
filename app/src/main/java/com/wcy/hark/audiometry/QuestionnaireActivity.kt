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

private data class Scene(val key: String, val title: String, val hasClarity: Boolean)

private val SCENES = listOf(
    Scene("scene1_babble", "情境 1：多人交談背景", hasClarity = false),
    Scene("scene2_speech_quiet", "情境 2：安靜下的語音", hasClarity = true),
    Scene("scene3_steady_noise", "情境 3：穩態噪音（冷氣/車流）", hasClarity = false),
    Scene("scene4_transients", "情境 4：突發聲響", hasClarity = false),
    Scene("scene5_speech_in_babble", "情境 5：語音＋交談背景", hasClarity = true),
)

/**
 * QuestionnaireActivity — 環境輔聽自編問卷（測試者測驗流程最後一步）。
 *
 * 每個情境以喇叭播放（見 experiment_scenes/），測試者先「輔聽 OFF」聆聽後
 * 填一組題，再「輔聽 ON」聆聽後再填一組（清晰度僅語音情境適用、舒適度、
 * 噪音干擾），結束後填一組整體題（延遲感、異音、自然度、滿意度、使用
 * 意願、開放意見）。全部存入 SQLite questionnaire_responses 表，綁定
 * session_id 供匯出。
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

    private var useHeadsetMic = true
    private var currentMode = SceneManager.Mode.CONVERSATION
    private var engineStarted = false

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
                onSubmit = { sceneAnswers, overall ->
                    saveToDb(sessionId, subjectName, earphoneModel, sceneAnswers, overall)
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
        if (!granted) return   // 無權限則保持靜默降級，開關不作用
        ContextCompat.startForegroundService(
            this,
            Intent(this, HarkAudioService::class.java).apply { action = HarkAudioService.ACTION_START }
        )
        engineStarted = true
        HarkAudioBridge.setUseHeadsetMic(useHeadsetMic)
        HarkAudioBridge.setSituationalMode(currentMode.id)
        applyGains()
        HarkAudioBridge.setBypassMode(true)   // 初始 OFF
    }

    private fun applyGains() {
        if (dspGainsLeft.size < 16 || dspGainsRight.size < 16) return
        dspGainsLeft.forEachIndexed { i, g -> HarkAudioBridge.setBandGain(0, i, g) }
        dspGainsRight.forEachIndexed { i, g -> HarkAudioBridge.setBandGain(1, i, g) }
    }

    /** ON：套用 DSL v5 增益 + 所選環境模式；OFF：bypass 原樣透傳。 */
    private fun applyDsp(on: Boolean) {
        if (!engineStarted) return
        if (on) {
            HarkAudioBridge.setBypassMode(false)
            HarkAudioBridge.setSituationalMode(currentMode.id)
            applyGains()
        } else {
            HarkAudioBridge.setBypassMode(true)
        }
    }

    private fun setMic(headset: Boolean) {
        useHeadsetMic = headset
        if (engineStarted) HarkAudioBridge.setUseHeadsetMic(headset)
    }

    private fun setMode(mode: SceneManager.Mode) {
        currentMode = mode
        if (engineStarted) HarkAudioBridge.setSituationalMode(mode.id)
    }

    override fun onDestroy() {
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
        sceneAnswers: Map<String, ConditionAnswers>,
        overall: OverallAnswers
    ) {
        val db = SRTResultDbHelper(this).writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            // 重測問卷＝覆蓋而非疊加：先清掉本 session_id 舊回覆，避免同一
            // 測試者流程留下重複的問卷紀錄混淆分析。
            db.delete(
                SRTResultContract.QuestionnaireEntry.TABLE_NAME,
                "${SRTResultContract.QuestionnaireEntry.COLUMN_NAME_SESSION_ID} = ?",
                arrayOf(sessionId.toString())
            )
            sceneAnswers.forEach { (sceneKey, ans) ->
                listOf("OFF" to ans.off, "ON" to ans.on).forEach { (cond, r) ->
                    if (r.comfort > 0 || r.noise > 0 || r.clarity > 0) {
                        db.insert(SRTResultContract.QuestionnaireEntry.TABLE_NAME, null, ContentValues().apply {
                            put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_SESSION_ID, sessionId)
                            put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_TEST_TIMESTAMP, now)
                            put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_SUBJECT_NAME, subjectName)
                            put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_SCENE_KEY, sceneKey)
                            put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_CONDITION, cond)
                            if (r.clarity > 0) put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_CLARITY, r.clarity)
                            if (r.comfort > 0) put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_COMFORT, r.comfort)
                            if (r.noise > 0) put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_NOISE_INTERFERENCE, r.noise)
                            earphoneModel?.let { put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_EARPHONE_MODEL, it) }
                        })
                    }
                }
            }
            db.insert(SRTResultContract.QuestionnaireEntry.TABLE_NAME, null, ContentValues().apply {
                put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_SESSION_ID, sessionId)
                put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_TEST_TIMESTAMP, now)
                put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_SUBJECT_NAME, subjectName)
                put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_SCENE_KEY, "OVERALL")
                put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_CONDITION, "NA")
                if (overall.delay > 0) put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_DELAY_FEEL, overall.delay)
                put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_ARTIFACT_FLAG, if (overall.artifact) 1 else 0)
                put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_ARTIFACT_NOTE, overall.artifactNote)
                if (overall.naturalness > 0) put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_NATURALNESS, overall.naturalness)
                if (overall.satisfaction > 0) put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_SATISFACTION, overall.satisfaction)
                if (overall.willingness > 0) put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_WILLINGNESS, overall.willingness)
                put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_FREE_TEXT, overall.freeText)
                earphoneModel?.let { put(SRTResultContract.QuestionnaireEntry.COLUMN_NAME_EARPHONE_MODEL, it) }
            })
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}

private data class Ratings(val clarity: Int = 0, val comfort: Int = 0, val noise: Int = 0)
private data class ConditionAnswers(val off: Ratings, val on: Ratings)
private data class OverallAnswers(
    val delay: Int, val artifact: Boolean, val artifactNote: String,
    val naturalness: Int, val satisfaction: Int, val willingness: Int, val freeText: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuestionnaireScreen(
    subjectName: String,
    onDspToggle: (Boolean) -> Unit,
    onMicToggle: (Boolean) -> Unit,
    onModeSelect: (SceneManager.Mode) -> Unit,
    onSubmit: (Map<String, ConditionAnswers>, OverallAnswers) -> Unit,
    onSkip: () -> Unit
) {
    var dspOn by remember { mutableStateOf(false) }   // 依施測順序，預設先 OFF
    var useHeadsetMic by remember { mutableStateOf(true) }
    var selectedMode by remember { mutableStateOf(SceneManager.Mode.CONVERSATION) }
    // scene key → (OFF ratings, ON ratings)
    val sceneStates = remember {
        SCENES.associate { it.key to (mutableStateOf(Ratings()) to mutableStateOf(Ratings())) }
    }
    var delay by remember { mutableIntStateOf(0) }
    var artifact by remember { mutableStateOf(false) }
    var artifactNote by remember { mutableStateOf("") }
    var naturalness by remember { mutableIntStateOf(0) }
    var satisfaction by remember { mutableIntStateOf(0) }
    var willingness by remember { mutableIntStateOf(0) }
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
                        "測試者：$subjectName\n請針對每個情境，先回想「輔聽關閉（OFF）」再回想「輔聽開啟（ON）」聆聽時的感受作答。",
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
                                        "ON：即時麥克風收音 → DSL v5 補償 + 環境模式；OFF：原樣透傳。" +
                                        "喇叭播放情境音時可即時切換比較。",
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
                    val (offState, onState) = sceneStates[scene.key]!!
                    SceneCard(scene, offState, onState)
                }
                item {
                    OverallCard(
                        delay, { delay = it },
                        artifact, { artifact = it },
                        artifactNote, { artifactNote = it },
                        naturalness, { naturalness = it },
                        satisfaction, { satisfaction = it },
                        willingness, { willingness = it },
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
                                val answers = sceneStates.mapValues { (_, pair) ->
                                    ConditionAnswers(pair.first.value, pair.second.value)
                                }
                                onSubmit(
                                    answers,
                                    OverallAnswers(delay, artifact, artifactNote, naturalness, satisfaction, willingness, freeText)
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
private fun SceneCard(scene: Scene, offState: MutableState<Ratings>, onState: MutableState<Ratings>) {
    Card(shape = RoundedCornerShape(14.dp), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(scene.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("輔聽 OFF", fontWeight = FontWeight.SemiBold, color = Color(0xFF757575), fontSize = 13.sp)
            RatingBlock(scene.hasClarity, offState)
            Spacer(Modifier.height(4.dp))
            Text("輔聽 ON", fontWeight = FontWeight.SemiBold, color = Color(0xFF1a56b0), fontSize = 13.sp)
            RatingBlock(scene.hasClarity, onState)
        }
    }
}

@Composable
private fun RatingBlock(hasClarity: Boolean, state: MutableState<Ratings>) {
    val r = state.value
    if (hasClarity) {
        RatingRow("聽得清楚嗎？", r.clarity) { state.value = r.copy(clarity = it) }
    }
    RatingRow("聽起來舒服嗎？", r.comfort) { state.value = state.value.copy(comfort = it) }
    RatingRow("有被噪音干擾嗎？（5＝完全不受干擾）", r.noise) { state.value = state.value.copy(noise = it) }
}

@Composable
private fun OverallCard(
    delay: Int, onDelay: (Int) -> Unit,
    artifact: Boolean, onArtifact: (Boolean) -> Unit,
    artifactNote: String, onArtifactNote: (String) -> Unit,
    naturalness: Int, onNaturalness: (Int) -> Unit,
    satisfaction: Int, onSatisfaction: (Int) -> Unit,
    willingness: Int, onWillingness: (Int) -> Unit,
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
