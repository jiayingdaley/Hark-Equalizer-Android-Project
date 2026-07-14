package com.wcy.hark.audiometry

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.io.DataInputStream
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * SsnAudioMixer — sample-accurate speech + speech-shaped-noise mixing.
 *
 * Decodes the word WAV and the pre-generated SSN (res/raw/ssn_noise.wav,
 * offline-shaped to the corpus long-term average spectrum).
 *
 * ★ 噪音模式採「總位準恆定」：語音＋噪音混音後的整體 RMS 固定於
 * totalLevelDbfs（由呼叫端以 dB SL 錨定），SNR 只決定總能量在語音與噪音
 * 之間怎麼分配 ★
 *
 *     噪音功率 = 總功率 / (1 + 10^(SNR/10))，語音功率 = 總功率 − 噪音功率
 *
 * 為什麼是總位準恆定而非噪音恆定：測試者聽到的每一題響度一致（聽感合理、
 * 不會覺得音量在跳）；聽損模擬器是位準相依的，總位準恆定讓模擬器的工作點
 * 全場固定，各 SNR 條件的差異純粹是語音與噪音的比例。絕對位準永遠不超過
 * totalLevelDbfs——不會有任何條件把音量往上推。
 */
class SsnAudioMixer(private val context: Context) {

    companion object {
        private const val TAG = "SsnAudioMixer"
        const val SAMPLE_RATE = 16000
            // 前後靜音墊。前墊吸收 AudioTrack 啟動/路由延遲，避免字頭被吃；
        // 太長會讓「播放中」顯示著卻沒聲音，測試者以為當機。300 ms 是折衷。
        private const val PAD_MS = 300

        /**
         * 固定呈現餘裕（presentation headroom），對「所有」trial 一律套用，
         * 因此各 trial 的絕對呈現級別彼此一致、SNR 不受影響，且大幅降低
         * 混音削波（進而觸發逐題正規化）的機率。
         */
        const val PRESENTATION_GAIN_DB = -6.0f
    }

    /**
     * @param durationMs 播放總長（毫秒）；-1 表示失敗
     * @param normGainDb 削波防護的額外正規化增益（dB，≤0）；0 表示未觸發。
     *                   此值必須寫入測試紀錄——它代表該 trial 的絕對呈現級別
     *                   相對其他 trial 額外降低了多少。
     */
    data class MixResult(val durationMs: Long, val normGainDb: Float)

    private var noisePcm: ShortArray? = null
    private var audioTrack: AudioTrack? = null

    // ── 離線處理鏈組態 ────────────────────────────────────────────────────
    // 原本 DSP 補償是把 Android DynamicsProcessing 掛在 AudioTrack 的 session 上，
    // 但系統音效掛上去之後就無從再插入任何處理級——聽損模擬器接不到它後面。
    // 而模擬器「必須」在補償之後（真實情境是助聽器先處理、聲音才進受損的耳蝸），
    // 所以補償與模擬一律改走離線原生處理，整條鏈變成：
    //
    //     語詞 → [離線 NLFC] → 混音(+SSN) → [離線 DSP 補償] → [離線 聽損模擬] → 播放
    //
    // 全離線的好處：樣本精確、完全可重現、刺激檔可存檔供事後檢核。

    /** DSP 補償的 16 頻段增益（dB）；null = 不套用補償（未輔助條件）。 */
    var dspGainsDb: FloatArray? = null

    /** 聽損模擬組態；none() = 不模擬。 */
    var hearingLossSim: HearingLossSim = HearingLossSim.none()

    /** 16 頻段等化器的 Q 值（與即時引擎預設一致）。 */
    var dspQFactor: Float = 1.4f

    /**
     * MPO（最大輸出限制，dBFS peak）——助聽器的一部分，作用於 DSP 補償之後、
     * 聽損模擬之前；未輔助條件不經過 DSP，也就沒有 MPO（正確：沒戴助聽器就
     * 沒有輸出限制器）。
     *
     * 為什麼需要：離線補償鏈是 16 頻帶「線性」增益（DSL v5 處方上限 +30 dB），
     * 沒有 WDRC 也沒有輸出限制。實測聽損體驗頁的「聽損＋Hark補償」把整體
     * RMS 推高 15–25 dB，從約 45 dB SPL 突跳到 70+ dB SPL，音量嚇到測試者。
     * 真實助聽器一定有 MPO，這裡補上等效行為（超過即整段等比縮小，無失真；
     * 縮減量記錄於 MixResult.normGainDb，資料可溯源）。
     *
     * −25 dBFS peak ≈ 78 dB SPL（0 dBFS ≈ 103 dB SPL 之耳道式耳機估計）。
     */
    var mpoCeilDbfs: Float = -25f

