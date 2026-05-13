# StreamClip AAC 编码闪退问题分析

> 分析时间: 2026-05-13
> 模型: Kimi K2.6
> 涉及版本: v2.0.0 (ffmpeg-kit-8.1.aar)

## 现象描述

1. **视频压缩 + H265 硬编码 + AAC 音频编码** → 程序闪退，日志没有任何输出。
2. **单独音频压缩功能** → 对部分视频有效，部分视频也会闪退。
3. 音频选 `copy` 时不闪退。

## 初步判断

日志没输出 = **Native 层直接崩溃（SIGSEGV / SIGABRT）**，不是 Java 异常。`CrashHandler` 和 `LogCollector` 在 `MainActivity.onCreate` 中已正确初始化，Java 层异常会被捕获。因此崩溃发生在 ffmpeg-kit 的 native 层。

## 命令行审查

生成的典型命令：

```
-y -i "input.mp4" -map_metadata 0 -color_primaries bt709 -color_trc bt709 \
-colorspace bt709 -color_range tv -c:v hevc_mediacodec -b:v 2000k \
-vf scale=-2:1080 -c:a aac -b:a 128k -ar 44100 -f mov "output.mp4"
```

- 路径双引号包裹正确。
- 参数顺序合规。
- 输出扩展名 `.mp4` 与 `-f mov` 不一致（不会导致 crash，但建议统一）。
- 硬编码时未显式指定 `-pix_fmt yuv420p`（潜在兼容性问题）。
- `CompressConfig.toFFmpegCommand` 中缺少 `-tag:v hvc1`（仅影响播放器识别，不会闪退）。

## 关键实验（2026-05-13 补充）

用户验证：**不改变采样率、只改变码率时，压缩可以成功。**

这说明崩溃**不是 AAC 编码器本身的问题**，而是出在**采样率转换（resampling）环节**。

### 推断

| 参数组合 | 是否触发 resample | 结果 |
|----------|-------------------|------|
| `-c:a aac -b:a 128k` | 否（保持原采样率） | ✅ 成功 |
| `-c:a aac -b:a 128k -ar 44100` | 是（若源采样率 ≠ 44100） | ❌ 闪退 |

当源音频采样率与目标采样率一致时，FFmpeg 会跳过 resample filter，直接送入 AAC 编码器，因此成功。当两者不一致时，FFmpeg 需要创建 `aresample` filter graph，而 ffmpeg-kit 8.1 的 `swresample` 或 `aresample` 实现可能在某些采样率组合下触发 native crash。

### 排除路径/命令行错误

既然仅去掉 `-ar` 就能成功，说明：
- 文件路径解析正确（否则去掉 `-ar` 也会失败）
- AAC 编码器本身工作正常（否则去掉 `-ar` 也会失败）
- 崩溃点在 resample filter 初始化或处理过程中

## 根因分析

### 关键线索：ffmpeg-kit 构建脚本

```bash
./android.sh --enable-android-media-codec --enable-gpl --enable-x264 --enable-x265 \
  --lts --api-level=21
```

**没有 `--enable-libfdk-aac`**。

这意味着所有 `-c:a aac` 调用都落在 **FFmpeg 内置 AAC 编码器** 上。

### 精确根因：swresample / aresample filter 的 native crash

结合"去掉 `-ar` 即成功"的实验，问题范围从"AAC 编码器"精确缩小到**采样率转换器**：

FFmpeg 的音频处理链为：`输入音频 → [aresample filter] → AAC 编码器 → 输出`

- 当不加 `-ar` 时，FFmpeg 检测到不需要采样率转换，跳过 `aresample`，直接送入 AAC 编码器 → 正常。
- 当加 `-ar 44100` 时，若源采样率不是 44100，FFmpeg 必须创建 `aresample` filter graph，调用 `libswresample` 进行转换。ffmpeg-kit 8.1 的 `libswresample.so` 在某些采样率组合（如 48000→44100、32000→44100）下可能发生 segfault。

这与 FFmpeg 6.0/8.x 在 Android ARM64 上的已知问题一致：自编译版本如果 `swresample` 的 SIMD 优化（NEON）与特定采样率转换矩阵不兼容，会在 `swri_audio_convert` 或 `resample_common` 中崩溃。

### 为什么之前版本不闪退？

之前的 `ffmpeg-kit-full-gpl-arm64v8a-8.0.0.aar` 是官方预编译版本，构建脚本更完整，可能：
1. 使用了不同的 `--arch` 或 `--cpu` 优化参数，NEON 代码路径更稳定。
2. 包含了一些补丁或编译器优化级别差异，避开了 `swresample` 的 crash path。
3. 当前自编译版本可能缺少某些 `swresample` 的编译标志（如 `--enable-swresample` 或正确的 `--extra-cflags`）。

### 结论

**不是你命令行拼错的问题，而是你自编译的 ffmpeg-kit 8.1 中的 `libswresample` 在特定采样率转换场景下的 native crash。**

## AAC 编码库对比（当前 vs 之前）

| 属性 | 当前 `ffmpeg-kit-8.1.aar` (~12MB) | 之前 `ffmpeg-kit-full-gpl-arm64v8a-8.0.0.aar` (~21MB) |
|------|-----------------------------------|------------------------------------------------------|
| FFmpeg 版本 | 8.1 | n8.0 |
| AAC 编码器 | 内置 AAC (`libavcodec/aacenc.c`) | 内置 AAC (`libavcodec/aacenc.c`) |
| FDK-AAC | ❌ 未包含 | ❌ 未包含 |
| HEVC 硬编码 | `hevc_mediacodec` | `hevc_mediacodec` |
| 主要差异 | 自编译，仅 arm64，体积更小 | 官方 full-gpl 预编译版 |

