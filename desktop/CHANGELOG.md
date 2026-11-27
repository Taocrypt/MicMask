# MicMask 电脑版更新日志

电脑版独立发版：标签 `win-v<版本>`（如 `win-v0.1.0-alpha.1`），Release 一律标为测试版且不设为 Latest（不影响安卓版）。
格式：每个版本一个 `## [版本号] - YYYY-MM-DD` 小节，CI 发版时取对应小节作为 Release 说明。

## [未发布]

- 第一版骨架（Windows，Compose Desktop + miuix，名称与图标同手机版）
- 传音（MicMask Link v1，与手机共用协议源码）：加密（SPAKE2 配对码 + AES-GCM）、Opus / PCM、局域网自动发现（mDNS `_micmask._tcp`）
  - 接收：手机 → 电脑，播放到所选设备（默认 VB-CABLE「CABLE Input」，系统里多出麦克风「CABLE Output」）
  - 发送：电脑所选录音设备 → 开着「接收」的手机（手机注入 QQ / 微信 / 游戏）
- 附带 VB-CABLE 官方驱动包（VB-Audio Software，捐赠软件，vb-cable.com）：未安装时可一键以管理员身份安装
- 检查更新（GitHub Release 的 win-v 标签）；公告 / 赞赏墙 / 使用统计接口已接好，本版未启用
- 已知限制：音频走 javax.sound（DirectSound），延迟比 WASAPI 高；还不能按进程抓声音；界面只有中文
