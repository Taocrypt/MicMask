# MicMask 电脑版（Windows）

Compose Desktop + Kotlin/JVM（界面 miuix），与手机 MicMask 用 MicMask Link v1 互传声音。使用说明见 [docs/desktop-manual.md](../docs/desktop-manual.md)。

## 目录
- `src/main/kotlin/…/desktop/`：界面（Ui.kt）、传音引擎（DeskLink.kt）、音频（AudioIO.kt、NativeAudio.kt、Gain.kt、DeskPlayout.kt）、
  发现（Discovery.kt）、链接/二维码（LinkUri.kt）、Beacon（DeskBeacon.kt）、更新（Updater.kt）、日志（DeskLog.kt）、自测（SelfTest.kt）
- 传音协议源码与手机共用：构建时从 `app/src/main/kotlin/com/taocrypt/micmask/link/` 复制（见 build.gradle.kts 的 linkShared），改协议只改 app/ 里的原件
- `native/`：mmaudio.dll（C++，WASAPI 共享模式 + 按进程抓声音），`native/build.cmd` 用 MSVC 构建到 `resources/windows/`
- `resources/windows/`：随安装包放进 app 资源目录的文件（mmaudio.dll、CI 下载的 VB-CABLE 官方包）
- `packaging/`：图标、签名脚本 sign.ps1

## 构建
- CI：`.github/workflows/desktop.yml`（windows-latest）：编 mmaudio.dll → 可选签名 → `packageReleaseMsi packageReleaseExe`（ProGuard + jlink）→ 可选签名 → 解包 MSI 跑 `MicMask.exe --smoke`
- 本地（Windows）：`native\build.cmd`，然后 `gradlew packageReleaseMsi`；其它系统可以 `gradlew run --args="--smoke --no-window"` 跑无界面自测（native 部分自动回退）
- 版本号：`-PappVersion=0.1.0-alpha.2`；标签 `win-v*` 发 Release（一律预发布、不设 Latest）
- 统计后台：环境变量 `BEACON_URL` / `BEACON_APP_ID` / `BEACON_KEY`（CI 用 secrets.BEACON_URL、BEACON_APP_ID、DESKTOP_BEACON_KEY），任一为空则整体关闭
- 签名：见 [docs/desktop-signing.md](../docs/desktop-signing.md)

更新日志：[CHANGELOG.md](CHANGELOG.md)
