# MicMask

安卓端实时变声器：在手机本地把你的声音实时变成另一个音色，直接送进 QQ、微信、游戏等应用的麦克风。

- **AI 变声**：端侧实时 RVC，ONNX Runtime 推理，不联网
- **音调变声**：低延迟的升降调
- **虚拟声卡 / 语音包**：一键播放音效进麦克风，支持 `.mim3` 效果包导入与制作
- **文本转语音**
- **麦克风注入**：借助 [Shizuku](https://shizuku.rikka.app/) 以 shell 身份注入麦克风，无需 Root；没有 Shizuku 时回退为外放录播
- **检查更新**：App 内从 GitHub Releases 更新，国内可用下载镜像

## 下载

到 [Releases](https://github.com/Taocrypt/MicMask/releases) 下载 `MicMask-<版本>-android-arm64-v8a.apk`。需要 Android 8.0+、arm64 设备。

## 模型

MicMask **不附带任何音色模型**。在「设置 → 模型仓库」填写一个 models.json 地址，或在模型页导入本地模型。请只使用你本人的声音或已获得授权的模型。

## 构建

```sh
./gradlew :app:assembleDebug
```

需要 JDK 17、Android SDK（compileSdk 37）。发版签名通过 `-PsigningProps=signing.properties`（storeFile / storePassword / keyAlias / keyPassword）传入，签名文件不要放进仓库。

推送 `v<版本号>` 标签后，GitHub Actions 会自动构建并发布 Release，说明取自 [CHANGELOG.md](CHANGELOG.md)。

## 为什么没有 NPU 加速

MicMask 的 AI 变声目前只用 CPU 推理，暂不引入高通 QNN（NPU/GPU）支持。原因来自骁龙 8 Gen 3 真机实测（2026-11-23）：

- CPU 已经够快：前台 4 线程下 hubert 约 64–68ms、音色合成器约 115–124ms，远低于 300ms 延迟目标。
- GPU（QNN）能跑，但只能吃到模型的一部分，反而比 CPU 慢 3–6 倍。
- NPU（QNN HTP）在普通 App 进程里可以调用，但现有模型的算子（动态形状、MatMulInteger、QLinearConv 等）大多不被支持；转成静态 QDQ 后 hubert 能整图上 NPU，却要约 860ms/次且输出偏差很大；合成器在 QNN 上无法建会话。
- 引入 QNN 运行库会让安装包从约 33MB 增大到约 75MB，而目前换不来任何收益。

完整数据、日志与模型转换脚本见 [qnntest/results](qnntest/results/README.md)。

### 自己测一测

如果你的设备是骁龙 8 Gen 2 / 8 Gen 3 / 8 Elite 等带 HTP 的高通芯片，欢迎帮忙测试 NPU 能不能跑出突破：

1. 从 [qnntest-3 测试包](https://github.com/Taocrypt/MicMask/releases/tag/qnntest-3) 下载 `MicMask-qnntest-3.apk`（独立包名，不影响正式版）和 `hubert_qnn_a16w8.onnx`。
2. 安装后点「选择模型文件」导入 onnx；装了 Shizuku 和正式版的，也可以「从 MicMask 复制模型」。测试时保持界面在前台，先跑 App 进程，有 Shizuku 再跑 shell 进程。
3. 点「复制日志」，贴到 [Issue](https://github.com/Taocrypt/MicMask/issues) 里，标题写上机型和芯片。如果某个模型在 NPU 上比 CPU 快且误差小，我们会重新评估加入 NPU 支持。

自己转换模型可参考 `qnntest/convert/` 下的脚本。

## 免责声明

- 本项目仅供学习与娱乐。禁止用于冒充他人、诈骗、骚扰或任何违法用途，使用者自行承担全部责任。
- 使用他人声音模型前须取得权利人授权。依法需要标识的合成内容，请按要求标识。
- 已知限制：目标 300 ms 延迟尚未达成；量化模型有音质损失；外放场景可能啸叫；部分机型注入不可用。

## 赞助

MicMask 完全免费、开源。喜欢的话可以在 [爱发电](https://afdian.com/a/taocrypt) 支持一下，赞助者会出现在 App 的赞赏墙上。

## 许可

[GPL-3.0](LICENSE)。上游与第三方声明见 [NOTICE.md](NOTICE.md)。