    /**
     * 對混音後的整段訊號依序施加「DSP 補償 → 聽損模擬」。
     *
     * ★ 順序不可對調 ★ 補償在前、模擬在後，才對應真實的
     * 「助聽器處理 → 受損耳蝸」路徑。反過來就變成訊號還原問題，實驗失去效度。
     *
     * @param mixF short 刻度的浮點混音（±32768）
     */
    private fun applyChain(mixF: FloatArray): FloatArray {
        var x = mixF

        // ① DSP 補償（輔助條件才有）
        val gains = dspGainsDb
        if (gains != null) {
            x = try {
                com.wcy.hark.audio.bridge.HarkAudioBridge.dspProcessOffline(
                    x, SAMPLE_RATE, gains, dspQFactor
                )
            } catch (e: Throwable) {
                Log.e(TAG, "offline DSP failed — 本 trial 未套用補償: ${e.message}", e)
                x
            }

            // ①b MPO：助聽器的輸出限制（見 mpoCeilDbfs 註解）。
            // 位置必須在模擬之前——它是助聽器的一部分，不是耳朵的。
            x = applyLimiter(x)
        }

        // ② 聽損模擬（模擬受損的耳蝸）
        x = hearingLossSim.processOffline(x, SAMPLE_RATE)

        return x
    }

    /**
     * MPO 限幅器：只壓「超出上限的瞬間峰值」，不動平均位準。
     *
     * ★ 為什麼不能用「整段等比例縮小」★（實測踩到，資料會反向）
     * 舊版量出整段峰值後把「整段」乘上 ceil/peak。語音與噪音一起降，SNR 看似不變，
     * 但聽損模擬器是位準相依的：擴展比 k = UCL/(UCL−HL)，中重度損失 k ≈ 3。整段
     * 降 15 dB，經過模擬耳蝸後聽起來是降 45 dB —— 輔助條件反而比未輔助更聽不見，
     * 測試者必須把位準「調高」才聽得到。這會讓 A/B 做出方向完全相反的結論。
     *
     * 真實助聽器的 MPO 是快速限幅器：只在瞬間峰值超標時降增益，平均位準不受影響。
     * 此處以前瞻峰值偵測 + 快攻慢放實作（attack 1 ms / release 50 ms，前瞻 2 ms），
     * 避免削掉起音又不產生抽吸感。
     */
    private fun applyLimiter(x: FloatArray): FloatArray {
        val ceil = 32768f * 10.0.pow(mpoCeilDbfs / 20.0).toFloat()
        val look = (SAMPLE_RATE * 0.002).toInt().coerceAtLeast(1)      // 2 ms 前瞻
        val atkC = kotlin.math.exp(-1.0 / (SAMPLE_RATE * 0.001)).toFloat()   // 1 ms
        val relC = kotlin.math.exp(-1.0 / (SAMPLE_RATE * 0.050)).toFloat()   // 50 ms

        // 前瞻峰值包絡：取未來 look 個樣本內的最大絕對值
        val peakEnv = FloatArray(x.size)
        for (i in x.indices) {
            var m = 0f
            val end = minOf(i + look, x.size - 1)
            for (j in i..end) { val a = kotlin.math.abs(x[j]); if (a > m) m = a }
            peakEnv[i] = m
        }

        val out = FloatArray(x.size)
        var gain = 1f
        var maxRedDb = 0f
        for (i in x.indices) {
            val target = if (peakEnv[i] > ceil) ceil / peakEnv[i] else 1f
            val c = if (target < gain) atkC else relC     // 降增益要快、回復要慢
            gain = target + c * (gain - target)
            out[i] = x[i] * gain
            val redDb = 20.0 * log10(gain.toDouble().coerceAtLeast(1e-6))
            if (redDb < maxRedDb) maxRedDb = redDb.toFloat()
        }
        if (maxRedDb < -0.1f) {
            Log.i(TAG, "MPO 限幅：瞬時最大降 %.1f dB（平均位準不受影響）".format(maxRedDb))
        }
        return out
    }

