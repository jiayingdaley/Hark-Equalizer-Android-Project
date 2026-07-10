package com.wcy.hark.audiometry

import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.HearingDisabled
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import com.wcy.hark.audiometry.sqlite.SRTResultContract
import com.wcy.hark.audiometry.sqlite.SRTResultDbHelper
import androidx.compose.ui.viewinterop.AndroidView
import com.wcy.hark.ui.theme.HarkTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── 數據模型 ──────────────────────────────────────────────────────────

data class SrtSession(
    val sessionId: Long,
    val timestamp: Long,
    val accuracy: Double,
    val totalQuestions: Int,
    val phoneVolume: Int,
    val subjectName: String = "未填寫"
)

data class SrtRecord(
    val questionNumber: Int,
    val correctWord: String,
    val userAnswer: String,
    val wasCorrect: Boolean
)

data class PureToneCsvItem(
    val file: File,
    val fileName: String,
    val formattedDate: String,
    val ear: String,
    val subjectName: String = "未填寫"
)

data class PureToneThreshold(
    val ear: String,
    val frequency: Int,
    val threshold: Int
)

data class ParsedPureToneResult(
    val environmentalNoise: Double,
    val thresholds: List<PureToneThreshold>,
    val hasReliabilityWarning: Boolean
)

data class SsnRecordItem(
    val questionNumber: Int,
    val snrDb: Float,
    val correctWord: String,
    val userAnswer: String,
    val wasCorrect: Boolean
)

data class SsnSessionItem(
    val sessionId: Long,
    val timestamp: Long,
    val subjectName: String,
    val snrList: String,
    val srt50: Float?,   // null = 未能內插
    val isSl: Boolean = false   // true = 無噪音小聲（dB SL）；false = 噪音下（dB SNR）
)

// ── Activity 實作 ──────────────────────────────────────────────────────

class TestHistoryActivity : ComponentActivity() {

    private lateinit var dbHelper: SRTResultDbHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        dbHelper = SRTResultDbHelper(this)

        setContent {
            HarkTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    TestHistoryScreen(
                        dbHelper = dbHelper,
                        externalFilesDir = getExternalFilesDir(null),
                        onBack = { finish() }
                    )
                }
            }
        }
    }
}

