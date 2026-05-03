
cmake_minimum_required(VERSION 3.22.1)

project("hark")

add_library(hark SHARED
        native-lib.cpp
        HarkAudioEngine.cpp
        BiquadFilter.cpp
        FilterChain.cpp
        DynamicsProcessor.cpp
)

find_library(log-lib log)

target_link_libraries(hark ${log-lib})

add_subdirectory(${CMAKE_SOURCE_DIR}/../../../../oboe oboe_build)

target_link_libraries(hark oboe -Wl,-z,max-page-size=16384)

set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O3 -ffast-math")

# Optional host-side test executable (only for building on non-Android hosts)
if (NOT ${CMAKE_SYSTEM_NAME} STREQUAL "Android")
        add_executable(test_dynamics
                        tests/test_dynamics.cpp
                        DynamicsProcessor.cpp)
        target_include_directories(test_dynamics PRIVATE ${CMAKE_SOURCE_DIR})
        target_link_libraries(test_dynamics m)
        set_target_properties(test_dynamics PROPERTIES RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR})
endif()
