package com.wcy.hark.data.experiment

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * EarphoneCalibrationRepository
 *
 * Manages ETSPL (Equivalent Threshold Sound Pressure Level) correction values
 * for each earphone model. The calibration table is stored as a JSON file in
 * the app's internal files directory, initially seeded from assets/.
 *
 * Correction values are set by the researcher using an external sound level
 * meter: correction[Hz] = measured_SPL - reference_SPL_at_that_dBFS.
 *
 * Reference: ISO 8253-1, ANSI S3.22 (audiometric earphone calibration)
 */
class EarphoneCalibrationRepository(private val context: Context) {

    companion object {
        private const val ASSET_FILE = "earphone_calibration.json"
        private const val LOCAL_FILE = "earphone_calibration.json"
        private const val COMMENT_KEY = "_comment"

        // Audiometric test frequencies (Hz)
        val TEST_FREQUENCIES = listOf(250, 500, 1000, 2000, 3000, 4000, 6000, 8000)
    }

    // Lazy-loaded local file in internal storage
    private val localFile: File get() = File(context.filesDir, LOCAL_FILE)

    /**
     * Returns a mutable JSONObject loaded from the local file.
     * On first call (local file not yet created), seeds from assets/.
     */
    private fun loadJson(): JSONObject {
        if (!localFile.exists()) {
            // First launch: copy asset to internal storage so it becomes writable
            context.assets.open(ASSET_FILE).use { input ->
                localFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return JSONObject(localFile.readText())
    }

    private fun saveJson(json: JSONObject) {
        localFile.writeText(json.toString(2))
    }

    /**
     * Returns the list of available earphone model names.
     */
    fun getEarphoneModels(): List<String> {
        val json = loadJson()
        return json.keys().asSequence()
            .filter { it != COMMENT_KEY }
            .toList()
    }

    /**
     * Returns the ETSPL correction value for the given earphone model and frequency.
     * Returns 0.0 if the model or frequency is not found.
     *
     * @param model   Earphone model name (must match a key in the JSON)
     * @param freqHz  Frequency in Hz (250/500/1000/2000/3000/4000/6000/8000)
     */
    fun getCorrection(model: String, freqHz: Int): Float {
        return try {
            val json   = loadJson()
            val entry  = json.optJSONObject(model) ?: return 0.0f
            entry.optDouble(freqHz.toString(), 0.0).toFloat()
        } catch (e: Exception) {
            0.0f
        }
    }

    /**
     * Returns all corrections for the given earphone model as a Map<Int, Float>.
     * Keys are frequencies in Hz, values are dB correction values.
     */
    fun getAllCorrections(model: String): Map<Int, Float> {
        return try {
            val json  = loadJson()
            val entry = json.optJSONObject(model) ?: return emptyMap()
            TEST_FREQUENCIES.associateWith { freq ->
                entry.optDouble(freq.toString(), 0.0).toFloat()
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * Updates and persists the ETSPL correction value for a specific earphone model
     * and frequency. Typically called after the researcher measures the actual SPL
     * output with a HATS or coupler microphone.
     *
     * @param model       Earphone model name
     * @param freqHz      Frequency in Hz
     * @param correction  Measured correction in dB
     */
    fun saveCorrection(model: String, freqHz: Int, correction: Float) {
        try {
            val json  = loadJson()
            val entry = json.optJSONObject(model) ?: JSONObject().also { json.put(model, it) }
            entry.put(freqHz.toString(), correction.toDouble())
            saveJson(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Calculates the estimated output level in dBHL for a given earphone model,
     * frequency, and dBFS signal level.
     *
     * Estimated dBHL = signalDbfs + ETSPL_correction + dBFS_to_dBSPL_offset
     *
     * The dBFS_to_dBSPL_offset is device- and earphone-specific. The researcher
     * should establish this baseline once with a reference tone and a sound level
     * meter, then hard-code or adjust it here.
     *
     * For now, returns signalDbfs + correction as a relative estimate.
     * The researcher should interpret this as "relative dBHL shift from baseline".
     *
     * @param model       Earphone model name
     * @param freqHz      Frequency in Hz
     * @param signalDbfs  Signal level in dBFS (typically -40 to 0)
     */
    fun estimateOutputDbhl(model: String, freqHz: Int, signalDbfs: Float): Float {
        val correction = getCorrection(model, freqHz)
        return signalDbfs + correction
    }
}
