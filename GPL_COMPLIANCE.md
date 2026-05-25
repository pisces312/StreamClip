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

本应用使用以下开源组件：

| 组件 | 许可证 | 源码地址 |
|------|--------|---------|
| FFmpeg | GPL v3.0 | https://github.com/FFmpeg/FFmpeg |
| FFmpegKit | LGPL v3.0 | https://github.com/arthenica/ffmpeg-kit |
| x264 | GPL v2.0+ | https://github.com/mirror/x264 |
| x265 | GPL v2.0+ | https://github.com/videolan/x265 |
| cpu_features | Apache 2.0 | https://github.com/google/cpu_features |

## 源码获取

### 应用源码

本应用完整源码托管于 GitHub：

**https://github.com/pisces312/StreamClip**

### FFmpegKit 定制构建

本应用使用的 FFmpegKit 为基于官方源码的定制构建，包含以下修改：
- 仅编译 arm64-v8a 架构
- 启用 x264/x265 编码器支持

定制构建脚本和文档：
**https://github.com/pisces312/ffmpeg-kit**

原始 FFmpegKit 源码：
**https://github.com/arthenica/ffmpeg-kit**

## 商业使用说明

本应用遵循 GPL-3.0 许可证，允许商业使用，但需遵守以下义务：

1. **提供源码**：分发本应用时，必须同时提供完整源码或源码获取方式
2. **保留版权声明**：不得移除或修改版权声明和许可证文本
3. **相同许可证**：基于本应用的衍生作品必须以 GPL-3.0 或兼容许可证发布
4. **允许收费**：可以销售本应用，但购买者享有上述所有权利

## 联系方式

如有许可证相关问题，请通过 GitHub Issues 联系：

https://github.com/pisces312/StreamClip/issues
