# AGENTS.md

本文件为在本仓库工作的 AI 编码代理（以及人类协作者）提供指引。

## Project Overview

StreamClip（轻剪辑）— Android 视频/音频处理工具，基于 FFmpeg 实现视频无损裁剪、合并、音频提取、视频压缩（硬/软编码）、音频压缩与音频编辑、自定义 FFmpeg/FFprobe 命令、元数据查看与编辑等功能。

- 单模块 Kotlin 项目（`:app`），`namespace = com.pisces312.streamclip`
- 当前版本 `versionCode 13` / `versionName 2.2.1`
- FFmpegKit 主路径仅支持 arm64-v8a（自编译 AAR 只含该 ABI）；Gradle 未额外配置 `abiFilters` 或 ABI splits
- **无 productFlavors**（2026-10-30 已移除原 full/github/store 三 flavor；差异仅是一个捐赠入口开关，详见 git `15f9c77`）

## Build & Run

```bash
# 常规 Gradle 构建（无 flavor，任务名不带前缀）
./gradlew assembleDebug
./gradlew assembleRelease

# 签名 release（arm64，zipalign + apksigner）
./build.sh                 # release
./build.sh debug           # debug
./build.sh release arm64 --no-minify

# 构建并安装到设备（透传参数给 build.sh，-d 指定设备）
./build-and-push.sh debug -d emulator-5554

# 只出 AAB（Google Play 上传用，Google 侧签名）
./build-aab.sh

# 手机本地构建（Termux + proot ubuntu, aarch64）
./build-termux.sh debug
```

> Termux 使用 JDK 21 回退，构建前需手动临时移走 `gradle/gradle-daemon-jvm.properties`，脚本不会自动处理。当前 `build-termux.sh` 仍检查 `platforms/android-36`，与 `compileSdk 37` 不一致，使用前需先修正。

### 构建环境（本机 Windows）

本机沙箱 shell 的 PATH 被裁剪，`java` / `git` / `adb` 常不在 PATH 中，构建前需显式设置：

```powershell
$env:JAVA_HOME = "D:\dev\jdk-25"
$env:PATH = "D:\dev\jdk-25\bin;$env:PATH"
$env:ANDROID_HOME = "D:\dev\android_sdk"
.\gradlew.bat :app:assembleDebug
```

git 在 `D:\dev\git\cmd\git.exe`。

### 签名

签名不在 Gradle 内配置，由 `build.sh` 完成：

- Keystore：`D:/my-projects/my-backup/backup-settings/my-android-release.keystore`
- Alias：`pisces312`
- 密码：环境变量 `KEY_STORE_PASSWORD`
- Build tools：`D:/dev/android_sdk/build-tools/36.0.0`

### 测试

**只有 host JVM 单元测试**：`app/src/test/`（JUnit，当前仅覆盖 `Mp4RotationUtils` 容器解析，约 0.3 s）。**无 Espresso / 无 `src/androidTest`**；FFmpeg 执行、Native 压缩、音频 PCM 编辑、批量队列和外部 Intent 均需真机手动回归。

## Architecture

**主界面为 `MainActivity + Fragment`**，`ViewPager2 + TabLayout` 做标签页导航，无 MVVM、无依赖注入、无 RxJava。Fragment 直接用 `lifecycleScope` 协程管理状态。

项目另有音频编辑、批量任务、日志、标签排序等独立 Activity。`CompressActivity` / `TrimActivity` / `ExtractActivity` / `MetadataActivity` 是 exported 的轻量容器，接收外部 `video/*` 的 `ACTION_VIEW`，复用对应 Fragment 并通过 `external_video_uri` 传入 `intent.data`。

### 标签页（`TabOrderManager.DEFAULT_ORDER`，用户可拖拽排序）

release 9 个：`settings`（设置）、`trim`（裁剪）、`merge`（合并）、`extract`（提取音频）、`compress`（压缩）、`audio_compress`（音频压缩）、`audio_editor`（音频编辑）、`custom`（自定义命令）、`metadata`（元数据）。
debug 额外含 `native_compress`（`NativeCompressFragment` 实验入口，release 不出现）。`trim2` / `Trim2Fragment` 仍保留映射，但不在 `DEFAULT_ORDER` 中，不是默认可达标签。

