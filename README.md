# StreamClip（剪流）

Android 视频处理工具，基于 FFmpeg 实现视频裁剪、音频提取、视频合并功能。

## 请我喝杯咖啡 | 捐赠支持

若项目对你有帮助，欢迎小额赞助，助力持续维护 ✨

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

## 功能

| 功能 | 说明 |
|------|------|
| 视频裁剪 | 选择时间范围，保留选定片段 |
| 音频提取 | 从视频中提取音频，支持 AAC/MP3/FLAC/WAV |
| 视频合并 | 将多个视频按顺序拼接为一个文件 |

## 使用说明

### 视频合并

1. 进入「合并」标签页
2. 点击「添加视频」打开文件选择器
3. **长按第一个视频**触发多选模式，继续选择其他视频
4. 点击「开始合并」

**注意**：合并的视频必须参数一致（分辨率、编码格式、帧率、旋转角度），否则程序会提示不兼容并阻断合并。

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

- v1.0.0 — 初始版本，裁剪/音频提取/合并基础功能


