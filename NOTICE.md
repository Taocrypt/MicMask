# NOTICE

MicMask 以 GNU General Public License v3.0 发布（见 LICENSE）。

## 上游项目

MicMask 由 atri-vc 改造而来，保留其版权与许可声明：

> atri-vc — https://github.com/spider996/atri-vc
> MIT License, Copyright (c) 2026 不太高性能萝卜籽
>
> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## 第三方组件

Apache-2.0 全文见 notices/Apache-2.0.txt。ui2/glass 下的组件经 Miuzarte/ScrcpyForAndroid（Apache-2.0）移植而来，源文件头部保留了出处。

### mobile-rvc（MIT）

原生 NEON fp16 声码器（`app/src/main/cpp/vocoder/`：conv_f16/conv_f32 卷积内核、线程池、NSF-HiFiGAN 解码器）移植自
[nasuiyile/mobile-rvc](https://github.com/nasuiyile/mobile-rvc)，源文件头部注明了出处与改动。全文见 `notices/mobile-rvc-MIT.txt`：

> mobile-rvc — https://github.com/nasuiyile/mobile-rvc
> MIT License, Copyright (c) 2026 nasuiyile
>
> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

mobile-rvc 的解码器结构又移植自 RVC（mobile-rvc 的 THIRD_PARTY_NOTICES.md 中列出），一并保留其声明，全文见 `notices/RVC-MIT.txt`：

> RVC-Project/Retrieval-based-Voice-Conversion-WebUI — https://github.com/RVC-Project/Retrieval-based-Voice-Conversion-WebUI
> MIT License, Copyright (c) 2023 liujing04, Copyright (c) 2023 源文雨, Copyright (c) 2023 Ftps
>
> （许可条款与上文 MIT 相同）

### TorchGate / noisereduce（MIT）

输入/输出降噪（`app/src/main/kotlin/com/taocrypt/micmask/TorchGate.kt`，Kotlin 重写并改为噪声谱持续跟踪）参考了 RVC WebUI 中的
[TorchGate](https://github.com/RVC-Project/Retrieval-based-Voice-Conversion-WebUI/tree/main/tools/torchgate)（作者 Asaf Zorea，随 RVC 以 MIT 许可发布，声明见上文 `notices/RVC-MIT.txt`），其谱门限算法源自
[timsainb/noisereduce](https://github.com/timsainb/noisereduce)。全文见 `notices/noisereduce-MIT.txt`：

> noisereduce — https://github.com/timsainb/noisereduce
> MIT License, Copyright (c) 2019, Tim Sainburg
>
> （许可条款与上文 MIT 相同）

### 3D-Speaker CAM++ 说话人嵌入模型（Apache-2.0，按需下载，不随 APK 分发）

可选功能「声纹门限」使用 3D-Speaker 的 CAM++ 中文通用说话人确认模型（`speech_campplus_sv_zh-cn_16k-common`，约 20 万说话人训练，输出 192 维嵌入），
ONNX 版本由 k2-fsa/sherpa-onnx 导出。模型文件 `3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx`（28,281,139 字节，
sha256 `e6af4d4c444f3dab01d991bb9b7d5af98eb979e329f6c8880a19d5553561ed25`）只在用户打开该功能或录入声纹时从原发布地址下载，
不包含在本仓库与 APK 中。fbank 前处理（`SpeakerFbank.kt`）按 3D-Speaker / torchaudio Kaldi fbank 的参数用 Kotlin 重写。
Apache-2.0 全文见 notices/Apache-2.0.txt。

> 3D-Speaker — https://github.com/modelscope/3D-Speaker
> Copyright 3D-Speaker (https://github.com/alibaba-damo-academy/3D-Speaker). All Rights Reserved.
> Licensed under the Apache License, Version 2.0
>
> 模型：ModelScope iic/speech_campplus_sv_zh-cn_16k-common — https://www.modelscope.cn/models/iic/speech_campplus_sv_zh-cn_16k-common （Apache License 2.0）
> 论文：Hui Wang, Siqi Zheng, Yafeng Chen, Luyao Cheng, Qian Chen. CAM++: A Fast and Efficient Network for Speaker Verification Using Context-Aware Masking. arXiv:2303.00332
>
> ONNX 导出：k2-fsa/sherpa-onnx — https://github.com/k2-fsa/sherpa-onnx （Apache-2.0），
> 发布页 https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models ，
> 镜像 https://huggingface.co/csukuangfj/speaker-embedding-models

### WebRTC AEC3 / webrtc-aec3-kmp（软件回声消除，MIT + BSD-3-Clause）

可选功能「软件回声消除（实验）」（`SoftAec.kt`，默认关闭）通过 Maven 依赖 `cn.enaium.webrtc.aec3:webrtc-aec3-kmp-android:1.0.3`
调用 WebRTC 的 AEC3 回声消除器，APK 中包含其原生库 `libwebrtc_aec3_jni.so`（仅 arm64-v8a）。

> webrtc-aec3-kmp（Kotlin 绑定与 JNI 层）— https://github.com/Enaium/webrtc-aec3-kmp
> 仓库 LICENSE 为 MIT License, Copyright (c) 2026 Enaium（全文见 `notices/webrtc-aec3-kmp-MIT.txt`）；
> 注意：该库发布到 Maven Central 的 POM 中声明的许可为 BSD-3-Clause，与仓库 LICENSE 不一致，此处两者一并保留。
>
> webrtc-aec3（从 WebRTC 抽出的 AEC3 源码，作为上述库的子模块编译进 .so）— https://github.com/Enaium/webrtc-aec3
> WebRTC — https://webrtc.googlesource.com/src
> BSD 3-Clause License, Copyright (c) 2011, The WebRTC project authors. All rights reserved.（全文见 `notices/WebRTC-BSD-3.txt`）
> WebRTC 另附「Additional IP Rights Grant (Patents)」专利授权声明：上游 webrtc-aec3 子模块未附带该文件，
> 本项目从 WebRTC 官方仓库取得原文收录于 `notices/WebRTC-PATENTS.txt`，适用于 Google 作为 WebRTC 代码包分发的部分。

该原生库中还静态包含以下第三方代码（随 WebRTC 源码一起编译）：

> Abseil C++（`absl/`）— https://github.com/abseil/abseil-cpp
> Apache License 2.0, Copyright The Abseil Authors（全文见 `notices/Apache-2.0.txt`）
>
> JsonCpp（`base/jsoncpp/`，AEC3 用于解析配置 JSON）— https://github.com/open-source-parsers/jsoncpp
> Copyright 2007-2011 Baptiste Lepilleur and The JsonCpp Authors；在承认公有领域的司法辖区为公有领域，其余辖区以 MIT License 发布（全文见 `notices/jsoncpp-LICENSE.txt`）
>
> Ooura FFT（`common_audio/third_party/ooura/fft_size_128/ooura_fft.cc`）— http://www.kurims.kyoto-u.ac.jp/~ooura/fft.html
> Copyright Takuya OOURA, 1996-2001.
> "You may use, copy, modify and distribute this code for any purpose (include commercial use) and without fee. Please refer to this package when you modify this code."
> WebRTC 作者的改动适用上文 WebRTC 许可与专利授权。

感谢 Enaium、WebRTC 项目作者、Abseil 作者、Baptiste Lepilleur 与 JsonCpp 作者、大浦拓哉（Takuya OOURA）。

### Alkove（声纹聚焦，Apache-2.0：代码与模型权重）

可选功能「声纹聚焦（Alkove）」（`AlkoveFocus.kt` / `AlkoveStore.kt`，默认关闭）通过 Maven 依赖 `ai.alkove:alkove:0.5.0`
（仓库 https://maven.alkove.ai ）调用 Alkove 的端侧声纹聚焦，APK 中包含其原生库 `libalkove.so`（仅 arm64-v8a）。
模型 `alkove-focus-1.alk`、`alkove-cue-1-preview.alk`、`alkove-timbre-1.alk` 不包含在本仓库与 APK 中，
只在用户选择该功能或注册声纹时，按 SDK 0.5.0 钉定的 revision 从 Hugging Face（或其镜像 hf-mirror.com）下载并校验 sha256。
Alkove 的代码与模型权重均以 Apache License 2.0 发布（全文见 `notices/Apache-2.0.txt`），其 NOTICE 原文收录于 `notices/alkove-NOTICE.txt`。

> Alkove — https://github.com/alkove-ai/alkove · https://alkove.ai
> Copyright 2026 Eddy Bordi. Licensed under the Apache License, Version 2.0
>
> 模型：https://huggingface.co/alkove-ai/alkove-focus-1 （revision 69075e5）、
> https://huggingface.co/alkove-ai/alkove-cue-1-preview （revision 0e1f303）、
> https://huggingface.co/alkove-ai/alkove-timbre-1 （revision cb87ad7），均为 Apache License 2.0
>
> alkove-focus-1 由 GTCRN 检查点（https://github.com/Xiaobin-Rong/gtcrn ，在 DNS3 上训练）继续训练而来：
> MIT License, Copyright (c) 2024 Rong Xiaobin（全文见 `notices/alkove-NOTICE.txt`）
>
> libalkove.so 中 alkove-core 的文本表（crates/core/src/text/tables.rs）派生自 Unicode Character Database 16.0：
> Unicode License v3, Copyright © 1991-2024 Unicode, Inc.（全文见 `notices/alkove-NOTICE.txt`）

本功能的接入思路参考了一位用户转来的 Alkove 接入提案（alkove-micmask.patch 及其说明）：在实时变声链路里、RVC 之前插入 Alkove Focus，
在 App 里用 Timbre 注册声纹后交给注入进程使用。本仓库按现有架构重新实现（模型改为运行时下载、与声纹门限并列为下拉选项、经 tune 参数传声纹）。
感谢 Eddy Bordi 与 Alkove 项目、Rong Xiaobin（GTCRN），以及提出该提案的用户。

### VirtuMic（系统权限模块，MIT）

`tools/magisk-module/` 的 Magisk 安装入口（`META-INF/com/google/android/update-binary`、`updater-script`，有改动）
以及「把 App 放进 `system/product/priv-app`、用 `system/product/etc/permissions/privapp-permissions-*.xml`
授予 `MODIFY_AUDIO_ROUTING` / `CAPTURE_AUDIO_OUTPUT`」的模块结构参照
[IndiTheo/VirtuMic](https://github.com/IndiTheo/VirtuMic) 的 `systemless-overlay-module`。
App 侧的「系统权限模式」为本仓库按现有注入服务重新实现，未复制 VirtuMic 的 Kotlin 代码。全文见 `notices/VirtuMic-MIT.txt`：

> MIT License, Copyright (c) 2026 IndiTheo and IndiDevs
>
> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

感谢 IndiTheo 与 IndiDevs。

感谢以上作者。

| 组件 | 许可 |
|---|---|
| AndroidX / Jetpack Compose | Apache-2.0 |
| Miuix (compose-miuix-ui)：界面组件库；ui2/glass 液态玻璃组件基于其示例工程 | Apache-2.0 |
| Kyant0/AndroidLiquidGlass（miuix 示例中液态玻璃效果的来源） | Apache-2.0 |
| Shizuku API / Provider | Apache-2.0 |
| VirtuMic：Magisk 系统权限模块的安装入口与 priv-app / privapp-permissions 结构（tools/magisk-module） | MIT（见上文） |
| ONNX Runtime | MIT |
| TorchGate（RVC WebUI，Asaf Zorea）/ noisereduce：输入/输出谱门限降噪思路（TorchGate.kt，Kotlin 重写） | MIT（见上文） |
| mobile-rvc：原生 NEON fp16 声码器内核（app/src/main/cpp/vocoder） | MIT（见上文） |
| mobile-rvc：AI 实时「响度跟随」「共振峰」「相位锁定」的思路与默认值（LiveRvc.kt、PhaseLock.kt，Kotlin 重写，共振峰改为 PSOLA） | MIT（见上文） |
| RVC WebUI：合成器 / 声码器结构（经 mobile-rvc） | MIT（见 notices/RVC-MIT.txt） |
| TTS 前端数据表（GPT-SoVITS / pypinyin / g2pW / chinese-roberta 词表） | MIT / MIT / Apache-2.0 / Apache-2.0 |
| 内置音效 app/src/main/assets/sfx | CC0 1.0 / 公有领域（见 sfx/LICENSES.md） |
| 3D-Speaker CAM++ 说话人嵌入模型（sherpa-onnx 导出的 ONNX，声纹门限按需下载，不随 APK 分发） | Apache-2.0（见上文） |
| webrtc-aec3-kmp：软件回声消除的 Kotlin/JNI 绑定（Maven 依赖，含 libwebrtc_aec3_jni.so） | MIT（POM 写 BSD-3-Clause，见上文） |
| WebRTC AEC3（经 webrtc-aec3-kmp）及其包含的 Abseil / JsonCpp / Ooura FFT | BSD-3-Clause + 专利授权 / Apache-2.0 / MIT 或公有领域 / Ooura 许可（见上文） |
| Alkove SDK `ai.alkove:alkove`（含 libalkove.so）与 Focus / Cue / Timbre 模型权重（声纹聚焦按需下载，不随 APK 分发） | Apache-2.0（Focus 派生自 GTCRN，MIT；文本表 Unicode License v3；见上文） |
| Concentus `io.github.jaredmdobson:concentus:1.0.2`：「传音」MicMask 自有协议的 Opus 编解码（手机与电脑版共用） | BSD-3-Clause（libopus：Xiph.Org、Skype、CSIRO、Microsoft 等；全文见 notices/concentus-BSD-3.txt） |
| JmDNS `org.jmdns:jmdns`：电脑版局域网发现（mDNS） | Apache-2.0 |
| JSON-java `org.json:json`：电脑版解析 GitHub Release / Beacon 数据 | Public Domain |
| Compose Multiplatform（JetBrains）：电脑版界面框架 | Apache-2.0 |

## 模型与声音

本仓库与发行版 APK 不包含任何音色模型。用户自行导入或通过自填仓库下载的模型，其权利归各自权利人所有，使用者须确保拥有使用授权。

## 名称与图标

「MicMask」名称与图标仅用于标识本项目。基于本项目的修改版请使用不同的名称与图标，避免与官方版本混淆。

### liquid-glass（MIT）

官网赞赏墙弹幕的液态玻璃折射（`site/vendor/liquid-glass.js`）原样收录自
[deepika-builds/liquid-glass](https://github.com/deepika-builds/liquid-glass)，文件头部注明了出处。全文见 `notices/liquid-glass-MIT.txt`：

> liquid-glass — https://github.com/deepika-builds/liquid-glass
> MIT License, Copyright (c) 2026 Deepika Rao

### MicYou（GPL-3.0-or-later + MicYou Plugin Exception 1.0）

「传音」功能兼容 [MicYou](https://github.com/MicYou-Dev/MicYou) 的局域网协议（握手串、`MicY`/`MicU` 帧格式、Protobuf 消息与字段号、端口与 mDNS 服务类型、会话与心跳规则），
协议编解码与收发逻辑（`app/src/main/kotlin/com/taocrypt/micmask/link/`）参照 MicYou 公开源码重新实现；`tools/micyou_sim.py` 内嵌的 `.proto` 消息定义取自 MicYou 的
`tauri-app/crates/micyou-protocol/proto/network.proto`。感谢 MicYou 作者与贡献者。
同一目录下的 `MmLink*.kt`（MicMask Link 自有协议：SPAKE2 配对、AES-GCM、Opus/PCM，规范见 `docs/link-protocol.md`）为 MicMask 自行设计实现，不含 MicYou 代码。

> MicYou — Turns your Android device into a high-quality PC microphone.
> Copyright (C) 2026 LanRhyme <https://github.com/MicYou-Dev/MicYou>
>
> This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by
> the Free Software Foundation, either version 3 of the License, or (at your option) any later version, with the MicYou Plugin Exception.

MicMask 整体按 GPL-3.0 发布；依 GPLv3 第 7 条，MicMask 中参照 MicYou 的部分按普通 GPL-3.0 提供，不附带其插件例外。
「MicYou」是其作者的项目名称，MicMask 只在界面与文档中说明「兼容 MicYou 电脑端」，不用其名称和图标做宣传，也与其作者无关联。

### VB-CABLE（VB-Audio Software，捐赠软件，随电脑版安装包原样附带）

MicMask 电脑版（Windows）的安装包里**原样附带** VB-Audio Software 的官方驱动包 `VBCABLE_Driver_Pack46.zip`（构建时从
https://download.vb-audio.com/Download_CABLE/VBCABLE_Driver_Pack46.zip 下载并校验 sha256，未做任何修改），
用户首次运行未检测到 VB-CABLE 时，可在电脑版里一键以管理员身份安装。

> **VB-CABLE 由 VB-Audio Software（Vincent Burel）提供，是捐赠软件（donationware），欢迎到 [vb-cable.com](https://vb-cable.com) 支持作者。**
> Copyright V.Burel ©2010-2024. All rights reserved.

依据 VB-Audio 的分发条款（https://vb-audio.com/Services/licensing.htm 「VB-CABLE Distribution with another product」，2026-11-28 核对）：
允许把 VB-CABLE 包原样随软件分发，并可嵌入安装程序静默安装，条件是用户能清楚识别 VB-CABLE 是 VB-Audio 的应用、并有机会捐赠；
须写明 VB-CABLE 的来源 www.vb-cable.com 以及「VB-CABLE 是捐赠软件，欢迎参与」。若在企业、机构等专业环境分发，须按数量向 VB-Audio 购买授权。
VB-CABLE A+B、C+D 不可分发，MicMask 不附带。VB-CABLE 不是 GPL 软件，不受 MicMask 的 GPL-3.0 约束；MicMask 与 VB-Audio 无关联。

