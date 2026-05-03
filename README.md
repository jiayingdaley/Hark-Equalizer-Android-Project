# Hark: An Android Low-Latency Hearing Aid and Audio Enhancement Platform

## Why is it called Hark?
The name Hark draws from an old English word meaning “listen!” — not just to hear sounds, but to awaken the sense of hearing as an intentional, essential act. It appears in Shakespeare’s plays and ancient hymns as a call to pay attention to something important.

In the same spirit, Hark is an app that helps users truly listen again — whether to a conversation, a melody, or the world around them. Much like the technique of [*sonification*](https://science.nasa.gov/mission/hubble/multimedia/sonifications/), which turns cosmic data into sound, Hark transforms digital signals into accessible hearing. It’s not just a hearing aid; it’s a hearing awakening.

## 1. Project Overview

Hark is an advanced Android application designed to function as a versatile, low-latency hearing-assistance and audio enhancement platform. Going beyond a simple equalizer, Hark transforms any standard wired (3.5mm/USB-C) or Bluetooth earphones into a powerful, real-time audio processing device.

The project's core mission is to provide a real-world solution for everyday listening challenges, from enhancing live conversations in noisy environments to providing a personalized media consumption experience. Hark is built upon a high-performance, modular C++ audio engine, making it a robust platform for both practical daily use and academic research into real-time digital signal processing (DSP) on mobile devices.

## 2. Core Features

- **Ultra-Low Latency Audio Engine**: Utilizes the **Google Oboe** library within a C++/NDK-based audio pipeline to achieve minimal round-trip latency, ensuring a natural and artifact-free listening experience.
- **Advanced Audio Processing Architecture**: Implements a professional-grade signal chain inspired by modern digital hearing aids:
    - **Pre-Gain Stage**: Provides initial amplification for a "super hearing" effect, boosting faint or distant sounds.
    - **Parallel Filter Bank**: A sophisticated parallel structure of IIR filters preserves full-spectrum audio detail, unlike traditional cascaded EQs. This includes:
        - **Low-Shelf Filter**: Manages low-frequency rumble and noise.
        - **16-Band Peaking EQ**: Allows for precise, user-configurable frequency shaping.
        - **High-Shelf Filter**: Retains high-frequency "air" and transient crispness.
    - **Dynamics Processing**: A two-stage system to manage audio dynamics for clarity and safety:
        - **Wide Dynamic Range Compression (WDRC)**: Compresses the dynamic range to make soft sounds audible without letting loud sounds become uncomfortable.
        - **Brick-wall Limiter**: Acts as a final safety measure to prevent any sudden, loud noises from causing digital clipping or harming the user's hearing.
    - **Post-Gain (Makeup Gain)**: Compensates for volume changes during dynamics processing to deliver a loud, clear final output.
- **Intelligent Device Management**: A robust state machine in Kotlin manages audio device connections, automatically handling hot-swapping between the built-in microphone, wired headsets (3.5mm & USB-C), and Bluetooth (SCO) devices.
- **Real-time Latency Monitoring**: The engine includes a mechanism to calculate and display the true round-trip audio latency in milliseconds, providing critical performance data for analysis and optimization.
- **Interactive Equalizer UI**: A custom-drawn, scrollable equalizer interface built with Jetpack Compose allows users to intuitively manipulate the gain of each frequency band.
- **Persistent Equalizer Profiles**: Employs **Jetpack DataStore (Preferences)** to securely and efficiently save user-defined audio profiles (8-band or 16-band gains and Q-factors), ensuring custom settings persist across app restarts without delay.
- **Enterprise-Grade Stability & Observability**: Integrated with **Firebase Crashlytics** to monitor native NDK/C++ and JVM crashes, and **Firebase Analytics** to optionally study user engagement with different hearing aid profiles via quantitative data.
- **Structured Logging**: Uses **Timber** to replace standard `Log.d`, allowing dynamic rerouting of logs to cloud crash reports in production release builds.
- **Safe & Reliable Operation**: The app includes protection against acoustic feedback by disabling the engine when outputting to the device's main speaker.

## 3. Technical Architecture

Hark's architecture is centered around a unified, modular audio processing pipeline implemented in C++ for maximum performance and control.

**Signal Chain:**
`Input -> [Pre-Gain] -> [Parallel Filter Bank] -> [Summing] -> [WDRC] -> [Limiter] -> [Post-Gain] -> Output`

- **Pre-Gain Stage**: A linear amplifier that boosts the raw input signal level, enhancing sensitivity to quiet sounds before any frequency shaping occurs.

- **Parallel Filter Bank**: The core of Hark's sound shaping capabilities. The input signal is simultaneously fed into all filters in the bank, and their outputs are summed together. This preserves the full frequency spectrum, preventing the signal roll-off that occurs with cascaded EQs. The bank consists of:
    - **1 Low-Shelf Filter**: Controls the gain of frequencies below 250 Hz to manage ambient noise.
    - **16 Peaking IIR Filters**: User-configurable bands for detailed equalization based on audiogram data or user preference.
    - **1 High-Shelf Filter**: Controls the gain of frequencies above 8 kHz to maintain high-frequency detail and naturalness.

- **Dynamics Processing Stage**:
    - **WDRC**: A custom `DynamicsProcessor` class applies gentle compression with a moderate threshold and ratio, making the overall sound more consistent and intelligible.
    - **Limiter**: The same `DynamicsProcessor` class is used with "brick-wall" settings (high threshold, high ratio, fast attack) to catch and suppress any sudden peaks, preventing distortion and ensuring a safe listening level.

- **Post-Gain (Makeup Gain) Stage**: A final linear amplification stage to compensate for any gain reduction from the dynamics processing, ensuring the final output is powerful and clear.

## 4. Center Frequencies

The custom engine supports two modes with audiologically-standard center frequencies for the 16 user-configurable Peaking EQ bands.

#### 16-Band Mode
- 250 Hz, 315 Hz, 400 Hz, 500 Hz, 630 Hz, 800 Hz, 1000 Hz, 1250 Hz, 1600 Hz, 2000 Hz, 2500 Hz, 3150 Hz, 4000 Hz, 5000 Hz, 6300 Hz, 8000 Hz

#### 8-Band Mode
- 250 Hz, 500 Hz, 1000 Hz, 1500 Hz, 2000 Hz, 4000 Hz, 6000 Hz, 8000 Hz

## 5. Tech Stack

- **Language**: Kotlin (for UI, App Logic, State Management) & C++ (for Real-time Audio Engine via NDK)
- **Core Audio API**: **Oboe** for low-latency audio I/O.
- **UI**: Jetpack Compose for a modern, declarative, and interactive user interface.
- **State & Persistence**: MVVM architecture utilizing Kotlin `StateFlow`/`mutableStateOf` alongside **Jetpack DataStore** for persistent, asynchronous storage of user audiogram settings.
- **Concurrency**: Kotlin Coroutines with `Mutex` for safe, asynchronous management of device state changes.
- **Observability**: **Firebase Analytics & Crashlytics** for remote monitoring, aligned with **Timber** for robust local/remote logging.

## 6. How to Build and Run

1.  Ensure you have the latest Android Studio, along with the Android **NDK** and **CMake** installed via the SDK Manager.
2.  Clone this repository.
3.  **Important configuration**: To use Firebase features (Crashlytics/Analytics required for production), add your specific `google-services.json` file downloaded from your Firebase console to the `/app` directory. If you are just testing locally, the build may complain about this missing file unless you disable the crashlytics plugin in `build.gradle.kts`.
4.  Open the project in Android Studio. It will automatically sync Gradle and CMake configurations.
4.  Connect an Android device (with USB debugging enabled) or start an emulator.
5.  Click the "Run 'app'" button.

## 7. Usage Instructions

1.  **Permissions**: Grant the "Record Audio" permission when prompted.
2.  **Connect Headphones**: For the best experience and to prevent acoustic feedback, please connect any type of wired or Bluetooth headphones. The engine will not start if the phone's speaker is the only audio output.
3.  **Activation**: Use the master power switch to start or stop the audio processing.
4.  **Mode Selection**: Use the "16-Band" and "8-Band" buttons to select the desired equalization detail.
5.  **Gain Adjustment**:
    - **Vertical Drag**: Touch and drag vertically on a frequency column to adjust the gain for that specific band.
    - **Horizontal Drag**: Touch and drag horizontally across the equalizer view to scroll and see bands that are off-screen.

## 8. Citation

If you use this work in your research, please cite our paper:

```bibtex
@article{Wu2025HarkAA,
  title={Hark: An Android-Based Hearing Assistance System with Integrated Audiometry and Low-Latency NDK Audio Processing},
  author={Chia-Yin Wu and Cheng-Lun Tsai},
  journal={2025 International Automatic Control Conference (CACS)},
  year={2025},
  pages={1-5},
  url={https://api.semanticscholar.org/CorpusID:284158249}
}
```

## 9. License

This project is licensed under the **Apache License 2.0**. See the [LICENSE](LICENSE) file for details.
