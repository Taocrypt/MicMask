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

| 组件 | 许可 |
|---|---|
| AndroidX / Jetpack Compose | Apache-2.0 |
| Miuix (compose-miuix-ui)：界面组件库；ui2/glass 液态玻璃组件基于其示例工程 | Apache-2.0 |
| Kyant0/AndroidLiquidGlass（miuix 示例中液态玻璃效果的来源） | Apache-2.0 |
| Shizuku API / Provider | Apache-2.0 |
| ONNX Runtime | MIT |
| mobile-rvc：原生 NEON fp16 声码器内核（app/src/main/cpp/vocoder） | MIT（见上文） |
| RVC WebUI：合成器 / 声码器结构（经 mobile-rvc） | MIT（见 notices/RVC-MIT.txt） |
| TTS 前端数据表（GPT-SoVITS / pypinyin / g2pW / chinese-roberta 词表） | MIT / MIT / Apache-2.0 / Apache-2.0 |
| 内置音效 app/src/main/assets/sfx | CC0 1.0 / 公有领域（见 sfx/LICENSES.md） |

## 模型与声音

本仓库与发行版 APK 不包含任何音色模型。用户自行导入或通过自填仓库下载的模型，其权利归各自权利人所有，使用者须确保拥有使用授权。

## 名称与图标

「MicMask」名称与图标仅用于标识本项目。基于本项目的修改版请使用不同的名称与图标，避免与官方版本混淆。
