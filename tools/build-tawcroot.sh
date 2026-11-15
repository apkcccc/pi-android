#!/usr/bin/env bash
# 从源码构建 tawcroot（wmww/tawc，MIT），产出 `libtawcroot.so`（静态非 PIE 的 arm64 可执行文件，
# 借 `.so` 的名字让 Android 把它解包到 nativeLibraryDir —— 与 proot 的做法相同）。
#
# 不下载二进制：钉死一个提交，从源码构建，可审计。没有这个文件时 App 照常工作——
# 选 tawcroot 会被判「缺组件」并回退 proot（见 docs/runtime-engines.md）。
#
# 用法：ANDROID_NDK_HOME=/path/to/ndk tools/build-tawcroot.sh [输出目录]
#   输出目录默认 app/src/main/jniLibs/arm64-v8a
set -euo pipefail

TAWC_REPO="https://github.com/wmww/tawc"
TAWC_REF="${TAWC_REF:-v6}"   # 钉死的 tag；要换版本在这里改并重跑探针
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/app/src/main/jniLibs/arm64-v8a}"
: "${ANDROID_NDK_HOME:?需要 ANDROID_NDK_HOME（推荐 NDK 27.2.12479018）}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
git clone --depth 1 --branch "$TAWC_REF" "$TAWC_REPO" "$WORK/tawc"
echo "tawc @ $(git -C "$WORK/tawc" rev-parse HEAD)"

( cd "$WORK/tawc/tawcroot" && ANDROID_NDK_HOME="$ANDROID_NDK_HOME" bash ./build.sh )

BIN="$(find "$WORK/tawc/tawcroot" -type f -name tawcroot -perm -u+x | head -n1)"
[ -n "$BIN" ] || { echo "没找到构建产物 tawcroot" >&2; exit 1; }
# 静态非 PIE：不能是动态链接，否则在 Android 上找不到 ld.so。
file "$BIN" | grep -q "statically linked" || { echo "产物不是静态链接：$(file "$BIN")" >&2; exit 1; }

mkdir -p "$OUT"
install -m 0755 "$BIN" "$OUT/libtawcroot.so"
echo "已写入 $OUT/libtawcroot.so"