// ── Compose UI 畫面 ─────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestHistoryScreen(
    dbHelper: SRTResultDbHelper,
    externalFilesDir: File?,
    onBack: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("語詞辨識", "噪音語詞", "純音聽力")

    val context = androidx.compose.ui.platform.LocalContext.current
    val srtDiagnosticMap = remember { loadSrtDiagnosticDatabase(context) }

    // 狀態讀取
    var srtSessions by remember { mutableStateOf(listOf<SrtSession>()) }
    var ssnSessions by remember { mutableStateOf(listOf<SsnSessionItem>()) }
    var pureToneFiles by remember { mutableStateOf(listOf<PureToneCsvItem>()) }
    var reloadKey by remember { mutableStateOf(0) }

    // 載入數據的 side effect
    LaunchedEffect(selectedTab, reloadKey) {
        when (selectedTab) {
            0 -> srtSessions = loadSrtSessions(dbHelper)
            1 -> ssnSessions = loadSsnSessions(dbHelper)
            else -> pureToneFiles = loadPureToneCsvs(externalFilesDir)
        }
    }

    // 詳細 Dialog 狀態
    var activeSrtSession by remember { mutableStateOf<SrtSession?>(null) }
    var srtRecordsForActiveSession by remember { mutableStateOf(listOf<SrtRecord>()) }
    var activePureToneItem by remember { mutableStateOf<PureToneCsvItem?>(null) }
    var activeSsnSession by remember { mutableStateOf<SsnSessionItem?>(null) }
    var ssnPointsForActiveSession by remember { mutableStateOf(listOf<Pair<Float, Float>>()) }
    var ssnAttenuationForActiveSession by remember { mutableStateOf(0 to 0f) } // (題數, 最大衰減 dB)
    var ssnRecordsForActiveSession by remember { mutableStateOf(listOf<SsnRecordItem>()) }

    // 刪除確認狀態（實驗資料珍貴：一律二次確認）
    var pendingDelete by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingDeleteLabel by remember { mutableStateOf("") }

    // 彈出視窗觸發
    LaunchedEffect(activeSrtSession) {
        activeSrtSession?.let {
            srtRecordsForActiveSession = loadSrtRecordsForSession(dbHelper, it.sessionId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("測試歷史紀錄", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                    )
                }
            }

            when (selectedTab) {
                0 -> {
                    // SRT 列表
                    if (srtSessions.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("暫無語詞辨識測試紀錄", color = Color.Gray, fontSize = 16.sp)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(srtSessions) { session ->
                                SrtSessionCard(
                                    session = session,
                                    onDelete = {
                                        pendingDeleteLabel = "語詞辨識紀錄（${SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(session.timestamp))}）"
                                        pendingDelete = {
                                            deleteSrtSession(dbHelper, session.sessionId)
                                            reloadKey++
                                        }
                                    }
                                ) { activeSrtSession = session }
                            }
                        }
                    }
                }
                1 -> {
                    // SSN 噪音下語詞列表
                    if (ssnSessions.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("暫無噪音下語詞測試紀錄", color = Color.Gray, fontSize = 16.sp)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(ssnSessions) { session ->
                                SsnSessionCard(
                                    session = session,
                                    onDelete = {
                                        pendingDeleteLabel = "噪音下語詞紀錄（${SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(session.timestamp))}）"
                                        pendingDelete = {
                                            deleteSsnSession(dbHelper, session.sessionId)
                                            reloadKey++
                                        }
                                    }
                                ) {
                                    // 重算各 SNR 分數並以彈窗顯示曲線圖
                                    ssnPointsForActiveSession = loadSsnPoints(dbHelper, session.sessionId)
                                    ssnAttenuationForActiveSession = loadSsnAttenuation(dbHelper, session.sessionId)
                                    ssnRecordsForActiveSession = loadSsnRecords(dbHelper, session.sessionId)
                                    activeSsnSession = session
                                }
                            }
                        }
                    }
                }
                else -> {
                    // Pure Tone 列表
                    if (pureToneFiles.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("暫無純音測驗記錄 (CSV)", color = Color.Gray, fontSize = 16.sp)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(pureToneFiles) { item ->
                                PureToneFileCard(
                                    item = item,
                                    onDelete = {
                                        pendingDeleteLabel = "純音測驗紀錄（${item.formattedDate}，${item.ear}）"
                                        pendingDelete = {
                                            item.file.delete()
                                            reloadKey++
                                        }
                                    }
                                ) { activePureToneItem = item }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── 刪除確認（實驗資料珍貴）──
    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("確定刪除？", fontWeight = FontWeight.Bold) },
            text = {
                Text("即將刪除：$pendingDeleteLabel\n\n⚠️ 實驗資料非常珍貴，刪除後無法復原。請確認這筆資料已備份或確定不再需要。")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete?.invoke()
                    pendingDelete = null
                }) { Text("刪除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }

    // ── 語詞明細對話框 ──
    if (activeSrtSession != null) {
        val session = activeSrtSession!!
        val dateString = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(session.timestamp))
        
        AlertDialog(
            onDismissRequest = { activeSrtSession = null },
            title = {
                Text(
                    text = "語詞測試詳情 ($dateString)",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    if (session.subjectName.isNotEmpty() && session.subjectName != "未填寫") {
                        Text(
                            text = "使用者：${session.subjectName}",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    Text(
                        text = "正確率：${String.format(Locale.getDefault(), "%.0f%%", session.accuracy)} (${session.totalQuestions} 題中答對 ${String.format(Locale.getDefault(), "%.0f", session.accuracy * session.totalQuestions / 100)} 題)",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    
                    if (session.phoneVolume != -1) {
                        Text(
                            text = "測驗時手機總音量：${session.phoneVolume} 級",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                        )
                    } else {
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // 診斷說明
                    val wrongRecords = srtRecordsForActiveSession.filter { !it.wasCorrect }
                    val diagnostic = getAcousticDiagnostic(wrongRecords, srtDiagnosticMap)
                    
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        Text(
                            text = diagnostic,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp),
                            lineHeight = 20.sp
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Text("答題明細：", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
                    
                    // 明細列表：完整展開，跟著整個對話框一起捲動（不另設固定高度框）
                    Column(modifier = Modifier.fillMaxWidth()) {
                        srtRecordsForActiveSession.forEach { record ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Q${record.questionNumber}. 標準音：[ ${record.correctWord} ]",
                                    fontSize = 14.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = if (record.userAnswer == "not_sure") "回答：不確定" else "回答：${record.userAnswer}",
                                    fontSize = 14.sp,
                                    color = if (record.wasCorrect) Color(0xFF2E7D32) else Color(0xFFC62828),
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Text(
                                    text = if (record.wasCorrect) "✓ 答對" else "✗ 答錯",
                                    fontSize = 12.sp,
                                    color = if (record.wasCorrect) Color(0xFF2E7D32) else Color(0xFFC62828),
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { activeSrtSession = null }) {
                    Text("關閉", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // ── 噪音下語詞（SSN）明細對話框 ──
    if (activeSsnSession != null) {
        val session = activeSsnSession!!
        val points = ssnPointsForActiveSession
        val dateString = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(session.timestamp))
        AlertDialog(
            onDismissRequest = { activeSsnSession = null },
            title = {
                Text("噪音下語詞測驗詳情", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    if (session.subjectName.isNotEmpty() && session.subjectName != "未填寫") {
                        Text("使用者：${session.subjectName}", fontWeight = FontWeight.Bold)
                    }
                    Text("測試日期：$dateString", fontWeight = FontWeight.SemiBold)
                    val unit = if (session.isSl) "dB SL" else "dB SNR"
                    Text(
                        if (session.isSl) "音量條件：${session.snrList} dB SL（無噪音小聲）"
                        else "SNR 條件：${session.snrList} dB",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = session.srt50?.let { "50% 閾值：${String.format(Locale.getDefault(), "%.1f", it)} $unit" }
                            ?: "SRT50：無法內插（辨識率未跨越 50%）",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                    val (attCount, attMaxDb) = ssnAttenuationForActiveSession
                    if (attCount > 0) {
                        Text(
                            text = "⚠️ 削波防護：$attCount 題觸發輸出正規化（最大額外衰減 " +
                                   "${String.format(Locale.getDefault(), "%.1f", attMaxDb)} dB；SNR 不受影響）",
                            fontSize = 12.sp,
                            color = Color(0xFFE65100),
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Psychometric Function：", fontWeight = FontWeight.Bold)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .padding(vertical = 4.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                PsychometricView(ctx).apply { setData(points, session.srt50) }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            update = { view -> view.setData(points, session.srt50) }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    Text("各 SNR 之辨識率：", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                    if (points.isEmpty()) {
                        Text("無有效數據", color = Color.Gray)
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("SNR (dB)", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Text("辨識率", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                            }
                            HorizontalDivider(color = Color.LightGray)
                            points.forEach { (snr, score) ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(if (snr == snr.toInt().toFloat()) "${snr.toInt()}" else "$snr", modifier = Modifier.weight(1f))
                                    Text(String.format(Locale.getDefault(), "%.0f%%", score),
                                         fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                                }
                            }
                        }
                    }

                    // 答題明細（完整展開，跟整個對話框一起捲動）
                    if (ssnRecordsForActiveSession.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                        Text("答題明細：", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                        Column(modifier = Modifier.fillMaxWidth()) {
                            ssnRecordsForActiveSession.forEach { record ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Q${record.questionNumber}. [ ${record.correctWord} ] @ ${if (record.snrDb == record.snrDb.toInt().toFloat()) "${record.snrDb.toInt()}" else "${record.snrDb}"} dB",
                                        fontSize = 14.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = if (record.userAnswer == "not_sure") "不確定" else record.userAnswer,
                                        fontSize = 14.sp,
                                        color = if (record.wasCorrect) Color(0xFF2E7D32) else Color(0xFFC62828),
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                    Text(
                                        text = if (record.wasCorrect) "✓" else "✗",
                                        fontSize = 14.sp,
                                        color = if (record.wasCorrect) Color(0xFF2E7D32) else Color(0xFFC62828),
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { activeSsnSession = null }) {
                    Text("關閉", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // ── 純音明細對話框 ──
    if (activePureToneItem != null) {
        val item = activePureToneItem!!
        val parsed = parsePureToneResultsCsv(item.file)
        
        AlertDialog(
            onDismissRequest = { activePureToneItem = null },
            title = {
                Text(
                    text = "純音聽閾測試紀錄",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                val leftEarMap = parsed.thresholds
                    .filter { it.ear.equals("Left", ignoreCase = true) }
                    .associate { it.frequency to (it.threshold as Int?) }
                val rightEarMap = parsed.thresholds
                    .filter { it.ear.equals("Right", ignoreCase = true) }
                    .associate { it.frequency to (it.threshold as Int?) }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    if (item.subjectName.isNotEmpty() && item.subjectName != "未填寫") {
                        Text("使用者：${item.subjectName}", fontWeight = FontWeight.Bold)
                    }
                    Text("測試日期：${item.formattedDate}", fontWeight = FontWeight.SemiBold)
                    Text("測試耳別：${item.ear}", fontWeight = FontWeight.SemiBold)
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // 合併顯示：環境背景噪音級別
                    val noiseLevel = parsed.environmentalNoise
                    val advice = when {
                        noiseLevel < 30.0 -> "🟢 環境極安靜，非常適合測試。"
                        noiseLevel in 30.0..50.0 -> "🟡 噪音適中，可能輕微干擾低頻聽力，建議在更靜處測量。"
                        else -> "🔴 背景噪音過大！干擾嚴重，建議參考價值降低，請重新測試。"
                    }
                    
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (noiseLevel < 50.0) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) 
                                             else Color(0xFFFFEBEE)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = String.format(Locale.getDefault(), "背景環境噪音：%.2f dB SPL", noiseLevel),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (noiseLevel < 50.0) MaterialTheme.colorScheme.primary else Color(0xFFC62828)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = advice, fontSize = 12.sp, lineHeight = 16.sp)
                        }
                    }
                    
                    if (parsed.hasReliabilityWarning) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFFFFF3E0) // Warm warning background
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "⚠️ 信度警告：此測試結果信度較低",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFE65100)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "1000 Hz 首次測試與第二次重測閾值差值達 10 dB 以上，表明使用者反應一致性較低，建議參考價值降低並重新測試。",
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    color = Color(0xFF5D4037)
                                )
                            }
                        }
                    }

                    // ── 繪製聽力圖走勢 Card（可切換 dB HL / dB FS）──
                    Spacer(modifier = Modifier.height(8.dp))
                    var showDbfs by remember { mutableStateOf(true) }  // 預設 dB FS（聽力圖式）
                    // dB HL → dB FS 換算：優先用目前耳機的實測校正表，未校準時
                    // 退回播音同款相對映射（100 dB HL = 0 dBFS）
                    val dbfsConverter = remember {
                        val calibRepo = com.wcy.hark.data.experiment.EarphoneCalibrationRepository(context)
                        val model = try {
                            runBlocking {
                                (context.applicationContext as com.wcy.hark.HarkApplication)
                                    .eqSettingsRepository.getSelectedEarphoneFlow().first()
                            }
                        } catch (e: Exception) { "其他" }
                        val table = calibRepo.getAllCalibrations(model);
                        { freqHz: Int, dbHl: Int ->
                            calibRepo.dbfsForTargetDbhl(table, freqHz, dbHl.toFloat()) ?: (dbHl - 100f)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("聽力圖走勢：", fontWeight = FontWeight.Bold)
                        TextButton(onClick = { showDbfs = !showDbfs }) {
                            Text(if (showDbfs) "顯示 dB HL" else "顯示 dB FS", fontSize = 12.sp)
                        }
                    }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .padding(vertical = 4.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        AndroidView(
                            factory = { context ->
                                AudiogramView(context).apply {
                                    setResults(leftEarMap, rightEarMap)
                                    setDbfsConverter(dbfsConverter)
                                }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            update = { view ->
                                view.setResults(leftEarMap, rightEarMap)
                                view.setDbfsConverter(dbfsConverter)
                                view.setDisplayDbfs(showDbfs)
                            }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    Text("各頻率之聽力閾值 (dB HL)：", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                    
                    if (parsed.thresholds.isEmpty()) {
                        Text("無有效頻率數據", color = Color.Gray)
                    } else {
                        // 畫出一個簡單漂亮的表格
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(8.dp)
                                    )
                                .padding(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("頻率 (Hz)", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Text("聽力閾值", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                            }
                            HorizontalDivider(color = Color.LightGray)
                            parsed.thresholds.forEach { t ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("${t.frequency} Hz", modifier = Modifier.weight(1f))
                                    Text("${t.threshold} dB HL", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { activePureToneItem = null }) {
                    Text("關閉", fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

// ── 卡片設計 ──────────────────────────────────────────────────────────

@Composable
fun SrtSessionCard(session: SrtSession, onDelete: (() -> Unit)? = null, onClick: () -> Unit) {
    val dateString = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(session.timestamp))
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Hearing,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "中文雙音節辨識測試",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (session.subjectName.isNotEmpty() && session.subjectName != "未填寫") {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Text(
                                    text = session.subjectName,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = dateString,
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = String.format(Locale.getDefault(), "%.0f%%", session.accuracy),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "答對 ${String.format(Locale.getDefault(), "%.0f", session.accuracy * session.totalQuestions / 100)} / ${session.totalQuestions} 題",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "刪除",
                             tint = Color(0xFFB0B0B0), modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun SsnSessionCard(session: SsnSessionItem, onDelete: (() -> Unit)? = null, onClick: () -> Unit) {
    val dateString = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(session.timestamp))
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFB2DFDB),
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.BarChart,
                            contentDescription = null,
                            tint = Color(0xFF00695C),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("噪音下語詞測驗", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        if (session.subjectName.isNotEmpty() && session.subjectName != "未填寫") {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Text(
                                    text = session.subjectName,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(dateString, fontSize = 12.sp, color = Color.Gray)
                    Text(
                        if (session.isSl) "音量: ${session.snrList} dB SL" else "SNR: ${session.snrList} dB",
                        fontSize = 11.sp, color = Color.Gray
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = session.srt50?.let { "SRT50\n${String.format(Locale.getDefault(), "%.1f", it)} dB" } ?: "SRT50\n—",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                    color = MaterialTheme.colorScheme.primary
                )
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "刪除",
                             tint = Color(0xFFB0B0B0), modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun PureToneFileCard(item: PureToneCsvItem, onDelete: (() -> Unit)? = null, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.HearingDisabled,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "純音聽閾測試 ${item.ear}",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (item.subjectName.isNotEmpty() && item.subjectName != "未填寫") {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Text(
                                    text = item.subjectName,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = item.formattedDate,
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.BarChart,
                    contentDescription = null,
                    tint = Color.LightGray,
                    modifier = Modifier.size(20.dp)
                )
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "刪除",
                             tint = Color(0xFFB0B0B0), modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

// ── 輔助方法 ──────────────────────────────────────────────────────────

private fun loadSsnSessions(dbHelper: SRTResultDbHelper): List<SsnSessionItem> {
    val list = mutableListOf<SsnSessionItem>()
    try {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            SRTResultContract.SSNSessionEntry.TABLE_NAME, null, null, null, null, null,
            "${SRTResultContract.SSNSessionEntry.COLUMN_NAME_TEST_TIMESTAMP} DESC"
        )
        with(cursor) {
            while (moveToNext()) {
                val srtIdx = getColumnIndex(SRTResultContract.SSNSessionEntry.COLUMN_NAME_SRT50)
                list.add(SsnSessionItem(
                    sessionId = getLong(getColumnIndexOrThrow(SRTResultContract.SSNSessionEntry.COLUMN_NAME_SESSION_ID)),
                    timestamp = getLong(getColumnIndexOrThrow(SRTResultContract.SSNSessionEntry.COLUMN_NAME_TEST_TIMESTAMP)),
                    subjectName = getString(getColumnIndexOrThrow(SRTResultContract.SSNSessionEntry.COLUMN_NAME_SUBJECT_NAME)) ?: "未填寫",
                    snrList = getString(getColumnIndexOrThrow(SRTResultContract.SSNSessionEntry.COLUMN_NAME_SNR_LIST)) ?: "",
                    srt50 = if (srtIdx >= 0 && !isNull(srtIdx)) getFloat(srtIdx) else null,
                    isSl = run {
                        val mIdx = getColumnIndex(SRTResultContract.SSNSessionEntry.COLUMN_NAME_TEST_MODE)
                        mIdx >= 0 && !isNull(mIdx) &&
                            getString(mIdx) == SRTResultContract.SSNSessionEntry.MODE_SL
                    }
                ))
            }
            close()
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return list
}

/** 由 ssn_test_records 重算各 SNR 的正確率（%）。 */
private fun loadSsnPoints(dbHelper: SRTResultDbHelper, sessionId: Long): List<Pair<Float, Float>> {
    val correct = mutableMapOf<Float, Int>()
    val total = mutableMapOf<Float, Int>()
    try {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            SRTResultContract.SSNRecordEntry.TABLE_NAME,
            arrayOf(SRTResultContract.SSNRecordEntry.COLUMN_NAME_SNR_DB,
                    SRTResultContract.SSNRecordEntry.COLUMN_NAME_WAS_CORRECT),
            "${SRTResultContract.SSNRecordEntry.COLUMN_NAME_SESSION_ID_FK} = ?",
            arrayOf(sessionId.toString()), null, null, null
        )
        with(cursor) {
            while (moveToNext()) {
                val snr = getFloat(0)
                total[snr] = (total[snr] ?: 0) + 1
                if (getInt(1) == 1) correct[snr] = (correct[snr] ?: 0) + 1
            }
            close()
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return total.map { (snr, n) -> snr to (correct[snr] ?: 0) * 100f / n }.sortedBy { it.first }
}

/** 讀取一場 SSN 測驗的逐題紀錄（依題號排序）。 */
private fun loadSsnRecords(dbHelper: SRTResultDbHelper, sessionId: Long): List<SsnRecordItem> {
    val list = mutableListOf<SsnRecordItem>()
    try {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            SRTResultContract.SSNRecordEntry.TABLE_NAME,
            arrayOf(
                SRTResultContract.SSNRecordEntry.COLUMN_NAME_QUESTION_NUMBER,
                SRTResultContract.SSNRecordEntry.COLUMN_NAME_SNR_DB,
                SRTResultContract.SSNRecordEntry.COLUMN_NAME_CORRECT_WORD,
                SRTResultContract.SSNRecordEntry.COLUMN_NAME_USER_ANSWER,
                SRTResultContract.SSNRecordEntry.COLUMN_NAME_WAS_CORRECT
            ),
            "${SRTResultContract.SSNRecordEntry.COLUMN_NAME_SESSION_ID_FK} = ?",
            arrayOf(sessionId.toString()), null, null,
            "${SRTResultContract.SSNRecordEntry.COLUMN_NAME_QUESTION_NUMBER} ASC"
        )
        with(cursor) {
            while (moveToNext()) {
                list.add(SsnRecordItem(
                    questionNumber = getInt(0),
                    snrDb = getFloat(1),
                    correctWord = getString(2) ?: "",
                    userAnswer = getString(3) ?: "",
                    wasCorrect = getInt(4) == 1
                ))
            }
            close()
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return list
}

/** 削波防護統計：回傳（觸發正規化的題數, 最大額外衰減 dB ≤0）。 */
private fun loadSsnAttenuation(dbHelper: SRTResultDbHelper, sessionId: Long): Pair<Int, Float> {
    var count = 0
    var minDb = 0f
    try {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            SRTResultContract.SSNRecordEntry.TABLE_NAME,
            arrayOf(SRTResultContract.SSNRecordEntry.COLUMN_NAME_NORM_GAIN_DB),
            "${SRTResultContract.SSNRecordEntry.COLUMN_NAME_SESSION_ID_FK} = ?",
            arrayOf(sessionId.toString()), null, null, null
        )
        with(cursor) {
            val idx = getColumnIndex(SRTResultContract.SSNRecordEntry.COLUMN_NAME_NORM_GAIN_DB)
            while (moveToNext()) {
                if (idx < 0 || isNull(idx)) continue
                val v = getFloat(idx)
                if (v < 0f) {
                    count++
                    if (v < minDb) minDb = v
                }
            }
            close()
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return count to minDb
}

private fun deleteSsnSession(dbHelper: SRTResultDbHelper, sessionId: Long) {
    try {
        val db = dbHelper.writableDatabase
        db.delete(SRTResultContract.SSNRecordEntry.TABLE_NAME,
            "${SRTResultContract.SSNRecordEntry.COLUMN_NAME_SESSION_ID_FK} = ?", arrayOf(sessionId.toString()))
        db.delete(SRTResultContract.SSNSessionEntry.TABLE_NAME,
            "${SRTResultContract.SSNSessionEntry.COLUMN_NAME_SESSION_ID} = ?", arrayOf(sessionId.toString()))
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private fun deleteSrtSession(dbHelper: SRTResultDbHelper, sessionId: Long) {
    try {
        val db = dbHelper.writableDatabase
        db.delete(SRTResultContract.SRTRecordEntry.TABLE_NAME,
            "${SRTResultContract.SRTRecordEntry.COLUMN_NAME_SESSION_ID_FK} = ?", arrayOf(sessionId.toString()))
        db.delete(SRTResultContract.TestSessionEntry.TABLE_NAME,
            "${SRTResultContract.TestSessionEntry.COLUMN_NAME_SESSION_ID} = ?", arrayOf(sessionId.toString()))
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private fun loadSrtSessions(dbHelper: SRTResultDbHelper): List<SrtSession> {
    val list = mutableListOf<SrtSession>()
    try {
        val db = dbHelper.readableDatabase
        val projection = arrayOf(
            SRTResultContract.TestSessionEntry.COLUMN_NAME_SESSION_ID,
            SRTResultContract.TestSessionEntry.COLUMN_NAME_TEST_TIMESTAMP,
            SRTResultContract.TestSessionEntry.COLUMN_NAME_OVERALL_ACCURACY,
            SRTResultContract.TestSessionEntry.COLUMN_NAME_TOTAL_QUESTIONS_ANSWERED,
            SRTResultContract.TestSessionEntry.COLUMN_NAME_PHONE_VOLUME,
            SRTResultContract.TestSessionEntry.COLUMN_NAME_SUBJECT_NAME
        )
        val sortOrder = "${SRTResultContract.TestSessionEntry.COLUMN_NAME_TEST_TIMESTAMP} DESC"
        val cursor = db.query(
            SRTResultContract.TestSessionEntry.TABLE_NAME,
            projection,
            null,
            null,
            null,
            null,
            sortOrder
        )
        with(cursor) {
            while (moveToNext()) {
                val sessionId = getLong(getColumnIndexOrThrow(SRTResultContract.TestSessionEntry.COLUMN_NAME_SESSION_ID))
                val timestamp = getLong(getColumnIndexOrThrow(SRTResultContract.TestSessionEntry.COLUMN_NAME_TEST_TIMESTAMP))
                val accuracy = getDouble(getColumnIndexOrThrow(SRTResultContract.TestSessionEntry.COLUMN_NAME_OVERALL_ACCURACY))
                val totalQuestions = getInt(getColumnIndexOrThrow(SRTResultContract.TestSessionEntry.COLUMN_NAME_TOTAL_QUESTIONS_ANSWERED))
                
                val phoneVolumeColIndex = getColumnIndex(SRTResultContract.TestSessionEntry.COLUMN_NAME_PHONE_VOLUME)
                val phoneVolume = if (phoneVolumeColIndex != -1) getInt(phoneVolumeColIndex) else -1
                
                val subjectNameColIndex = getColumnIndex(SRTResultContract.TestSessionEntry.COLUMN_NAME_SUBJECT_NAME)
                val subjectName = if (subjectNameColIndex != -1) getString(subjectNameColIndex) ?: "未填寫" else "未填寫"
                
                list.add(SrtSession(sessionId, timestamp, accuracy, totalQuestions, phoneVolume, subjectName))
            }
        }
        cursor.close()
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return list
}

private fun loadSrtRecordsForSession(dbHelper: SRTResultDbHelper, sessionId: Long): List<SrtRecord> {
    val list = mutableListOf<SrtRecord>()
    try {
        val db = dbHelper.readableDatabase
        val projection = arrayOf(
            SRTResultContract.SRTRecordEntry.COLUMN_NAME_QUESTION_NUMBER,
            SRTResultContract.SRTRecordEntry.COLUMN_NAME_CORRECT_WORD,
            SRTResultContract.SRTRecordEntry.COLUMN_NAME_USER_ANSWER,
            SRTResultContract.SRTRecordEntry.COLUMN_NAME_WAS_CORRECT
        )
        val selection = "${SRTResultContract.SRTRecordEntry.COLUMN_NAME_SESSION_ID_FK} = ?"
        val selectionArgs = arrayOf(sessionId.toString())
        val cursor = db.query(
            SRTResultContract.SRTRecordEntry.TABLE_NAME,
            projection,
            selection,
            selectionArgs,
            null,
            null,
            "${SRTResultContract.SRTRecordEntry.COLUMN_NAME_QUESTION_NUMBER} ASC"
        )
        with(cursor) {
            while (moveToNext()) {
                val qNum = getInt(getColumnIndexOrThrow(SRTResultContract.SRTRecordEntry.COLUMN_NAME_QUESTION_NUMBER))
                val correctWord = getString(getColumnIndexOrThrow(SRTResultContract.SRTRecordEntry.COLUMN_NAME_CORRECT_WORD))
                val userAnswer = getString(getColumnIndexOrThrow(SRTResultContract.SRTRecordEntry.COLUMN_NAME_USER_ANSWER))
                val wasCorrect = getInt(getColumnIndexOrThrow(SRTResultContract.SRTRecordEntry.COLUMN_NAME_WAS_CORRECT)) == 1
                list.add(SrtRecord(qNum, correctWord, userAnswer, wasCorrect))
            }
        }
        cursor.close()
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return list
}

private fun loadPureToneCsvs(directory: File?): List<PureToneCsvItem> {
    if (directory == null || !directory.exists()) return emptyList()
    val files = directory.listFiles { _, name ->
        name.contains("PureTone") && name.endsWith("_Results.csv")
    } ?: emptyArray()
    
    files.sortByDescending { it.lastModified() }
    
    val list = mutableListOf<PureToneCsvItem>()
    val sdfInput = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
    val sdfOutput = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())
    
    files.forEach { file ->
        val name = file.name
        var ear = "雙耳"
        if (name.contains("Left")) ear = "左耳"
        else if (name.contains("Right")) ear = "右耳"
        
        var formattedDate = ""
        try {
            val dateStr = name.substring(0, 19)
            val date = sdfInput.parse(dateStr)
            if (date != null) {
                formattedDate = sdfOutput.format(date)
            }
        } catch (e: Exception) {
            formattedDate = sdfOutput.format(Date(file.lastModified()))
        }
        
        // Fast read of first line to extract Subject Name metadata
        var subjectName = "未填寫"
        try {
            file.bufferedReader().use { reader ->
                val firstLine = reader.readLine()
                if (firstLine != null && firstLine.startsWith("Subject Name,")) {
                    val tokens = firstLine.split(",")
                    if (tokens.size >= 2) {
                        subjectName = tokens[1].trim()
                    }
                }
            }
        } catch (e: Exception) {
            // fallback if empty or legacy CSV format
        }
        
        list.add(PureToneCsvItem(file, name, formattedDate, ear, subjectName))
    }
    return list
}

private fun parsePureToneResultsCsv(file: File): ParsedPureToneResult {
    var noise = 0.0
    var reliabilityWarning = false
    val thresholds = mutableListOf<PureToneThreshold>()
    try {
        file.bufferedReader().use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val trimmed = line?.trim() ?: ""
                if (trimmed.startsWith("Subject Name")) {
                    // Metadata header, skip threshold processing
                    continue
                }
                if (trimmed.startsWith("Environmental Noise")) {
                    val tokens = trimmed.split(",")
                    if (tokens.size >= 2) {
                        noise = tokens[1].toDoubleOrNull() ?: 0.0
                    }
                } else if (trimmed.startsWith("Reliability Warning")) {
                    val tokens = trimmed.split(",")
                    if (tokens.size >= 2) {
                        reliabilityWarning = tokens[1].toBoolean()
                    }
                } else {
                    val tokens = trimmed.split(",")
                    if (tokens.size >= 3) {
                        val ear = tokens[0].trim()
                        val freq = tokens[1].toIntOrNull()
                        val threshold = tokens[2].toIntOrNull()
                        if (freq != null && threshold != null) {
                            thresholds.add(PureToneThreshold(ear, freq, threshold))
                        }
                    }
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return ParsedPureToneResult(noise, thresholds, reliabilityWarning)
}

// ── 科學錯題分析診斷 ─────────────────────────────────────────────

data class SrtDiagnosticItem(
    val questionId: Int,
    val correctWord: String,
    val heardWord: String,
    val riskyFrequencies: List<Double>,
    val confusionScore: Double
)

private fun loadSrtDiagnosticDatabase(context: android.content.Context): Map<String, SrtDiagnosticItem> {
    val database = mutableMapOf<String, SrtDiagnosticItem>()
    try {
        val jsonString = context.assets.open("srt_diagnostic_data.json").bufferedReader().use { it.readText() }
        val jsonObject = org.json.JSONObject(jsonString)
        val keys = jsonObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val itemObject = jsonObject.getJSONObject(key)
            val questionId = itemObject.getInt("question_id")
            val correctWord = itemObject.getString("correct_word")
            val heardWord = itemObject.getString("heard_word")
            val confusionScore = itemObject.getDouble("confusion_score")
            
            val riskyFreqsArray = itemObject.getJSONArray("risky_frequencies")
            val riskyFrequencies = mutableListOf<Double>()
            for (i in 0 until riskyFreqsArray.length()) {
                riskyFrequencies.add(riskyFreqsArray.getDouble(i))
            }
            
            database[key] = SrtDiagnosticItem(
                questionId = questionId,
                correctWord = correctWord,
                heardWord = heardWord,
                riskyFrequencies = riskyFrequencies,
                confusionScore = confusionScore
            )
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return database
}

private fun getAcousticDiagnostic(
    wrongRecords: List<SrtRecord>,
    diagnosticMap: Map<String, SrtDiagnosticItem>
): String {
    if (wrongRecords.isEmpty()) {
        return "✨ 恭喜！本次測試您答對了所有題目！這代表您目前的音訊補償或聽力狀況在語詞辨識上非常良好。"
    }

    // 頻譜分析僅適用「聽成另一個詞」的錯題；按「不確定」屬未作答，另計。
    val notSureCount = wrongRecords.count { it.userAnswer == "not_sure" }
    val substitutionRecords = wrongRecords.filter { it.userAnswer != "not_sure" }

    // 每個詞對的票數以 (1 − confusion_score) 加權：
    // 聲學上本來就極相似（confusion 高）的詞對，聽錯所提供的診斷資訊較少。
    val freqWeight = mutableMapOf<Double, Double>()
    var validDataCount = 0
    substitutionRecords.forEach { record ->
        val item = diagnosticMap["${record.correctWord},${record.userAnswer}"]
        if (item != null) {
            validDataCount++
            val w = (1.0 - item.confusionScore).coerceAtLeast(0.1)
            item.riskyFrequencies.forEach { freq ->
                freqWeight[freq] = (freqWeight[freq] ?: 0.0) + w
            }
        }
    }

    // 先驗正規化：對照表中各頻帶本身出現次數不均（低頻與 8kHz 偏多），
    // 直接比原始票數會系統性偏向低頻。以「觀察值 / 全表先驗」的過度代表比例比較。
    val prior = mutableMapOf<Double, Int>()
    diagnosticMap.values.forEach { it.riskyFrequencies.forEach { f -> prior[f] = (prior[f] ?: 0) + 1 } }
    val priorTotal = prior.values.sum().toDouble().coerceAtLeast(1.0)
    val obsTotal = freqWeight.values.sum().coerceAtLeast(1e-9)
    fun overRepresentation(freqs: List<Double>): Double {
        val obs = freqs.sumOf { freqWeight[it] ?: 0.0 } / obsTotal
        val pri = freqs.sumOf { (prior[it] ?: 0).toDouble() } / priorTotal
        return if (pri > 0) obs / pri else 0.0
    }

    val lowFreqs = listOf(250.0, 315.0, 400.0, 500.0, 630.0, 800.0)
    val midFreqs = listOf(1000.0, 1250.0, 1600.0, 2000.0, 2500.0)
    val highFreqs = listOf(3150.0, 4000.0, 5000.0, 6300.0, 8000.0)

    val totalWrong = wrongRecords.size
    val minValidForDiagnosis = 5   // 4AFC 有 25% 猜對率，錯題太少不足以下頻譜結論

    return buildString {
        append("📊 錯題頻譜特徵分析（探索性參考）：\n")
        append("本次測試共答錯 $totalWrong 題")
        if (notSureCount > 0) append("（其中 $notSureCount 題按「不確定」，屬未作答，不納入頻譜分析）")
        append("。")

        if (validDataCount >= minValidForDiagnosis) {
            val lowOR = overRepresentation(lowFreqs)
            val midOR = overRepresentation(midFreqs)
            val highOR = overRepresentation(highFreqs)
            val orSum = (lowOR + midOR + highOR).coerceAtLeast(1e-9)

            // 前 3 名風險頻率（同樣以過度代表比例排序）
            val topFrequencies = freqWeight.keys
                .sortedByDescending { f ->
                    val p = (prior[f] ?: 0).toDouble() / priorTotal
                    if (p > 0) (freqWeight[f]!! / obsTotal) / p else 0.0
                }
                .take(3)

            append("\n\n【混淆特徵分佈（相對於題庫先驗之過度代表比例）】")
            append(String.format(Locale.getDefault(), "\n  低頻共鳴區 (250-800Hz): %.0f%%", lowOR / orSum * 100))
            append(String.format(Locale.getDefault(), "\n  中頻人聲區 (1000-2500Hz): %.0f%%", midOR / orSum * 100))
            append(String.format(Locale.getDefault(), "\n  高頻摩擦音 (3150-8000Hz): %.0f%%", highOR / orSum * 100))

            if (topFrequencies.isNotEmpty()) {
                append("\n\n最常涉及的分辨頻率：")
                append(topFrequencies.joinToString { "${it.toInt()}Hz" })
            }

            append("\n\n")
            if (highOR >= lowOR && highOR >= midOR) {
                append("📌 觀察：您聽錯的詞對，其分辨線索多位於【高頻摩擦音區間】（如 ㄙ、ㄕ、ㄒ 等摩擦音，能量集中於 3150–8000Hz）。\n\n")
                append("💡 參考建議：可對照純音聽力圖確認高頻閾值後，嘗試微調 4000Hz 與 8000Hz 的 EQ 增益，觀察辨識是否改善。")
            } else if (midOR >= lowOR && midOR >= highOR) {
                append("📌 觀察：您聽錯的詞對，其分辨線索多位於【中頻人聲核心區間】（1000–2500Hz，語音共振峰與語意理解的關鍵頻帶）。\n\n")
                append("💡 參考建議：可對照純音聽力圖後，嘗試微調 1000Hz、1600Hz 與 2000Hz 的 EQ 增益。")
            } else {
                append("📌 觀察：您聽錯的詞對，其分辨線索多位於【低頻與母音/鼻音共鳴區間】（能量多低於 1000Hz）。\n\n")
                append("💡 參考建議：可嘗試微調 500Hz 與 800Hz 增益，並檢查耳塞密合度——密合不良會造成低頻嚴重衰減。")
            }
            append("\n\n※ 本分析為探索性參考：答錯也可能來自猜測（四選一有 25% 猜對率）、詞彙熟悉度或注意力，請以純音聽力檢測結果為主要依據。")
        } else if (validDataCount > 0) {
            append("\n\n📌 有效錯題僅 $validDataCount 題（少於 $minValidForDiagnosis 題），樣本不足以進行頻譜特徵分析——少量錯誤可能只是猜測波動。\n\n")
            append("💡 參考建議：可對照純音聽力檢測結果調整 EQ，或增加測驗題數後再分析。")
        } else {
            append("\n\n📌 錯題無對應的頻譜特徵資料（例如皆為「不確定」），無法進行頻譜分析。\n\n")
            append("💡 參考建議：請以純音聽力檢測結果為依據調整 EQ，或重新進行一次語詞測驗。")
        }
    }
}

