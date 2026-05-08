# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

StreamClip（剪流）— Android 视频处理工具，基于 FFmpeg 实现视频裁剪、音频提取、视频合并、视频压缩、自定义 FFmpeg 命令等功能。单模块 Kotlin 项目，版本 1.3.0。

## Build & Run

```bash
# Standard Gradle build
./gradlew assembleRelease

# Debug build
./gradlew assembleDebug

# Full build with signing (arm64 only)
./build.sh

# Debug build via script
./build.sh debug
```

**Signing** is configured externally via `build.sh` (not in Gradle). Keystore: `D:\nili\my-git-projects\my-backup\backup-settings\my-android-release.keystore`, alias `pisces312`, password via `KEY_STORE_PASSWORD` env var.

**No test infrastructure exists** — no JUnit, no Espresso, no test directories.

## Architecture

**Single Activity + Fragment** architecture with ViewPager2 for tab navigation (6 tabs). No MVVM — fragments manage state directly using coroutines (`lifecycleScope`).

### Key Layers

- **Activity**: `MainActivity` (launcher, 6-tab ViewPager2), `LogActivity` (log viewer), `BatchTaskActivity` (batch task list)
- **Fragment** (6 tabs): `TrimSimpleFragment`, `Trim2Fragment`, `MergeFragment`, `ExtractFragment`, `CompressFragment`, `CustomCommandFragment`
- **Service**: `FFmpegService` (singleton, core FFmpeg wrapper), `BatchTaskService` (foreground service, batch processing), `TaskQueueManager` (singleton, thread-safe queue with StateFlow)
- **Adapter**: `MainPagerAdapter`, `VideoListAdapter`, `BatchVideoListAdapter`, `BatchTaskAdapter`, `FfmpegLogAdapter`
- **Model**: `TaskType`, `TaskStatus`, `TaskConfig`, `CompressConfig`, `BatchTaskItem`, `VideoInfo`
- **Utility**: `FileUtils` (URI-to-path with multiple fallbacks), `SettingsManager` (SharedPreferences), `LogCollector` (memory + file logging), `LocaleHelper` (i18n), `CrashHandler`

### FFmpeg Integration

FFmpeg via **ffmpeg-kit 6.0** (local AAR at `app/libs/`). All operations use `FFmpegKit.executeAsync()` with Kotlin coroutines (`suspendCancellableCoroutine`). Probe via `FFprobeKit.execute()`.

Patterns:
- Trim: `-c copy` (lossless)
- Merge: concat demuxer
- Extract Audio: `-c:a copy` (lossless)
- Compress: configurable encoder (HW/SW), bitrate/CRF, scale
- Custom Command: user-provided arguments

### File Picking

Uses SAF (Storage Access Framework) via `ActivityResultContracts`. `FileUtils` handles URI-to-path with fallbacks (external storage, MediaStore, cache copy).

## Localization

- Default: Chinese (simplified)
- English: `app/src/main/res/values-en/strings.xml`
- Language switching via `LocaleHelper` with `AppCompatDelegate.setApplicationLocales()` (Android 13+) or `createConfigurationContext()` (older)

## Build Configuration

- AGP 8.9.1, Kotlin 2.1.0
- compileSdk 36, targetSdk 36, minSdk 26
- Java/Kotlin target: JVM 17
- View Binding enabled, Data Binding disabled
- Core library desugaring enabled
- arm64 only (no x86, no armeabi-v7a)

## Key Dependencies

| Dependency | Purpose |
|---|---|
| FFmpeg-kit 6.0 (local AAR) | FFmpeg execution |
| ExoPlayer (Media3 1.6.1) | Video preview in trim fragments |
| Material Design 3 | UI components |
| smart-exception (local JAR) | Exception tracking |

## Coding Conventions

- Single `object` singletons for services and managers (FFmpegService, TaskQueueManager, etc.)
- Coroutines for all async work — no RxJava
- View Binding — no Data Binding
- No MVVM — fragments manage state directly
- Kotlin DSL for Gradle (`build.gradle.kts`)
