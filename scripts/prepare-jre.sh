#!/usr/bin/env bash
# Extracts the Android arm64 JRE into assets/jre.zip (gitignored, ~130 MB extracted).
# Download jre21-android-arm64.tar.xz into jre/ first (URL in AGENTS.md).
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Elige la versión más alta disponible en jre/ (Minecraft 26.x exige Java 25).
tar="${JRE_TAR:-$(ls "$root"/jre/jre*-android-arm64.tar.xz 2>/dev/null | sort -V | tail -1)}"
if [ -z "$tar" ] || [ ! -f "$tar" ]; then
    echo "no hay JRE en jre/*.tar.xz — descarga jre25-android-arm64.tar.xz desde AngelAuraMC/angelauramc-openjdk-build" >&2
    exit 1
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
tar -xJf "$tar" -C "$tmp"

# Los binarios del JRE 25 enlazan libc++_shared.so, que el tarball no incluye (viene del NDK).
ndk="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/r27d}"
cxx="$(ls "$ndk"/toolchains/llvm/prebuilt/*/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so 2>/dev/null | head -1)"
if [ -z "$cxx" ]; then
    echo "no encuentro libc++_shared.so — instala el NDK o exporta ANDROID_NDK_HOME" >&2
    exit 1
fi
cp "$cxx" "$tmp/lib/libc++_shared.so"

mkdir -p "$root/app/src/main/assets"
rm -f "$root/app/src/main/assets/jre.zip"
# The JRE keeps its full layout: the app executes <filesDir>/jre/bin/java directly (targetSdk 28).
(cd "$tmp" && bsdtar --format=zip -cf "$root/app/src/main/assets/jre.zip" .)

ls -lh "$root/app/src/main/assets/jre.zip"
