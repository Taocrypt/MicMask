# audiodiag —— MicMask 音频注入诊断工具

独立的调试 App（包名 `com.taocrypt.micmask.audiodiag`），用来排查 MicMask 的注入通道
（`AudioPolicy` 注入轨道）在某些机型 / 系统上建不起来的问题，最初是为了 Android 17（API 37）
一加机型上 `createAudioTrackSource` 抛 `UnsupportedOperationException: Cannot create AudioTrack` 做的。

不影响正式版：独立包名，CI（`.github/workflows/build.yml`）只构建 `:app`，不构建本模块。
本工具不录音，也不会播放可听见的声音（测试轨道只写 20ms 静音）。

## 它测什么（audiodiag-2）

通过 Shizuku 以 shell 身份（uid 2000）用 `app_process` 拉起 `ShellDiag`（与 MicMask 自启注入进程同一方式），依次测试约 48 项，每项 15 秒超时。
报告**最前面**是「能用的组合」摘要：列出注入 track 初始化成功（`STATE_INITIALIZED`）且 `play()` 后写入 20ms 静音返回 >0、`playState=PLAYING` 的组合；
初始化成功但写入/播放异常的单独列出（`PART`），另附 usage 对照、对照项、各轮统计。下面是完整明细。

### 身份

- `PKG`：本包包上下文（`createPackageContext(本包)`）外包 `ShellContext`（包名伪装 `com.android.shell`，同 audiodiag-1 / MicMask 现用）。
- `SH`：SRVR 式。`systemContext.createPackageContext("com.android.shell", CONTEXT_IGNORE_SECURITY)` 作基底，
  `AttributionSource.Builder(2000).setPackageName("com.android.shell")`，API 34+ 再 `setPid(myPid)`。

身份同时用于 `AudioPolicy.Builder(ctx)`（以及对应的 `AudioManager`）和 `AudioTrack.Builder.setContext`。

### mix

| 记号 | 规则 |
|---|---|
| `UID` | `MIX_ROLE_INJECTOR` + `RULE_MATCH_UID(目标 uid)`（微信 uid，没装微信用本 App uid；MicMask 现用） |
| `PRE5` | `MIX_ROLE_INJECTOR` + `CAPTURE_PRESET` 1/5/6/7/9 |
| `TUID` / `TPRE5` | 同上但用 `setTargetMixType(MIX_TYPE_RECORDERS)` |
| `SRVR` | SRVR 式：`MIX_ROLE_INJECTOR` + `CAPTURE_PRESET` DEFAULT/MIC/CAMCORDER/VOICE_RECOGNITION/VOICE_COMMUNICATION/UNPROCESSED/VOICE_PERFORMANCE，排除自身与本 App uid（`excludeMixRule(RULE_MATCH_UID, uid)`，没有就 `addMixRule(RULE_EXCLUDE_UID, uid)`） |

全部 `ROUTE_FLAG_LOOP_BACK`。

### 编号

- `ATTR-SYS` / `ATTR-USG`：只建 `AudioAttributes`，分别用反射 `setSystemUsage(USAGE_VIRTUAL_SOURCE)` 和公开 `setUsage(USAGE_VIRTUAL_SOURCE)`，看哪个被拒。
- `APP-MEDIA`、`S-MEDIA`、`S-MEDIA-PKG`、`S-MEDIA-SH`：对照，普通 `USAGE_MEDIA` 轨道（App 进程 / shell 不设 Context / 设 PKG / 设 SH）。
- `SRC-<身份>-<mix>-<格式>`：`AudioPolicy.createAudioTrackSource(mix)`。
- `MAN-<身份>-<mix>-<路径>-<usage>-<性能>-<缓冲>-<格式>`：绕开 `createAudioTrackSource` 手建注入轨道
  （`USAGE_VIRTUAL_SOURCE` + `addTag("addr=<mix registration>")`，轨道格式取 `mix.getFormat()`，IN_MONO 换成 OUT_MONO）。
  - 身份：`SH`、`PKG`，或交叉 `SHxPKG`（策略 SH、轨道 PKG）/ `PKGxSH`
  - 路径：`CTOR` 旧构造函数（不带 Context、不能设性能模式）、`BLD` Builder 不设 Context、`BLDCTX` Builder + `setContext`
  - usage：`SYS` 反射 `setSystemUsage`，`USG` 公开 `setUsage`（audiodiag-1 只有后者）
  - 性能：`LL` `PERFORMANCE_MODE_LOW_LATENCY`、`NO` `PERFORMANCE_MODE_NONE`、`DEF` 不设
  - 缓冲：`CB` 紧凑 `max(minBuf, 2×10ms 帧)`，`MIN` = `minBuf`