> 新增标签页时必须同步更新：`TabOrderManager`（`DEFAULT_ORDER` + `TAB_ICONS`）、`MainPagerAdapter`、`MainActivity`、`TabOrderActivity`。

### Key Layers

| 层 | 文件 |
|---|---|
| Activity | `MainActivity`（主入口，标签页容器、关于/捐赠对话框）、`BaseActivity`、`LogActivity`、`ui/BatchTaskActivity`、`ui/CompressActivity`、`ui/ExtractActivity`、`ui/TrimActivity`、`ui/MetadataActivity`、`ui/TabOrderActivity`、`ui/AudioEditorActivity` |
| Fragment | `TrimSimpleFragment`、`Trim2Fragment`、`MergeFragment`、`ExtractFragment`、`CompressFragment`、`AudioCompressFragment`、`AudioEditorFragment`、`CustomCommandFragment`、`MetadataFragment`、`SettingsTabFragment`、`NativeCompressFragment` |
| Service | `FFmpegService`（单例，FFmpeg/FFprobe 封装核心）、`BatchTaskService`（前台服务，批量处理）、`TaskQueueManager`（单例，线程安全队列 + StateFlow）、`MetadataService`、`BatchNotificationManager` |
| Model | `MediaInfo`、`VideoMetadata`、`CompressConfig`、`TaskConfig`、`TaskType`、`TaskStatus`、`BatchTaskItem` |
| Util | `FileUtils`（URI→路径，多级回退）、`SettingsManager`、`LogCollector`（内存+文件日志）、`LocaleHelper`、`CrashHandler`、`TabOrderManager`、`Mp4RotationUtils` |
| Audio | `audio/`：`AudioDecoder`、`AudioEncoder`、`AudioPlayer`、`WaveformView`、`WaveformProcessor`、`FastWaveformLoader`、`FFmpegWaveformLoader` |
| Native 压缩 | `videocompressor/NativeVideoCompressor`（基于 isoparser 的非 FFmpeg 实现路径） |

### FFmpeg Integration

FFmpeg 通过 **ffmpeg-kit 8.1 自编译 AAR**（`app/libs/ffmpeg-kit-8.1.aar`，包名 `com.arthenica.ffmpegkit`，GPL-3.0，仅 arm64-v8a，含 libx264 / libx265 / libmp3lame / libopencore_amrnb）。源码在 `D:\3rd-party-projects\ffmpeg-kit`。

- 执行：`FFmpegKit.executeAsync()` 包在 `suspendCancellableCoroutine` 中；探测：`FFprobeKit.execute()`
- 操作模式：
  - 裁剪：`-c copy`（无损）
  - 合并：concat demuxer
  - 提取音频：`-c:a copy`（无损）
  - 压缩：可配置编码器（`hevc_mediacodec` / `h264_mediacodec` / `libx265` / `libx264`）、码率或 CRF、缩放、帧率、色彩参数
  - 自定义命令：用户直接输入 FFmpeg 或 FFprobe 参数

`FFmpegService.executeCommand()` 用 `activeSessions`（ConcurrentHashMap）追踪自己创建的全部异步会话；`cancelCurrentSession()` 取消**最近启动的一个**会话（调用方都是单任务 UI 的取消按钮）。已知局限：批量任务与单任务并发时，取消按钮可能命中批量会话；批量侧取消走协程 job.cancel → `invokeOnCancellation` 按自身 sessionId 精确取消，不受此影响。需要原始 `FFmpegSession`（而非 `Result`）的同步调用方（`MetadataService`、`AudioEncoder`、`FFmpegWaveformLoader`）统一走 `FFmpegService.executeTrackedSync()`：内部用 executeAsync + latch，会话运行期同样注册进 `activeSessions`，因此也可被取消；不要再直接调 `FFmpegKit.execute()`。注意 `executeTrackedSync` 会阻塞调用线程，只能从后台线程调用。

