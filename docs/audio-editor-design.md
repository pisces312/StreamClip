# 音频编辑器 (Audio Editor)

> 在 StreamClip 中新增的音频编辑功能，支持波形可视化、裁剪、预览播放、多格式导出。

## 功能概览

| 功能 | 说明 |
|------|------|
| 文件选择 | 系统文件选择器，支持 content:// URI |
| 全格式解码 | MP3/FLAC/WAV/AAC/OGG/Opus/AMR/3GP/M4A |
| 波形可视化 | 多级缩放（8级）、拖拽浏览、选区标记 |
| 选区裁剪 | 拖动起点/终点设置裁剪范围 |
| 预览播放 | AudioTrack 流式播放，选区循环，seekTo 跳转 |
| 多格式导出 | MP3/FLAC/WAV，通过 FFmpeg 编码 |
| 淡入淡出 | afade filter，0~5 秒可调 |
| 录音 | （占位，后续实现） |

## 架构设计

### 技术选型

- **解码**：Android 原生 MediaExtractor + MediaCodec，无需第三方库
- **编码**：复用 StreamClip 已有的 ffmpeg-kit-8.1.aar (arm64-v8a)
- **播放**：AudioTrack 流式播放
- **波形**：参考 Ringdroid 算法，Kotlin 重写

### 数据流

```
音频文件 → AudioDecoder → PCM (ShortBuffer)
                              ├→ WaveformProcessor → WaveformView (显示)
                              ├→ AudioPlayer (播放)
                              └→ AudioEncoder → FFmpeg → 导出文件
```

## 核心文件

### 源码 (`app/src/main/java/com/pisces312/streamclip/`)

| 文件 | 职责 |
|------|------|
| `audio/AudioDecoder.kt` | MediaExtractor + MediaCodec 解码为 PCM |
| `audio/AudioEncoder.kt` | FFmpeg 编码 PCM → MP3/FLAC/WAV/M4A/Opus |
| `audio/AudioPlayer.kt` | AudioTrack 流式播放，选区回放 |
| `audio/WaveformProcessor.kt` | 多级缩放波形数据计算 |
| `audio/WaveformView.kt` | Canvas 波形绘制（缩放/拖拽/选区/播放线） |
| `ui/AudioEditorActivity.kt` | 编辑器主界面 Activity |
| `fragment/AudioEditorFragment.kt` | Tab 入口 Fragment |

### 资源文件

| 文件 | 说明 |
|------|------|
| `res/layout/fragment_audio_editor.xml` | Tab 入口页布局 |
| `res/layout/activity_audio_editor.xml` | 编辑器主布局 |
| `res/drawable/ic_audio_wave.xml` | Tab 图标 |
| `res/drawable/ic_audio.xml` | 选择音频按钮图标 |
| `res/drawable/ic_mic.xml` | 录音按钮图标 |
| `res/drawable/ic_back.xml` | 返回箭头 |
| `res/drawable/ic_pause.xml` | 暂停按钮 |
| `res/drawable/ic_rewind.xml` | 快退按钮 |
| `res/drawable/ic_ffwd.xml` | 快进按钮 |
| `res/drawable/ic_zoom_in.xml` | 放大按钮 |
| `res/drawable/ic_zoom_out.xml` | 缩小按钮 |

### Tab 接入点

集成到 StreamClip 需修改以下文件：

| 文件 | 修改内容 |
|------|---------|
| `util/TabOrderManager.kt` | `DEFAULT_ORDER` 和 `TAB_ICONS` 新增 `audio_editor` |
| `adapter/MainPagerAdapter.kt` | 新增 `AudioEditorFragment` 映射 |
| `MainActivity.kt` | `getTabText()` 新增 `title_audio_editor` |
| `ui/TabOrderActivity.kt` | `tabTitles` map 新增 `audio_editor` |
| `AndroidManifest.xml` | 注册 `AudioEditorActivity` |
| `res/values/strings.xml` | 新增 `title_audio_editor = "音频编辑"` |
| `res/values/colors.xml` | 新增波形配色（gray_900 + waveform_*） |

