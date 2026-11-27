# MicMask Link v1 传音协议规范

> 状态：v1（2026-11-28 定稿第一版）。实现：`app/src/main/kotlin/com/taocrypt/micmask/link/MmLink*.kt`（纯 Kotlin，不依赖 Android），
> 电脑版（`desktop/`）构建时原样复制这几份源码编译，两端同一份代码。单测：`tools/linktest/`（kotlinc 独立运行）。
> MicYou 兼容协议（`MicYouWire.kt`、`LinkNet.kt`）保持原样，作为可选项继续提供。

## 1. 目标与范围

- 局域网内手机 ↔ 电脑、手机 ↔ 手机互传实时音频，**双向**：一条会话里任一端都可以发也可以收（由 CAPS 声明）。
- 默认加密：不知道配对码的人连不上，被动监听也听不到。
- 48 kHz 单声道，10 ms 一帧（480 样本），与手机端 shell 主循环节拍一致。
- 编码可选：Opus（默认）或 PCM s16le。

## 2. 角色、端口与发现

| 项 | 值 |
|---|---|
| 监听方（服务端） | 打开「接收」的一端：显示 6 位配对码 |
| 发起方（客户端） | 选设备并输入配对码的一端 |
| 端口 | TCP 与 UDP 同号，默认 **47310** |
| mDNS 服务类型 | `_micmask._tcp`（实例名 = 设备名，如 `MicMask-<机型>`、`MicMask-PC-<主机名>`） |
| mDNS TXT | `app=micmask`、`v=<协议最高版本>`、`plat=android|windows`、`id=<设备 ID 前 4 字节十六进制>` |
| USB | 电脑执行 `adb reverse tcp:47310 tcp:47310`，手机连 `127.0.0.1`；adb reverse 只转 TCP，此时音频走 TCP（见 §6） |

同一时间一个监听方只服务一条会话；新会话握手成功后接管旧会话（旧会话收到 BYE「被新连接接管」）。

## 3. TCP 帧

```
帧 = u16 BE 长度 L（= 1 + 内容长度，≤ 65535）| u8 类型 | 内容（L−1 字节）
```

| 类型 | 名称 | 方向 | 内容 |
|---|---|---|---|
| 0x01 | HELLO | 双方各一次 | 见 §4.1（明文） |
| 0x02 | PAKE | 双方各一次 | SPAKE2 消息：P-256 非压缩点 65 字节（明文） |
| 0x03 | CONFIRM | 双方各一次 | HMAC-SHA256 确认值 32 字节（明文） |
| 0x10 | SEALED | 握手后 | `u64 BE 计数 | AES-GCM(内层消息)‖tag16`，AAD = 类型字节 0x10 |
| 0x7F | ERROR | 任一时刻 | `u8 错误码 | u8 长度 | UTF-8 说明`（明文，发完即断开） |

错误码：1 = 版本不兼容，2 = 配对码不对，3 = 忙/被锁定（配对码错太多），4 = 协议错误。

## 4. 握手

```
客户端 A                                   服务端 B
HELLO(a) ───────────────────────────────▶
          ◀─────────────────────────────── HELLO(b, connId)
PAKE(pA) ───────────────────────────────▶
          ◀─────────────────────────────── PAKE(pB)
CONFIRM(cA) ────────────────────────────▶  校验 cA；错 → ERROR(2)
          ◀─────────────────────────────── CONFIRM(cB)
校验 cB；之后双方只发 SEALED
SEALED{CAPS} ◀──────────────────────────▶ SEALED{CAPS}
```

### 4.1 HELLO 与版本协商

```
HELLO = "MMLK"(4) | minVer u8 | maxVer u8 | deviceId(16) | name(u8 长度 + UTF-8) | platform(u8 长度 + UTF-8)
      | nonce(16, 随机) | connId u32（服务端分配，非 0；客户端填 0）| needCode u8（v1 恒为 1）
```

