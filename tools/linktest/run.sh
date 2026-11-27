#!/usr/bin/env bash
# MicMask Link v1 纯逻辑单测：kotlinc 独立编译 app/ 里 link/ 的纯 Kotlin 文件 + Concentus，跑 LinkTest.kt。
# 需要：kotlinc（KOTLINC 指定路径，默认 PATH 里的 kotlinc）与 JDK 17+。Concentus jar 自动从 Maven Central 下载到 .cache/。
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"; root="$(cd "$here/../.." && pwd)"
KC="${KOTLINC:-kotlinc}"
mkdir -p "$here/.cache"
jar="$here/.cache/concentus-1.0.2.jar"
[ -f "$jar" ] || curl -fsSL -o "$jar" https://repo1.maven.org/maven2/io/github/jaredmdobson/concentus/1.0.2/concentus-1.0.2.jar
L="$root/app/src/main/kotlin/com/taocrypt/micmask/link"
"$KC" -nowarn -cp "$jar" "$L/MicYouWire.kt" "$L/LinkNet.kt" "$L/MmLinkCrypto.kt" "$L/MmLinkProto.kt" "$L/MmLinkOpus.kt" "$L/MmLinkNet.kt" "$here/LinkTest.kt" -d "$here/.cache/out" 2>&1 | grep -v "^warning" || true
java -cp "$here/.cache/out:$jar:$(dirname "$(dirname "$(readlink -f "$(command -v "$KC")")")")/lib/kotlin-stdlib.jar" LinkTestKt
