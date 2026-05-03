#include <iostream>
#include <cmath>
#include "../DynamicsProcessor.h"

// Simple native test harness for DynamicsProcessor
// Generates sine signals at different dBFS levels, processes them, and reports
// RMS levels to observe gain reduction behavior.

static double dbToLinear(double db) {
    return pow(10.0, db / 20.0);
}

static double computeRms(const float *buf, int n) {
    double sum = 0.0;
    for (int i = 0; i < n; ++i) sum += buf[i] * buf[i];
    return sqrt(sum / n);
}

int main() {
    const double sampleRate = 48000.0;
    const int durationSec = 1;
    const int N = static_cast<int>(sampleRate * durationSec);
    const double freq = 1000.0; // 1kHz test tone

    DynamicsProcessor dp;
    // Use same defaults as HarkAudioEngine.updateDSPParameters
    dp.setParameters(-40.0f, 2.0f, -70.0f, 0.9f, 10.0f, 80.0f, sampleRate);

    const double testLevelsDb[] = {-80.0, -50.0, -30.0, -10.0};
    const int numLevels = sizeof(testLevelsDb) / sizeof(testLevelsDb[0]);

    for (int lvl = 0; lvl < numLevels; ++lvl) {
        double a = dbToLinear(testLevelsDb[lvl]);
        std::vector<float> in(N), out(N);
        for (int i = 0; i < N; ++i) {
            double t = static_cast<double>(i) / sampleRate;
            in[i] = static_cast<float>(a * sin(2.0 * M_PI * freq * t));
        }

        for (int i = 0; i < N; ++i) {
            out[i] = dp.process(in[i]);
        }

        double rmsIn = computeRms(in.data(), N);
        double rmsOut = computeRms(out.data(), N);
        double dbIn = 20.0 * log10(rmsIn + 1e-12);
        double dbOut = 20.0 * log10(rmsOut + 1e-12);
        double gainReduction = dbIn - dbOut;

        std::cout << "Input target: " << testLevelsDb[lvl] << " dBFS, "
                  << "Measured in RMS: " << dbIn << " dB, "
                  << "Measured out RMS: " << dbOut << " dB, "
                  << "Gain reduction: " << gainReduction << " dB\n";
    }

    std::cout << "Test complete." << std::endl;
    return 0;
}