    /** Parses a mono 16-bit PCM WAV from res/raw. */
    private fun readWavResource(resId: Int): ShortArray {
        context.resources.openRawResource(resId).use { ins ->
            val dis = DataInputStream(ins)
            val header = ByteArray(12)
            dis.readFully(header) // RIFF....WAVE
            // Walk chunks until "data"
            while (true) {
                val chunkId = ByteArray(4); dis.readFully(chunkId)
                val sizeBytes = ByteArray(4); dis.readFully(sizeBytes)
                val size = (sizeBytes[0].toInt() and 0xFF) or
                           ((sizeBytes[1].toInt() and 0xFF) shl 8) or
                           ((sizeBytes[2].toInt() and 0xFF) shl 16) or
                           ((sizeBytes[3].toInt() and 0xFF) shl 24)
                if (String(chunkId) == "data") {
                    val data = ByteArray(size)
                    dis.readFully(data)
                    val out = ShortArray(size / 2)
                    for (i in out.indices) {
                        out[i] = (((data[i * 2 + 1].toInt() shl 8) or
                                   (data[i * 2].toInt() and 0xFF))).toShort()
                    }
                    return out
                } else {
                    dis.skipBytes(size)
                }
            }
        }
    }

    private fun ensureNoiseLoaded(noiseResId: Int): ShortArray {
        return noisePcm ?: readWavResource(noiseResId).also { noisePcm = it }
    }

    private fun rms(x: ShortArray, from: Int = 0, len: Int = x.size): Double {
        var sum = 0.0
        for (i in from until from + len) sum += x[i].toDouble() * x[i]
        return sqrt(sum / len) / 32768.0
    }

    private fun rmsF(x: FloatArray): Double {
        var sum = 0.0
        for (v in x) sum += v.toDouble() * v
        return sqrt(sum / x.size) / 32768.0
    }

    /**
     * 語詞 PCM → short 刻度浮點；nlfc = true 時先經離線 NLFC（與即時引擎
     * 相同演算法：cutoff 4.5 kHz、2:1），供語詞測驗做移頻效益的行為驗證。
     * 增益計算（RMS）在處理「之後」進行，SNR/呈現位準不受移頻影響。
     */
    private fun speechFloats(pcm: ShortArray, nlfc: Boolean): FloatArray {
        val f = FloatArray(pcm.size) { pcm[it].toFloat() }
        if (!nlfc) return f
        return try {
            com.wcy.hark.audio.bridge.HarkAudioBridge.nlfcProcessOffline(
                f, SAMPLE_RATE, 4500f, 2.0f
            )
        } catch (e: Throwable) {
            Log.e(TAG, "offline NLFC failed, using unprocessed speech: ${e.message}")
            f
        }
    }

