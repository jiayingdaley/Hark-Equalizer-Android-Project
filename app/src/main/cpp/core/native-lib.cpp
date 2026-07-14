#include <jni.h>
#include "HarkAudioEngine.h"
#include "FrequencyLowering.h"
#include "HearingLossSimulator.h"
#include "FilterChain.h"
#include "CrossoverBank8.h"
#include "PrescriptionFitting.h"
#include "HarkDspConfig.h"
#include <cmath>
#include <vector>
#include <algorithm>

// The single, static instance of our audio engine.
static HarkAudioEngine engine;

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_startEngine(JNIEnv *env, jobject /* this */) {
    engine.start();
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_stopEngine(JNIEnv *env, jobject /* this */) {
    engine.stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setBandGain(
        JNIEnv *env, jobject /* this */, jint ear, jint bandIndex, jfloat gainDb) {
    engine.setBandGain(ear, bandIndex, gainDb);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setBandQ(
        JNIEnv *env, jobject /* this */, jint bandIndex, jfloat q_factor) {
    engine.setBandQ(bandIndex, q_factor);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setAudioInputDeviceId(
        JNIEnv *env, jobject /* this */, jint device_id) {
    engine.setInputDeviceId(device_id);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_isEngineActuallyRunning(
        JNIEnv *env, jobject /* this */) {
    return (jboolean) engine.isEngineRunning();
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setNoiseReductionEnabled(
        JNIEnv *env, jobject /* this */, jboolean enabled) {
    engine.setNoiseReductionEnabled(enabled);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_resetGesture(
        JNIEnv *env, jobject /* this */) {
    engine.resetGesture();
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_logLatencyStatistics(
        JNIEnv *env, jobject /* this */) {
    engine.logLatencyStatistics();
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_calibrateNoiseSuppressor(
        JNIEnv *env, jobject /* this */) {
    engine.calibrateNoiseSuppressor();
}

/**
 * Situational mode:
 *   0 = TRANSPARENCY
 *   1 = CONVERSATION
 *   2 = OUTDOOR
 *   3 = CINEMA
 *   4 = AUTO
 */
extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setSituationalMode(
        JNIEnv *env, jobject /* this */, jint mode) {
    SituationalMode m;
    switch (mode) {
        case 1:  m = SituationalMode::CONVERSATION; break;
        case 2:  m = SituationalMode::OUTDOOR;      break;
        case 3:  m = SituationalMode::CINEMA;       break;
        case 4:  m = SituationalMode::AUTO;         break;
        default: m = SituationalMode::TRANSPARENCY; break;
    }
    engine.setSituationalMode(m);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setBandWdrcParameters(
        JNIEnv *env, jobject /* this */,
        jint band, jfloat thresholdDb, jfloat ratio,
        jfloat attackMs, jfloat releaseMs) {
    engine.setBandWdrcParameters(band, thresholdDb, ratio, attackMs, releaseMs);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_getEnvironmentEnergy(JNIEnv *env, jobject /* this */) {
    jfloatArray result = env->NewFloatArray(5);
    float energy[5];
    for (int i = 0; i < 5; ++i) {
        energy[i] = engine.getBandEnergy(i);
    }
    env->SetFloatArrayRegion(result, 0, 5, energy);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setBypassMode(
        JNIEnv *env, jobject /* this */, jboolean bypass) {
    engine.setBypassMode(bypass);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setMasterGain(
        JNIEnv *env, jobject /* this */, jfloat gain) {
    engine.setMasterGain(gain);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setMuted(
        JNIEnv *env, jobject /* this */, jboolean muted) {
    engine.setMuted(muted);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setInputGainOffset(
        JNIEnv *env, jobject /* this */, jfloat gainDb) {
    engine.setInputGainOffset(gainDb);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setUseHeadsetMic(
        JNIEnv *env, jobject /* this */, jboolean useHeadset) {
    engine.setUseHeadsetMic(useHeadset);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setOutputBufferBursts(
        JNIEnv *env, jobject /* this */, jint bursts) {
    engine.setOutputBufferBursts(bursts);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setIsBluetoothInput(
        JNIEnv *env, jobject /* this */, jboolean isBluetooth) {
    engine.setIsBluetoothInput(isBluetooth);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setHeadphonesConnected(
        JNIEnv *env, jobject /* this */, jboolean connected) {
    engine.setHeadphonesConnected(connected);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setMediaCaptureMode(
        JNIEnv *env, jobject /* this */, jboolean enabled) {
    engine.setMediaCaptureMode(enabled);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_pushMediaAudioData(
        JNIEnv *env, jobject /* this */, jfloatArray data, jint numFrames) {
    jfloat *c_data = env->GetFloatArrayElements(data, nullptr);
    if (c_data != nullptr) {
        engine.pushMediaAudioData(c_data, numFrames);
        env->ReleaseFloatArrayElements(data, c_data, JNI_ABORT);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setDcBlockerEnabled(
        JNIEnv *env, jobject /* this */, jboolean enabled) {
    engine.setDcBlockerEnabled(enabled);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setCrossoverWdrcEnabled(
        JNIEnv *env, jobject /* this */, jboolean enabled) {
    engine.setCrossoverWdrcEnabled(enabled);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setLimiterEnabled(
        JNIEnv *env, jobject /* this */, jboolean enabled) {
    engine.setLimiterEnabled(enabled);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setTransientSuppressorEnabled(
        JNIEnv *env, jobject /* this */, jboolean enabled) {
    engine.setTransientSuppressorEnabled(enabled);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setOwnVoiceDetectorEnabled(
        JNIEnv *env, jobject /* this */, jboolean enabled) {
    engine.setOwnVoiceDetectorEnabled(enabled);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setFrequencyLoweringEnabled(
        JNIEnv *env, jobject /* this */, jboolean enabled) {
    engine.setFrequencyLoweringEnabled(enabled);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setFrequencyLoweringParams(
        JNIEnv *env, jobject /* this */, jfloat cutoffHz, jfloat ratio) {
    engine.setFrequencyLoweringParams(cutoffHz, ratio);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_isFrequencyLoweringEnabled(
        JNIEnv *env, jobject /* this */) {
    return engine.isFrequencyLoweringEnabled();
}

/**
 * 離線 NLFC：對整段測試音（如語詞刺激）套用與即時引擎相同的移頻演算法，
 * 供語詞測驗評估移頻效益（4AFC 行為驗證）。獨立實例、不影響即時引擎狀態。
 * 演算法固有延遲（kFftSize 樣本）以尾端補零沖出後截齊，輸出與輸入等長對齊。
 */
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_nlfcProcessOffline(
        JNIEnv *env, jobject /* this */, jfloatArray input, jint sampleRate,
        jfloat cutoffHz, jfloat ratio) {
    jsize n = env->GetArrayLength(input);
    jfloat *in = env->GetFloatArrayElements(input, nullptr);

    FrequencyLowering fl;
    fl.setSampleRate(static_cast<double>(sampleRate));
    fl.setParameters(cutoffHz, ratio);
    fl.reset();

    const int latency = FrequencyLowering::kFftSize;
    std::vector<float> out(static_cast<size_t>(n));
    // 先送 latency 長度後才開始收有效輸出
    for (jsize i = 0; i < n; ++i) {
        float y = fl.process(in[i]);
        if (i >= latency) out[static_cast<size_t>(i - latency)] = y;
    }
    // 尾端補零，把最後 latency 個樣本沖出
    for (int i = 0; i < latency && i < n; ++i) {
        out[static_cast<size_t>(n - latency + i)] = fl.process(0.0f);
    }
    env->ReleaseFloatArrayElements(input, in, JNI_ABORT);

    jfloatArray result = env->NewFloatArray(n);
    env->SetFloatArrayRegion(result, 0, n, out.data());
    return result;
}

/**
 * 離線聽損模擬：對整段測試音套用感音神經性聽損模擬（頻譜模糊 + 多頻帶擴展器）。
 *
 * ★ 必須在 DSP 補償「之後」呼叫 ★ —— 真實情境是助聽器先處理、聲音才進入受損的
 * 耳蝸。順序反了，檢驗的就會是「訊號還原」而不是助聽器。
 *
 * @param thresholdsDbfs 該測試者本人於 8 個 DSP 頻帶的聽閾（dBFS），由自調式純音量得
 * @param lossDb         各頻帶的模擬損失量（dB）
 * @param uclDb          不舒適閾（dB SL），一般 100
 * @param broadenFactor  聽覺濾波器加寬倍數（1.0 = 不做頻譜模糊）
 */
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_hlSimProcessOffline(
        JNIEnv *env, jobject /* this */, jfloatArray input, jint sampleRate,
        jfloatArray thresholdsDbfs, jfloatArray lossDb,
        jfloat uclDb, jfloat broadenFactor) {

    jsize n = env->GetArrayLength(input);
    if (n <= 0) return env->NewFloatArray(0);

    jfloat *in = env->GetFloatArrayElements(input, nullptr);
    jfloat *thr = env->GetFloatArrayElements(thresholdsDbfs, nullptr);
    jfloat *loss = env->GetFloatArrayElements(lossDb, nullptr);

    HearingLossSimulator sim;
    sim.configure(static_cast<double>(sampleRate), thr, loss, uclDb, broadenFactor);
    sim.reset();

    // 頻譜模糊層有 STFT 固有延遲；先送 latency 個樣本再開始收，尾端補零沖出，
    // 使輸出與輸入等長且時間對齊（與 nlfcProcessOffline 相同作法）。
    const int latency = sim.latencySamples();
    std::vector<float> out(static_cast<size_t>(n), 0.0f);

    for (jsize i = 0; i < n; ++i) {
        float y = sim.process(in[i]);
        if (i >= latency) out[static_cast<size_t>(i - latency)] = y;
    }
    for (int i = 0; i < latency && i < n; ++i) {
        out[static_cast<size_t>(n - latency + i)] = sim.process(0.0f);
    }

    env->ReleaseFloatArrayElements(input, in, JNI_ABORT);
    env->ReleaseFloatArrayElements(thresholdsDbfs, thr, JNI_ABORT);
    env->ReleaseFloatArrayElements(lossDb, loss, JNI_ABORT);

    jfloatArray result = env->NewFloatArray(n);
    env->SetFloatArrayRegion(result, 0, n, out.data());
    return result;
}

/**
 * 離線 DSP 補償：對整段測試音套用處方等化，作法與即時引擎相同 ——
 * 16 段 UI 增益依 UI_TO_INTERNAL_MAP 平均進 8 個「不重疊」的分頻帶，
 * 以 CrossoverBank8 拆分、每帶施加線性增益、相位補償重建。
 *
 * ★ 不可用 16 個 peaking 濾波器串接 ★（實測踩到，訊號爆掉 +95 dB）
 * Peaking 濾波器互相重疊：Q=1.4、1/3 倍頻程間距下，任一頻率同時吃到
 * 4–6 個相鄰濾波器的裙擺，dB 增益疊加。DSL 處方 16 段各 +30 dB 時
 * 總增益疊到 +95 dB，輸出峰值 10⁹ 量級，MPO 壓到底、正規化保險絲再
 * 降 −70 dB —— 聽感是「一聲爆音之後幾乎無聲」。分頻帶做法各頻率只
 * 屬於一帶，增益永不疊加，且與測試者實際聽到的即時引擎行為一致。
 *
 * 語詞測驗原以 Android DynamicsProcessing 掛載於播放工作階段的方式施加補償；
 * 但系統音效掛在 session 之後就無從再插入任何處理級，因此無法在其後串接聽損
 * 模擬器。為滿足「模擬器必須在補償之後」的效度要求，補償改為離線原生處理。
 */
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_dspProcessOffline(
        JNIEnv *env, jobject /* this */, jfloatArray input, jint sampleRate,
        jfloatArray gainsDb16, jfloat qFactor) {

    jsize n = env->GetArrayLength(input);
    if (n <= 0) return env->NewFloatArray(0);

    jfloat *in = env->GetFloatArrayElements(input, nullptr);
    jsize nGains = env->GetArrayLength(gainsDb16);
    jfloat *gains = env->GetFloatArrayElements(gainsDb16, nullptr);

    (void) qFactor;   // 分頻帶做法不需要 Q（保留參數以維持 JNI 介面）
    const double sr = static_cast<double>(sampleRate);
    const double nyquist = sr * 0.5;

    // 16 段 UI 增益 → 8 個內部頻帶（每帶取所屬 UI 段之平均，與即時引擎一致）
    float gainSum[8] = {};
    int count[8] = {};
    for (int i = 0; i < 16; ++i) {
        const float g = (i < nGains) ? gains[i] : 0.0f;
        // 超出 Nyquist 的 UI 段不納入平均（16 kHz 素材時的 8000 Hz 段）
        if (HarkDspConfig::UI_CENTER_FREQS[i] >= nyquist * 0.95) continue;
        const int b = UI_TO_INTERNAL_MAP[i];
        gainSum[b] += g;
        count[b]++;
    }
    float linGain[8];
    for (int b = 0; b < 8; ++b) {
        const float avgDb = (count[b] > 0) ? gainSum[b] / count[b] : 0.0f;
        linGain[b] = std::pow(10.0f, avgDb / 20.0f);
    }

    CrossoverBank8 bank;
    bank.setSampleRate(sr);
    std::vector<float> out(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        float bands[8];
        bank.split(in[i], bands);
        for (int b = 0; b < 8; ++b) bands[b] *= linGain[b];
        out[static_cast<size_t>(i)] = bank.recombine(bands);
    }

    env->ReleaseFloatArrayElements(input, in, JNI_ABORT);
    env->ReleaseFloatArrayElements(gainsDb16, gains, JNI_ABORT);

    jfloatArray result = env->NewFloatArray(n);
    env->SetFloatArrayRegion(result, 0, n, out.data());
    return result;
}

/**
 * 純音的閉式聽損模擬增益：純音是單一頻率的穩態訊號，經擴展器的作用僅是位準映射，
 * 不需跑濾波器組與封包追蹤。供「模擬聽損—純音測試」直接施加於音源振幅。
 */
extern "C" JNIEXPORT jfloat JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_hlSimToneGainDb(
        JNIEnv *env, jobject /* this */, jfloat levelDbfs, jfloat thresholdDbfs,
        jfloat lossDb, jfloat uclDb) {
    return HearingLossSimulator::toneGainDb(levelDbfs, thresholdDbfs, lossDb, uclDb);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setWdrcExpanderThreshold(
        JNIEnv *env, jobject /* this */, jfloat thresholdDb) {
    engine.setWdrcExpanderThreshold(thresholdDb);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setLimiterParameters(
        JNIEnv *env, jobject /* this */, jfloat thresholdDb, jfloat releaseMs) {
    engine.setLimiterParameters(thresholdDb, 20.0f, 0.5f, releaseMs);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setStreamOverrides(
        JNIEnv *env, jobject /* this */, jint sharingMode, jint inputPreset) {
    engine.setStreamOverrides(sharingMode, inputPreset);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_getDiagnosticMetrics(
        JNIEnv *env, jobject /* this */) {
    // Array size is 6: [rawInputPeak, outputPeak, wouldBlockRate,
    //                   inputXRuns, outputXRuns, postInputGainPeak]
    jfloatArray result = env->NewFloatArray(6);
    if (result == nullptr) return nullptr;
    float metrics[6];
    engine.getDiagnosticMetrics(metrics);
    env->SetFloatArrayRegion(result, 0, 6, metrics);
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_isNoiseReductionEnabled(JNIEnv *env, jobject /* this */) {
    return (jboolean) engine.isNoiseReductionEnabled();
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_getMasterGain(JNIEnv *env, jobject /* this */) {
    return (jfloat) engine.getMasterGain();
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_getInputGainOffset(JNIEnv *env, jobject /* this */) {
    return (jfloat) engine.getInputGainOffset();
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_getWdrcExpanderThreshold(JNIEnv *env, jobject /* this */) {
    return (jfloat) engine.getWdrcExpanderThreshold();
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_getLimiterThreshold(JNIEnv *env, jobject /* this */) {
    return (jfloat) engine.getLimiterThreshold();
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_getLimiterRelease(JNIEnv *env, jobject /* this */) {
    return (jfloat) engine.getLimiterRelease();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_isDcBlockerEnabled(JNIEnv *env, jobject /* this */) {
    return (jboolean) engine.isDcBlockerEnabled();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_isCrossoverWdrcEnabled(JNIEnv *env, jobject /* this */) {
    return (jboolean) engine.isCrossoverWdrcEnabled();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_isLimiterEnabled(JNIEnv *env, jobject /* this */) {
    return (jboolean) engine.isLimiterEnabled();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_isTransientSuppressorEnabled(JNIEnv *env, jobject /* this */) {
    return (jboolean) engine.isTransientSuppressorEnabled();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_isOwnVoiceDetectorEnabled(JNIEnv *env, jobject /* this */) {
    return (jboolean) engine.isOwnVoiceDetectorEnabled();
}

// =============================================================================
// Experiment Signal Generator JNI Bindings
// These functions control the three signal generator modes added to
// HarkAudioEngine for academic measurement purposes.
// =============================================================================

/**
 * setCalibTone — Start or stop a fixed-frequency calibration sine tone.
 * freqHz:    Target frequency in Hz (250/500/1000/2000/3000/4000/6000/8000).
 * levelDbfs: Output amplitude in dBFS (range: -40 to 0).
 * enabled:   true = generate, false = stop.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setCalibTone(
        JNIEnv *env, jobject /* this */,
        jfloat freqHz, jfloat levelDbfs, jboolean enabled) {
    engine.setCalibTone(freqHz, levelDbfs, (bool)enabled);
}

/**
 * setLogChirp — Start or stop a log-swept sine chirp (ANSI S3.22 OSPL90).
 * startHz:     Start frequency (Hz), typically 250.
 * endHz:       End frequency (Hz), typically 8000.
 * durationSec: Total sweep duration in seconds (10–60).
 * levelDbfs:   Output amplitude in dBFS.
 * enabled:     true = start sweep from beginning, false = stop.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setLogChirp(
        JNIEnv *env, jobject /* this */,
        jfloat startHz, jfloat endHz, jfloat durationSec,
        jfloat levelDbfs, jboolean enabled) {
    engine.setLogChirp(startHz, endHz, durationSec, levelDbfs, (bool)enabled);
}

/**
 * setPinkNoise — Start or stop pink noise output.
 * levelDbfs: Output amplitude in dBFS.
 * enabled:   true = generate, false = stop.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setPinkNoise(
        JNIEnv *env, jobject /* this */,
        jfloat levelDbfs, jboolean enabled) {
    engine.setPinkNoise(levelDbfs, (bool)enabled);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setExperimentModeActive(
        JNIEnv *env, jobject /* this */, jboolean active) {
    engine.setExperimentModeActive((bool)active);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wcy_hark_audio_bridge_HarkAudioBridge_setInjectDspMode(
        JNIEnv *env, jobject /* this */, jboolean inject) {
    engine.setInjectDspMode((bool)inject);
}