格式：`48s16` 48k 立体声 16bit、`48m16` 48k 单声道 16bit、`48mi16` mix 用 `CHANNEL_IN_MONO`、`16m16` 16k 单声道、`48sF` 48k 立体声 float。

分组（报告里每项前标注）：A `createAudioTrackSource`（11 项）、B SRVR 复刻（SH + SRVR mix + BLDCTX + 紧凑缓冲，SYS/USG × LL/NO）、
C 同 B 换 PKG 身份、D 同 B 换 UID 规则、E 交叉身份、F 建轨路径、G 缓冲/性能、H 格式、I audiodiag-1 旧 MAN 项（修 usage 后与 setUsage 对照）。

每项记录成功/失败、异常全文、mix `getFormat()`、attrs 的 usage / systemUsage / tags、track state / performanceMode / playState / 写入返回值 / 路由；
失败或部分成功时附上该项开始后 `logcat -d -v threadtime -t 2000` 中匹配 `AudioFlinger|AudioPolicy|APM_|AudioTrack|AudioHardening|audio_hw|AudioSystem|AudioAttributes` 的行。

报告开头包含设备型号、SoC、Android 版本 / SDK、ROM 指纹（`ro.build.fingerprint`、`ro.build.display.id`）、
Shizuku 版本与 uid、shell 权限检查、`myAttributionSource()`、PKG / SH 两种身份的 AttributionSource、`setSystemUsage` / `excludeMixRule` 是否存在、
`dumpsys audio` 中与 hardening 相关的状态。

### 可选：临时关闭音频加固后再测一遍

勾选后会**临时修改系统设置**：记录 `dumpsys audio` 中的加固状态 → 执行
`cmd audio set-enable-hardening disable` → 把全部组合再测一遍 → 无论成败都执行
`cmd audio set-enable-hardening enable` 恢复。若诊断中途 App 被杀、来不及恢复，下次打开本 App 会自动补执行 enable。

注意：恢复固定执行 `enable`。如果设备原本处于 `disable` 或 `throw` 模式，测完后会变成 `enable`，请对照报告里「关闭前状态」自行调整。

## 用法

1. 安装 APK（与 MicMask 同一签名）。
2. 打开 Shizuku 并启动服务；打开本 App，点「开始诊断」，在弹出的 Shizuku 授权框里允许，然后再点一次「开始诊断」。
3. 保持亮屏、本 App 在前台，等 2–4 分钟（勾了关加固再测约翻倍）直到显示「诊断结束」，报告会回到顶部显示摘要。
4. 点「分享 txt」把报告发给开发者（也可「复制」，或「保存到 Download」写到 `Download/MicMask/`）。

## 构建

```sh
./gradlew :audiodiag:assembleRelease -PsigningProps=<signing.properties>
```

在 aarch64 Linux 上用替换的 aapt2 构建时，release 的资源优化步骤可能产出空包（APK 里没有 AndroidManifest.xml），
需要加 `-Pandroid.enableResourceOptimizations=false`。

## 致谢

audiodiag-2 的 SH 身份（`com.android.shell` 包上下文 + `ShellIdentityContext`）、SRVR 式 mix（多 CAPTURE_PRESET + 排除 uid）、
先手建注入轨道（`setSystemUsage(USAGE_VIRTUAL_SOURCE)`、低延迟性能模式、紧凑缓冲）再以 `createAudioTrackSource` 兜底的思路，
来自 [SR-Voiceover-Roulette](https://github.com/assdazz/SR-Voiceover-Roulette--)（`ShizukuVirtualMicService.java`），
MIT 许可，Copyright (c) 2026 ChiZhou (迟昼)。改写的代码在 `src/main/kotlin/com/taocrypt/micmask/audiodiag/SrvrStyle.kt`，文件头附 MIT 许可全文。
