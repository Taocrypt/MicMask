# MicMask Tailscale 插件（在线下载）

让 MicMask 的「传音」在不同网络之间（手机 4G ↔ 家里电脑、两部不在同一 Wi-Fi 的手机）也能直连：
App 在进程内跑一个**用户态 Tailscale 节点**（tsnet，不需要 VPN 权限、不占系统 VPN 槽位），
再把 tailnet 端口与本机 `127.0.0.1` 端口互相转发，MicMask 现有的 `java.net` 套接字代码不用改。

**插件不随 APK / 电脑版安装包分发**：用户在 App 里开启时才按 `manifest.json` 下载对应平台的库，
校验 SHA-256 后加载。没开就零体积、零网络请求。

| 平台 | 文件 | 加载方式 |
|---|---|---|
| Android arm64-v8a | `micmask-tailscale-<ver>-android-arm64-v8a.so`（落地名 `libmicmask_tailscale.so`） | `System.load` + JNI |
| Windows x64 | `micmask-tailscale-<ver>-windows-x64.dll`（落地名 `micmask_tailscale.dll`） | `System.load` + JNI |

两个平台用同一套 Kotlin 代码（`kotlin/`），不依赖 JNA，也不需要额外的胶水 .so。

## 目录

```
plugins/tailscale/
  VERSION                 插件基础版本（CI 分支构建为 <VERSION>-dev.<run>，标签 ts-plugin-v<ver> 为正式）
  native/                 Go 源码（package main，-buildmode=c-shared）
    core.go               启动/状态/expose/dial/停止/登出 + 纯 C ABI（mmts_*）
    jni.go                JNI 入口（Java_com_taocrypt_micmask_ts_TsNative_*）
    ifaces.go hooks_*.go  Android 网卡列表注入（见「Android 注意事项」）
    plugin_test.go        进程内控制服务器 + DERP 的回环集成测试
    include/win32/jni_md.h  Linux 上交叉编译 Windows DLL 用
  kotlin/com/taocrypt/micmask/ts/
    TsBridge.kt           接口 TsBridge、TsStatus、TsInterfaces
    TsPlugin.kt           清单下载、SHA-256 校验、安装、加载
    TsNative.kt           JNI 声明（包名/类名固定，勿改）
  kotlin-test/TsSmoke.kt  JVM 冒烟（CI 用，不进 App）
  scripts/make_manifest.py  生成 manifest.json
  scripts/collect_licenses.py  生成第三方许可证合集
```

## 为什么不直接用 libtailscale 的 C API

