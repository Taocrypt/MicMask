# QNN 加速测试记录

设备：vivo V2329A · 骁龙 8 Gen 3（SM8650，HTP v75）· Android 14 · ORT `onnxruntime-android-qnn` 1.22.0 + `qnn-runtime` 2.33.0。
数值为 5 次平均 ms/次；输入：hubert 16320 采样点（约 1s@16k），合成器 100 帧。误差为与同模型 CPU 输出的最大相对误差。

## 结论（2026-11-23）
- **正式版继续走 CPU**，P1-6（QNN 依赖）暂缓。前台 CPU：hubert 约 64–68ms、合成器约 115–124ms，远低于 300ms 目标。
- GPU（QNN）在 App 进程可用（需 `uses-native-library libOpenCL.so`），但只吃到图碎片，比 CPU 慢 3–6 倍。
- NPU（QNN HTP）在 App 进程**可达**：hubert 静态 QDQ 模型整图（禁回退）建会话并执行成功；但 861ms/次、误差 1.17，又慢又错，不可用。
- 现有模型（MatMulInteger、QLinearConv、动态形状）HTP 不支持；合成器转静态后 QNN 建会话报 `SafeInt Integer overflow`（未定位）。

## qnntest-3（1e25d67）—— App 前台 + DSP 路径 + OpenCL 修复后
| 模型 | 进程 | CPU | GPU(QNN) | HTP | HTP 整图 |
|---|---|---|---|---|---|
| hubert_qnn_a16w8 | App | 163.9 | FAIL（NHWC Conv） | 866.9（误差 1.26） | 861.1（误差 1.17） |
| synth_qnn_a16w8 | App | 381.5 | FAIL（SafeInt） | FAIL | FAIL |
| synth_f32_static | App | 322.9 | FAIL | FAIL | FAIL |
| hubert_dyn8 | App | 68.5 | 215.0（0.130） | FAIL（6031） | FAIL（有 CPU 节点） |
| rmvpe_q8 | App | 37.2 | 93.2（0.136） | FAIL（6031） | FAIL |
| atri48_stream_qdq | App | 115.0 | CRASH | CRASH | CRASH |
| atri48_stream_b50_qdq | App | 190.5 | CRASH | CRASH | CRASH |
| hubert_qnn_a16w8 | shell | 182.7 | FAIL | ~865 后 rc=139 | ~860 后 rc=139 |
| hubert_dyn8 | shell | 63.4 | 365.4 | CRASH | CRASH |
| rmvpe_q8 | shell | 35.5 | 201.7 | CRASH | CRASH |
| atri48_stream_qdq | shell | 124.0 | CRASH | CRASH | CRASH |
| atri48_stream_b50_qdq | shell | 193.4 | CRASH | CRASH | CRASH |

## qnntest-2（3749a90）—— App 测试进程在后台被降频
App：hubert_dyn8 CPU 832 / GPU 824 / HTP 830（实为回退 CPU）；shell：hubert_dyn8 CPU 64.5、GPU 413、HTP 执行错 6031；合成器 GPU/HTP 段错误。
原因：`:probe` 进程在后台；libOpenCL 不在 app 命名空间；`:probe` 未设 `ADSP_LIBRARY_PATH`。

## qnntest-1（a5c9e0e）
shell：hubert/rmvpe QNN “Failed to finalize QNN graph”；atri48 GPU 段错误 rc=139。

## 模型转换（convert/）
- `deq_hubert.py`：hubert_dyn8 的 MatMulInteger+DynamicQuantizeLinear 还原为 fp32 MatMul，固定 T=16320；`fold.py` 常量折叠。
- `qdq.py`：QNN QDQ 量化（A16W8，MinMax，10 段校准）。hubert A16W8 与 fp32 余弦 0.973（dyn8 为 0.986）；A8W8 余弦 0.085，不可用。
- `synth.py`：合成器随机算子（RandomNormalLike/RandomUniform）替换为常量后转静态，仅用于测速，A16W8 SNR 16.3dB。
- 合成器模型来自角色音色，按项目规则不放入公开仓库。

## 下一步（仅测试包）
fp16 hubert 走 HTP；定位合成器 SafeInt 溢出；shell 进程 HTP 结束时 rc=139。

## qnntest-4 待测项（2026-11-25，本地构建，未提交、未发版）
目标：按 mobile-rvc 的做法改写声码器图，fp32 图交给 QNN HTP 以 fp16 执行（`enable_htp_fp16_precision=1`）。

**转换**（`convert/dec_rewrite.py`，产物在本机 `/workspace/work/qnnconv/dec4/`，角色音色衍生模型不进仓库）
- 去量化（权重 DQ 折成 fp32，激活 Q→DQ 删除）→ 截取 dec（输入 z[1,192,35]、har[1,1,35×upp]，T=100 档）→ 改写：
  - noise_convs.3（k1）→ Mul+Add；noise_convs.0/1/2（k=2s, stride s）→ har 多相分解 + s 通道 3 抽头 Conv；
  - 40k→48k 尾巴 `/rs/ConvTranspose`（1→1，up=6，K=144）→ 24 个 Slice + Concat + MatMul[6×24] + 交织（MicMask 独有）；
  - 说话人条件：现有模型 cond 已折进 conv_pre bias、flow 的 cond 也已折叠，没有可改的长度 1 卷积。
- host 验证（ORT CPU，3 组 jfk 特征）：改写前后 SNR 120–130 dB，最大绝对误差 ≤6e-7（40k v2、48k v2 各一个音色）；
  整合成器改写版与同噪声未改写版 SNR 120–130 dB。host 上 onnxconverter fp16 近似与 fp32 比 29–35 dB（不代表 HTP）。

