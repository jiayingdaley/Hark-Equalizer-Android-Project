package com.wcy.hark.data.experiment

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Per-frequency calibration state for one earphone model.
 *
 * @param refDbfs        dBFS level at which the researcher played the calibration tone
 * @param measuredDbSpl  SPL measured externally (coupler / sound level meter) at refDbfs;
 *                       null = this frequency has not been calibrated yet
 */
data class FreqCalibration(
    val refDbfs: Float,
    val measuredDbSpl: Float?
)

/**
 * EarphoneCalibrationRepository (schema v2)
 *
 * Stores a per-earphone, per-frequency measured calibration table as JSON in the
 * app's internal files directory (seeded from assets on first launch).
 *
 * Schema v2:
 *   { "_version": 2,
 *     "<model>": { "<freqHz>": { "refDbfs": -20.0, "measuredDbSpl": 78.3 | null }, ... } }
 *
 * Conversion math (linear output assumption within the usable range):
 *   dBSPL(f, dbfs) = measuredDbSpl + (dbfs - refDbfs)
 *   dBHL           = dBSPL - RETSPL[f]
 *   dbfs for target dBHL = refDbfs + (targetDbhl + RETSPL[f]) - measuredDbSpl
 *
 * Reference: ISO 389-1 (RETSPL), ISO 8253-1, ANSI S3.22
 */
class EarphoneCalibrationRepository(private val context: Context) {

    companion object {
        private const val ASSET_FILE = "earphone_calibration.json"
        private const val LOCAL_FILE = "earphone_calibration.json"
        private const val COMMENT_KEY = "_comment"
        private const val VERSION_KEY = "_version"
        private const val SCHEMA_VERSION = 2
        const val DEFAULT_REF_DBFS = -20.0f

        // 型號改名對照（舊顯示名 → 系統 API 實際回報名，AudioDeviceInfo.productName）。
        // 讀檔時就地遷移，讓既有量測校正資料跟著新名稱走；DataStore 已選型號
        // 也用這張表換算。歷史資料庫紀錄保留舊字串不動（僅為標籤）。
        val MODEL_RENAMES: Map<String, String> = mapOf(
            "Apple EarPods (Type-C)" to "EarPods (USB-C)",
            "AirPods Pro 2" to "AirPods Pro",
            "SONY WH-1000XM6" to "WH-1000XM6"
        )

        // Audiometric test frequencies (Hz)
        val TEST_FREQUENCIES = listOf(250, 500, 1000, 2000, 3000, 4000, 6000, 8000)

        // RETSPL (Reference Equivalent Threshold SPL), ISO 389-1, TDH-39 supra-aural.
        // Swap for ISO 389-2 insert-earphone values if insert phones are used.
        val RETSPL: Map<Int, Float> = mapOf(
            250 to 25.5f, 500 to 11.5f, 1000 to 7.0f, 2000 to 9.0f,
            3000 to 10.0f, 4000 to 9.5f, 6000 to 15.5f, 8000 to 13.0f
        )
    }

    private val localFile: File get() = File(context.filesDir, LOCAL_FILE)

