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