[libtailscale](https://github.com/tailscale/libtailscale)（BSD-3-Clause）就是把 `tailscale.com/tsnet` 用 cgo 编成
C 库。它的 `tailscale_listen` / `tailscale_accept` / `tailscale_dial` 把连接以 socketpair(2) + SCM_RIGHTS
传递的文件描述符交给 C（源码 `fd_linux.go` / `fd_other.go` 使用 `syscall.Socketpair`、`golang.org/x/sys/unix`），
**在 Windows 上无法编译**；而且 MicMask 要的是「转发到 127.0.0.1:端口」而不是裸 fd。

所以本插件与 libtailscale 一样直接构建在 `tsnet` 上（版本钉在与 libtailscale 当前 go.mod 相同的
`tailscale.com v1.104.1`），转发逻辑写在 Go 里，跨边界只传整数和字符串。libtailscale 的
`tailscale_status_json`、登录 URL 读取方式（`ipnstate.Status.AuthURL`）与本插件一致。

## API

### Kotlin（`com.taocrypt.micmask.ts`）

```kotlin
interface TsBridge {
    fun start(stateDir: String, hostname: String, authKey: String? = null,
              controlUrl: String? = null, ephemeral: Boolean = false, waitMs: Int = 15_000): String?  // 返回登录 URL；null = 已在线
    fun status(withPeers: Boolean = false): TsStatus     // running, state, ips, loginUrl, self, tailnet, health, peers, exposed, dials, error
    fun expose(port: Int, udp: Boolean, localPort: Int = port)   // tailnet:port -> 127.0.0.1:localPort
    fun unexpose(port: Int, udp: Boolean)
    fun dial(host: String, port: Int, udp: Boolean): Int         // 返回本机 127.0.0.1 端口 -> tailnet host:port
    fun undial(localPort: Int, udp: Boolean)
    fun stop()                                                   // 保留登录状态
    fun logout(wipe: Boolean = true)                             // 从 tailnet 登出并停止；wipe 删除 tsnet 状态文件
    fun setInterfaces(json: String = TsInterfaces.currentJson()) // 仅 Android 生效
    fun setLogFile(path: String?)
    val pluginVersion: String
}

object TsPlugin {
    const val ABI = 1
    val DEFAULT_MANIFEST_URLS: List<String>
    fun platformId(): String?                     // "android-arm64-v8a" / "windows-x64" / null
    fun fetchManifest(urls: List<String>): Manifest
    fun install(dir, manifest, asset, progress, keepGoing): Installed   // 下载 + 大小 + SHA-256 + 原子改名，删旧版本
    fun installed(dir): Installed?
    fun load(installed, verify = true): TsBridge  // 加载前再校验一次 SHA-256；检查 ABI
    fun ensure(dir, manifestUrls, progress): TsBridge   // 一步到位；清单拉不到但本地有已校验版本时用本地
    fun loadedOrNull(): TsBridge?
    fun uninstall(dir, keepState = true)
}
```

所有调用都是阻塞的，放后台线程。出错抛 `TsException`（带库里的错误信息）。

前 5 个方法与约定的 `TsBridge` 形状一致：`start(stateDir, hostname, authKey?) → loginUrl?`、
`status() → {running, ips}`、`expose(port, udp)`、`dial(host, port, udp) → localPort`、`stop()`；
其余为可选参数和补充方法。

### C ABI（`mmts_*`，同一个库里导出，供 JNA / C 调用）

```c
int   mmts_abi_version(void);                 // 1
char* mmts_version(void);
int   mmts_set_log_file(const char* path);    // NULL/"" 关闭
int   mmts_start(const char* state_dir, const char* hostname, const char* auth_key,
                 const char* control_url, int ephemeral, int upload_logs);  // 非阻塞
char* mmts_wait_json(int timeout_ms);         // 等到 Running 或拿到登录 URL，返回状态 JSON
char* mmts_status_json(int with_peers);
int   mmts_expose(int port, int local_port, int udp);
int   mmts_unexpose(int port, int udp);
int   mmts_dial(const char* host, int port, int udp);   // >0 本机端口；-1 失败
int   mmts_undial(int local_port, int udp);
int   mmts_stop(void);
int   mmts_logout(int wipe);
int   mmts_set_interfaces_json(const char* json);
char* mmts_last_error(void);
void  mmts_free(char* p);                     // 释放上面返回的 char*
```

返回 int 的函数：0 成功，-1 失败（`mmts_last_error`）。进程内只有一个节点实例；`start` 对同一 stateDir 幂等。

### manifest.json

```json
{
  "schema": 1, "name": "micmask-tailscale", "version": "0.1.0", "abi": 1,
  "tailscale": "1.104.1", "license": "BSD-3-Clause",
  "platforms": {
    "android-arm64-v8a": { "file": "micmask-tailscale-0.1.0-android-arm64-v8a.so",
      "url": "https://github.com/Taocrypt/MicMask/releases/download/ts-plugin-v0.1.0/micmask-tailscale-0.1.0-android-arm64-v8a.so",
      "urls": ["<同上>", "<可选镜像>"], "sha256": "…", "size": 0 },
    "windows-x64": { … }
  }
}
```

`urls` 依次尝试（`make_manifest.py --mirror <前缀>` 可加镜像）。完整性只靠 `sha256`，所以镜像不可信也无妨；
**清单本身**要从可信来源拿（建议 App 内置清单地址，正式版可再在 App 里钉住 sha256）。

## 转发语义与限制

**expose（tailnet → 本机）**
- TCP：在 tailnet `:port` 上监听，每个连入连接新拨一条 `127.0.0.1:localPort`。完全透明。
- UDP：在本机每个 tailnet IP（100.x 与 fd7a:…）的 `:port` 上收包；**每个远端 (ip:port) 分配一个本机 UDP 套接字**连到
  `127.0.0.1:localPort`，回包按来源原路返回；2 分钟无回包回收。
  MicMask 看到的来源地址是 `127.0.0.1:<临时端口>`，不是对端 tailnet IP —— 若协议靠来源地址识别设备，要改用协议内的设备 ID
  （MicMask Link v1 已有设备 ID）。

**dial（本机 → tailnet）**
- TCP：本机监听 `127.0.0.1:<随机端口>`，每个连入连接新拨一次 `host:port`（host 可以是 tailnet IP 或 MagicDNS 名）。
- UDP：一个本机 UDP 套接字 ↔ 一条 tsnet UDP 流；回包发给**最近一次**发包的本机地址。适合一对一音频流。

**libtailscale / tsnet 对 UDP 的支持程度（读源码 + 实测）**
- libtailscale 的 C API 只把连接作为流式 fd 交出，UDP 没有专门接口。
- tsnet 本身支持：`Server.Dial(ctx, "udp", addr)` 与 `Server.ListenPacket("udp", "ip:port")`（**必须给出具体 IP**，所以 expose UDP
  要等节点拿到 tailnet IP，最多等 30 秒）。数据走 gVisor 用户态 UDP，经 WireGuard 封装；P2P 打洞成功时直连，否则经 DERP 中继
  （DERP 是 TCP/HTTPS 上的中继，UDP 语义保留但延迟/抖动随中继变大）。
- 实测（进程内控制服务器 + DERP，Linux）：expose/dial 的 TCP、UDP 往返都通过，1200 字节 UDP 数据报完整往返。
  未做弱网/丢包/长时间压测。

## Android 注意事项

1. **网卡列表**：Android 不允许普通 App 用 netlink 枚举网卡，Go 的 `net.Interfaces` 会失败。官方 Tailscale App 从 Java 注入；本插件同理：
   `start` 之后调用 `setInterfaces()`（默认用 `java.net.NetworkInterface` 生成），并在 `ConnectivityManager.NetworkCallback`
   的 `onAvailable/onLost/onLinkPropertiesChanged` 里再调一次。没注入时用「内核为出站 UDP 选的源地址」合成一个网卡（与
   `tailscale.com/feature/androidbin` 相同做法），能用但不如真实列表。
2. **后台**：节点跑在 App 进程里。进程被冻结（Android 11+ cached app freezer）或杀掉，tailnet 连接就断；
   要在后台持续可达，必须在**已有的前台服务**（变声/传音服务）存活期间运行，并持有该服务。
   Doze 下网络被限制，空闲久了 DERP/控制连接会断，恢复网络后 tsnet 会自动重连，但期间不可达。
   不建议为它单独开前台服务；传音结束就 `stop()`。
3. **加载位置**：库下载到 App 私有目录（如 `filesDir/plugins/tailscale/<ver>/`），下载后设为只读，再 `System.load`。
   这在 targetSdk 34 上可用（W^X 限制的是 execve，不是 dlopen）。仅 arm64-v8a；32 位设备 `platformId()` 返回 null。
4. **16 KB 页**：.so 以 `-z max-page-size=16384` 链接。
5. **R8**：`TsNative` 的方法由 JNI 名字绑定，混淆会断：
   ```
   -keep class com.taocrypt.micmask.ts.TsNative { native <methods>; }
   ```
6. **与官方 Tailscale App 共存**：可以。两者是不同节点（各占一个设备名额）；官方 App 开着 VPN 时，本插件的流量会被它再包一层，能通但多一跳。
7. 进程内只能加载一个插件版本；升级后重启 App 生效。

## Windows 注意事项

- 电脑版已依赖 `org.json`，不需要新依赖。jlink 模块只用到 `java.base`（HttpURLConnection、NetworkInterface、MessageDigest）。
- 下载目录建议 `%LOCALAPPDATA%\MicMask\plugins\tailscale\`。ProGuard 同样要 keep `TsNative` 的 native 方法。
- 首次 expose 时 Windows 防火墙**不会**弹窗：tailnet 监听在用户态网络栈里，本机只有 `127.0.0.1` 的套接字。

## 账号、隐私、国内连通性

- **需要 Tailscale 账号**（个人免费计划即可，具体额度以 tailscale.com/pricing 为准），或自建 **Headscale**（开源控制服务器）：
  `start(controlUrl = "https://你的 headscale")`。两端设备必须在同一个 tailnet。
- 登录：`start()` 返回 `https://login.tailscale.com/a/…`，用系统浏览器打开（Android 用 Custom Tab / `ACTION_VIEW`），
  登录后节点自动变为 Running；也可用 auth key（`tskey-auth-…`，在 Tailscale 管理后台生成，可设为一次性/临时节点）。
- **隐私**：
  - 默认**关闭** Tailscale 的遥测日志上传（设置 `TS_NO_LOGS_NO_SUPPORT`，不向 log.tailscale.com 发送日志）；节点状态文件里的本地日志缓冲只留在设备上。
  - 控制服务器（Tailscale 公司或你的 Headscale）会知道：账号、设备名、OS、tailnet IP、公网 IP/端点、在线状态。**音频内容**走端到端 WireGuard 加密，
    经 DERP 中继时中继也看不到内容。MicMask Link v1 本身也有 AES-GCM，加密两层。
  - 登出（`logout(wipe = true)`）会注销节点并删除本地 tsnet 状态文件。
  - 设备名建议用户可改，默认不要用真实姓名/手机号。
- **国内连通性（推断，未实测）**：controlplane.tailscale.com / login.tailscale.com 在中国大陆访问不稳定，常见需要代理才能登录；
  官方 DERP 中继没有大陆节点，打洞失败时会绕到香港/东京等地，延迟可能 100 ms 以上，影响实时语音。
  建议：同一 Wi-Fi 下仍走 MicMask 原生直连；跨网时优先 Headscale + 自建（国内）DERP；在 UI 上显示「直连 / 中继」与延迟。
  插件下载本身走 GitHub Release，国内可在 manifest 里加镜像（`--mirror`），SHA-256 保证完整性。

## 许可证与商标

- 本目录代码属于 MicMask，按仓库根目录 LICENSE（GPL-3.0）发布。
- 插件库静态链接 `tailscale.com`（BSD-3-Clause），以及 gVisor（Apache-2.0）、wireguard-go（MIT）、golang.org/x/*（BSD-3-Clause）等。
  每个平台的完整第三方许可证合集由 CI 生成：`micmask-tailscale-<ver>-<platform>-licenses.txt`（随 Release 发布）。
  App 的「开源许可」页应展示或链接它（BSD-3 第 2 条要求二进制分发时附带版权声明）。
- **Tailscale 是 Tailscale Inc. 的商标。** 本插件不是 Tailscale 官方产品，也未获其认可。界面上可写「通过 Tailscale 连接」「支持 Tailscale / Headscale」，
  不要用 Tailscale 的 logo 作为 MicMask 的功能图标，不要写成「Tailscale 官方」或暗示合作（参见 https://tailscale.com/community/community-projects 的说明）。
- 使用 Tailscale 的托管服务即表示用户同意其服务条款 https://tailscale.com/terms 与隐私政策 https://tailscale.com/privacy-policy ；
  App 在首次启用前应提示这一点并给出链接。

tailscale.com 的许可证原文（BSD-3-Clause）：

```
BSD 3-Clause License

Copyright (c) 2020 Tailscale Inc & contributors.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its
   contributors may be used to endorse or promote products derived from
   this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

（libtailscale 的 LICENSE 为同一 BSD-3 条款，版权行 “Copyright (c) 2023 Tailscale & AUTHORS. All rights reserved.”；本插件不包含 libtailscale 的代码。）

## 集成步骤（App 侧，尚未做）

1. 把 `kotlin/com/taocrypt/micmask/ts/` 三个文件拷进 `app` 与 `desktop` 的源码（或抽成共享模块）；加 R8/ProGuard keep 规则。
2. 设置页「传音 → 远程连接」加开关「通过 Tailscale 连接（需下载约 N MB 插件）」，首次开启：展示条款/隐私说明 → `TsPlugin.ensure(dir, urls, progress)`
   显示下载进度 → `start(File(dir,"state").path, 设备名)`；返回 URL 就打开浏览器，然后轮询 `status()` 直到 `running`。
3. 接收端：`expose(47310, udp=false)` 与 `expose(<Link UDP 端口>, udp=true)`，把 `status().ips` 与 `self`（MagicDNS 名）展示给用户/写进配对信息。
4. 发送端：选 tailnet 设备（`status(withPeers=true).peers`）→ `dial(peerIp, 47310, false)` / `dial(peerIp, udpPort, true)` → 用返回的 127.0.0.1 端口走现有连接代码。
   注意 Link 协议里的 UDP 端口协商：对端告诉的端口需替换为本机 `dial` 出来的端口。
5. Android：在 `ConnectivityManager.registerDefaultNetworkCallback` 里调用 `setInterfaces()`；只在前台服务存活期间运行，传音结束 `stop()`。
6. 「关于/开源许可」页加入 `*-licenses.txt`；设置里提供「登出 Tailscale 并删除插件」（`logout(true)` + `TsPlugin.uninstall`）。
7. 正式发布：打 `ts-plugin-v<ver>` 标签 → CI 发 prerelease；App 里的 `DEFAULT_MANIFEST_URLS` 改成该版本的 manifest 地址（可加镜像）。

## 构建与测试

CI：`.github/workflows/tailscale-plugin.yml`（`feat/desktop-windows` 分支与 `ts-plugin-v*` 标签）。
- Linux 主机：`gofmt`、`go vet`、`go test`（登录 URL；expose/dial 的 TCP+UDP 回环）
- 交叉编译 android-arm64（NDK r28c，API 26）与 windows-x64（MinGW），检查导出的 JNI 符号与依赖 DLL
- 编 Kotlin 加载器，在 Linux 上用 JVM 跑：清单 → 下载/校验 → 加载 → 加入本地 tailnet → dial/expose TCP+UDP → stop
- Windows runner：JVM 加载 DLL，连真实 Tailscale 控制服务器拿到登录 URL 后登出
- 生成 manifest.json、许可证合集，上传 artifact；标签时发布 Release（prerelease、不设 Latest）

本地（需要 Go ≥ go.mod 版本与 C 编译器）：

```sh
cd plugins/tailscale/native
CGO_CFLAGS="-I$JAVA_HOME/include -I$JAVA_HOME/include/linux" go test -run 'TestLoginURL|TestExposeAndDial' -v .
```
