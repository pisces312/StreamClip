#!/bin/bash
cd /home/pisces312/ffmpeg-kit-6.0
export ANDROID_SDK_ROOT=/mnt/d/nili/dev/android_sdk
export ANDROID_NDK_ROOT=/home/pisces312/android-ndk-r25b
./android.sh --enable-android-media-codec --enable-gpl --enable-x264 --enable-x265 --lts --api-level=21 --disable-arm-v7a --disable-arm-v7a-neon --disable-x86 --disable-x86-64 > build.log 2>&1
echo "Build completed with exit code $?" >> build.log