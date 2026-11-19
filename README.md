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

## 免责声明

- 本项目仅供学习与娱乐。禁止用于冒充他人、诈骗、骚扰或任何违法用途，使用者自行承担全部责任。
- 使用他人声音模型前须取得权利人授权。依法需要标识的合成内容，请按要求标识。
- 已知限制：目标 300 ms 延迟尚未达成；量化模型有音质损失；外放场景可能啸叫；部分机型注入不可用。

## 赞助

MicMask 完全免费、开源。喜欢的话可以在 [爱发电](https://afdian.com/a/taocrypt) 支持一下，赞助者会出现在 App 的赞赏墙上。

## 许可

[GPL-3.0](LICENSE)。上游与第三方声明见 [NOTICE.md](NOTICE.md)。
