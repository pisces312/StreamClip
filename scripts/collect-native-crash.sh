#!/bin/bash
set -e

# StreamClip Native Crash Collector (Git Bash version)
# Usage: ./collect-native-crash.sh

NDK="/home/pisces312/android-ndk-r27d"
SYM="/mnt/d/nili/3rd_party_projects/ffmpeg-kit/android/libs/arm64-v8a"
OUT="D:/downloads/crash_logs/crash_$(date +%Y%m%d_%H%M%S)"

step() {
    echo ""
    echo "[$1/6] $2"
}

echo "========================================"
echo " StreamClip Native Crash Collector"
echo "========================================"

# 1. Check ADB
step 1 "Check ADB"
if ! command -v adb &>/dev/null; then
    if [ -f "/d/nili/dev/android_sdk/platform-tools/adb.exe" ]; then
        export PATH="$PATH:/d/nili/dev/android_sdk/platform-tools"
        echo ">>> Using SDK adb"
    else
        echo "ERROR: adb not found"
        exit 1
    fi
fi
echo ">>> adb found"

# 2. Check device
step 2 "Check device"
DEVS=$(adb devices | grep -E "\tdevice$" || true)
if [ -z "$DEVS" ]; then
    echo "ERROR: No device connected"
    echo "1. Connect phone via USB"
    echo "2. Enable USB debugging"
    exit 1
fi
echo ">>> Device connected"

# 3. Create output dir
step 3 "Create output directory"
mkdir -p "$OUT"
echo ">>> Output: $OUT"

# 4. Collect logcat
step 4 "Collect logcat"
LOGFILE="$OUT/logcat.txt"
adb logcat -c  # clear buffer
adb logcat -v threadtime > "$LOGFILE" &
LOGCAT_PID=$!

echo ""
echo "========================================"
echo " Reproduce the crash on your phone now"
echo " Press ENTER after crash..."
echo "========================================"
read -r

kill $LOGCAT_PID 2>/dev/null || true
echo ">>> Logcat saved"

# 5. Pull tombstones
step 5 "Pull tombstones"
COUNT=0

# Check root
HAS_ROOT=false
if adb shell "su -c 'id'" 2>/dev/null | grep -q "uid=0"; then
    HAS_ROOT=true
    echo ">>> Root detected"
fi

# Try pull tombstones
TS=$(adb shell "ls /data/tombstones/" 2>/dev/null || true)
if [ -n "$TS" ] && ! echo "$TS" | grep -q "No such file"; then
    echo "$TS" | while read -r f; do
        f=$(echo "$f" | tr -d '\r')
        [ -z "$f" ] && continue
        echo "$f" | grep -q "No such file" && continue
        
        if adb pull "/data/tombstones/$f" "$OUT/" 2>/dev/null; then
            echo ">>> Pulled: $f"
            COUNT=$((COUNT + 1))
        else
            echo ">>> Failed to pull $f"
        fi
    done
fi

if [ "$COUNT" -eq 0 ]; then
    echo ">>> No tombstones (need root)"
fi

# 6. Parse with ndk-stack
step 6 "Parse symbols"
NDK_STACK=""
if [ -f "$NDK/ndk-stack" ]; then
    NDK_STACK="$NDK/ndk-stack"
elif [ -f "/d/nili/dev/android_sdk/ndk/ndk-stack.cmd" ]; then
    NDK_STACK="/d/nili/dev/android_sdk/ndk/ndk-stack.cmd"
fi

if [ -n "$NDK_STACK" ] && [ "$COUNT" -gt 0 ]; then
    for t in "$OUT"/tombstone_*; do
        [ -f "$t" ] || continue
        o="$t.stack.txt"
        name=$(basename "$t")
        echo -n ">>> Parsing: $name..."
        
        if echo "$NDK_STACK" | grep -q "\.cmd$"; then
            "$NDK_STACK" -sym "$SYM" -dump "$t" > "$o" 2>/dev/null || true
        else
            "$NDK_STACK" -sym "$SYM" -dump "$t" > "$o" 2>/dev/null || true
        fi
        
        if [ -f "$o" ]; then
            echo " OK"
        else
            echo " FAIL"
        fi
    done
fi

# Summary
SUMMARY="$OUT/crash_summary.txt"
DEVICE=$(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r')
ANDROID=$(adb shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')

cat > "$SUMMARY" <<EOF
=== StreamClip Native Crash Summary ===
Time: $(date)
Device: $DEVICE
Android: $ANDROID
Tombstones: $COUNT

=== Key Logs ===
EOF

grep -E "signal|tombstone|DEBUG|FFmpegKit|libffmpeg|libav" "$LOGFILE" 2>/dev/null | tail -50 >> "$SUMMARY" || true

echo ""
echo "========================================"
echo " Done! Output: $OUT"
echo "========================================"

# Open explorer
start "$OUT"