    /**
     * Loads the local JSON, seeding from assets on first launch and migrating
     * v1 files (plain numeric corrections — all unmeasured zeros) to v2.
     */
    @Synchronized
    private fun loadJson(): JSONObject {
        if (!localFile.exists()) {
            context.assets.open(ASSET_FILE).use { input ->
                localFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        var json = JSONObject(localFile.readText())
        if (json.optInt(VERSION_KEY, 1) < SCHEMA_VERSION) {
            json = migrateToV2(json)
            saveJson(json)
        }
        // 型號改名遷移：把舊名稱底下的（可能已量測的）校正資料搬到新名稱
        var renamed = false
        MODEL_RENAMES.forEach { (old, new) ->
            if (json.has(old)) {
                if (!json.has(new)) json.put(new, json.getJSONObject(old))
                json.remove(old)
                renamed = true
            }
        }
        if (renamed) saveJson(json)
        return json
    }

    /** v1 corrections were all 0.0 / unmeasured, so they are discarded. */
    private fun migrateToV2(old: JSONObject): JSONObject {
        val v2 = JSONObject()
        v2.put(VERSION_KEY, SCHEMA_VERSION)
        for (key in old.keys()) {
            if (key == COMMENT_KEY || key == VERSION_KEY) continue
            val freqs = JSONObject()
            TEST_FREQUENCIES.forEach { f ->
                freqs.put(f.toString(), JSONObject().apply {
                    put("refDbfs", DEFAULT_REF_DBFS.toDouble())
                    put("measuredDbSpl", JSONObject.NULL)
                })
            }
            v2.put(key, freqs)
        }
        return v2
    }

    @Synchronized
    private fun saveJson(json: JSONObject) {
        localFile.writeText(json.toString(2))
    }

    /** Returns the list of available earphone model names. */
    fun getEarphoneModels(): List<String> {
        val json = loadJson()
        return json.keys().asSequence()
            .filter { it != COMMENT_KEY && it != VERSION_KEY }
            .toList()
    }

    private fun parseEntry(obj: JSONObject?): FreqCalibration? {
        obj ?: return null
        val measured = if (obj.isNull("measuredDbSpl")) null
                       else obj.optDouble("measuredDbSpl").toFloat()
        return FreqCalibration(
            refDbfs = obj.optDouble("refDbfs", DEFAULT_REF_DBFS.toDouble()).toFloat(),
            measuredDbSpl = measured
        )
    }

    /** Returns the calibration entry for a model + frequency, or null if absent. */
    fun getCalibration(model: String, freqHz: Int): FreqCalibration? {
        return try {
            parseEntry(loadJson().optJSONObject(model)?.optJSONObject(freqHz.toString()))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Batch getter — load the whole table for a model once (e.g. before a
     * pure-tone test) so no JSON file reads happen on the audio path.
     */
    fun getAllCalibrations(model: String): Map<Int, FreqCalibration> {
        return try {
            val entry = loadJson().optJSONObject(model) ?: return emptyMap()
            TEST_FREQUENCIES.mapNotNull { f ->
                parseEntry(entry.optJSONObject(f.toString()))?.let { f to it }
            }.toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** Persists a researcher measurement for one model + frequency. */
    fun saveMeasurement(model: String, freqHz: Int, refDbfs: Float, measuredDbSpl: Float) {
        try {
            val json = loadJson()
            val entry = json.optJSONObject(model) ?: JSONObject().also { json.put(model, it) }
            entry.put(freqHz.toString(), JSONObject().apply {
                put("refDbfs", refDbfs.toDouble())
                put("measuredDbSpl", measuredDbSpl.toDouble())
            })
            saveJson(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** True when all TEST_FREQUENCIES have a measured SPL for this model. */
    fun isFullyCalibrated(model: String): Boolean {
        val table = getAllCalibrations(model)
        return TEST_FREQUENCIES.all { table[it]?.measuredDbSpl != null }
    }

    /**
     * dBFS required to produce the target dBHL at this frequency,
     * or null when the frequency is uncalibrated.
     */
    fun dbfsForTargetDbhl(model: String, freqHz: Int, targetDbhl: Float): Float? {
        val cal = getCalibration(model, freqHz) ?: return null
        val measured = cal.measuredDbSpl ?: return null
        val retspl = RETSPL[freqHz] ?: return null
        return cal.refDbfs + (targetDbhl + retspl) - measured
    }

    /** Same as [dbfsForTargetDbhl] but against a pre-loaded table (audio path safe). */
    fun dbfsForTargetDbhl(table: Map<Int, FreqCalibration>, freqHz: Int, targetDbhl: Float): Float? {
        val cal = table[freqHz] ?: return null
        val measured = cal.measuredDbSpl ?: return null
        val retspl = RETSPL[freqHz] ?: return null
        return cal.refDbfs + (targetDbhl + retspl) - measured
    }

    /**
     * Estimated output dBHL for a signal at [signalDbfs], or null when the
     * frequency is uncalibrated (no absolute baseline available).
     */
    fun estimateOutputDbhl(model: String, freqHz: Int, signalDbfs: Float): Float? {
        val cal = getCalibration(model, freqHz) ?: return null
        val measured = cal.measuredDbSpl ?: return null
        val retspl = RETSPL[freqHz] ?: return null
        val dbSpl = measured + (signalDbfs - cal.refDbfs)
        return dbSpl - retspl
    }

    /** 同上，但用預載的校正表（避免音訊互動路徑上重複讀 JSON）。 */
    fun estimateOutputDbhlFromTable(table: Map<Int, FreqCalibration>, freqHz: Int, signalDbfs: Float): Float? {
        val cal = table[freqHz] ?: return null
        val measured = cal.measuredDbSpl ?: return null
        val retspl = RETSPL[freqHz] ?: return null
        return measured + (signalDbfs - cal.refDbfs) - retspl
    }
}
