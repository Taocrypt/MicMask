#!/usr/bin/env bash
# 打包 MicMask 系统权限模块：bash tools/magisk-module/build.sh → build/micmask-privapp-module.zip
# 模块里不含 APK（刷入时从手机上已安装的 MicMask 复制），README.md / build.sh 不进 zip。
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
OUT="$ROOT/build/micmask-privapp-module.zip"
mkdir -p "$ROOT/build"
rm -f "$OUT"
cd "$HERE"
zip -r -X "$OUT" META-INF module.prop customize.sh system -x '*.apk' >/dev/null
echo "已生成 $OUT"
