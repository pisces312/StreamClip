# StreamClip 原生视频压缩（视频压缩(原生)）设计实现文档

## 1. 需求概述

新增一个名为"视频压缩(原生)"的标签页，提供不依赖 FFmpeg、仅使用 Android 系统自带 MediaCodec API 的视频压缩能力。当 FFmpeg 方式因兼容性问题无法压缩时，用户可 fallback 到此方式。

### 1.1 功能范围

- 单文件视频压缩（暂不支持批量）
- 显示当前设备所有可用的 H.264/H.265 编码器
- 默认优先选择 H.265 硬件编码器
- 支持调节分辨率、码率、帧率、I 帧间隔
- 不保留元数据（与 FFmpeg 压缩区分）

### 1.2 非目标

- 批量压缩队列（保持与现有功能隔离，后续可扩展）
- 元数据保留（原生 MediaCodec + MediaMuxer 对元数据支持有限）
- 音频重新编码（复用原始音频轨道，仅做复制）

---

## 2. 背景与动机

StreamClip 现有的"视频压缩"功能基于 FFmpeg 实现，功能强大但存在以下限制：

1. **兼容性问题**：部分设备（尤其是某些国产 ROM）对 FFmpeg 的 so 库加载不稳定，存在崩溃风险。
2. **体积与权限**：FFmpeg 二进制体积大，且需要 JNI 层交互。
3. **Fallback 需求**：用户反馈某些视频在 FFmpeg 压缩时失败，需要一个更轻量、兼容性更高的备选方案。

Android 系统自 4.1（API 16）起提供 `MediaCodec` API，自 5.0（API 21）起支持 `MediaMuxer`，可在不引入任何第三方 native 库的情况下完成视频重编码。这种方式的兼容性完全取决于设备厂商的编码器实现，覆盖范围极广。

---

## 3. VideoCompressor 开源库选型