### File Picking

使用 SAF（`ActivityResultContracts`），但 FFmpeg 最终需要文件路径。`FileUtils.getPathResultFromUri()` 依次尝试 `file://`、ExternalStorage/Downloads/Media document URI、MediaStore `_data` 查询，全部失败后复制到 cache。返回的 `PathResult.isDirectRead` 区分原地直读与缓存复制，不能假设所有 `content://` URI 都能获得真实路径。

## 当前实现边界（修改前必读）

### Native 压缩

- 仅在 debug 默认标签中出现，不接入批量队列；底层是 `MediaExtractor + MediaCodec + OpenGL + isoparser`，不是 FFmpeg 路径。
- `VideoController` 只在目标宽高与原始宽高不同才重编码视频；选择“原始分辨率”时实际复制视频流，编码器、码率、帧率和 I 帧间隔等参数不会驱动视频重编码，音频也走流复制。
- 当前 `convertVideo()` 最终固定 `return true`，Kotlin 封装的协程取消也不会立即终止阻塞中的 Java 转换循环。修改该路径必须核对 `VideoController.java`，不能只凭 `NativeVideoCompressor` 的 `Result` 判断真实成功或取消状态。

### 音频编辑

- 核心状态是完整解码后的 16-bit PCM；B/D 加载模式只加速波形预览，播放和编辑仍需完整 PCM，A 模式当前回退到 C。录音入口仍是开发占位。
- 导出先把选区 PCM 写成临时 WAV，再经 `AudioEncoder` → `FFmpegService.executeTrackedSync()` 执行（会话可取消）；播放使用 `AudioTrack + Thread`，不是 Media3/ExoPlayer。

### 批量任务

- `BatchTaskService` 以前台 Service 顺序执行，失败默认重试一次。模型支持 `COMPRESS` / `EXTRACT_AUDIO` / `CUSTOM_COMMAND`，但当前 UI 只在压缩页创建批量任务。
- 队列仅保存在内存，Service 为 `START_NOT_STICKY`，没有进程重启后的恢复能力。
- 取消/重试链路已修复（2026-10-31，见 `docs/batch-task-fix-plan.md`）：取消经 `BatchTaskService.cancelTask()` 真正终止运行协程与 FFmpeg 会话；`markCancelled()` 会把任务移出队列；CANCELLED/COMPLETED 为终态，迟到回调不可覆盖。暂停/恢复功能已整体移除（2026-10-31，产品决策：只需取消），通知栏仅保留取消按钮。

## 关键陷阱（AAR 升级 / 视频处理前必读）

### 1. FFmpeg 8.1 重编码不传播 display matrix

`ffmpeg_mux_init.c` 中 `coded_side_data`（含 display matrix / 旋转）**只在 `streamcopy_init()`（即 `-c copy`）里复制**。因此视频重编码时输出流会丢失旋转元数据。

- App 用 `-noautorotate` + 重编码，旋转选"保持不变"时**不能**指望 FFmpeg 保留方向
- 正确做法：压缩成功后显式调用 `Mp4RotationUtils.copyRotation(源文件, 输出文件)`
- 单任务（`CompressFragment`）和批量任务（`BatchTaskService`）两条链路都要调

### 2. ffprobe 的 rotation 符号与 `Mp4RotationUtils` 相反

`av_display_rotation_get()` 公式为 `-atan2(m[1], m[0])`，所以 ffprobe 显示 `-90` 的文件，其 tkhd 矩阵等价于 `Mp4RotationUtils` 的 **"90"** 分支。

**绝不要**把 `-90` 归一化成 `270` 再 `setRotation()`，会错 180°。`copyRotation` 已改为**逐字段原样复制**源矩阵（不再按 0/90/180/270 分类后重建），从根本上规避该陷阱。

### 2b. 不同 muxer 的 tkhd 平移量不同

同一旋转的矩阵 2x2 部分一致，但 tx/ty 会因 muxer 而异。实测两个竖拍源，都是 ffprobe `-90`：