    /**
     * Mixes and plays one word at the given SNR (dB).
     */
    fun playWordInNoise(
        wordResId: Int, noiseResId: Int, snrDb: Float,
        totalLevelDbfs: Float, nlfc: Boolean = false
    ): MixResult {
        return try {
            val speech = speechFloats(readWavResource(wordResId), nlfc)
            val noise = ensureNoiseLoaded(noiseResId)

            val padSamples = SAMPLE_RATE * PAD_MS / 1000
            val totalLen = speech.size + 2 * padSamples

            // Random noise segment
            val start = Random.nextInt(0, (noise.size - totalLen).coerceAtLeast(1))

            val speechRms = rmsF(speech).coerceAtLeast(1e-6)
            val noiseRms = rms(noise, start, totalLen).coerceAtLeast(1e-6)
            // 總位準恆定：混音（語音重疊段）的總功率固定，SNR 決定分配。
            //   noisePow = totalPow / (1 + 10^(snr/10))；speechPow = totalPow − noisePow
            val totalPow = 10.0.pow(totalLevelDbfs / 10.0)
            val ratio = 10.0.pow(snrDb / 10.0)                    // speechPow / noisePow
            val noiseTargetRms = kotlin.math.sqrt(totalPow / (1.0 + ratio))
            val speechTargetRms = kotlin.math.sqrt(totalPow * ratio / (1.0 + ratio))
            val noiseGain = (noiseTargetRms / noiseRms).toFloat()
            val speechGain = (speechTargetRms / speechRms).toFloat()

            // 固定呈現餘裕（全 trial 一致，不影響 SNR 也不影響 trial 間可比性）
            val presentGain = 10.0.pow(PRESENTATION_GAIN_DB / 20.0).toFloat()

            // 先以浮點混音，再依序過「DSP 補償 → 聽損模擬」，最後一次性量化，
            // 避免中途削波造成失真
            val raw = FloatArray(totalLen)
            for (i in 0 until totalLen) {
                val s = if (i in padSamples until padSamples + speech.size)
                    speech[i - padSamples] else 0f
                raw[i] = (s * speechGain + noise[start + i] * noiseGain) * presentGain
            }
            val mixF = applyChain(raw)

            var peak = 0f
            for (v in mixF) {
                val a = if (v >= 0f) v else -v
                if (a > peak) peak = a
            }
            // 削波防護：超出 16-bit 範圍時整段等比例縮小，並回報衰減量
            var normGainDb = 0f
            if (peak > 32767f) {
                val g = 32000f / peak
                for (i in mixF.indices) mixF[i] *= g
                normGainDb = (20.0 * log10(g.toDouble())).toFloat()
                Log.w(TAG, "Mix clipped (peak=$peak); normalized by $normGainDb dB — recorded in trial data")
            }
            val mix = ShortArray(totalLen) { mixF[it].toInt().coerceIn(-32768, 32767).toShort() }

            playPcm(mix)
            MixResult(totalLen * 1000L / SAMPLE_RATE, normGainDb)
        } catch (e: Exception) {
            Log.e(TAG, "playWordInNoise failed: ${e.message}", e)
            MixResult(-1L, 0f)
        }
    }

    /**
     * 無噪音的「小聲語詞」測驗：將語詞呈現於指定的絕對數位位準 levelDbfs（dBFS，
     * 相對滿刻度）。levelDbfs 由施測端以「該受試者純音閾值 + 感覺級（dB SL）」換算
     * 而得，故位準綁定個人聽力（見 SSNTestActivity）。無噪音、無 SNR 混音。
     */
    fun playWordQuiet(wordResId: Int, levelDbfs: Float, nlfc: Boolean = false): MixResult {
        return try {
            val speech = speechFloats(readWavResource(wordResId), nlfc)
            val padSamples = SAMPLE_RATE * PAD_MS / 1000
            val totalLen = speech.size + 2 * padSamples

            val speechRms = rmsF(speech).coerceAtLeast(1e-6)         // 正規化 (0..1)
            val targetRms = 10.0.pow(levelDbfs / 20.0)               // 目標正規化 RMS
            val gain = (targetRms / speechRms).toFloat()

            val raw = FloatArray(totalLen)
            for (i in 0 until totalLen) {
                raw[i] = if (i in padSamples until padSamples + speech.size)
                    speech[i - padSamples] * gain else 0f
            }
            // 呈現位準已定 → 依序過「DSP 補償 → 聽損模擬」
            val mixF = applyChain(raw)

            var peak = 0f
            for (v in mixF) {
                val a = if (v >= 0f) v else -v
                if (a > peak) peak = a
            }
            var normGainDb = 0f
            if (peak > 32767f) {
                val g = 32000f / peak
                for (i in mixF.indices) mixF[i] *= g
                normGainDb = (20.0 * log10(g.toDouble())).toFloat()
            }
            val mix = ShortArray(totalLen) { mixF[it].toInt().coerceIn(-32768, 32767).toShort() }
            playPcm(mix)
            MixResult(totalLen * 1000L / SAMPLE_RATE, normGainDb)
        } catch (e: Exception) {
            Log.e(TAG, "playWordQuiet failed: ${e.message}", e)
            MixResult(-1L, 0f)
        }
    }

    private fun playPcm(mix: ShortArray) {
        stop()
        audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            mix.size * 2,
            AudioTrack.MODE_STATIC,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        audioTrack?.write(mix, 0, mix.size)
        audioTrack?.play()
    }

    val audioSessionId: Int get() = audioTrack?.audioSessionId ?: 0

    fun stop() {
        try {
            audioTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.stop()
                it.release()
            }
        } catch (e: Exception) { /* best effort */ }
        audioTrack = null
    }

    fun release() {
        stop()
        noisePcm = null
    }
}
