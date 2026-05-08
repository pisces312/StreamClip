# StreamClip（剪流）

[English](./README.md) | 中文

基于 FFmpeg 的 Android 视频处理工具，支持视频裁剪、音频提取、视频合并、视频压缩等功能。

## 功能

| 功能 | 说明 |
|------|------|
| 视频裁剪 | 选择时间范围，保留选定片段 |
| 音频提取 | 从视频中提取音频，支持 AAC/MP3/FLAC/WAV |
| 视频合并 | 将多个视频按顺序拼接为一个文件 |
| 视频压缩 | 硬编码/软编码可选，支持 H.264/H.265 |
| 自定义 FFmpeg | 输入任意 FFmpeg 参数，带实时日志弹窗 |
| GPS 元数据 | 输出文件保留 GPS 信息，Android 图库可识别 |

## 亮点

- **实时进度** — 显示百分比、已用时间、预估剩余时间和输出文件大小
- **批处理** — 支持任务队列和状态追踪
- **防休眠** — 执行期间保持屏幕常亮（默认开启）
- **国际化** — 支持中英文切换
- **捐赠** — 支持支付宝和微信支付

## 技术栈

- Kotlin + Android SDK 35
- FFmpeg-kit 6.0（arm64 自编译）
- Material Design 3
- ViewPager2 + TabLayout

## 构建

```bash
./gradlew assembleRelease
```

需要配置签名环境变量：`KEY_ALIAS`, `KEY_PASSWORD`, `KEY_STORE`, `KEY_STORE_PASSWORD`

## 版本

- **v1.3.1** — 批处理队列、帧率控制、双语 README
- **v1.3.0** — 自定义 FFmpeg 命令页面，GPS 元数据修复
- **v1.2.0** — 视频压缩（硬编码+软编码）、GPS 元数据保留、捐赠功能、实时日志
- **v1.1.0** — 压缩功能重构，H.264/H.265 Tab 切换
- **v1.0.0** — 初始版本，裁剪/音频提取/合并基础功能

## 捐赠

如果这个项目对你有帮助，欢迎支持维护：

<div align="center">
  <table>
    <tr>
      <td align="center" width="50%">
        <img src="app/src/main/assets/donate-alipay.png" width="200" alt="支付宝"><br>
        <b>支付宝</b>
      </td>
      <td align="center" width="50%">
        <img src="app/src/main/assets/donate-wechat.png" width="200" alt="微信"><br>
        <b>微信</b>
      </td>
    </tr>
  </table>
</div>