| 源文件 | 矩阵（16 进制） | tx |
|---|---|---|
| 微信导出 4K | `0, 00010000, 0, FFFF0000, 0, 0, 08700000, 0, 40000000` | `2160 << 16`（= 编码高度） |
| 手机相机 720p | `0, 00010000, 0, FFFF0000, 0, 0, 0, 0, 40000000` | `0` |

因此**不能统一重建矩阵**，只能把源的 9 个 32 位字段原样写入目标（编码尺寸变化时才按比例缩放 tx/ty）。

### 3. tkhd 宽高不能为 90°/270° 交换

`tkhd` 的 width/height 必须保持**编码尺寸（未旋转）**，方向只由 matrix 表达。iPhone / ffmpeg 生成的文件都如此（4K 竖拍源实测：`tkhd 3840x2160` + 90° 矩阵）。

Android `MPEG4Extractor` 把 tkhd 宽高原样存成 `kKeyDisplayWidth/Height`（注释即 "presentation display size"），**不会**按矩阵转置。若写成 `2160x3840`，下游按 display 尺寸缩放时就会把竖屏画面塞进横框，表现为**缩略图是横屏且被压扁**；而播放器只看 matrix，播放仍正常，从而掩盖问题。

`Mp4RotationUtils` 早期版本会交换宽高，已修正为只写矩阵。

### 4. 负角度与取模

Kotlin 中 `-90 % 180 == -90`（不是 90）。判断横竖屏必须用 `MediaInfo.normalizedRotation`，不要直接 `rotation % 180 == 90`。

### 5. 色彩元数据来源

- `hevc_mediacodec` 只写容器 nclx box，不写 bitstream VUI
- 部分视频 nclx 与 VUI 不一致（如 nclx=BT.601 而 VUI=BT.709）
- 统一以 **ffprobe 读到的 bitstream VUI**（`color_primaries` / `color_transfer` / `colorspace`）为准，见 `FFmpegService.probeMediaInfo()` 与 `CompressConfig.toFFmpegCommand()`

### 6. 64 位 box size（size 字段 == 1）必须优先处理

ISO-BMFF 的 box 头有三种写法：`size=1` 表示随后 8 字节是 64 位 `largesize`（头部 16 字节）；`size=0` 表示延伸到文件末尾；其余为 32 位 size。

**`size=1` 必须在"size < 8 视为非法"的检查之前判断**，否则 64 位 box 会被当成非法并中断遍历。`mp4`/`mov` 容器常见 `mdat` 用 64 位形式，即使文件只有 100 MB。

> 实际故障：`VID_20261026_202905.mp4` 结构为 `ftyp / free / mdat(64bit) / moov`，旧版 `findVideoTkhdOffset()` 在 `mdat` 处 `break` 返回 null，`copyRotation` 静默失败 → 输出无旋转元数据 → 竖屏视频播放成"左转 90°"。同期的微信导出文件（`moov` 在前）不受影响，因此该 bug 隐藏了一轮迭代。

另外：视频轨识别优先用 `mdia/hdlr` 的 handler type `vide`，仅当读不到 handler 时回退到旧启发式（`volume == 0 && tkhd w > 0 && h > 0`）。

### 7. 缩放后的宽高必须保持偶数

H.264/H.265 MediaCodec 对奇数输出尺寸可能初始化失败或输出 0 帧。修改分辨率计算或 scale filter 时，不要移除 `CompressConfig` 中的 `trunc(.../2)*2` 偶数对齐。

## 回归测试

旋转/容器相关改动必须跑一遍回归：

```powershell
$env:JAVA_HOME = "D:\dev\jdk-25"
$env:PATH = "D:\dev\jdk-25\bin;$env:PATH"
$env:ANDROID_HOME = "D:\dev\android_sdk"
.\gradlew.bat :app:testDebugUnitTest
```