## 关键类详解

### AudioDecoder

```kotlin
data class DecodedAudio(
    val samples: ShortBuffer,    // interleaved PCM: {s1c1, s1c2, s2c1, ...}
    val sampleRate: Int,
    val channels: Int,
    val numSamples: Int,         // per channel
    val avgBitrateKbps: Int,
    val fileType: String,
    val fileSize: Int
)

fun decode(filePath: String, listener: ProgressListener? = null): DecodedAudio
```

- 使用 `MediaExtractor` 读取容器，`MediaCodec` 异步解码为 PCM 16-bit
- 解码后数据存入 `ShortBuffer`，供波形计算、播放、编码共用
- 支持进度回调，可取消

### AudioEncoder

```kotlin
enum class OutputFormat(val extension: String, val displayName: String) {
    MP3, FLAC, M4A, WAV, OPUS
}

data class EncodeConfig(
    val format: OutputFormat,
    val bitrate: Int = 192000,
    val fadeInSec: Float = 0f,
    val fadeOutSec: Float = 0f
)

fun encode(samples, sampleRate, channels, numSamples,
           startTimeSec, endTimeSec, outputPath, config): EncodeResult
```

- 工作流：选区 PCM → 临时 WAV → FFmpeg 转码 → 目标格式
- 淡入淡出通过 FFmpeg `afade` filter 实现
- MP3 用 `libmp3lame`，FLAC 用 `flac`，WAV 用 `pcm_s16le`

### WaveformView

- 5 级缩放，每级将采样数减半（类似 Ringdroid）
- Canvas 绘制波形竖线，选区高亮
- 支持触摸拖动选区边界、fling 滚动
- 播放位置实时更新（50ms 间隔）

### AudioPlayer

- `AudioTrack` MODE_STREAM 流式播放
- 支持选区播放范围 (`setPlaybackRange`)
- `seekTo` 跳转后自动续播/暂停
- 播放完成回调

## Android 格式支持参考

### 解码（MediaCodec）

| 格式 | 编码器 | Android 支持 |
|------|--------|-------------|
| MP3 | mp3 | API 1+ |
| FLAC | flac | API 14+ |
| WAV/PCM | pcm | API 1+ |
| AAC/LC | aac | API 1+ |
| OGG/Vorbis | vorbis | API 5+ |
| Opus | opus | API 21+ |
| AMR-NB/WB | amr | API 1+ |
| xHE-AAC | usac | API 33+ |

### 编码（通过 FFmpeg）

| 格式 | 编码器 | 说明 |
|------|--------|------|
| MP3 | libmp3lame | 需 FFmpeg 编译含 LAME |
| FLAC | flac | 无损 |
| WAV | pcm_s16le | 无压缩 |
| M4A/AAC | aac | Android 原生也可编码 |
| Opus | libopus | 需 FFmpeg 编译含 Opus |

## 编译修复记录

开发过程中遇到并解决的编译问题：

1. **ShortBuffer 未导入** — AudioDecoder.kt 添加 `java.nio.ShortBuffer` import
2. **companion object 内嵌套 enum** — AudioDecoder/AudioEncoder 的 `DecodedAudio`/`OutputFormat` 等从 companion object 移到类级别
3. **ProgressListener lambda 类型不匹配** — 改为 `object : ProgressListener` 显式实现
4. **isPlaying 属性 vs 函数** — `p.isPlaying` → `p.isPlaying()`
5. **AndroidManifest 未注册** — 补充 `AudioEditorActivity` 声明
6. **TabOrderActivity tabTitles 缺失** — 补充 `audio_editor` 映射

## 后续计划

- [ ] 录音功能
- [ ] 多段拼接
- [ ] 音量调节
- [ ] 均衡器
- [ ] 降噪
- [ ] 元数据编辑
