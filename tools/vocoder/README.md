# 原生 NEON fp16 声码器：离线验证工具

App 端实现：`app/src/main/cpp/vocoder/`（内核移植自 [nasuiyile/mobile-rvc](https://github.com/nasuiyile/mobile-rvc)，MIT，见 NOTICE.md）、
`core/.../VocoderSplit.kt`（在手机上从流式合成器 ONNX 拆出前半图 + fp16 dec 权重）、`app/.../NeonVocoder.kt`（JNI 与缓存）、`LiveRvc.kt`（开关 `ai_vocoder`）。

## 拆分（PC/JVM，与手机同一份代码）

```sh
TASK=":cli:installDist" sh build.sh          # 或 ./gradlew :cli:installDist
java -cp "cli/build/install/cli/lib/*" micmask.cli.VocTestKt 模型_stream_qdq.onnx 输出目录
# → 输出目录/pre.onnx（ORT 前半图）check.onnx（原图 + z/har/cond 输出）dec.mmvoc（fp16 权重）names.txt
```

## 主机端编译 voc_cli（Linux arm64，fp16 需 CPU 支持 asimdhp；x86_64 去掉 -march 只能跑 fp32 标量路径）

```sh
S=app/src/main/cpp/vocoder
clang++ -std=c++17 -O3 -march=armv8.2-a+fp16 -c $S/conv_f16.cpp -o /tmp/f16.o
clang++ -std=c++17 -O3 $S/conv_f32.cpp $S/native_decoder.cpp tools/vocoder/voc_cli.cpp /tmp/f16.o -lpthread -o /tmp/voc_cli
```

## 对比

```sh
python3 tools/vocoder/compare.py 输出目录 /tmp/voc_cli calib.npz --threads 2 --n 4
```

`compare.py` 用 PyTorch 独立实现一份 fp32 参考（直接读 ONNX 权重并反量化，不经过 Kotlin 提取代码），输出：
`neon16_vs_ref32`（fp16 内核误差）、`neon32_vs_ref32`、`ref32_vs_ort`（现有 ORT QDQ 整图的 dec 与 fp32 的差距）及耗时。

## 2026-11-25 主机实测（Neoverse-V2 arm64，2 核，非手机）

| 模型 | NEON fp16 vs fp32 参考 SNR | NEON fp32 vs 参考 | ORT QDQ dec vs 参考 | NEON fp16 2 线程 | ORT 整图 2 线程（其中前半图） |
|---|---|---|---|---|---|
| 初初 v2 40k L35（convert_rvc.py） | 41–49 dB | 58–67 dB | 0.2–13.5 dB | ≈158 ms | ≈109 ms（≈12 ms） |
| atri48 v2 48k L35（旧版导出） | 45–55 dB | 61–74 dB | 13.6–22.9 dB | ≈192 ms | ≈129 ms（≈12 ms） |
| KIKI v1 40k L35（手机 .pth 导入模板，cond 未融合） | 50–51 dB | 69 dB | 8.6–13.8 dB | ≈160 ms | ≈110 ms（≈12 ms） |

结论：数值正确；但在这台主机上 ORT 的 int8 QDQ 卷积（i8mm/dotprod）比 fp16 内核快约 1.5 倍，所以「自动」档会在加载时实测两者、只在 NEON 更快时启用。手机上的快慢要真机测。
