#!/usr/bin/env bash
# StreamClip AAB Build Script for Google Play Store
# Usage: ./build-aab.sh [--no-minify]
# Note: AAB does NOT need local signing - Google signs it after upload.

set -e

NO_MINIFY=false

for arg in "$@"; do
    case "$arg" in
        --no-minify) NO_MINIFY=true ;;
    esac
done

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$PROJECT_DIR/app"

VERSION=$(grep 'versionName' "$APP_DIR/build.gradle.kts" | head -1 | sed 's/.*versionName *= *"\([^"]*\)".*/\1/')
VERSION="v${VERSION:-2.2.0}"

GRADLE_TASK="bundleRelease"

echo "=== Building StreamClip $VERSION AAB ==="

cd "$PROJECT_DIR"
GRADLE_ARGS="-PbuildAbi=arm64-v8a"
[[ "$NO_MINIFY" == true ]] && GRADLE_ARGS="$GRADLE_ARGS -PenableMinify=false -PenableShrinkResources=false"

./gradlew "$GRADLE_TASK" $GRADLE_ARGS

BUILD_DIR="$APP_DIR/build/outputs/bundle/release"
AAB="$BUILD_DIR/app-release.aab"

if [[ ! -f "$AAB" ]]; then
    echo "ERROR: AAB not found at $AAB"
    ls "$BUILD_DIR" 2>/dev/null || true
    exit 1
fi

OUTPUT="$PROJECT_DIR/StreamClip-${VERSION}.aab"
cp "$AAB" "$OUTPUT"

SIZE=$(du -h "$OUTPUT" | cut -f1)
echo "=== Done: $OUTPUT ($SIZE) ==="
echo "Upload this AAB to Google Play Console. Google will sign it."
