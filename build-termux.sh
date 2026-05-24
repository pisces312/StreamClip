#!/usr/bin/env bash
# StreamClip Build Script for Termux/proot Ubuntu (aarch64)
# 在手机本地（Termux + proot ubuntu）上构建 APK，构建产物直接落在 sdcard 上
#
# Usage: ./build-termux.sh [debug|release] [full|github|store]
#   默认: debug full
#
# 要求环境：
#   /usr/lib/jvm/java-21-openjdk-arm64    (Gradle 9.4 daemon 用)
#   $HOME/android-sdk                      (含 build-tools/35.0.0 aarch64 + platforms/android-36)
#
# release 签名所需环境变量：
#   STREAMCLIP_KEYSTORE   keystore 路径
#   KEY_ALIAS             key alias（默认 pisces312）
#   KEY_STORE_PASSWORD    keystore 密码

set -e

BUILD_TYPE="${1:-debug}"
FLAVOR="${2:-full}"

case "$BUILD_TYPE" in debug|release) ;; *) echo "Usage: $0 [debug|release] [full|github|store]"; exit 1 ;; esac
case "$FLAVOR" in full|github|store) ;; *) echo "Usage: $0 [debug|release] [full|github|store]"; exit 1 ;; esac

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$PROJECT_DIR/app"

# 环境
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-arm64}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

BUILD_TOOLS_DIR="$ANDROID_HOME/build-tools/35.0.0"

# 检查
[[ -d "$ANDROID_HOME/platforms/android-36" ]] || { echo "ERROR: $ANDROID_HOME/platforms/android-36 不存在"; exit 1; }
[[ -x "$BUILD_TOOLS_DIR/aapt2" ]] || { echo "ERROR: aapt2 不可执行"; exit 1; }

# release 预检
if [[ "$BUILD_TYPE" == "release" ]]; then
    [[ -n "$KEY_STORE_PASSWORD" ]] || { echo "ERROR: KEY_STORE_PASSWORD 未设置"; exit 1; }
    KEYSTORE="${STREAMCLIP_KEYSTORE:-}"
    [[ -f "$KEYSTORE" ]] || { echo "ERROR: keystore 不存在: $KEYSTORE"; exit 1; }
    KEY_ALIAS="${KEY_ALIAS:-pisces312}"
fi

# 自动检测版本
VERSION=$(grep 'versionName' "$APP_DIR/build.gradle.kts" | head -1 | sed 's/.*versionName *= *"\([^"]*\)".*/\1/')
[[ -z "$VERSION" ]] && VERSION="unknown"

FLAVOR_CAP="$(echo "$FLAVOR" | sed 's/\b./\u&/')"
BUILD_TYPE_CAP="$(echo "$BUILD_TYPE" | sed 's/\b./\u&/')"
TASK="assemble${FLAVOR_CAP}${BUILD_TYPE_CAP}"

echo "=== Building StreamClip v$VERSION ($BUILD_TYPE/$FLAVOR) on $(uname -m) ==="

cd "$PROJECT_DIR"
# sdcard 不支持 chmod +x，用 bash 调用 gradlew
# AGP 默认从 Maven 下载 x86_64 版 aapt2，必须强制覆盖为本地 aarch64 版
# 手机内存有限，限制 jvm 堆 + 单 worker 防 OOM
# release 需要更大的堆（R8 minify 阶段约需 3G+）
if [[ "$BUILD_TYPE" == "release" ]]; then
    JVM_HEAP="-Xmx4096m"
else
    JVM_HEAP="-Xmx1536m"
fi
bash ./gradlew "$TASK" --no-daemon --max-workers=1 \
    "-Pandroid.aapt2FromMavenOverride=$BUILD_TOOLS_DIR/aapt2" \
    "-Dorg.gradle.jvmargs=$JVM_HEAP -Dfile.encoding=UTF-8"

# 找产物
BUILD_DIR="$APP_DIR/build/outputs/apk/$FLAVOR/$BUILD_TYPE"
if [[ "$BUILD_TYPE" == "debug" ]]; then
    APK="$BUILD_DIR/app-$FLAVOR-debug.apk"
else
    APK="$BUILD_DIR/app-$FLAVOR-release-unsigned.apk"
fi

[[ -f "$APK" ]] || { echo "ERROR: APK 没找到: $APK"; ls "$BUILD_DIR" 2>/dev/null || true; exit 1; }

# 输出路径（项目就在 sdcard 上，无需再拷贝）
if [[ "$BUILD_TYPE" == "release" ]]; then
    OUT="$PROJECT_DIR/StreamClip-v${VERSION}-${FLAVOR}-release-signed.apk"
    ALIGNED="$BUILD_DIR/app-aligned.apk"

    echo "=== Aligning ==="
    "$BUILD_TOOLS_DIR/zipalign" -f 4 "$APK" "$ALIGNED"

    echo "=== Signing ==="
    java -jar "$BUILD_TOOLS_DIR/lib/apksigner.jar" sign \
        --ks "$KEYSTORE" \
        --ks-pass "pass:$KEY_STORE_PASSWORD" \
        --ks-key-alias "$KEY_ALIAS" \
        --key-pass "pass:$KEY_STORE_PASSWORD" \
        --out "$OUT" \
        "$ALIGNED"

    rm -f "$ALIGNED"
else
    OUT="$PROJECT_DIR/StreamClip-v${VERSION}-${FLAVOR}-${BUILD_TYPE}.apk"
    cp -f "$APK" "$OUT"
fi

SIZE=$(du -h "$OUT" | cut -f1)
echo ""
echo "=== Done: $OUT ($SIZE) ==="
echo "用文件管理器打开 ${PROJECT_DIR#/mnt/sdcard} 即可点击安装"