**APK**：`qnntest-4.apk`（ORT 1.22.0 + QNN 2.33.0，同 qnntest-3）；对照包 `qnntest-4-ort129.apk`（ORT 1.29.0 + QNN 2.42.0，mobile-rvc 用的版本），同包名，覆盖安装切换。

**真机步骤**：「1'. 选择模型文件」导入下列文件（每个 .onnx 连同它的 `.onnx.inputs`），然后点「4. qnntest-4（App 进程）」，可选「5.（shell）」，最后「复制日志」。
| 文件 | 作用 |
|---|---|
| `<音色>_dec_rw_notail.onnx` | 改写后的声码器（到 tanh，不含 48k 尾巴）——首要测试项 |
| `<音色>_dec_rw.onnx` | 改写后的声码器（含改写过的 48k 尾巴） |
| `<音色>_synth_rw.onnx` | 改写后的整合成器（随机算子换常量），看整图能否上 HTP |
| `<音色>_dec_f32.onnx` | 未改写的 fp32 声码器（对照：预计 HTP 拒） |
| `hubert_f32_static.onnx` | fp32 hubert（T=16320），fp16 跑 HTP 的对照 |

档位：CPU 4 线程 / NPU fp16（可回退）/ NPU fp16 整图（禁回退）/ NPU fp16 整图+上下文缓存（finalization mode 3，先生成再从缓存加载）。
每项记录：建会话成功与否和耗时、首轮、稳态 p50/p95（20 次）、与 CPU 输出 SNR；最后一行「qnntest-4 汇总」。
要看：整图（禁回退）能否建会话；日志里的 `partition`/`supported` 行（QNN 吃下多少节点）；SafeInt 是否还出现；p50 和 CPU 比；SNR（参考：NEON fp16 41–55 dB）。

| 模型 | 进程 | ORT | CPU p50 | NPU fp16 | NPU 整图 | 整图+缓存 | SNR |
|---|---|---|---|---|---|---|---|
| （待填） | | | | | | | |

## qnntest-4 真机结果（vivo V2329A，SM8650，Android 14，ORT 1.22 + QNN 2.33，2026-11-25）
App 进程（p50，T=100 档 dec，真实输入）：
| 模型 | CPU 4 线程 fp32 | HTP fp16 | HTP 整图禁回退 | HTP+上下文缓存 | 与 CPU SNR |
|---|---|---|---|---|---|
| dingzhen_dec_rw_notail | 321.5 ms | 41.0 ms | 40.3 ms | 39.1 ms（加载 192 ms） | 11.8 dB |
| dingzhen_dec_rw | 333.6 ms | 41.1 ms | 41.2 ms | 40.5 ms（加载 189 ms） | 11.8 dB |
| atri48_dec_rw | 352.6 ms | 45.7 ms | 48.7 ms | 43.7 ms（加载 195 ms） | 15.3 dB |
- 整图禁回退能建会话，SafeInt 未再出现；首次编译 4–5 s，上下文缓存生成 6–10 s（约 31 MB）。
- 精度不达标：所有 HTP 档 SNR 相同（11.8 / 15.3 dB，最大相对误差 0.83 / 0.22），低于 20 dB；host fp16 模拟为 29–35 dB。
- shell 进程：HTP 能跑完计时（p50 约 38–40 ms），但每项结束后 Segmentation fault（rc=139），疑在会话释放/退出时（inferred）。
- ORT 1.29 对照包未测。

## qnntest-4-ort129（ORT 1.29 + QNN 2.42）· vivo V2329A SM8650 · 2026-11-25 18:26
- SNR vs CPU：dingzhen 32.9 dB（最大相对误差 0.024），atri48 34.1 dB（0.029）。ORT1.22/QNN2.33 为 11.8/15.3 dB → 精度问题出在旧 QNN 后端（observed）。
- App 进程 HTP p50：dingzhen_notail 38.5 / 整图 39.0 / 缓存 36.6 ms；atri48 45.3 / 44.9 / 43.9 ms。CPU fp32 279–363 ms。
- shell 进程（uid 2000）：HTP 全部正常完成并打印 SNR，无 segfault（observed）。p50 dingzhen 36.6–38.7、atri48 43.6–45.2 ms。
- 缓存生成 6.6–9.1 s，加载 173–204 ms；首次编译 3.9–5.6 s。

## qnntest-6（2026-11-25，vivo V2329A SM8650 Android 14，ORT 1.29 + 外置 QNN 2.42 V75 包）
APK 不带 libQnn*.so，QNN 包 zip 导入；App 从 files/qnn 加载，shell 从 /data/local/tmp/micmask-qnn 加载（observed，/proc/self/maps 确认映射的是外置目录）。
- 全部 12 项 ×2 进程完成，无崩溃；SNR 与 qnntest-4 相同（32.9 / 34.1 dB）。
- 「无编译器·仅缓存」（去掉 libQnnHtpPrepare.so，maps 中无 Prepare）App 与 shell 均能跑：建会话 223–289 ms，p50 35.9–44.0 ms。
- p50（App/shell，ms）：dingzhen_notail CPU 236/257、HTP 39.0/38.6、缓存 36.1/36.1、仅缓存 36.0/35.9；atri48 CPU 309/343、HTP 45.0/44.5、缓存 43.7/43.2、仅缓存 43.9/43.9；dingzhen_dec_rw CPU 264/287、HTP 38.7/38.9、缓存 36.8/36.4、仅缓存 36.8/36.7。
- 缓存生成 5.7–8.8 s；首次整图建会话 3.4–5.5 s。
结论：按需下载可行；生成缓存后可删编译器，运行只需约 6.4 MB 库。
