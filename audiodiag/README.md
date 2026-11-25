# audiodiag —— MicMask 音频注入诊断工具

独立的调试 App（包名 `com.taocrypt.micmask.audiodiag`），用来排查 MicMask 的注入通道
（`AudioPolicy` 注入轨道）在某些机型 / 系统上建不起来的问题，最初是为了 Android 17（API 37）
一加机型上 `createAudioTrackSource` 抛 `UnsupportedOperationException: Cannot create AudioTrack` 做的。

不影响正式版：独立包名，CI（`.github/workflows/build.yml`）只构建 `:app`，不构建本模块。
本工具不录音，也不会播放可听见的声音（测试轨道只写 20ms 静音）。

## 它测什么

通过 Shizuku 以 shell 身份（uid 2000）用 `app_process` 拉起 `ShellDiag`（与 MicMask 自启注入进程同一方式），依次测试：

| 编号 | 内容 |
|---|---|
| `APP-MEDIA` | 对照：App 进程（前台 Activity）普通 `USAGE_MEDIA` AudioTrack |
| `S-MEDIA` / `S-MEDIA-CTX` | 对照：shell 进程普通 `USAGE_MEDIA` AudioTrack（不设 / 设 shell 身份 Context） |
| `SRC-<规则>-<匹配>-<格式>` | `AudioPolicy.createAudioTrackSource(mix)`；规则 = `ROLE`（`setTargetMixRole(MIX_ROLE_INJECTOR)`）或 `TYPE`（`setTargetMixType(MIX_TYPE_RECORDERS)`）；匹配 = `UID`（微信 uid，没装微信用本 App uid）或 `PRESET`（录音源 CAPTURE_PRESET 1/5/6/7/9） |
| `MAN-<路径>-<格式>` | 绕开 `createAudioTrackSource`，按 AOSP 实现手建注入轨道（`USAGE_VIRTUAL_SOURCE` + `addTag("addr=<mix registration>")`）：`CTOR` = 旧构造函数（同 AOSP 主线）、`BLD` = `AudioTrack.Builder`、`BLDCTX` = Builder + `setContext(shell 身份)` |

格式：`48s16` 48k 立体声 16bit、`48m16` 48k 单声道 16bit、`16m16` 16k 单声道 16bit、`48sF` 48k 立体声 float。

每项记录成功/失败、异常全文、track state / playState / 写入返回值；失败时附上该项开始后
`logcat -d -v threadtime -t 2000` 中匹配 `AudioFlinger|AudioPolicy|APM_|AudioTrack|AudioHardening|audio_hw|AudioSystem` 的行。

报告开头包含设备型号、SoC、Android 版本 / SDK、ROM 指纹（`ro.build.fingerprint`、`ro.build.display.id`）、
Shizuku 版本与 uid、shell 权限检查、`myAttributionSource()`、`dumpsys audio` 中与 hardening 相关的状态。

### 可选：临时关闭音频加固后再测一遍

勾选后会**临时修改系统设置**：记录 `dumpsys audio` 中的加固状态 → 执行
`cmd audio set-enable-hardening disable` → 把全部组合再测一遍 → 无论成败都执行
`cmd audio set-enable-hardening enable` 恢复。若诊断中途 App 被杀、来不及恢复，下次打开本 App 会自动补执行 enable。

注意：恢复固定执行 `enable`。如果设备原本处于 `disable` 或 `throw` 模式，测完后会变成 `enable`，请对照报告里「关闭前状态」自行调整。

## 用法

1. 安装 APK（与 MicMask 同一签名）。
2. 打开 Shizuku 并启动服务；打开本 App，点「开始诊断」，在弹出的 Shizuku 授权框里允许，然后再点一次「开始诊断」。
3. 保持亮屏、本 App 在前台，等 1–2 分钟直到显示「诊断结束」。
4. 点「分享 txt」把报告发给开发者（也可「复制」，或「保存到 Download」写到 `Download/MicMask/`）。

## 构建

```sh
./gradlew :audiodiag:assembleRelease -PsigningProps=<signing.properties>
```

在 aarch64 Linux 上用替换的 aapt2 构建时，release 的资源优化步骤可能产出空包（APK 里没有 AndroidManifest.xml），
需要加 `-Pandroid.enableResourceOptimizations=false`。
