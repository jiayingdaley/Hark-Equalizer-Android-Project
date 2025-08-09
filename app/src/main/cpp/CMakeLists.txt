
cmake_minimum_required(VERSION 3.22.1)

project("hark")

add_library(hark SHARED native-lib.cpp HarkAudioEngine.cpp BiquadFilter.cpp FilterChain.cpp)

find_library(log-lib log)

target_link_libraries(hark ${log-lib})

add_subdirectory(${CMAKE_SOURCE_DIR}/../../../../oboe oboe_build)

target_link_libraries(hark oboe -Wl,-z,max-page-size=16384)

set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O3 -ffast-math")