选用 [VideoCompressor](https://github.com/vincentmi/VideoCompressor)（MIT 许可证）作为基础，原因：

- 纯 Java 实现，仅依赖 Android SDK 和 `isoparser`/`aspectjrt` 两个 JAR
- 逻辑清晰：MediaExtractor → MediaCodec 解码 → OpenGL 缩放 → MediaCodec 编码 → MediaMuxer 封装
- 许可证允许修改后闭源使用

### 3.1 原库局限性

| 局限 | 说明 |
|------|------|
| 仅支持 H.264 | 硬编码 `MIME_TYPE = "video/avc"`，无 H.265 支持 |
| 无编码器选择 | 使用 `MediaCodec.createEncoderByType()`，由系统自动选择 |
| 参数不可调节 | 仅提供 High/Medium/Low 三档固定质量，内部写死分辨率缩放和码率计算 |
| 使用已废弃 API | 基于 `AsyncTask` 执行异步任务 |

---

## 4. 技术方案

### 4.1 总体架构

```
┌─────────────────────────────────────────────────────────────┐
│                        UI Layer                              │
│  ┌───────────────────────────────────────────────────────┐  │
│  │              NativeCompressFragment                    │  │
│  │  • 编码器选择 Spinner（枚举所有 H.264/H.265 编码器）    │  │
│  │  • 分辨率选择 Spinner（原始 / 缩小 1.5x~3.0x）         │  │
│  │  • 码率选择 Spinner（自动 / 1~8 Mbps）                 │  │
│  │  • 帧率选择 Spinner（原始 / 24/25/30/60 fps）          │  │
│  │  • I 帧间隔 SeekBar（1~10 秒）                         │  │
│  └───────────────────────────────────────────────────────┘  │
└──────────────────────────┬──────────────────────────────────┘
                           │ Kotlin Coroutine
┌──────────────────────────▼──────────────────────────────────┐
│                   NativeVideoCompressor                      │
│  • listAvailableEncoders()  — 枚举设备编码器                  │
│  • getDefaultHevcEncoder()  — 获取默认 H.265 硬件编码器       │
│  • compressVideo()          — suspend + 进度回调              │
└──────────────────────────┬──────────────────────────────────┘
                           │ JNI / Java
┌──────────────────────────▼──────────────────────────────────┐
│              VideoController (改造后的 Java 类)              │
│  • convertVideo() 新增参数：mimeType, width, height,         │
│    bitrate, frameRate, iFrameInterval, encoderName          │
│  • MediaCodec.createByCodecName() 指定编码器                 │
│  • MediaFormat.createVideoFormat() 使用传入的 MIME type      │
└─────────────────────────────────────────────────────────────┘
```

### 4.2 源码改造清单

将 VideoCompressor 的 Java 源码复制到 `com.pisces312.streamclip.videocompressor` 包下，进行以下改造：

#### 4.2.1 VideoController.java

| 改造点 | 原实现 | 改造后 |
|--------|--------|--------|
| MIME 类型常量 | `MIME_TYPE = "video/avc"` | `DEFAULT_MIME_TYPE = "video/avc"`，运行时传入 `mimeType` 参数 |
| 编码器创建 | `MediaCodec.createEncoderByType(MIME_TYPE)` | `MediaCodec.createByCodecName(encoderName)`，若 `encoderName` 为空则 fallback 到 `createEncoderByType(mimeType)` |
| 分辨率 | 内部根据 `quality` 计算缩放比例 | 新增 `customWidth`、`customHeight` 参数，为 0 时保持原始分辨率 |
| 码率 | 内部固定公式计算 | 新增 `customBitrate` 参数，为 0 时使用原始码率 |
| 帧率 | 未显式设置 | 新增 `customFrameRate` 参数，写入 `MediaFormat.KEY_FRAME_RATE` |
| I 帧间隔 | 硬编码 5 秒 | 新增 `customIFrameInterval` 参数，写入 `MediaFormat.KEY_I_FRAME_INTERVAL` |
| 音频轨道 | 仅复制 `audio/mp4a-latm` | 扩展支持 `audio/mp4a-latm`、`audio/ac3`、`audio/eac3`、`audio/vorbis` |
| `convertVideo` 签名 | `convertVideo(source, dest, quality, listener)` | `convertVideo(source, dest, quality, listener, mimeType, customWidth, customHeight, customBitrate, customFrameRate, customIFrameInterval, encoderName)` |

#### 4.2.2 VideoCompress.java

将内部的 `AsyncTask` 调用适配到新的 `convertVideo` 签名，为保持向后兼容，未使用的参数传 `null` / `0`。

#### 4.2.3 新增 NativeVideoCompressor.kt

Kotlin 封装层，提供协程友好的 API：

```kotlin
data class NativeCompressConfig(
    val mimeType: String = "video/hevc",      // 默认 H.265
    val encoderName: String? = null,          // 编码器名称，null 则自动选择
    val targetWidth: Int = 0,                 // 目标宽，0 = 原始
    val targetHeight: Int = 0,                // 目标高，0 = 原始
    val bitrateKbps: Int = 0,                 // 目标码率(kbps)，0 = 原始
    val frameRate: Int = 30,                  // 目标帧率
    val iFrameInterval: Int = 10              // I 帧间隔(秒)
)

data class EncoderInfo(
    val name: String,                         // 编码器名称，如 "c2.android.hevc.encoder"
    val mimeType: String,                     // "video/avc" 或 "video/hevc"
    val isHardware: Boolean                   // 是否硬件编码器
)

object NativeVideoCompressor {
    fun listAvailableEncoders(): List<EncoderInfo>
    fun getDefaultHevcEncoder(): EncoderInfo?
    suspend fun compressVideo(
        inputPath: String,
        outputPath: String,
        config: NativeCompressConfig,
        onProgress: (Float) -> Unit
    ): Result<Unit>
}
```

**编码器枚举逻辑**：使用 `MediaCodecList(MediaCodecList.ALL_CODECS)` 遍历所有 codec，筛选出 `isEncoder == true` 且 `supportedTypes` 包含 `video/avc` 或 `video/hevc` 的项。硬件编码器判断依据为名称包含 `omx`、`c2` 或 `hw`（不区分大小写）。

**默认选择策略**：优先选 `video/hevc` + `isHardware` 的编码器；若无则退而求其次选任意 H.265 编码器；再没有则列表第一个。

---

## 5. UI 设计

### 5.1 界面布局

文件：`res/layout/fragment_native_compress.xml`

采用 `ScrollView` + `LinearLayout` 纵向布局，从上到下依次为：

1. **说明卡片**（`MaterialCardView`）：
   - 标题："原生压缩说明"
   - 内容："此功能仅依赖 Android 系统自带的 MediaCodec 库，无需 FFmpeg。兼容性最高，当 FFmpeg 方式无法压缩时可选用此方式。"

2. **原始视频信息卡片**：选择视频后显示路径、大小、分辨率、帧率、码率。

3. **配置区域**：
   - 编码器（`Spinner`）：显示所有可用编码器的友好名称，如 `H.265 (硬件) - c2.qti.hevc.encoder`
   - 分辨率（`Spinner`）：原始 / 缩小 1.5x / 缩小 2.0x / 缩小 2.25x / 缩小 3.0x
   - 码率（`Spinner`）：自动 / 1~8 Mbps
   - 帧率（`Spinner`）：原始 / 24 / 25 / 30 / 60 fps
   - I 帧间隔（`SeekBar` + `TextView`）：1~10 秒，默认 3 秒

4. **操作区域**：
   - "选择视频" 按钮
   - "开始压缩" 按钮
   - 进度条 + 百分比文字

5. **输出视频信息卡片**：压缩完成后显示输出文件大小和分辨率。

### 5.2 关键交互逻辑

- 未选择视频时，"开始压缩"按钮可点击但会提示请先选择视频。
- 选择视频后，通过 `MediaMetadataRetriever` 探测原始信息，并动态计算分辨率选项的实际像素值（如 `1280x720 (缩小 1.5x)`）。
- 压缩过程使用 `lifecycleScope.launch` + `suspend` 函数，支持在 Fragment 销毁时自动取消（`currentJob?.cancel()`）。
- 若用户在设置中开启了"保持屏幕常亮"，压缩期间会添加 `FLAG_KEEP_SCREEN_ON`。

---

## 6. 集成点

### 6.1 Tab 注册

新增标签页需要在以下文件中注册：

| 文件 | 修改内容 |
|------|----------|
| `TabOrderManager.kt` | `DEFAULT_ORDER` 列表中加入 `"native_compress"`；`TAB_ICONS` 映射中加入图标（复用 `ic_compress`） |
| `MainPagerAdapter.kt` | `createFragment()` 的 `when` 分支中加入 `"native_compress" -> NativeCompressFragment()` |
| `MainActivity.kt` | `getTabTitle()` 中加入 `"native_compress" -> getString(R.string.title_native_compress)` |

### 6.2 字符串资源

新增以下字符串（`values/strings.xml` 和 `values-en/strings.xml` 均需添加）：

| Key | 中文 | 英文 |
|-----|------|------|
| `title_native_compress` | 视频压缩(原生) | Video Compress (Native) |
| `native_compress_hint_title` | 原生压缩说明 | Native Compression |
| `native_compress_hint` | 此功能仅依赖 Android 系统自带的 MediaCodec 库... | This feature relies solely on Android built-in MediaCodec library... |
| `cfg_native_encoder` | 编码器 | Encoder |
| `cfg_native_iframe_interval` | I帧间隔 | I-Frame Interval |
| `start_native_compress` | 开始压缩 | Start Compression |
| `native_compressing` | 原生压缩中... | Native compressing... |

### 6.3 依赖配置

VideoCompressor 源码依赖两个 JAR 库，需复制到 `app/libs/` 并在 `build.gradle.kts` 中声明：

```kotlin
implementation(files("libs/isoparser-1.0.6.jar"))
implementation(files("libs/aspectjrt-1.7.3.jar"))
```

---

## 7. 文件清单

### 7.1 新增文件

| 路径 | 说明 |
|------|------|
| `app/src/main/java/com/pisces312/streamclip/videocompressor/NativeVideoCompressor.kt` | Kotlin 封装层：配置数据类、编码器枚举、协程 API |
| `app/src/main/java/com/pisces312/streamclip/fragment/NativeCompressFragment.kt` | 原生压缩 Fragment，UI 逻辑与压缩流程控制 |
| `app/src/main/res/layout/fragment_native_compress.xml` | 原生压缩界面布局 |
| `app/libs/isoparser-1.0.6.jar` | MP4 容器解析库（来自 VideoCompressor） |
| `app/libs/aspectjrt-1.7.3.jar` | AspectJ 运行时（来自 VideoCompressor） |

### 7.2 复制并改造的文件（来自 VideoCompressor）

| 路径 | 改造内容 |
|------|----------|
| `app/src/main/java/com/pisces312/streamclip/videocompressor/VideoCompress.java` | 修改 `convertVideo` 调用，适配新参数签名 |
| `app/src/main/java/com/pisces312/streamclip/videocompressor/VideoController.java` | 核心改造：支持 H.265、编码器选择、自定义参数 |
| `app/src/main/java/com/pisces312/streamclip/videocompressor/MP4Builder.java` | 包名变更，无逻辑改动 |
| `app/src/main/java/com/pisces312/streamclip/videocompressor/OutputSurface.java` | 包名变更，无逻辑改动 |
| `app/src/main/java/com/pisces312/streamclip/videocompressor/Sample.java` | 包名变更，无逻辑改动 |
| `app/src/main/java/com/pisces312/streamclip/videocompressor/Track.java` | 包名变更，无逻辑改动 |

### 7.3 修改的现有文件

| 路径 | 修改内容 |
|------|----------|
| `app/build.gradle.kts` | 添加两个本地 JAR 依赖 |
| `app/src/main/java/com/pisces312/streamclip/util/TabOrderManager.kt` | `DEFAULT_ORDER` 和 `TAB_ICONS` 加入 `native_compress` |
| `app/src/main/java/com/pisces312/streamclip/adapter/MainPagerAdapter.kt` | `createFragment()` 加入 `native_compress` 分支及 import |
| `app/src/main/java/com/pisces312/streamclip/MainActivity.kt` | `getTabTitle()` 加入 `native_compress` 分支 |
| `app/src/main/res/values/strings.xml` | 新增原生压缩相关中文字符串 |
| `app/src/main/res/values-en/strings.xml` | 新增原生压缩相关英文字符串 |

---

## 8. 兼容性说明

### 8.1 编码器兼容性

- H.265（HEVC）硬件编码器在 Android 6.0+ 设备上较为普及，但低端设备可能仅有 H.264 硬件编码器。
- 若设备无 H.265 编码器，界面中的编码器 Spinner 会列出所有 H.264 编码器，用户仍可使用 H.264 进行压缩。
- 软件编码器（如 `c2.android.hevc.encoder`）速度极慢，仅作为 fallback，界面中会标注"软件"以示区分。

### 8.2 与 FFmpeg 压缩的区别

| 特性 | FFmpeg 压缩 | 原生压缩 |
|------|-------------|----------|
| 依赖 | ffmpeg-kit AAR（~20MB） | Android SDK 内置 |
| 编码格式 | H.264 / H.265 / VP9 等 | H.264 / H.265（取决于设备） |
| 元数据 | 可保留 / 可编辑 | 不保留（MediaMuxer 限制） |
| 音频处理 | 可重编码、可提取 | 仅复制原始音频轨道 |
| 滤镜 | 丰富（scale、crop、deinterlace 等） | 仅支持分辨率缩放（OpenGL） |
| 批量处理 | 支持 | 暂不支持 |
| 适用场景 | 功能完整、高质量需求 | 兼容性优先、快速 fallback |

---

## 9. 后续可扩展项

1. **批量压缩**：复用现有的 `BatchTaskService` + `TaskQueueManager` 框架，将 `NativeVideoCompressor.compressVideo()` 作为任务执行器。
2. **元数据保留**：调研 `MediaMuxer` 在 Android 10+ 的 `addTrack` / `setLocation` 等 API，有限保留创建时间、地理位置等基础信息。
3. **更多编码格式**：如 `video/av01`（AV1）在 Android 10+ 部分设备上的支持。
4. **编码器性能评分**：根据实际压缩速度对编码器进行排序或标记推荐。
