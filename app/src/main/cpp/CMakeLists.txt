
cmake_minimum_required(VERSION 3.22.1)

project("hark")

add_library(hark SHARED
        core/native-lib.cpp
        core/HarkAudioEngine.cpp
        dsp/BiquadFilter.cpp
        dsp/FilterChain.cpp
        dsp/DynamicsProcessor.cpp
        dsp/NoiseSuppressor.cpp
        dsp/LinkwitzRileyCrossover.cpp
        dsp/CrossoverBank8.cpp
        dsp/TransientSuppressor.cpp
        dsp/OwnVoiceDetector.cpp
        dsp/FrequencyLowering.cpp
        dsp/FrequencySmearing.cpp
        dsp/HearingLossSimulator.cpp
        dsp/SituationalPresets.cpp
        dsp/PrescriptionFitting.cpp
        interaction/GestureDetector.cpp
)

target_include_directories(hark PRIVATE
        core
        dsp
        interaction
)

find_library(log-lib log)

target_link_libraries(hark ${log-lib})

add_subdirectory(${CMAKE_SOURCE_DIR}/../../../../oboe oboe_build)

target_link_libraries(hark oboe -Wl,-z,max-page-size=16384)

set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O3 -ffast-math")