- 用例：`app/src/test/java/com/pisces312/streamclip/util/Mp4RotationUtilsTest.kt`（host JVM，无需设备，全部用例约 0.3 秒）
- 测试数据由 `Mp4Fixture.kt` **在内存里合成**：`Mp4RotationUtils` 只看 box 头、`tkhd` 矩阵和 `hdlr`，从不读 `mdat` 内容，所以真实视频不是必需品，仓库里也不放二进制素材。
- 已覆盖的容器形态：64 位 `mdat`（size field == 1）在 `moov` 之前、32 位 `mdat`、`size == 0` 的空盒、v1 `tkhd`、音频轨在前（靠 `hdlr` 而非旧启发式识别视频轨）、矩阵 9 字段逐字复制（`tx = 0` 与 `tx = height` 两种 muxer 风格）、分辨率变化时 tx/ty 缩放、`tkhd` 宽高不得交换、以及各类异常输入。
- 合成数据的保真度由 `builder_reproduces_real_tkhd_bytes` 钉死：它拿两个原始视频里截出来的 92 字节 `tkhd` 当黄金样本比对。改 `Mp4Fixture` 时这条必须先绿，否则合成数据就失去意义了。
- 验证过用例的有效性：把 `readBoxHeader()` 的 64 位分支改回旧逻辑，`copyRotation_restores_matrix_when_mdat_uses_64bit_size` 立即失败。
- `testOptions.unitTests.isReturnDefaultValues = true` 是为了让 `LogCollector` 里的 `android.util.Log` 在 JVM 上不抛 "not mocked"

### 可选：用真实素材交叉验证

`real_recordings_still_parse_like_the_fixtures` 在 `test_data/` 存在时会解析两个原始问题视频（校验尺寸与矩阵，并做一次 `copyRotation` 往返），不存在则 skip。`test_data/` **已 gitignore**，日常开发不需要它，只在怀疑合成数据与真实 muxer 有偏差时才用。

重新生成方式（从真实视频无损剪切，`-c copy` 会保留 display matrix）：

```powershell
$ffm = "D:\dev\ffmpeg\bin\ffmpeg.exe"

# A: moov 在前 + 32 位 mdat（微信导出 4K，rotation -90）
& $ffm -y -ss 3 -t 1 -i "E:\mmexport1793363542958.mp4" `
    -map 0:v:0 -map 0:a:0 -c copy -movflags +faststart test_data\neg90_mdat32.mp4

# B: mdat 在前 + 64 位 mdat（手机相机 720p，rotation -90）
& $ffm -y -ss 3 -t 1.5 -i "E:\VID_20261026_202905.mp4" `
    -map 0 -c copy test_data\neg90_mdat64_raw.mp4
# 再把 B 的 mdat 头改写为 [size=1]["mdat"][64 位 largesize]，同时给 stco/co64 全部
# 偏移 +8（插入 8 字节导致后续数据整体后移）。这一步没有 ffmpeg 现成参数，
# 需要用脚本做字节手术；改完必须用 ffprobe 确认 rotation 仍为 -90 且能完整解码。
```


## Build Configuration

- **AGP 9.4.0**（Kotlin 编译由 AGP 内置 KGP 2.2.10 提供，根 `build.gradle.kts` 只声明 `com.android.application`）
- **Gradle 9.6.1**（wrapper，腾讯云镜像）。AGP 9.4.0 的硬性下限是 Gradle 9.6.0，两者必须同批升级
- **daemon JVM = Java 25**（`D:\dev\jdk-25`）。由 `gradle/gradle-daemon-jvm.properties` 的 `toolchainVersion=25` 钉住，**优先级高于 `JAVA_HOME`**——只改 `JAVA_HOME` 不会切换 daemon JVM。该文件的 `toolchainUrl.*` 已移除，因此要求本机自带 JDK 25，不会自动下载
- compileSdk 37 / targetSdk 37 / minSdk 26（compileSdk 37 需 build-tools ≥ 37 或 AGP 自带；targetSdk 37 对应 Android 17 行为变更，真机验证重点见 `docs/toolchain-upgrade-2026-10-agp94-gradle96-jdk25.md`）
- Java / Kotlin target：JVM 17
- View Binding 开启、Data Binding 关闭、`buildConfig = true`
- debug 包名追加 `.debug`；release 默认启用 R8 minify 与 resource shrinking，可分别用 `-PenableMinify=false` / `-PenableShrinkResources=false` 关闭（`build.sh --no-minify` 同时关闭两者）
- Core library desugaring 开启（`desugar_jdk_libs 2.1.4`）
- 仓库走阿里云镜像（`settings.gradle.kts`）
- `gradle.properties` 开启 `configuration-cache`
- 注：`build.sh` / `build-aab.sh` 传的 `-PbuildAbi` 目前在 Gradle 侧无对应 `abiFilters`，是无效参数（ABI 限制实际由 AAR 自身决定）