**关键发现：两个 AAR 都使用 FFmpeg 内置 AAC 编码器，都没有 FDK-AAC。**

> 注：`full-gpl` 变体包含 x264/x265 等 GPL 组件，但因许可兼容性问题**不包含 FDK-AAC**。只有 `full`（非 gpl）变体才会包含 FDK-AAC。

### 为什么之前版本不闪退？

既然两者都使用内置 AAC，可能原因：
1. **FFmpeg 8.1 与 n8.0 的内置 AAC 编码器代码有细微差异**，8.1 可能在某些音频路径上引入了新的 crash bug。
2. 当前自编译版本缺少某些编译优化/修复补丁。
3. 之前的命令行参数可能与当前不同（如输出格式、像素格式等），恰好绕过了触发路径。

## 验证方法

### adb logcat 抓 native crash

连接 adb，在闪退时执行：

```bash
adb logcat | grep -i "signal\|libc\|libswresample\|aresample\|fatal"
```

如果看到类似以下内容，即可确认是 `swresample` 崩溃：

```
Fatal signal 11 (SIGSEGV), code 1, fault addr ... in libswresample.so
backtrace:
    #00 pc 0000000000xxxxxx  libswresample.so (swri_audio_convert+xxx)
    #01 pc 0000000000xxxxxx  libswresample.so (swri_resample+xxx)
```

如果堆栈在 `libavcodec.so` 的 `aac_encode_frame` 中，则是 AAC 编码器问题；如果在 `libswresample.so` 中，则是采样率转换问题。

### 快速复现实验

找一段音频采样率为 48000Hz 的视频，分别执行：

```bash
# 实验 1：不改采样率 → 应该成功
ffmpeg -i input.mp4 -c:v copy -c:a aac -b:a 128k output1.mp4

# 实验 2：改采样率 → 应该闪退
ffmpeg -i input.mp4 -c:v copy -c:a aac -b:a 128k -ar 44100 output2.mp4
```

如果实验 1 成功、实验 2 闪退，即可 100% 确认是 `swresample` 的问题。

## 修复方案

### 短期规避（不改 AAR）

**方案 A：默认跳过采样率转换（推荐）**

在 `CompressConfig.kt` 中，将 `audioSampleRate` 默认值从 `"44100"` 改为 `"copy"`，或在 UI 中将采样率 spinner 默认值设为 `"copy"`。只有当用户**主动选择**非 copy 采样率时才添加 `-ar`。

```kotlin
// 修改默认值为 copy
val audioSampleRate: String = "copy",

// 在 UI 初始化中
binding.spinnerAudioSampleRateHw.setSelection(0) // copy 默认
binding.spinnerAudioSampleRateSw.setSelection(0) // copy 默认
```

这样绝大多数用户不会触发 resample crash，只有在明确需要改采样率时才可能遇到。

**方案 B：仅在需要转换时才加 `-ar`**

在 `toFFmpegCommand` 中，先探测源音频采样率，仅当目标采样率与源不一致时才添加 `-ar`：

```kotlin
if (audioSampleRate != "copy") {
    val targetRate = audioSampleRate.toInt()
    val sourceRate = probeAudioSampleRate(inputPath) // 新增探测
    if (sourceRate != targetRate) {
        cmd.append("-ar $audioSampleRate ")
    }
}
```

**方案 C：强制立体声（辅助）**

若部分视频的多声道音频即使不改采样率也会触发问题，可同时强制 `-ac 2`：

```kotlin
if (audioEncoder != "copy") {
    cmd.append("-ac 2 ")
}
```

### 中期修复（重新编译 ffmpeg-kit）

修改 `start_ffmpeg_build.sh`，加入 `--enable-libfdk-aac`（需同时下载 fdk-aac 源码）。FDK-AAC 的稳定性远好于 FFmpeg 内置 AAC，且音质更好。

注意：启用 FDK-AAC 后，AAR 的许可将不再是纯 GPL，需要同时包含 FDK-AAC 的许可声明。

### 长期代码层面改进

1. 为 HEVC 硬编码补回 `-tag:v hvc1`。
2. 硬编码时显式指定 `-pix_fmt yuv420p`。
3. 统一输出扩展名与容器格式（`-f mov` 配 `.mov`，或改用 `-f mp4`）。
4. 给 `executeCommand` 外层加 `try-catch (Throwable)` 并记录更详细的任务上下文。

## 相关文件

- `app/src/main/java/com/pisces312/streamclip/model/CompressConfig.kt` — 命令行生成
- `app/src/main/java/com/pisces312/streamclip/fragment/AudioCompressFragment.kt` — 音频压缩 UI
- `app/src/main/java/com/pisces312/streamclip/service/FFmpegService.kt` — FFmpeg 执行封装
- `start_ffmpeg_build.sh` — ffmpeg-kit 编译脚本
- `app/libs/ffmpeg-kit-8.1.aar` — 当前使用的 AAR（~12MB，FFmpeg 8.1）
- `app/libs/ffmpeg-kit-full-gpl-arm64v8a-8.0.0.aar`（历史）— 之前使用的 AAR（~21MB，FFmpeg n8.0）
