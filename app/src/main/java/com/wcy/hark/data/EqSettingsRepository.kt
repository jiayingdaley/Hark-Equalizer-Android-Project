package com.wcy.hark.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "eq_settings")

class EqSettingsRepository(private val context: Context) {

    // We store gains as preferences. For 16 bands, we store 16 floats.
    // For 8 bands, we store 8 floats.
    private fun getBandGainKey(ear: String, mode: Int, index: Int) = floatPreferencesKey("band_gain_${ear}_${mode}_${index}")
    private fun getBandQKey(mode: Int, index: Int) = floatPreferencesKey("band_q_${mode}_${index}")

    fun getBandGainsFlow(ear: String, mode: Int, numBands: Int): Flow<List<Float>> = context.dataStore.data.map { preferences ->
        val gains = mutableListOf<Float>()
        for (i in 0 until numBands) {
            gains.add(preferences[getBandGainKey(ear, mode, i)] ?: 0f)
        }
        gains
    }
    
    fun getBandQsFlow(mode: Int, numBands: Int): Flow<List<Float>> = context.dataStore.data.map { preferences ->
        val qs = mutableListOf<Float>()
        for (i in 0 until numBands) {
            qs.add(preferences[getBandQKey(mode, i)] ?: 1.8f) // DEFAULT_Q is 1.8f
        }
        qs
    }

    suspend fun saveBandGain(ear: String, mode: Int, index: Int, gain: Float) {
        context.dataStore.edit { preferences ->
            preferences[getBandGainKey(ear, mode, index)] = gain
        }
    }

    suspend fun saveBandQ(mode: Int, index: Int, q: Float) {
        context.dataStore.edit { preferences ->
            preferences[getBandQKey(mode, index)] = q
        }
    }
    
    suspend fun resetBands(mode: Int, numBands: Int) {
        context.dataStore.edit { preferences ->
            for (i in 0 until numBands) {
                preferences[getBandGainKey("left", mode, i)] = 0f
                preferences[getBandGainKey("right", mode, i)] = 0f
                preferences[getBandQKey(mode, i)] = 1.8f
            }
        }
    }
}
