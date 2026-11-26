// SPDX-License-Identifier: MIT
// Ported from nasuiyile/mobile-rvc (https://github.com/nasuiyile/mobile-rvc),
// android/app/src/main/cpp/engine/common.hpp — MIT License, Copyright (c) 2026 nasuiyile.
// mobile-rvc in turn ports parts of RVC-Project/Retrieval-based-Voice-Conversion-WebUI
// (MIT License, Copyright (c) 2023 liujing04, 源文雨, Ftps).
// Full license texts: notices/mobile-rvc-MIT.txt, notices/RVC-MIT.txt.
// Modifications for MicMask (GPL-3.0 as a whole): file I/O helpers removed, log tag renamed.
// Shared helpers: logging, file I/O, half-float conversion, timing.
#pragma once

#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>

#ifdef __ANDROID__
#include <android/log.h>
#define VOC_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "micmask-voc", __VA_ARGS__)
#define VOC_LOGW(...) __android_log_print(ANDROID_LOG_WARN, "micmask-voc", __VA_ARGS__)
#define VOC_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "micmask-voc", __VA_ARGS__)
#else
#define VOC_LOGI(...) (fprintf(stderr, "[voc] " __VA_ARGS__), fputc('\n', stderr))
#define VOC_LOGW(...) (fprintf(stderr, "[voc][W] " __VA_ARGS__), fputc('\n', stderr))
#define VOC_LOGE(...) (fprintf(stderr, "[voc][E] " __VA_ARGS__), fputc('\n', stderr))
#endif

namespace mmvoc {

struct Error : std::runtime_error {
  using std::runtime_error::runtime_error;
};

// IEEE 754 binary16 <-> binary32 (round-to-nearest-even), portable.
inline float half_to_float(uint16_t h) {
  uint32_t sign = (h & 0x8000u) << 16, exp = (h >> 10) & 0x1Fu, mant = h & 0x3FFu, f;
  if (exp == 0) {
    if (mant == 0) f = sign;
    else {
      exp = 127 - 15 + 1;
      while ((mant & 0x400u) == 0) { mant <<= 1; --exp; }
      mant &= 0x3FFu;
      f = sign | (exp << 23) | (mant << 13);
    }
  } else if (exp == 31) {
    f = sign | 0x7F800000u | (mant << 13);
  } else {
    f = sign | ((exp + 127 - 15) << 23) | (mant << 13);
  }
  float out;
  memcpy(&out, &f, 4);
  return out;
}

inline uint16_t float_to_half(float v) {
  uint32_t x;
  memcpy(&x, &v, 4);
  uint32_t sign = (x >> 16) & 0x8000u;
  int32_t exp = static_cast<int32_t>((x >> 23) & 0xFF) - 127 + 15;
  uint32_t mant = x & 0x7FFFFFu;
  if (((x >> 23) & 0xFF) == 0xFF) return static_cast<uint16_t>(sign | 0x7C00u | (mant ? 0x200u : 0));
  if (exp >= 31) return static_cast<uint16_t>(sign | 0x7C00u);
  if (exp <= 0) {
    if (exp < -10) return static_cast<uint16_t>(sign);
    mant |= 0x800000u;
    uint32_t shift = static_cast<uint32_t>(14 - exp);
    uint32_t h = mant >> shift;
    uint32_t rem = mant & ((1u << shift) - 1), half = 1u << (shift - 1);
    if (rem > half || (rem == half && (h & 1))) ++h;
    return static_cast<uint16_t>(sign | h);
  }
  uint32_t h = (static_cast<uint32_t>(exp) << 10) | (mant >> 13);
  uint32_t rem = mant & 0x1FFFu;
  if (rem > 0x1000u || (rem == 0x1000u && (h & 1))) ++h;
  return static_cast<uint16_t>(sign | h);
}

using Clock = std::chrono::steady_clock;
inline double ms_since(Clock::time_point t0) {
  return std::chrono::duration<double, std::milli>(Clock::now() - t0).count();
}

}  // namespace mmvoc
