// SPDX-License-Identifier: GPL-3.0-or-later
// MicMask: JNI bridge for the native NSF-HiFiGAN decoder (com.taocrypt.micmask.NeonVocoder).
#include <jni.h>

#include <cstdio>
#include <memory>
#include <string>
#include <vector>

#include "common.hpp"
#include "conv_kernels.hpp"
#include "native_decoder.hpp"

namespace {

thread_local std::string g_err;

struct Handle {
  std::unique_ptr<mmvoc::NativeDecoder> dec;
  std::vector<float> z, har, cond, out;
};

bool read_all(const char* path, std::vector<uint8_t>& buf) {
  FILE* f = fopen(path, "rb");
  if (!f) return false;
  fseek(f, 0, SEEK_END);
  long n = ftell(f);
  fseek(f, 0, SEEK_SET);
  buf.resize(n > 0 ? static_cast<size_t>(n) : 0);
  size_t got = n > 0 ? fread(buf.data(), 1, buf.size(), f) : 0;
  fclose(f);
  return n > 0 && got == buf.size();
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_taocrypt_micmask_NeonVocoder_nFp16Supported(JNIEnv*, jclass) {
  return mmvoc::fp16_supported() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_com_taocrypt_micmask_NeonVocoder_nLastError(JNIEnv* env, jclass) {
  return env->NewStringUTF(g_err.c_str());
}

JNIEXPORT jlong JNICALL Java_com_taocrypt_micmask_NeonVocoder_nCreate(JNIEnv* env, jclass, jstring jpath, jint threads,
                                                                       jboolean fp16) {
  g_err.clear();
  const char* p = env->GetStringUTFChars(jpath, nullptr);
  std::string path = p ? p : "";
  if (p) env->ReleaseStringUTFChars(jpath, p);
  std::vector<uint8_t> blob;
  if (!read_all(path.c_str(), blob)) {
    g_err = "cannot read " + path;
    return 0;
  }
  try {
    auto* h = new Handle();
    h->dec.reset(new mmvoc::NativeDecoder(blob.data(), blob.size(), threads, fp16 == JNI_TRUE));
    return reinterpret_cast<jlong>(h);
  } catch (const std::exception& e) {
    g_err = e.what();
  } catch (...) {
    g_err = "unknown error";
  }
  return 0;
}

JNIEXPORT jstring JNICALL Java_com_taocrypt_micmask_NeonVocoder_nDescribe(JNIEnv* env, jclass, jlong hp) {
  auto* h = reinterpret_cast<Handle*>(hp);
  return env->NewStringUTF(h ? h->dec->describe().c_str() : "");
}

JNIEXPORT jint JNICALL Java_com_taocrypt_micmask_NeonVocoder_nInfo(JNIEnv*, jclass, jlong hp, jint what) {
  auto* h = reinterpret_cast<Handle*>(hp);
  if (!h) return 0;
  switch (what) {
    case 0: return h->dec->in_channels();
    case 1: return h->dec->upp();
    case 2: return h->dec->out_per_frame();
    case 3: return h->dec->fp16() ? 1 : 0;
    case 4: return h->dec->pre_channels();
    default: return 0;
  }
}

// z: [C*T] row-major, har: [T*upp], cond: [c0] or null; out: [T*perFrame]. Returns samples written, -1 on error.
JNIEXPORT jint JNICALL Java_com_taocrypt_micmask_NeonVocoder_nRun(JNIEnv* env, jclass, jlong hp, jfloatArray jz, jint T,
                                                                  jfloatArray jhar, jfloatArray jcond, jfloatArray jout) {
  auto* h = reinterpret_cast<Handle*>(hp);
  if (!h || T <= 0) return -1;
  const int C = h->dec->in_channels(), upp = h->dec->upp(), pf = h->dec->out_per_frame();
  const jsize nz = static_cast<jsize>(C) * T, nh = static_cast<jsize>(T) * upp, no = static_cast<jsize>(T) * pf;
  if (env->GetArrayLength(jz) < nz || env->GetArrayLength(jhar) < nh || env->GetArrayLength(jout) < no) {
    g_err = "array size";
    return -1;
  }
  h->z.resize(nz);
  h->har.resize(nh);
  h->out.resize(no);
  env->GetFloatArrayRegion(jz, 0, nz, h->z.data());
  env->GetFloatArrayRegion(jhar, 0, nh, h->har.data());
  const float* cond = nullptr;
  if (jcond) {
    const jsize nc = h->dec->pre_channels();
    if (env->GetArrayLength(jcond) < nc) {
      g_err = "cond size";
      return -1;
    }
    h->cond.resize(nc);
    env->GetFloatArrayRegion(jcond, 0, nc, h->cond.data());
    cond = h->cond.data();
  }
  try {
    h->dec->run(h->z.data(), T, h->har.data(), cond, h->out.data());
  } catch (const std::exception& e) {
    g_err = e.what();
    return -1;
  }
  env->SetFloatArrayRegion(jout, 0, no, h->out.data());
  return no;
}

JNIEXPORT void JNICALL Java_com_taocrypt_micmask_NeonVocoder_nFree(JNIEnv*, jclass, jlong hp) {
  delete reinterpret_cast<Handle*>(hp);
}

}  // extern "C"
