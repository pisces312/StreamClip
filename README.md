# StreamClip（剪流）

English | [中文](./README.zh.md)

An Android video processing app built on FFmpeg — 基于 FFmpeg 的 Android 视频处理工具。

## Features / 功能

| Feature | Description |
|---------|-------------|
| Trim | Cut video by time range（视频裁剪） |
| Extract Audio | Export audio in AAC/MP3/FLAC/WAV（音频提取） |
| Merge | Combine multiple videos（视频合并） |
| Compress | Hardware/Software encoding, H.264/H.265（视频压缩，硬/软编码） |
| Custom FFmpeg | Run arbitrary FFmpeg commands with real-time logs（自定义 FFmpeg 命令） |
| GPS Metadata | Preserve GPS data in output files（GPS 元数据保留） |

## Highlights / 亮点

- **Real-time progress / 实时进度** — Shows percentage, elapsed time, estimated remaining time, and output file size
- **Batch processing / 批处理** — Queue multiple tasks with status tracking
- **Anti-sleep / 防休眠** — Keeps screen awake during processing (enabled by default)
- **i18n / 国际化** — Supports Chinese and English (中/英文切换)
- **Donation / 捐赠** — Alipay & WeChat Pay supported

## Tech Stack / 技术栈

- Kotlin + Android SDK 35
- FFmpeg-kit 6.0 (arm64, self-built)
- Material Design 3
- ViewPager2 + TabLayout

## Build / 构建

```bash
./gradlew assembleRelease
```

Requires signing env vars: `KEY_ALIAS`, `KEY_PASSWORD`, `KEY_STORE`, `KEY_STORE_PASSWORD`

## Versions / 版本

- **v1.3.1** — Batch processing queue, frame rate control, bilingual README
- **v1.3.0** — Custom FFmpeg command page, GPS metadata fix
- **v1.2.0** — Video compression (hardware + software encoding), GPS metadata preservation, donation, real-time logs
- **v1.1.0** — Compression refactor with H.264/H.265 tabs
- **v1.0.0** — Initial release (trim/extract/merge)

## Donate / 捐赠

If this project helps you, consider supporting maintenance:

<div align="center">
  <table>
    <tr>
      <td align="center" width="50%">
        <img src="app/src/main/assets/donate-alipay.png" width="200" alt="Alipay"><br>
        <b>Alipay</b>
      </td>
      <td align="center" width="50%">
        <img src="app/src/main/assets/donate-wechat.png" width="200" alt="WeChat"><br>
        <b>WeChat</b>
      </td>
    </tr>
  </table>
</div>

---

[中文说明](./README.zh.md)
