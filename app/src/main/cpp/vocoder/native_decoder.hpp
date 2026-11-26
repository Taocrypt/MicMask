// SPDX-License-Identifier: MIT
// Ported from nasuiyile/mobile-rvc (https://github.com/nasuiyile/mobile-rvc),
// android/app/src/main/cpp/engine/native_decoder.hpp — MIT License, Copyright (c) 2026 nasuiyile.
// mobile-rvc in turn ports parts of RVC-Project/Retrieval-based-Voice-Conversion-WebUI
// (MIT License, Copyright (c) 2023 liujing04, 源文雨, Ftps).
// Full license texts: notices/mobile-rvc-MIT.txt, notices/RVC-MIT.txt.
// Modifications for MicMask (GPL-3.0 as a whole): weights come from a MicMask "MMVOC16" blob
// (extracted on the phone from the voice's streaming ONNX, see core/.../VocoderSplit.kt) instead of
// weights.bin + manifest.json; weight-norm is already removed and the speaker term is already folded
// into conv_pre's bias by the exporter; conv / pad geometry is read per layer; an optional
// polyphase FIR tail resamples the decoder output to 48 kHz exactly like the ONNX graph does.
//
// Native NSF-HiFiGAN decoder (RVC "dec" without the sine source) on NEON fp16/fp32.
#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace mmvoc {

class ThreadPool;

class NativeDecoder {
 public:
  // blob: MMVOC16 file contents (see VocoderSplit.kt for the layout).
  NativeDecoder(const uint8_t* blob, size_t n, int threads, bool want_fp16);
  ~NativeDecoder();
  bool fp16() const { return fp16_; }
  int upp() const { return upp_; }
  int in_channels() const { return cin_; }
  int out_per_frame() const { return per_frame_; }
  std::string describe() const;
  int pre_channels() const { return conv_pre_.Co; }
  // z: [in_channels][T] (row-major), har: [T*upp]; cond: optional [pre_channels()] speaker term added to
  // conv_pre's bias (models whose exporter did not fold it); out: [T*out_per_frame()] at 48 kHz
  void run(const float* z, int T, const float* har, const float* cond, float* out);

 private:
  struct Conv {
    int Co = 0, Ci = 0, ntaps = 0;
    std::vector<int> off;
    std::vector<float> w32;
    std::vector<uint16_t> w16;
    std::vector<float> bias;
  };
  struct Stage {
    int rate = 1, cin = 0, cout = 0, noise_s = 1;
    std::vector<Conv> phases;  // transposed conv, one per output phase
    Conv noise;
    std::vector<std::vector<Conv>> res;  // per resblock: convs1[0],convs2[0],convs1[1],... (ResBlock1)
  };
  bool fp16_ = false;
  bool resblock1_ = true;
  int upp_ = 1, cin_ = 192, num_kernels_ = 3;
  Conv conv_pre_, conv_post_;
  std::vector<float> pre_bias_;  // conv_pre bias as exported (cond is added per run when given)
  std::vector<Stage> stages_;
  std::unique_ptr<ThreadPool> pool_;
  // 48 kHz tail: y[j] = sum_m o[m] * h[off + j*dn - m*up]
  int up_ = 1, dn_ = 1, off_ = 0, per_frame_ = 0;
  std::vector<float> h_;

  // scratch
  std::vector<float> fbuf_[5];
  std::vector<uint16_t> hbuf_;
  std::vector<float> abuf32_;
  std::vector<float> dec_out_;

  void pack(Conv& c, const std::vector<float>& w, int Co, int Ci, int K, const std::vector<int>& kidx,
            const std::vector<int>& off, const float* bias);
  void conv(const Conv& c, const void* x, int ldx, int T, float* y, int ldy, bool accumulate);
};

}  // namespace mmvoc
