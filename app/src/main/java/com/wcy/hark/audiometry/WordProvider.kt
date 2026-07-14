package com.wcy.hark.audiometry
import com.wcy.hark.R

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.random.Random

data class WordQuestion(
    val options: List<String>,
    val correctWord: String,
    val audioFileName: String
)

class WordProvider(private val context: Context) {

    companion object {
        private const val CSV_FILE_NAME = "wordlist.csv"
        private const val HEADER_TO_SKIP = "HSE List4"
        private const val TAG = "WordProvider"
    }

    // allWordRows 儲存原始 CSV 檔案中的每一行資料 (List<String>)
    // 以及該行在 CSV 中的原始索引 (相對於資料行的第0行)
    private val allWordRowsWithOriginalIndex: MutableList<Pair<List<String>, Int>> = mutableListOf()

    init {
        Log.d(TAG, "Initializing WordProvider...")
        loadWordsFromCSV()
    }

    private fun loadWordsFromCSV() {
        var linesProcessedForData = 0 // 計數實際被當作"潛在資料"處理的行 (表頭之後)
        var validRowsAdded = 0
        var originalCsvDataRowIndex = 0 // CSV 資料行的0-indexed (表頭之後)
        try {
            Log.d(TAG, "Attempting to open CSV: $CSV_FILE_NAME from assets.")
            context.assets.open(CSV_FILE_NAME).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, "UTF-8")).use { reader ->
                    var line: String?
                    var fileLineNumber = 0 // 追蹤檔案的實際行號

                    // 1. 讀取並處理表頭
                    line = reader.readLine()
                    fileLineNumber++
                    val firstLineTrimmed = line?.trim()
                    if (firstLineTrimmed == HEADER_TO_SKIP) {
                        Log.i(TAG, "Header '$HEADER_TO_SKIP' found and skipped on file line $fileLineNumber.")
                    } else {
                        Log.w(TAG, "File line $fileLineNumber: Expected header '$HEADER_TO_SKIP', but found '$firstLineTrimmed'.")
                        // 如果第一行不是表頭，且它看起來像有效資料，則需要處理
                        // 但目前設計是強烈依賴第一行為表頭並跳過。
                        // 若第一行就開始是資料，此處會印警告但不會處理它作為資料，而是從下一行開始。
                        // 確保 CSV 的第一行確實是 HEADER_TO_SKIP。
                    }

                    // 2. 讀取資料行
                    while (reader.readLine().also { line = it } != null) {
                        fileLineNumber++
                        linesProcessedForData++
                        val currentLineTrimmed = line?.trim()

                        if (currentLineTrimmed?.isNotBlank() == true) {
                            val tokens = currentLineTrimmed.split(",").map { it.trim() }
                            if (tokens.size == 4) {
                                allWordRowsWithOriginalIndex.add(Pair(tokens, originalCsvDataRowIndex))
                                validRowsAdded++
                                Log.d(TAG, "Added valid row (data index $originalCsvDataRowIndex, file line $fileLineNumber): $tokens")
                                originalCsvDataRowIndex++ // 只有加入成功的資料行，其0-indexed的原始資料行號才遞增
                            } else {
                                Log.w(TAG, "Skipped data row (data index $originalCsvDataRowIndex, file line $fileLineNumber) due to incorrect token count (${tokens.size}): '$currentLineTrimmed'")
                            }
                        } else {
                            Log.d(TAG, "Skipped blank line (file line $fileLineNumber): '$currentLineTrimmed'")
                        }
                    }
                }
            }
            Log.i(TAG, "Finished reading CSV. Lines processed after header: $linesProcessedForData. Valid word rows added: $validRowsAdded.")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading words from CSV: ${e.message}", e)
        }
        Log.i(TAG, "Final size of allWordRowsWithOriginalIndex: ${allWordRowsWithOriginalIndex.size}")
        if (allWordRowsWithOriginalIndex.isEmpty()) {
            Log.e(TAG, "CRITICAL: No valid data rows loaded from CSV. Check CSV format and header.")
        }
    }

    // getRandomQuestions, generateQuestionFromRow, getTotalAvailableUniqueQuestions 保持與上一版相同
    // (因為它們依賴於 loadWordsFromCSV 正確填充 allWordRowsWithOriginalIndex)
    /**
     * @param parity 互斥詞表分半：0 = 偶數列、1 = 奇數列、-1 = 不分（整個詞庫）。
     *   A/B 兩階段各用一半，保證同一個詞不會在兩階段重複出現——否則第二階段
     *   會有「背答案」效應，練習效果與補償效果混在一起無法歸因。
     */
    fun getRandomQuestions(count: Int, parity: Int = -1): List<WordQuestion> {
        Log.d(TAG, "getRandomQuestions called. Requesting $count questions. Available unique rows from CSV: ${allWordRowsWithOriginalIndex.size}")
        if (allWordRowsWithOriginalIndex.isEmpty() || count <= 0) {
            Log.w(TAG, "No rows available or requested count is zero. Returning empty list.")
            return emptyList()
        }

        // 複製一份包含原始索引的資料列，以便隨機選取不重複
        val availableRowsWithIndex = (if (parity >= 0)
            allWordRowsWithOriginalIndex.filterIndexed { i, _ -> i % 2 == parity }
        else allWordRowsWithOriginalIndex).toMutableList()
        val selectedQuestions = mutableListOf<WordQuestion>()

        val numToSelect = if (count > availableRowsWithIndex.size) {
            Log.w(TAG, "Requested $count questions, but only ${availableRowsWithIndex.size} unique rows are available. Will return all available rows.")
            availableRowsWithIndex.size
        } else {
            count
        }

        availableRowsWithIndex.shuffle()

        for (i in 0 until numToSelect) {
            // availableRowsWithIndex 已經被打亂，所以直接從頭取
            val (rowData, originalRowIdx_0_based) = availableRowsWithIndex[i]
            generateQuestionFromRow(rowData, originalRowIdx_0_based)?.let {
                selectedQuestions.add(it)
            }
        }
        Log.d(TAG, "Returning ${selectedQuestions.size} questions to SRTTestActivity.")
        return selectedQuestions
    }

    private fun generateQuestionFromRow(rowOptions: List<String>, originalCsvRowIndex_0_based: Int): WordQuestion? {
        if (rowOptions.size != 4) {
            Log.w(TAG, "generateQuestionFromRow: Row does not have 4 options: $rowOptions for original 0-based index $originalCsvRowIndex_0_based")
            return null
        }

        val correctWordColumnIndexInRow_0_based = Random.nextInt(0, 4)
        val correctWordForDisplay = rowOptions[correctWordColumnIndexInRow_0_based]

        // 產生音訊檔名，對應 Python 腳本的命名邏輯 (r從2開始, c從1開始)
        // originalCsvRowIndex_0_based 是 CSV 資料行的 0-indexed (0 to 49) -> 對應 r = index + 2
        // correctWordColumnIndexInRow_0_based 是欄位的 0-indexed (0 to 3) -> 對應 c = index + 1
        val outputAudioRowNumber = originalCsvRowIndex_0_based + 2
        val outputAudioColumnNumber = correctWordColumnIndexInRow_0_based + 1

        val audioFileNameBase = "hselist4_r${outputAudioRowNumber}_c${outputAudioColumnNumber}"
        val audioFileName = "${audioFileNameBase}.wav"

        Log.d(TAG, "Generated for display: '${correctWordForDisplay}', audio: '${audioFileName}' (from CSV data_row_idx=${originalCsvRowIndex_0_based}, data_col_idx=${correctWordColumnIndexInRow_0_based})")

        return WordQuestion(
            options = rowOptions,
            correctWord = correctWordForDisplay,
            audioFileName = audioFileName
        )
    }

    fun getTotalAvailableUniqueQuestions(): Int {
        return allWordRowsWithOriginalIndex.size
    }
}