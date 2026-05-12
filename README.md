# StreamClip（剪流）

An Android video processing app built on FFmpeg — 基于 FFmpeg 的 Android 视频处理工具。

[![GitHub release](https://img.shields.io/github/v/release/pisces312/StreamClip)](https://github.com/pisces312/StreamClip/releases)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)

---

## ✨ 核心亮点 / Core Highlights

### ⚡ 无损瞬间完成 / Lossless & Instant

| 功能 / Feature | 原理 / Principle | 速度 / Speed |
|---|---|---|
| 视频裁剪 / Trim | `-c copy` 流复制，不重新编码 | 秒级 / Seconds |
| 音频提取 / Extract Audio | 直接抽取音频流，无损导出 | 秒级 / Seconds |
| 视频合并 / Merge | 拼接容器，不触碰音视频数据 | 秒级 / Seconds |

> 所有无损操作均直接复制原始数据流，**不重新编码**，既保证画质零损失，又实现秒级完成。<br>
> All lossless operations use stream copying, **no re-encoding**, ensuring zero quality loss and near-instant completion.

### 📸 完整元数据保留 / Full Metadata Preservation

- **拍摄时间 / Creation Time** — 保留原始拍摄时间戳
- **GPS 地理位置 / GPS Location** — 经纬度信息完整保留，Android 图库可识别
- **设备信息 / Device Info** — 相机型号、制造商等 EXIF 信息
- **旋转角度 / Rotation** — 视频方向标记不丢失

> 即使经过压缩处理，元数据依然可以通过 `-map_metadata 0` 完整保留到输出文件。<br>
> Even after compression, metadata is preserved via `-map_metadata 0` in the output file.

### 🚀 硬件编码压缩 / Hardware-Accelerated Compression

- **MediaCodec 硬编码 / Hardware Encoding** — 利用手机 GPU 编解码器，速度极快
- **H.264 / H.265 双支持 / Dual Codec** — 根据需求选择兼容性或压缩率
- **软硬编码自由切换 / Flexible Switching** — 硬编码失败自动回退软编码
- **压缩后元数据仍在 / Metadata Kept** — 拍摄时间、GPS 等关键信息不丢失

---

## 🛠 功能一览 / Feature Overview

| 功能 / Feature | 说明 / Description |
|---|---|
| 视频裁剪 / Trim | 选择时间范围，无损裁剪视频片段 |
| 音频提取 / Extract Audio | 从视频中提取音频，支持 AAC / MP3 / FLAC / WAV |
| 视频合并 / Merge | 将多个视频按顺序拼接为一个文件 |
| 视频压缩 / Compress | 硬编码 / 软编码可选，支持 H.264 / H.265 |
| 自定义 FFmpeg / Custom FFmpeg | 输入任意 FFmpeg 参数，带实时日志输出 |

---

## 📊 实时进度 / Real-Time Progress

- 百分比 / Percentage
- 已用时间 / Elapsed time
- 预估剩余时间 / Estimated remaining time
- 输出文件大小 / Output file size

## 🔄 批量处理 / Batch Processing

支持任务队列和状态追踪，多个任务自动排队依次执行。<br>
Queue multiple tasks with status tracking, processed sequentially.

## 🌐 多语言 / i18n

- 🇨🇳 简体中文 / Simplified Chinese
- 🇺🇸 English / 英文

## 🔒 防休眠 / Anti-Sleep

执行期间保持屏幕常亮（默认开启），避免后台被系统清理。<br>
Keeps screen awake during processing (enabled by default).

---

## 🏗 技术栈 / Tech Stack

- Kotlin + Android SDK 35
- FFmpeg-kit 8.1（arm64 自编译，GPL-3.0）
  - 构建源码：[ffmpeg-kit](https://github.com/pisces312/ffmpeg-kit) · [ffmpeg](https://github.com/pisces312/ffmpeg)
- Material Design 3
- ViewPager2 + TabLayout

---

## 📥 下载与构建 / Download & Build

前往 [Releases](https://github.com/pisces312/StreamClip/releases) 下载预编译 APK。<br>
Visit [Releases](https://github.com/pisces312/StreamClip/releases) for pre-built APKs.

```bash
./gradlew assembleRelease
```

需要配置签名环境变量：`KEY_ALIAS`、`KEY_PASSWORD`、`KEY_STORE`、`KEY_STORE_PASSWORD`

---

## 📜 版本历史 / Changelog

| 版本 / Version | 更新内容 / Changes |
|---|---|
| **v1.3.1** | 批处理队列、帧率控制、双语 README / Batch queue, frame rate control, bilingual README |
| **v1.3.0** | 自定义 FFmpeg 命令页面、GPS 元数据修复 / Custom FFmpeg page, GPS metadata fix |
| **v1.2.0** | 视频压缩（硬编码+软编码）、GPS 元数据保留、捐赠功能 / Compression, metadata preservation, donation |
| **v1.1.0** | 压缩功能重构，H.264/H.265 Tab 切换 / Compression refactor, H.264/H.265 tabs |
| **v1.0.0** | 初始版本：裁剪 / 音频提取 / 合并 / Initial release: trim / extract / merge |

---

## ☕ 捐赠 / Donate

如果这个项目对你有帮助，欢迎支持维护：<br>
If this project helps you, consider supporting maintenance:

<div align="center">
  <table>
    <tr>
      <td align="center" width="50%">
        <img src="app/src/main/assets/donate-alipay.png" width="200" alt="支付宝 / Alipay"><br>
        <b>支付宝 / Alipay</b>
      </td>
      <td align="center" width="50%">
        <img src="app/src/main/assets/donate-wechat.png" width="200" alt="微信 / WeChat"><br>
        <b>微信 / WeChat</b>
      </td>
    </tr>
  </table>
</div>

---

## 📄 许可 / License

本项目基于 [GPL-3.0](LICENSE) 发布，完整遵循该协议要求：

- **ffmpeg-kit 源码**：[github.com/pisces312/ffmpeg-kit](https://github.com/pisces312/ffmpeg-kit)
- **ffmpeg 源码**：[github.com/pisces312/FFmpeg](https://github.com/pisces312/FFmpeg)

对应源码与本项目使用完全相同的 GPL-3.0 许可证，可自由获取、修改和分发。
