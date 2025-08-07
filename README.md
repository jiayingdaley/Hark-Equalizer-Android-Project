# Hark: An Android Low-Latency Hearing Aid and Audio Enhancement Platform

### Why is it called Hark?
The name Hark draws from an old English word meaning “listen!” — not just to hear sounds, but to awaken the sense of hearing as an intentional, essential act. It appears in Shakespeare’s plays and ancient hymns as a call to pay attention to something important.

In the same spirit, Hark is an app that helps users truly listen again — whether to a conversation, a melody, or the world around them. Much like the technique of [*sonification*](https://science.nasa.gov/mission/hubble/multimedia/sonifications/), which turns cosmic data into sound, Hark transforms digital signals into accessible hearing. It’s not just a hearing aid; it’s a hearing awakening.


## 1. Project Overview

Hark is an advanced Android application designed to function as a versatile, low-latency hearing-assistance and audio enhancement platform. Going beyond a simple equalizer, Hark transforms any standard wired or Bluetooth earphones into a powerful audio processing device.

The project's core mission is to provide a real-world solution for everyday listening challenges, including:
- **Live Conversation (Hearing Aid Mode)**: Capturing and enhancing ambient sounds in real-time to improve speech clarity in social situations.
- **Media Enhancement (Media Mode)**: Processing internal audio from applications like YouTube or Spotify to create a personalized listening experience.

Hark is built upon a high-performance, modular C++ audio engine, serving as a platform for both practical daily use and academic research into real-time digital signal processing (DSP) on mobile devices.

## 2. Core Features

- **Ultra-Low Latency Audio Engine**: Utilizes the **Oboe** library for a high-performance C++/NDK-based audio pipeline, minimizing delay for a natural listening experience.
- **Multi-Scenario Support**: Dedicated modes for live hearing assistance and internal media enhancement.
- **Acoustic Feedback Cancellation (AFC)**: Implements a custom real-time algorithm to detect and suppress the high-pitched squeal (feedback) common in hearing aid applications.
- **Modular & Comparative Filter Engine**: Allows users to switch between and compare different processing filters:
    - **Custom 16/8-Band Biquad EQ**: A high-precision, audiologically-standard software equalizer.
    - **Android System EQ**: The device's built-in hardware equalizer (for comparison).
- **Advanced DSP Controls**: Features user-adjustable **Q factor** for fine-tuning filter bandwidth, in addition to standard gain controls.
- **Performance Profiling**: An on-screen display shows the real-time processing latency of the DSP engine in microseconds (μs), providing quantitative performance data.
- **Real-time Spectrum Visualizer**: A graphical display shows the audio frequency spectrum before and after processing, offering instant visual feedback on how the sound is being modified.
- **Processed Audio Recording**: Ability to record the post-processed audio output to a `.wav` file for analysis and demonstration.
- **Foreground Service Operation**: Ensures all audio processing continues reliably when the app is in the background.

## 3. Technical Deep Dive

Hark's architecture is centered around a unified, modular audio processing pipeline implemented in C++ for maximum performance and control.


### C++ Oboe Audio Pipeline

The entire real-time audio path is managed by a custom C++ engine using the Oboe library to achieve the lowest possible latency.

`Oboe Input -> [Pre-Processing] -> [Filter Stage] -> [Post-Processing] -> Oboe Output`

- **Pre-Processing Module**:
    - **Acoustic Feedback Cancellation (AFC)**: This module is active only in Hearing Aid mode. It uses an FFT to analyze the input signal, detect persistent frequency peaks indicative of feedback, and dynamically applies a Notch filter to eliminate them.
    *TODO: If performance of the custom AFC is insufficient, investigate integrating a professional library such as SpeexDSP (lightweight, for voice) or the WebRTC AudioProcessing module (comprehensive, includes AEC, NS, VAD).*

- **Filter Stage (The Experimental Variable)**:
    - This is a swappable module controlled by the user. The core is the **Custom Biquad Filter Engine**.
    - **Biquad Filter**: Implements a second-order IIR Peaking EQ filter based on the "Audio EQ Cookbook" formulas. The filter coefficients are recalculated in real-time as users adjust gain (dB) or Q factor.
    *TODO: Evolve the vertical slider UI into a more intuitive graphical curve editor where users can drag points on the frequency response graph.*

- **Post-Processing Module**:
    - **Visualizer**: An FFT is computed on the processed buffer to generate data for the real-time spectrum analyzer UI.
    - **Recorder**: A `WavWriter` can be activated to save the final processed audio to a file.

- **Performance Profiler**:
    - High-precision timers (`std::chrono`) are wrapped around each stage of the pipeline (AFC, Filter, etc.) to measure their individual CPU execution time. This data is then passed to the UI for display.

## 4. Center Frequencies

The custom engine uses hardcoded, audiologically-standard center frequencies.

#### 16-Band Mode
- 250 Hz, 315 Hz, 400 Hz, 500 Hz, 630 Hz, 800 Hz, 1000 Hz, 1250 Hz, 1600 Hz, 2000 Hz, 2500 Hz, 3150 Hz, 4000 Hz, 5000 Hz, 6300 Hz, 8000 Hz

#### 8-Band Mode
- 250 Hz, 500 Hz, 1000 Hz, 1500 Hz, 2000 Hz, 4000 Hz, 6000 Hz, 8000 Hz

## 5. Tech Stack

- **Language**: Kotlin + **C++ (via Android NDK)**
- **Core Audio API**: **Oboe**
- **Comparison/Legacy APIs**: `android.media.audiofx.Equalizer`
- **Concurrency**: `Thread`, `ReentrantLock` for UI and service management.
- **UI**: Android Views (XML), programmatically generated controls, and custom-drawn views for visualization.

## 6. How to Build and Run

1.  Ensure you have the latest Android Studio, along with the Android **NDK** and **CMake** installed via the SDK Manager.
2.  Clone this repository.
3.  Open the project in Android Studio. It will automatically sync Gradle and CMake configurations.
4.  Connect an Android device (with USB debugging enabled) or start an emulator.
5.  Click the "Run 'app'" button.

## 7. Usage Instructions

1.  **Permissions**: Grant the "Record Audio" permission when prompted.
2.  **Mode Selection**: Use the "Hearing Aid" and "Media EQ" buttons to select the operating mode.
3.  **Filter Selection**: Use the dropdown menu to choose the desired filter engine.
4.  **Activation**: Use the master power switch to start or stop the audio processing.
5.  **Gain & Q-Factor Adjustment**: Use the vertical sliders to adjust the gain for each band. An advanced settings panel will allow for Q-factor adjustment.