- 协商版本 = `min(双方 maxVer)`，若小于 `max(双方 minVer)` 则不兼容 → ERROR(1)。v1 时 min = max = 1。
- 将来的 v2 可以把 maxVer 提到 2 而 minVer 保持 1，老客户端照样能连（按 v1 跑）。
- `deviceId`：每台设备首次运行随机生成并保存（手机 prefs `link_dev_id`，电脑 `%APPDATA%\MicMask\desktop.properties`）。

### 4.2 SPAKE2 配对（RFC 9382）

- 套件：**P256-SHA256-HKDF-HMAC**；M、N 为 RFC 9382 §6 给出的 P-256 点；h = 1。
- A = 客户端、B = 服务端；身份 idA / idB = 双方 deviceId（16 字节）。
- 口令 w：`w = OS2IP(HKDF-SHA256(ikm = 6 位配对码 ASCII, salt = "MicMask Link v1 pairing", info = "w", L = 64)) mod n`。
  （RFC 9382 允许自选 MHF；口令只有约 20 bit，慢哈希意义不大，取 64 字节再模 n 以消除偏差。）
- `pA = x·G + w·M`，`pB = y·G + w·N`；`K = h·x·(pB − w·N) = h·y·(pA − w·M)`。
- `TT = len(idA)‖idA‖len(idB)‖idB‖len(pA)‖pA‖len(pB)‖pB‖len(K)‖K‖len(w)‖w`（len 为 8 字节小端，w 为 32 字节大端）。
- `Ke‖Ka = SHA-256(TT)`；`KcA‖KcB = HKDF(Ka, salt = 空, info = "ConfirmationKeys"‖"MicMask Link v1", L = 32)`；
  `cA = HMAC(KcA, TT)`，`cB = HMAC(KcB, TT)`。
- 实现已用 RFC 9382 附录 B 第一组测试向量逐项校验（pA、pB、K、Ke、cA、cB，见 `tools/linktest`）。

安全性：被动监听拿不到 Ke；主动攻击者每次连接只能验证一个猜测（10⁻⁶）。服务端配对码**连续错 5 次锁 60 秒**（ERROR 3），限制在线猜码速度。
配对码保存在接收端，可在页面上「换一个」；发送端按设备名记住上次成功的配对码，下次自动填。

### 4.3 会话密钥

```
OKM = HKDF-SHA256(ikm = Ke, salt = nonceA‖nonceB, info = "MicMask Link v1 keys", L = 64)
c2s 控制 = OKM[0:16]   s2c 控制 = OKM[16:32]   c2s 音频 = OKM[32:48]   s2c 音频 = OKM[48:64]
```

全部 AES-128-GCM，nonce = `00 00 00 00 ‖ 64 位计数（大端）`。每把密钥只在一个方向、一个通道上用，计数单调递增、不复用。
接收方用 64 包滑动窗口防重放（控制按 SEALED 计数，UDP 按 pn）。

## 5. 内层消息（SEALED 里）

| 类型 | 名称 | 内容 |
|---|---|---|
| 0x20 | CAPS | `flags u32 | 偏好编码 u8 | Opus 码率 u32 | 帧样本数 u16（=480）` |
| 0x21 | PING | `u64 发送时刻毫秒` |
| 0x22 | PONG | 原样回 PING 的时刻 → 算 RTT |
| 0x23 | STATS | `RTT u16 | 抖动 f32 毫秒 | 丢包率 f32 | 接收缓冲 u16 毫秒 | 延迟估计 u16 毫秒`（收音频的一端每秒发） |
| 0x24 | BYE | `u8 长度 + UTF-8 原因` |
| 0x25 | AUDIO | 音频走 TCP 时：`seq u32 | ts u32 | codec u8 | flags u8 | 样本数 u16 | 数据` |
| 0x26 | UDP_OK | 客户端收到 UDP 探测回应后发，告诉服务端「UDP 双向已通」 |

