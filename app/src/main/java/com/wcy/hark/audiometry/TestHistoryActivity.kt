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
    val tabs = listOf("語詞辨識 (SRT)", "純音聽力 (Pure Tone)")
    
    val context = androidx.compose.ui.platform.LocalContext.current
    val srtDiagnosticMap = remember { loadSrtDiagnosticDatabase(context) }

    // 狀態讀取
    var srtSessions by remember { mutableStateOf(listOf<SrtSession>()) }
    var pureToneFiles by remember { mutableStateOf(listOf<PureToneCsvItem>()) }

    // 載入數據的 side effect
    LaunchedEffect(selectedTab) {
        if (selectedTab == 0) {
            srtSessions = loadSrtSessions(dbHelper)
        } else {
            pureToneFiles = loadPureToneCsvs(externalFilesDir)
        }
    }

    // 詳細 Dialog 狀態
    var activeSrtSession by remember { mutableStateOf<SrtSession?>(null) }
    var srtRecordsForActiveSession by remember { mutableStateOf(listOf<SrtRecord>()) }
    var activePureToneItem by remember { mutableStateOf<PureToneCsvItem?>(null) }

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

            if (selectedTab == 0) {
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
                            SrtSessionCard(session = session) {
                                activeSrtSession = session
                            }
                        }
                    }
                }
            } else {
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
                            PureToneFileCard(item = item) {
                                activePureToneItem = item
                            }
                        }
                    }
                }
            }
        }
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
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (session.subjectName.isNotEmpty() && session.subjectName != "未填寫") {
                        Text(
                            text = "受試者：${session.subjectName}",
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
                    
                    // 明細列表
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                    ) {
                        items(srtRecordsForActiveSession) { record ->
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
                        Text("受試者：${item.subjectName}", fontWeight = FontWeight.Bold)
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
                                    text = "1000 Hz 首次測試與第二次重測閾值差值達 10 dB 以上，表明受試者反應一致性較低，建議參考價值降低並重新測試。",
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    color = Color(0xFF5D4037)
                                )
                            }
                        }
                    }

                    // ── 繪製聽力圖走勢 Card ──
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("聽力圖走勢：", fontWeight = FontWeight.Bold)
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
                                }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            update = { view ->
                                view.setResults(leftEarMap, rightEarMap)
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
fun SrtSessionCard(session: SrtSession, onClick: () -> Unit) {
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
        }
    }
}

