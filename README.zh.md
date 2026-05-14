# StreamClip（轻剪辑）

基于 FFmpeg 的 Android 视频处理工具。

[![GitHub release](https://img.shields.io/github/v/release/pisces312/StreamClip)](https://github.com/pisces312/StreamClip/releases)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)

---

## ✨ 核心亮点

### ⚡ 无损瞬间完成

| 功能 | 原理 | 速度 |
|---|---|---|
| 视频裁剪 | `-c copy` 流复制，不重新编码 | 秒级 |
| 音频提取 | 直接抽取音频流，无损导出 | 秒级 |
| 视频合并 | 拼接容器，不触碰音视频数据 | 秒级 |

> 所有无损操作均直接复制原始数据流，**不重新编码**，既保证画质零损失，又实现秒级完成。

### 📸 完整元数据保留

- **拍摄时间** — 保留原始拍摄时间戳
- **GPS 地理位置** — 经纬度信息完整保留，Android 图库可识别
- **设备信息** — 相机型号、制造商等 EXIF 信息
- **旋转角度** — 视频方向标记不丢失

> 即使经过压缩处理，元数据依然可以通过 `-map_metadata 0` 完整保留到输出文件。

### 🚀 硬件编码压缩

- **MediaCodec 硬编码** — 利用手机 GPU 编解码器，速度极快
- **H.264 / H.265 双支持** — 根据需求选择兼容性或压缩率
- **软硬编码自由切换** — 硬编码失败自动回退软编码
- **压缩后元数据仍在** — 拍摄时间、GPS 等关键信息不丢失

---

## 🛠 功能一览

| 功能 | 说明 |
|---|---|
| 视频裁剪 | 选择时间范围，无损裁剪视频片段 |
| 音频提取 | 从视频中提取音频，支持 AAC / MP3 / FLAC / WAV |
| 视频合并 | 将多个视频按顺序拼接为一个文件 |
| 视频压缩 | 硬编码 / 软编码可选，支持 H.264 / H.265 |
| 自定义 FFmpeg | 输入任意 FFmpeg 参数，带实时日志输出 |

---

## 📊 实时进度

- 百分比
- 已用时间
- 预估剩余时间
- 输出文件大小

## 🔄 批量处理

支持任务队列和状态追踪，多个任务自动排队依次执行。

## 🌐 多语言

- 🇨🇳 简体中文
- 🇺🇸 English

## 🔒 防休眠

执行期间保持屏幕常亮（默认开启），避免后台被系统清理。

---

## 🏗 技术栈

- Kotlin + Android SDK 35
- FFmpeg-kit 6.0（arm64 自编译）
- Material Design 3
- ViewPager2 + TabLayout

---

## 📥 下载与构建

前往 [Releases](https://github.com/pisces312/StreamClip/releases) 下载预编译 APK。

```bash
./gradlew assembleRelease
```

需要配置签名环境变量：`KEY_ALIAS`、`KEY_PASSWORD`、`KEY_STORE`、`KEY_STORE_PASSWORD`

---

## 📜 版本历史

| 版本 | 更新内容 |
|---|---|
| **v1.3.1** | 批处理队列、帧率控制、双语 README |
| **v1.3.0** | 自定义 FFmpeg 命令页面、GPS 元数据修复 |
| **v1.2.0** | 视频压缩（硬编码+软编码）、GPS 元数据保留、捐赠功能 |
| **v1.1.0** | 压缩功能重构，H.264/H.265 Tab 切换 |
| **v1.0.0** | 初始版本：裁剪 / 音频提取 / 合并 |

---

## ☕ 捐赠

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

---

## 📄 许可

[GPL-3.0](LICENSE)
