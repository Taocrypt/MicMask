# MicMask 原生声码器（NEON fp16 NSF-HiFiGAN dec），只出 arm64-v8a。
# 内核移植自 nasuiyile/mobile-rvc（MIT），见 vocoder/*.cpp 文件头与 notices/。
cmake_minimum_required(VERSION 3.22)
project(micmask_vocoder CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(micmask_vocoder SHARED
  vocoder/conv_f32.cpp
  vocoder/conv_f16.cpp
  vocoder/native_decoder.cpp
  vocoder/jni_vocoder.cpp)
target_compile_options(micmask_vocoder PRIVATE -O3 -fvisibility=hidden -ffunction-sections -fdata-sections
  -Wall -Wextra -Wno-unused-parameter)
if(ANDROID_ABI STREQUAL "arm64-v8a")
  # fp16 内核只在运行时检测到 asimdhp+fphp 后才调用，库本身仍能在 ARMv8.0 上加载
  set_source_files_properties(vocoder/conv_f16.cpp PROPERTIES COMPILE_OPTIONS "-march=armv8.2-a+fp16")
endif()
target_link_libraries(micmask_vocoder PRIVATE log)
target_link_options(micmask_vocoder PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
