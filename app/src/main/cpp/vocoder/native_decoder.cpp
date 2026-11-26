// SPDX-License-Identifier: MIT
// Ported from nasuiyile/mobile-rvc (https://github.com/nasuiyile/mobile-rvc),
// android/app/src/main/cpp/engine/native_decoder.cpp — MIT License, Copyright (c) 2026 nasuiyile.
// mobile-rvc in turn ports parts of RVC-Project/Retrieval-based-Voice-Conversion-WebUI
// (MIT License, Copyright (c) 2023 liujing04, 源文雨, Ftps).
// Full license texts: notices/mobile-rvc-MIT.txt, notices/RVC-MIT.txt.
// Modifications for MicMask (GPL-3.0 as a whole): see native_decoder.hpp. run() is unchanged
// except for the 48 kHz tail; the constructor reads the MMVOC16 blob.
#include "native_decoder.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>

#include "common.hpp"
#include "conv_kernels.hpp"
#include "thread_pool.hpp"

namespace mmvoc {

namespace {

constexpr int kMargin = 64;  // zero columns on both sides of every conv input row

inline int round_up(int v, int m) { return (v + m - 1) / m * m; }
inline int ld_for(int L) { return round_up(L, kConvTileT) + 2 * kMargin; }

// Sequential little-endian reader over the MMVOC16 blob.
class Blob {
 public:
  Blob(const uint8_t* p, size_t n) : p_(p), n_(n) {}
  int32_t i32() {
    need(4);
    int32_t v;
    memcpy(&v, p_ + pos_, 4);
    pos_ += 4;
    return v;
  }
  int32_t pos_i32(const char* what, int lo, int hi) {
    int32_t v = i32();
    if (v < lo || v > hi) throw Error(std::string("vocoder blob: bad ") + what + " = " + std::to_string(v));
    return v;
  }
  std::vector<float> f16(size_t count) {
    need(count * 2);
    std::vector<float> v(count);
    for (size_t i = 0; i < count; ++i) {
      uint16_t h;
      memcpy(&h, p_ + pos_ + 2 * i, 2);
      v[i] = half_to_float(h);
    }
    pos_ += count * 2;
    return v;
  }
  std::vector<float> f32(size_t count) {
    need(count * 4);
    std::vector<float> v(count);
    memcpy(v.data(), p_ + pos_, count * 4);
    pos_ += count * 4;
    return v;
  }
  void magic(const char* m, size_t len) {
    need(len);
    if (memcmp(p_ + pos_, m, len) != 0) throw Error("vocoder blob: bad magic");
    pos_ += len;
  }
  size_t left() const { return n_ - pos_; }

