#!/usr/bin/env bash
# StreamClip Build & Push to Device
# Usage: ./build-and-push.sh [debug|release] [arm64] [full|github|store] [--no-minify] [-d <device_id>]
#   先调用 build.sh 构建，再安装到指定设备（模拟器或真机）
#   -d <device_id>  指定目标设备，省略则交互选择
#
# Examples:
#   ./build-and-push-to-emulator.sh                    # 构建release，交互选择设备
#   ./build-and-push-to-emulator.sh debug              # 构建debug，交互选择设备
#   ./build-and-push-to-emulator.sh -d emulator-5554   # 指定模拟器
#   ./build-and-push-to-emulator.sh -d 192.168.1.5:5555 # 指定真机（WiFi调试）
#   ./build-and-push-to-emulator.sh debug --no-minify -d emulator-5554

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# 解析参数：提取 -d <device>，其余透传给 build.sh
DEVICE=""
BUILD_ARGS=()
i=0
while [[ $i -lt $# ]]; do
    arg="${@:$((i+1)):1}"
    if [[ "$arg" == "-d" ]]; then
        i=$((i+1))
        if [[ $i -lt $# ]]; then
            DEVICE="${@:$((i+1)):1}"
            i=$((i+1))
        fi
    else
        BUILD_ARGS+=("$arg")
        i=$((i+1))
    fi
done

# 1. 调用 build.sh 构建
echo "=== Step 1: Building ==="
"$SCRIPT_DIR/build.sh" "${BUILD_ARGS[@]}"

# 2. 查找刚生成的 APK
APK=$(ls -t "$SCRIPT_DIR"/StreamClip-*.apk 2>/dev/null | head -1)
if [[ -z "$APK" ]]; then
    echo "ERROR: No APK found after build"
    exit 1
fi
echo "=== APK: $APK ==="

# 3. 检查 adb 是否可用
if ! command -v adb &> /dev/null; then
    echo "ERROR: adb not found in PATH"
    echo "Add Android SDK platform-tools to PATH, e.g.:"
    echo '  export PATH="$PATH:/path/to/android_sdk/platform-tools"'
    exit 1
fi

# 4. 获取设备列表
mapfile -t DEVICE_LIST < <(adb devices | awk 'NR>1 && $1 {print $1}')
DEVICE_COUNT=${#DEVICE_LIST[@]}

if [[ "$DEVICE_COUNT" -eq 0 ]]; then
    echo "ERROR: No device connected"
    exit 1
elif [[ "$DEVICE_COUNT" -eq 1 ]]; then
    # 只有一个设备，直接使用
    DEVICE="${DEVICE_LIST[0]}"
    echo "=== Single device: $DEVICE ==="
elif [[ -n "$DEVICE" ]]; then
    # 验证指定设备是否存在
    FOUND=false
    for d in "${DEVICE_LIST[@]}"; do
        if [[ "$d" == "$DEVICE" ]]; then
            FOUND=true
            break
        fi
    done
    if [[ "$FOUND" == false ]]; then
        echo "ERROR: Device '$DEVICE' not found"
        echo "Available devices:"
        for d in "${DEVICE_LIST[@]}"; do echo "  $d"; done
        exit 1
    fi
    echo "=== Using device: $DEVICE ==="
else
    # 多设备且未指定，交互选择
    echo "=== Multiple devices found ==="
    PS3="Select device: "
    select d in "${DEVICE_LIST[@]}"; do
        if [[ -n "$d" ]]; then
            DEVICE="$d"
            break
        fi
    done
    echo "=== Selected: $DEVICE ==="
fi

# 5. 安装到指定设备
echo "=== Step 2: Installing to $DEVICE ==="
adb -s "$DEVICE" install -r "$APK"

echo "=== Done ==="