CAPS flags：`1 = 本端会发音频`、`2 = 本端想收音频`、`4 = 支持 Opus`、`8 = 支持 PCM`（v1 两端都必须支持 PCM）。
**发送编码**由发送方决定：发送方偏好 Opus 且双方都支持 Opus → Opus，否则 PCM。对方没声明「想收」时，发送方不发音频。
未知内层消息类型一律忽略（向前兼容）。

## 6. UDP

```
头（明文，作 AAD）= "MA"(2) | ver u8（=1）| 类型 u8 | connId u32 | pn u32（每方向递增，作 GCM 计数）
音频（类型 1）    = 头 | seq u32 | ts u32 | codec u8 | flags u8 | 样本数 u16 | AES-GCM(编码数据)‖tag16      （AAD = 前 24 字节）
探测（类型 2）/ 回应（类型 3）= 头 | AES-GCM(u64 发送时刻毫秒)‖tag16
```

- 音频用「音频」密钥（方向同上）；PCM 10 ms 一包 = 24 + 960 + 16 = 1000 字节，小于 1472，不分片；Opus 约 55 字节/包。
- 客户端握手后每 0.5 s 向服务端 `IP:47310` 发探测，直到收到回应；收到后发 TCP 内层 UDP_OK，双方音频改走 UDP。
- 服务端只接受**来源 IP = TCP 对端**且认证通过的数据报，并以最近一个认证通过的来源地址作为回发地址（适应 NAT/端口变化）。
- UDP 不通（防火墙、AP 隔离、USB adb reverse）时音频走 TCP 内层 AUDIO，功能不变、抗抖动差一些。
- `seq`：音频帧序号（与 pn 分开，探测包不占 seq）；`ts`：发送端 48 kHz 样本计数，接收端用来算抖动。

## 7. 接收处理（沿用现有实现）

- 重排：`SeqReorder`（与 MicYou 兼容路径同一份）：缺口等 3 个包（30 ms）再判丢；早于期望的丢弃；序号大跳当对端重启。
- 丢包补偿：Opus 用缺口后第一包的**带内 FEC** 恢复最后一帧，更早的用解码器 **PLC**，最多补 5 帧；PCM 直接跳过（由缓冲处理）。
- 抖动：`JitterMeter`（RFC 3550 风格）。
- 缓冲与漂移：手机注入沿用实时变声输出缓冲（预缓冲 80 ms + 积压裁剪）；外放 / 电脑播放用 `PlayoutBuffer`（电脑预缓冲 60 ms，超过 +120 ms 丢旧帧回到预缓冲）。
- 心跳：双方每秒 PING；5 秒没收到任何 TCP 消息即断开。

## 8. 编码参数

- Opus：Concentus 1.0.2（libopus 纯 Java 移植，BSD-3-Clause），48 kHz 单声道、10 ms、VOIP、默认 48 kbps 目标（实测约 44 kbps）、带内 FEC 开、预期丢包 10 %、DTX 关、复杂度 5。
- PCM：s16le，960 字节/帧，约 800 kbps。适合 USB / 热点直连。

## 9. 与 MicYou 兼容协议的关系

- 手机「传音」页顶部「协议」可选：**MicMask 自有（默认）** / **兼容 MicYou**。兼容路径（TCP 8554 + UDP 8555、`_micyou._tcp`、明文、只 PCM）保持原样。
- 两种协议端口不同，互不干扰；同一时间只能用一种（切换前要先停止收发）。

## 10. 后续（未实现）

- 二维码配对（内容建议 `micmask://link?h=<ip,…>&p=47310&n=<设备名>&c=<配对码>`）。
- 配对后保存对端身份，免码重连（需在 HELLO 里加入持久公钥与签名，v2）。
- 手机端在一条会话里同时收发（协议已支持，手机 UI 目前发送/接收各自一条会话）。
- 时钟漂移的 ASRC 微调（目前靠缓冲裁剪）。