 private:
  const uint8_t* p_;
  size_t n_, pos_ = 0;
  void need(size_t k) {
    if (pos_ + k > n_) throw Error("vocoder blob: truncated");
  }
};

// taps of an ordinary (dilated) conv with left padding `pad`: y[t] = sum_k w[k] x[t + k*dil - pad]
void same_taps(int K, int dil, int pad, std::vector<int>& kidx, std::vector<int>& off) {
  kidx.clear();
  off.clear();
  for (int k = 0; k < K; ++k) {
    kidx.push_back(k);
    off.push_back(k * dil - pad);
  }
}

}  // namespace

NativeDecoder::NativeDecoder(const uint8_t* data, size_t n, int threads, bool want_fp16) {
  fp16_ = want_fp16 && fp16_supported();
  pool_.reset(new ThreadPool(threads));
  Blob b(data, n);
  b.magic("MMVOC16\0", 8);
  if (b.i32() != 1) throw Error("vocoder blob: unsupported version");
  cin_ = b.pos_i32("cin", 1, 4096);
  const int c0 = b.pos_i32("c0", 8, 4096);
  const int nstage = b.pos_i32("nstage", 1, 8);
  num_kernels_ = b.pos_i32("nk", 1, 8);
  const int rbtype = b.pos_i32("resblock", 1, 2);
  resblock1_ = rbtype == 1;
  upp_ = b.pos_i32("upp", 1, 100000);
  std::vector<int> kidx, off;

  // conv_pre (speaker term already folded into its bias by the exporter)
  {
    int K = b.pos_i32("pre.K", 1, 64), pad = b.pos_i32("pre.pad", 0, 64);
    std::vector<float> w = b.f16(static_cast<size_t>(c0) * cin_ * K);
    std::vector<float> bias = b.f32(c0);
    same_taps(K, 1, pad, kidx, off);
    pack(conv_pre_, w, c0, cin_, K, kidx, off, bias.data());
    pre_bias_ = bias;
  }

  int ch = c0, prod = 1;
  for (int i = 0; i < nstage; ++i) {
    Stage st;
    st.rate = b.pos_i32("ups.rate", 1, 64);
    int K = b.pos_i32("ups.K", 1, 256), p = b.pos_i32("ups.pad", 0, 256);
    st.cin = b.pos_i32("ups.cin", 1, 4096);
    st.cout = b.pos_i32("ups.cout", 8, 4096);
    if (st.cin != ch || st.cout % kConvCoTile) throw Error("vocoder blob: channel mismatch");
    const int u = st.rate;
    if (K != u + 2 * p) throw Error("vocoder blob: transposed conv geometry");
    prod *= u;
    std::vector<float> wt = b.f16(static_cast<size_t>(st.cin) * st.cout * K);  // [cin][cout][K]
    std::vector<float> bt = b.f32(st.cout);
    // transposed conv -> one ordinary conv per output phase (dense [cout][cin][K] view)
    std::vector<float> dense(static_cast<size_t>(st.cout) * st.cin * K);
    for (int ci = 0; ci < st.cin; ++ci)
      for (int co = 0; co < st.cout; ++co)
        for (int k = 0; k < K; ++k)
          dense[(static_cast<size_t>(co) * st.cin + ci) * K + k] = wt[(static_cast<size_t>(ci) * st.cout + co) * K + k];
    for (int ph = 0; ph < u; ++ph) {
      kidx.clear();
      off.clear();
      for (int k = 0; k < K; ++k) {
        int d = k - p - ph;
        if (((d % u) + u) % u == 0) { kidx.push_back(k); off.push_back(-(d / u)); }
      }
      Conv c;
      pack(c, dense, st.cout, st.cin, K, kidx, off, bt.data());
      st.phases.push_back(std::move(c));
    }
    // noise conv (strided) -> polyphase channels: y[t] = sum_k W[k] har[t*s + k - pad]
    {
      int kn = b.pos_i32("noise.K", 1, 4096), s = b.pos_i32("noise.stride", 1, 4096), pad = b.pos_i32("noise.pad", 0, 4096);
      std::vector<float> nw = b.f16(static_cast<size_t>(st.cout) * kn);
      std::vector<float> nb = b.f32(st.cout);
      if (kn != s + 2 * pad) throw Error("vocoder blob: noise conv geometry");
      st.noise_s = s;
      if (s == 1) {
        same_taps(kn, 1, pad, kidx, off);
        pack(st.noise, nw, st.cout, 1, kn, kidx, off, nb.data());
      } else {
        // k = q*s + phi + pad  ->  W'[co][phi][q]
        int qlo = static_cast<int>(std::floor(static_cast<double>(-pad - (s - 1)) / s));
        int qhi = static_cast<int>(std::floor(static_cast<double>(kn - 1 - pad) / s));
        int nq = qhi - qlo + 1;
        std::vector<float> wp(static_cast<size_t>(st.cout) * s * nq, 0.f);
        for (int co = 0; co < st.cout; ++co)
          for (int ph = 0; ph < s; ++ph)
            for (int q = qlo; q <= qhi; ++q) {
              int k = q * s + ph + pad;
              if (k >= 0 && k < kn) wp[(static_cast<size_t>(co) * s + ph) * nq + (q - qlo)] = nw[static_cast<size_t>(co) * kn + k];
            }
        kidx.clear();
        off.clear();
        for (int q = qlo; q <= qhi; ++q) { kidx.push_back(q - qlo); off.push_back(q); }
        pack(st.noise, wp, st.cout, s, nq, kidx, off, nb.data());
      }
    }
    // resblocks
    for (int j = 0; j < num_kernels_; ++j) {
      int nconv = b.pos_i32("res.nconv", 1, 16);
      if (resblock1_ && (nconv % 2)) throw Error("vocoder blob: ResBlock1 needs conv pairs");
      std::vector<Conv> convs;
      for (int m = 0; m < nconv; ++m) {
        int k = b.pos_i32("res.K", 1, 64), dil = b.pos_i32("res.dil", 1, 64), pad = b.pos_i32("res.pad", 0, 4096);
        if (2 * pad != (k - 1) * dil) throw Error("vocoder blob: resblock padding");
        std::vector<float> w = b.f16(static_cast<size_t>(st.cout) * st.cout * k);
        std::vector<float> bias = b.f32(st.cout);
        same_taps(k, dil, pad, kidx, off);
        Conv c;
        pack(c, w, st.cout, st.cout, k, kidx, off, bias.data());
        convs.push_back(std::move(c));
      }
      st.res.push_back(std::move(convs));
    }
    ch = st.cout;
    stages_.push_back(std::move(st));
  }
  if (prod != upp_) throw Error("vocoder blob: upp mismatch");
  // noise strides must tile the remaining upsampling
  {
    int rest = upp_;
    for (auto& st : stages_) {
      rest /= st.rate;
      if (st.noise_s != rest) throw Error("vocoder blob: noise stride mismatch");
    }
  }
  {
    int K = b.pos_i32("post.K", 1, 64), pad = b.pos_i32("post.pad", 0, 64), has_bias = b.pos_i32("post.bias", 0, 1);
    std::vector<float> w = b.f16(static_cast<size_t>(ch) * K);  // [1][ch][K]
    std::vector<float> bias(kConvCoTile, 0.f);
    if (has_bias) bias[0] = b.f32(1)[0];
    std::vector<float> w8(static_cast<size_t>(kConvCoTile) * ch * K, 0.f);
    std::copy(w.begin(), w.end(), w8.begin());
    same_taps(K, 1, pad, kidx, off);
    pack(conv_post_, w8, kConvCoTile, ch, K, kidx, off, bias.data());
  }
  {
    up_ = b.pos_i32("tail.up", 1, 1000);
    dn_ = b.pos_i32("tail.dn", 1, 1000);
    int taps = b.pos_i32("tail.taps", 0, 100000);
    off_ = b.pos_i32("tail.off", 0, 100000);
    per_frame_ = b.pos_i32("tail.perFrame", 1, 100000);
    h_ = b.f32(taps);
    if (up_ != dn_ && taps == 0) throw Error("vocoder blob: resampler without taps");
    if (static_cast<long long>(upp_) * up_ < static_cast<long long>(per_frame_) * dn_) throw Error("vocoder blob: tail too short");
  }
  b.magic("MMEN", 4);
  VOC_LOGI("native decoder: %s", describe().c_str());
}

NativeDecoder::~NativeDecoder() = default;

std::string NativeDecoder::describe() const {
  std::string r = std::to_string(stages_.size()) + " stages, upp " + std::to_string(upp_) + ", rates";
  for (auto& s : stages_) r += " " + std::to_string(s.rate);
  r += resblock1_ ? ", resblock1" : ", resblock2";
  r += ", " + std::to_string(pool_->size()) + " threads, ";
  r += fp16_ ? "fp16" : "fp32";
  if (up_ != dn_) r += ", tail " + std::to_string(up_) + "/" + std::to_string(dn_);
  return r;
}

void NativeDecoder::pack(Conv& c, const std::vector<float>& w, int Co, int Ci, int K, const std::vector<int>& kidx,
                         const std::vector<int>& off, const float* bias) {
  c.Co = Co;
  c.Ci = Ci;
  c.ntaps = static_cast<int>(kidx.size());
  c.off = off;
  for (int o : off)
    if (o < -kMargin || o > kMargin) throw Error("vocoder: tap offset outside margin");
  c.bias.assign(Co, 0.f);
  if (bias) std::copy(bias, bias + Co, c.bias.begin());
  std::vector<float> p(static_cast<size_t>(Co) * Ci * c.ntaps);
  size_t q = 0;
  for (int cot = 0; cot < Co / kConvCoTile; ++cot)
    for (int ci = 0; ci < Ci; ++ci)
      for (int t = 0; t < c.ntaps; ++t)
        for (int l = 0; l < kConvCoTile; ++l)
          p[q++] = w[(static_cast<size_t>(cot * kConvCoTile + l) * Ci + ci) * K + kidx[t]];
  if (fp16_) {
    c.w16.resize(p.size());
    for (size_t i = 0; i < p.size(); ++i) c.w16[i] = float_to_half(p[i]);
  } else {
    c.w32.swap(p);
  }
}

void NativeDecoder::conv(const Conv& c, const void* x, int ldx, int T, float* y, int ldy, bool accumulate) {
  ConvArgs a;
  a.Co = c.Co;
  a.Ci = c.Ci;
  a.ntaps = c.ntaps;
  a.off = c.off.data();
  a.w = fp16_ ? static_cast<const void*>(c.w16.data()) : static_cast<const void*>(c.w32.data());
  a.bias = c.bias.data();
  a.x = x;
  a.ldx = ldx;
  a.T = T;
  a.y = y;
  a.ldy = ldy;
  a.accumulate = accumulate;
  if (fp16_) conv_f16(a, *pool_);
  else conv_f32(a, *pool_);
}

void NativeDecoder::run(const float* z, int T, const float* har, const float* cond, float* out) {
  if (cond)
    for (int c = 0; c < conv_pre_.Co; ++c) conv_pre_.bias[c] = pre_bias_[c] + cond[c];
  // buffer sizing: largest channels x row stride over all stages
  size_t need = static_cast<size_t>(std::max(cin_, conv_pre_.Co)) * ld_for(T);
  {
    int L = T;
    for (auto& st : stages_) {
      int Lo = L * st.rate;
      need = std::max(need, static_cast<size_t>(std::max(st.cin, st.cout)) * ld_for(Lo));
      need = std::max(need, static_cast<size_t>(st.noise_s) * ld_for(Lo));
      L = Lo;
    }
    need = std::max(need, static_cast<size_t>(kConvCoTile) * ld_for(L));
  }
  for (auto& b : fbuf_)
    if (b.size() < need) b.assign(need, 0.f);
  if (fp16_) {
    if (hbuf_.size() < 2 * need) hbuf_.assign(2 * need, 0);
  } else if (abuf32_.size() < 2 * need) {
    abuf32_.assign(2 * need, 0.f);
  }
  ThreadPool& pool = *pool_;
  const size_t es = fp16_ ? 2 : 4;
  uint8_t* abase[2] = {fp16_ ? reinterpret_cast<uint8_t*>(hbuf_.data()) : reinterpret_cast<uint8_t*>(abuf32_.data()),
                       fp16_ ? reinterpret_cast<uint8_t*>(hbuf_.data() + need)
                             : reinterpret_cast<uint8_t*>(abuf32_.data() + need)};
  // conv-input view: column 0 of row 0
  auto A = [&](int i) -> void* { return abase[i] + kMargin * es; };
  auto F = [&](int i) -> float* { return fbuf_[i].data() + kMargin; };

  // write rows of a conv input: dst[c][0..L) = act(src[c][t]), zero margins
  auto make_input = [&](int dst, const float* src, int lds, int C, int L, float slope) {
    int ld = ld_for(L);
    pool.parallel_for(C, [&](int c) {
      thread_local std::vector<float> tmp;
      if (tmp.size() < static_cast<size_t>(L)) tmp.resize(static_cast<size_t>(L));
      const float* s = src + static_cast<ptrdiff_t>(c) * lds;
      for (int t = 0; t < L; ++t) {
        float v = s[t];
        tmp[t] = v < 0 ? v * slope : v;
      }
      uint8_t* row = abase[dst] + static_cast<size_t>(c) * ld * es;
      memset(row, 0, kMargin * es);
      size_t tail = static_cast<size_t>(ld - kMargin - L);
      if (fp16_) {
        uint16_t* r = reinterpret_cast<uint16_t*>(row) + kMargin;
        f32_to_f16(tmp.data(), r, L);
        memset(r + L, 0, tail * 2);
      } else {
        float* r = reinterpret_cast<float*>(row) + kMargin;
        memcpy(r, tmp.data(), sizeof(float) * L);
        memset(r + L, 0, tail * 4);
      }
    });
  };

  // conv_pre(z) (+ cond(g) folded in bias)
  int L = T;
  make_input(0, z, T, cin_, L, 1.f);
  int x = 0;  // index of fp32 buffer holding the current activation
  conv(conv_pre_, A(0), ld_for(L), L, F(x), ld_for(L), false);

  for (auto& st : stages_) {
    const int Lo = L * st.rate, ldi = ld_for(L), ldo = ld_for(Lo);
    const int xn = (x + 1) % 5, r = (x + 2) % 5, b = (x + 3) % 5, tmp = (x + 4) % 5;
    // leaky_relu -> transposed conv (polyphase) -> interleave
    make_input(0, F(x), ldi, st.cin, L, 0.1f);
    for (int ph = 0; ph < st.rate; ++ph) {
      conv(st.phases[ph], A(0), ldi, L, F(tmp), ldi, false);
      float* dst = F(xn);
      const float* src = F(tmp);
      pool.parallel_for(st.cout, [&](int c) {
        const float* s = src + static_cast<size_t>(c) * ldi;
        float* d = dst + static_cast<size_t>(c) * ldo + ph;
        for (int m = 0; m < L; ++m) d[static_cast<size_t>(m) * st.rate] = s[m];
      });
    }
    // + noise_conv(har): har as noise_s polyphase channels
    {
      const int s = st.noise_s;
      std::vector<float> poly(static_cast<size_t>(s) * Lo);
      for (int m = 0; m < Lo; ++m)
        for (int ph = 0; ph < s; ++ph) poly[static_cast<size_t>(ph) * Lo + m] = har[static_cast<size_t>(m) * s + ph];
      make_input(1, poly.data(), Lo, s, Lo, 1.f);
      conv(st.noise, A(1), ldo, Lo, F(xn), ldo, true);
    }
    // resblocks; xs accumulates in buffer x (previous activation is dead now)
    make_input(1, F(xn), ldo, st.cout, Lo, 0.1f);  // shared first input of every resblock
    const size_t plane = static_cast<size_t>(st.cout) * ldo;
    for (int j = 0; j < num_kernels_; ++j) {
      auto& convs = st.res[j];
      float* R = F(r);
      memcpy(R - kMargin, F(xn) - kMargin, plane * sizeof(float));
      if (resblock1_) {
        for (size_t m = 0; m + 1 < convs.size(); m += 2) {
          if (m > 0) make_input(0, R, ldo, st.cout, Lo, 0.1f);
          conv(convs[m], m == 0 ? A(1) : A(0), ldo, Lo, F(b), ldo, false);
          make_input(0, F(b), ldo, st.cout, Lo, 0.1f);
          conv(convs[m + 1], A(0), ldo, Lo, R, ldo, true);
        }
      } else {
        for (size_t m = 0; m < convs.size(); ++m) {
          if (m > 0) make_input(0, R, ldo, st.cout, Lo, 0.1f);
          conv(convs[m], m == 0 ? A(1) : A(0), ldo, Lo, R, ldo, true);
        }
      }
      float* XS = F(x);
      if (j == 0) memcpy(XS - kMargin, R - kMargin, plane * sizeof(float));
      else {
        pool.parallel_for(st.cout, [&](int c) {
          float* d = XS + static_cast<size_t>(c) * ldo;
          const float* sr = R + static_cast<size_t>(c) * ldo;
          for (int t = 0; t < Lo; ++t) d[t] += sr[t];
        });
      }
    }
    {
      float inv = 1.f / num_kernels_;
      float* XS = F(x);
      pool.parallel_for(st.cout, [&](int c) {
        float* d = XS + static_cast<size_t>(c) * ldo;
        for (int t = 0; t < Lo; ++t) d[t] *= inv;
      });
    }
    L = Lo;
  }
  // leaky_relu(0.01) -> conv_post -> tanh
  const int ld = ld_for(L);
  make_input(0, F(x), ld, stages_.back().cout, L, 0.01f);
  int y = (x + 1) % 5;
  conv(conv_post_, A(0), ld, L, F(y), ld, false);
  const float* yp = F(y);
  const int nout = T * per_frame_;
  if (up_ == dn_) {
    for (int t = 0; t < nout; ++t) out[t] = std::tanh(yp[t]);
    return;
  }
  // 48 kHz tail (same as the graph's ConvTranspose(stride=up) + Slice(off::dn))
  if (dec_out_.size() < static_cast<size_t>(L)) dec_out_.resize(static_cast<size_t>(L));
  float* o = dec_out_.data();
  for (int t = 0; t < L; ++t) o[t] = std::tanh(yp[t]);
  const int taps = static_cast<int>(h_.size());
  const float* h = h_.data();
  pool.parallel_for(std::max(1, pool.size() * 2), [&](int part) {
    int parts = std::max(1, pool.size() * 2);
    int j0 = static_cast<int>(static_cast<long long>(nout) * part / parts);
    int j1 = static_cast<int>(static_cast<long long>(nout) * (part + 1) / parts);
    for (int j = j0; j < j1; ++j) {
      long long pos = off_ + static_cast<long long>(j) * dn_;
      // m*up <= pos and pos - m*up < taps
      long long mlo = pos - taps + 1 <= 0 ? 0 : (pos - taps + 1 + up_ - 1) / up_;
      long long mhi = std::min<long long>(L - 1, pos / up_);
      float acc = 0.f;
      for (long long m = mlo; m <= mhi; ++m) acc += o[m] * h[pos - m * up_];
      out[j] = acc;
    }
  });
}

}  // namespace mmvoc
