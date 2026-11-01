# GPL 合规声明

**轻剪辑 (StreamClip)** 是一个基于 FFmpeg 的 Android 视频处理应用。

## 许可证

本应用整体以 **GNU General Public License v3.0 (GPL-3.0)** 发布。

```
StreamClip - Android video processing app based on FFmpeg
Copyright (C) 2025 pisces312

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.
```

完整许可证文本见 [LICENSE](LICENSE) 文件。

## 开源组件

本应用打包了以下开源组件。**版本号与 `app/src/main/java/com/pisces312/streamclip/util/OssComponents.kt` 及 App 内「关于 → 开源许可」保持一致**，升级依赖时三处同步。

### 原生层（编入 `libffmpegkit.so` / `libav*.so`）

| 组件 | 版本 | 许可证 | 源码地址 |
|------|------|--------|---------|
| FFmpeg | n9.0.2 | GPL-3.0 | https://github.com/FFmpeg/FFmpeg |
| FFmpegKitNext | 9.0.2（pisces312 fork） | LGPL-3.0 | https://github.com/pisces312/ffmpeg-kit-next |
| x264 | BUILD 165（`b35605a`） | GPL-2.0+ | https://code.videolan.org/videolan/x264 |
| x265 | 4.2 / BUILD 216（tag `4.2`） | GPL-2.0+ | https://bitbucket.org/multicoreware/x265_git |
| LAME | 4.0 | LGPL-2.0 | https://lame.sourceforge.io |
| libiconv | 1.17 | LGPL-2.1 | https://www.gnu.org/software/libiconv/ |
| cpu_features | 0.11.0 | Apache-2.0 | https://github.com/google/cpu_features |

### Java / Kotlin 依赖

| 组件 | 版本 | 许可证 | 坐标 |
|------|------|--------|------|
| AndroidX Core | 1.18.0 | Apache-2.0 | `androidx.core:core-ktx` |
| AndroidX AppCompat | 1.7.1 | Apache-2.0 | `androidx.appcompat:appcompat` |
| AndroidX Activity | 1.13.0 | Apache-2.0 | `androidx.activity:activity-ktx` |
| AndroidX ConstraintLayout | 2.2.1 | Apache-2.0 | `androidx.constraintlayout:constraintlayout` |
| AndroidX Lifecycle | 2.10.0 | Apache-2.0 | `androidx.lifecycle:lifecycle-runtime-ktx`、`lifecycle-viewmodel-ktx` |
| AndroidX DocumentFile | 1.0.1 | Apache-2.0 | `androidx.documentfile:documentfile` |
| Material Components | 1.13.0 | Apache-2.0 | `com.google.android.material:material` |
| AndroidX Media3 | 1.6.1 | Apache-2.0 | `androidx.media3:media3-exoplayer`、`media3-ui`、`media3-common` |
| MP4Parser (isoparser) | 1.0.6 | Apache-2.0 | `libs/isoparser-1.0.6.jar` |
| AspectJ Runtime | 1.7.3 | EPL-1.0 | `libs/aspectjrt-1.7.3.jar` |
| Desugar JDK Libs | 2.1.4 | GPL-2.0 (Classpath Exception) | `com.android.tools:desugar_jdk_libs` |

> 完整许可证文本已随 APK 打包在 `res/raw/license_*.txt`，可在 App 内「关于 → 开源许可」逐条查看。

## 源码获取

### 应用源码

本应用完整源码托管于 GitHub：

**https://github.com/pisces312/StreamClip**

### FFmpegKitNext 定制构建

本应用使用的 FFmpegKitNext 为基于官方源码的定制构建，包含以下修改：
- 基于 **FFmpeg n9.0.2** 源码
- 仅编译 arm64-v8a 架构（NDK r27d，`aarch64-linux-android26`）
- 静态链接 x264、x265、LAME 4.0、libiconv 1.17、zlib，并启用 MediaCodec 硬编硬解
- 启用 `--enable-version3`（FFmpegKitNext 强制），未启用 `--enable-nonfree`

定制构建脚本和文档：
**https://github.com/pisces312/ffmpeg-kit-next**

原始 FFmpegKitNext 源码：
**https://github.com/arthenica/ffmpeg-kit-next**

## 商业使用说明

本应用遵循 GPL-3.0 许可证，允许商业使用，但需遵守以下义务：

1. **提供源码**：分发本应用时，必须同时提供完整源码或源码获取方式
2. **保留版权声明**：不得移除或修改版权声明和许可证文本
3. **相同许可证**：基于本应用的衍生作品必须以 GPL-3.0 或兼容许可证发布
4. **允许收费**：可以销售本应用，但购买者享有上述所有权利

## 联系方式

如有许可证相关问题，请通过 GitHub Issues 联系：

https://github.com/pisces312/StreamClip/issues
