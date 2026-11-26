// SPDX-License-Identifier: GPL-3.0-or-later
// 主机端（Linux arm64 / x86_64）离线跑原生声码器，供 compare.py 对比 ORT 输出。
//   voc_cli <blob> <z.f32> <har.f32> <cond.f32|-> <T> <out.f32> <fp16:0|1> <threads> [reps]
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include "../../app/src/main/cpp/vocoder/common.hpp"
#include "../../app/src/main/cpp/vocoder/conv_kernels.hpp"
#include "../../app/src/main/cpp/vocoder/native_decoder.hpp"

static std::vector<uint8_t> rd(const char* p) {
  FILE* f = fopen(p, "rb");
  if (!f) { fprintf(stderr, "open %s\n", p); exit(2); }
  fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
  std::vector<uint8_t> v(n);
  if (fread(v.data(), 1, n, f) != (size_t)n) exit(2);
  fclose(f);
  return v;
}
static std::vector<float> rdf(const char* p) {
  auto b = rd(p);
  std::vector<float> v(b.size() / 4);
  memcpy(v.data(), b.data(), v.size() * 4);
  return v;
}

int main(int argc, char** argv) {
  if (argc < 9) { fprintf(stderr, "usage: voc_cli blob z har cond|- T out fp16 threads [reps]\n"); return 1; }
  auto blob = rd(argv[1]);
  auto z = rdf(argv[2]), har = rdf(argv[3]);
  std::vector<float> cond;
  if (std::string(argv[4]) != "-") cond = rdf(argv[4]);
  int T = atoi(argv[5]);
  bool fp16 = atoi(argv[7]) != 0;
  int threads = atoi(argv[8]), reps = argc > 9 ? atoi(argv[9]) : 5;
  printf("fp16_supported=%d\n", mmvoc::fp16_supported() ? 1 : 0);
  mmvoc::NativeDecoder dec(blob.data(), blob.size(), threads, fp16);
  printf("decoder: %s\n", dec.describe().c_str());
  std::vector<float> out((size_t)T * dec.out_per_frame());
  dec.run(z.data(), T, har.data(), cond.empty() ? nullptr : cond.data(), out.data());  // warm-up
  double best = 1e9, sum = 0;
  for (int r = 0; r < reps; ++r) {
    auto t0 = mmvoc::Clock::now();
    dec.run(z.data(), T, har.data(), cond.empty() ? nullptr : cond.data(), out.data());
    double ms = mmvoc::ms_since(t0);
    best = std::min(best, ms); sum += ms;
  }
  printf("run T=%d: best %.1f ms, mean %.1f ms (%d reps, %d threads, %s)\n", T, best, sum / reps, reps, threads, dec.fp16() ? "fp16" : "fp32");
  FILE* f = fopen(argv[6], "wb");
  fwrite(out.data(), 4, out.size(), f);
  fclose(f);
  return 0;
}