## Key Dependencies

| 依赖 | 用途 |
|---|---|
| `libs/ffmpeg-kit-8.1.aar`（本地，12 MB） | FFmpeg 执行（自编译，GPL-3.0） |
| `libs/isoparser-1.0.6.jar` + `libs/aspectjrt-1.7.3.jar`（本地） | MP4 元数据解析（NativeVideoCompressor 路径） |
| `androidx.media3` 1.6.1（exoplayer / ui / common） | 裁剪页视频预览 |
| `com.google.android.material` 1.13.0 | Material Design 3 组件 |
| `androidx.documentfile` 1.0.1 | SAF 文件操作 |
| `androidx.appcompat` 1.7.1 / `core-ktx` 1.18.0 / `activity-ktx` 1.13.0 / `lifecycle-*` 2.10.0 / `constraintlayout` 2.2.1 | 基础框架 |

## Coding Conventions

- Service / Manager 用 Kotlin `object` 单例（`FFmpegService`、`TaskQueueManager` 等）
- 新代码异步优先用协程，不引入 RxJava；Native 压缩和音频播放仍保留 Java `Thread` / `Handler` / 遗留 `AsyncTask`
- 常规布局优先 View Binding，不用 Data Binding；动态视图和部分 Adapter 仍保留 `findViewById`
- 无 MVVM：Fragment 直接持状态
- Gradle 用 Kotlin DSL（`build.gradle.kts`）
- 新 UI 文案必须同时加 `values/strings.xml`（中文默认）和 `values-en/strings.xml`

## Localization

- 默认中文（`res/values/strings.xml`）、英文（`res/values-en/strings.xml`）
- 切换逻辑在 `LocaleHelper`：Android 13+ 用 `AppCompatDelegate.setApplicationLocales()`，更早版本用 `createConfigurationContext()`

## 文档索引（`docs/`）

| 文件 | 内容 |
|---|---|
| `rotation-design.md` | 早期旋转/方向设计（含已过时结论，当前行为以源码和回归测试为准） |
| `batch-queue-design.md` | 批量任务队列设计 |
| `audio-editor-design.md` / `audio-editor-changes.md` / `audio-editor-20260621-changes.md` / `audio-editor-scenarios.md` | 音频编辑器设计、变更与场景 |
| `native-video-compress-design.md` | Native 压缩路径设计 |
| `resolution-design.md` | 分辨率处理 |
| `build-on-termux-ubuntu.md` | 手机本地构建流程 |
| `toolchain-upgrade-2026-10-agp94-gradle96-jdk25.md` | AGP 9.4 / Gradle 9.6 / JDK 25 / compileSdk 37 升级记录与取证 |
| `ffmpeg-kit-migration-plan.md` | ffmpeg-kit 迁移记录 |
| `*-crash-analysis.md` | 历史崩溃分析（aac / swresample / ffmpeg-kit 8.1） |
| `capture-native-crash-log.md` | 原生崩溃日志抓取 |

文档时效性：根 `CHANGELOG.md` 与应用内 `app/src/main/assets/CHANGELOG.md` 是两套文件，关于页读取后者。README、早期设计文档和 crash 分析中的方案不一定已落地；与当前代码冲突时，以源码和自动化测试为准。

## Git 约定

- 主分支：`main`；多远程（GitHub `pisces312/StreamClip` + 阿里云 Codeup）
- commit / push 前先确认（用户习惯：改动先 review 再提交）
