#!/usr/bin/env bash
# StreamClip Build Script
# Usage: ./build.sh [debug|release] [arm64] [full|github|store] [--no-minify]
#   (default: release arm64 full; only arm64 supported due to local AAR)
#
# Examples:
#   ./build.sh                  # Build release, arm64, full flavor
#   ./build.sh debug            # Build debug, arm64, full flavor
#   ./build.sh release arm64 store --no-minify   # Build store release without minify

set -e

BUILD_TYPE="${1:-release}"
ABI="${2:-arm64}"
FLAVOR="${3:-full}"
NO_MINIFY=false

# Parse optional flags
for arg in "$@"; do
    case "$arg" in
        --no-minify) NO_MINIFY=true ;;
    esac
done

# Validate FLAVOR
case "$FLAVOR" in
    full|github|store) ;;
    *) echo "Usage: $0 [debug|release] [arm64] [full|github|store] [--no-minify]"; exit 1 ;;
esac

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$PROJECT_DIR/app"
BUILD_TOOLS="D:/dev/android_sdk/build-tools/36.0.0"
KEYSTORE="D:/my-projects/my-backup/backup-settings/my-android-release.keystore"
KEYSTORE_PASS="${KEY_STORE_PASSWORD:-}"
KEY_ALIAS="${KEY_ALIAS:-pisces312}"

# Auto-detect version from build.gradle.kts
VERSION=""
GRADLE_FILE="$APP_DIR/build.gradle.kts"
if [[ -f "$GRADLE_FILE" ]]; then
    VERSION=$(grep 'versionName' "$GRADLE_FILE" | head -1 | sed 's/.*versionName *= *"\([^"]*\)".*/\1/')
fi
if [[ -z "$VERSION" ]]; then
    VERSION="1.2.0"
fi
VERSION="v$VERSION"

# Validate BUILD_TYPE
case "$BUILD_TYPE" in
    debug|release) ;;
    *) echo "Usage: $0 [debug|release] [arm64] [full|github|store] [--no-minify]"; exit 1 ;;
esac

# Only arm64 supported (local AAR is arm64-only)
case "$ABI" in
    arm64) ;;
    *) echo "Only arm64 is supported (local AAR is arm64-only)"; exit 1 ;;
esac

ABI_FILTER="arm64-v8a"
BUILD_TYPE_CAP="$(echo "$BUILD_TYPE" | sed 's/\b./\u&/')"
FLAVOR_CAP="$(echo "$FLAVOR" | sed 's/\b./\u&/')"
GRADLE_TASK="assemble${FLAVOR_CAP}${BUILD_TYPE_CAP}"

echo "=== Building StreamClip $VERSION for $BUILD_TYPE / $ABI / $FLAVOR ==="

# Validate signing env vars for release
if [[ "$BUILD_TYPE" == "release" ]]; then
    if [[ -z "$KEYSTORE_PASS" ]]; then
        echo "ERROR: KEY_STORE_PASSWORD env var not set"
        exit 1
    fi
fi

# Build
cd "$PROJECT_DIR"
GRADLE_ARGS="-PbuildAbi=$ABI_FILTER"
if [[ "$NO_MINIFY" == true ]]; then
    GRADLE_ARGS="$GRADLE_ARGS -PenableMinify=false -PenableShrinkResources=false"
    echo "=== Minify/ShrinkResources disabled ==="
fi
./gradlew "$GRADLE_TASK" $GRADLE_ARGS

# Find APK
BUILD_DIR="$APP_DIR/build/outputs/apk/$FLAVOR/$BUILD_TYPE"
UNSIGNED_APK=""
if [[ "$BUILD_TYPE" == "release" ]]; then
    UNSIGNED_APK="$BUILD_DIR/app-$FLAVOR-release-unsigned.apk"
else
    UNSIGNED_APK="$BUILD_DIR/app-$FLAVOR-debug.apk"
fi

if [[ ! -f "$UNSIGNED_APK" ]]; then
    echo "ERROR: APK not found at $UNSIGNED_APK"
    ls "$BUILD_DIR" 2>/dev/null || true
    exit 1
fi

# Sign or copy
SIGNED_APK=""
if [[ "$BUILD_TYPE" == "release" ]]; then
    ALIGNED_APK="$BUILD_DIR/app-release-aligned.apk"
    SIGNED_APK="$PROJECT_DIR/StreamClip-${VERSION}-${ABI}-${FLAVOR}-signed.apk"

    echo "=== Aligning ==="
    "$BUILD_TOOLS/zipalign" -f 4 "$UNSIGNED_APK" "$ALIGNED_APK"

    echo "=== Signing ==="
    java -jar "$BUILD_TOOLS/lib/apksigner.jar" sign \
        --ks "$KEYSTORE" \
        --ks-pass "pass:$KEYSTORE_PASS" \
        --ks-key-alias "$KEY_ALIAS" \
        --key-pass "pass:$KEYSTORE_PASS" \
        --out "$SIGNED_APK" \
        "$ALIGNED_APK"

    rm -f "$ALIGNED_APK"
else
    SIGNED_APK="$PROJECT_DIR/StreamClip-${VERSION}-${ABI}-${FLAVOR}-debug.apk"
    cp -f "$UNSIGNED_APK" "$SIGNED_APK"
fi

SIZE=$(du -h "$SIGNED_APK" | cut -f1)
echo "=== Done: $SIGNED_APK ($SIZE) ==="