@Composable
fun PureToneFileCard(item: PureToneCsvItem, onClick: () -> Unit) {
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
                            text = "純音聽閾測試 (${item.ear})",
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
            Icon(
                imageVector = Icons.Default.BarChart,
                contentDescription = null,
                tint = Color.LightGray,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ── 輔助方法 ──────────────────────────────────────────────────────────

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
    
    val freqBands = listOf(
        250.0, 315.0, 400.0, 500.0, 630.0, 800.0, 
        1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 
        3150.0, 4000.0, 5000.0, 6300.0, 8000.0
    )
    val freqCount = freqBands.associateWith { 0 }.toMutableMap()
    var validDataCount = 0
    
    wrongRecords.forEach { record ->
        // 使用正確詞與使用者回答作為 key，查詢預計算的 Mel 頻譜特徵差異數據
        val key = "${record.correctWord},${record.userAnswer}"
        val item = diagnosticMap[key]
        if (item != null) {
            validDataCount++
            item.riskyFrequencies.forEach { freq ->
                freqCount[freq] = (freqCount[freq] ?: 0) + 1
            }
        }
    }
    
    val totalWrong = wrongRecords.size
    
    return buildString {
        append("📊 錯題頻譜特徵診斷與 EQ 建議：\n")
        append("本次測試共答錯 $totalWrong 題。")
        
        if (validDataCount > 0) {
            // 將 16 個助聽器頻段劃分為低、中、高頻三大聲學區間
            val lowFreqs = listOf(250.0, 315.0, 400.0, 500.0, 630.0, 800.0)
            val midFreqs = listOf(1000.0, 1250.0, 1600.0, 2000.0, 2500.0)
            val highFreqs = listOf(3150.0, 4000.0, 5000.0, 6300.0, 8000.0)
            
            val lowCount = lowFreqs.sumOf { freqCount[it] ?: 0 }
            val midCount = midFreqs.sumOf { freqCount[it] ?: 0 }
            val highCount = highFreqs.sumOf { freqCount[it] ?: 0 }
            val totalMentions = lowCount + midCount + highCount
            
            val lowRatio = if (totalMentions > 0) lowCount.toFloat() / totalMentions else 0f
            val midRatio = if (totalMentions > 0) midCount.toFloat() / totalMentions else 0f
            val highRatio = if (totalMentions > 0) highCount.toFloat() / totalMentions else 0f
            
            // 找出最頻繁出現的前 3 名風險頻率
            val topFrequencies = freqCount.entries
                .filter { it.value > 0 }
                .sortedByDescending { it.value }
                .take(3)
                .map { it.key }
            
            append("\n\n【混淆風險特徵分佈】")
            append(String.format(Locale.getDefault(), "\n  低頻共鳴區 (250-800Hz): %.1f%%", lowRatio * 100))
            append(String.format(Locale.getDefault(), "\n  中頻人聲區 (1000-2500Hz): %.1f%%", midRatio * 100))
            append(String.format(Locale.getDefault(), "\n  高頻摩擦音 (3150-8000Hz): %.1f%%", highRatio * 100))
            
            if (topFrequencies.isNotEmpty()) {
                append("\n\n⚠️ 聲學特徵極相似之高混淆風險頻率：")
                append(topFrequencies.joinToString { "${it.toInt()}Hz" })
            }
            
            append("\n\n")
            if (highRatio >= lowRatio && highRatio >= midRatio) {
                append("⚠️ 診斷：您聽錯的詞對在頻譜上的特徵差異主要分布在【高頻摩擦音區間】（如 ㄙ、ㄕ、ㄒ 等摩擦音）。這類輔音的聲學能量集中在 3150Hz 至 8000Hz，您可能在此高頻區段的分辨較為吃力。\n\n")
                append("💡 EQ 建議：建議在使用等化器 (EQ) 時，適度將 4000Hz 與 8000Hz 頻段的增益額外調高 3~5 dB，這能有效突顯輔音的聲學細節，提升字詞辨識清晰度。")
            } else if (midRatio >= lowRatio && midRatio >= highRatio) {
                append("⚠️ 診斷：您的聽錯詞對特徵差異主要分布在【中頻人聲核心區間】（如 ㄓ、ㄌ、ㄋ 等聲母）。這類語音的共振峰能量主要分布在 1000Hz 至 2500Hz 之間，是日常對話中語意理解與語音辨識的最關鍵頻帶。\n\n")
                append("💡 EQ 建議：建議調高 1000Hz、1600Hz 與 2000Hz 頻段的 EQ 增益，可以讓對話的人聲邊緣與字詞細節更加分明與飽滿。")
            } else {
                append("⚠️ 診斷：您的聽錯詞對特徵差異主要分布在【低頻與母音/鼻音共鳴區間】（如 ㄅ、ㄇ、ㄉ 等聲母，或鼻音 ㄣ、ㄤ）。這些語音在物理聲學上的主要能量低於 1000Hz。\n\n")
                append("💡 EQ 建議：建議微調 500Hz 與 800Hz 增益。另外請檢查您的耳塞密合度。若是使用藍牙/有線耳機，耳塞密合度不良會造成低頻嚴重衰減，直接削弱這類低頻與鼻音共鳴的辨識能力。")
            }
        } else {
            // 回退邏輯：若少數錯題不在對照表內，使用均勻的綜合建議
            append("\n\n⚠️ 診斷：聽錯詞對在不同頻帶的能量分布較為均勻，無單一主導頻譜區間。\n\n")
            append("💡 EQ 建議：建議您對照 PTA 純音聽力檢測的結果，對聽力閾值受損較嚴重的頻段進行對應的 EQ 增益微調，或重新進行一次測試確認。")
        }
    }
}

