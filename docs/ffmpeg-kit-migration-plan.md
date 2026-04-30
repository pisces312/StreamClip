# StreamClip 改用 ffmpeg-kit AAR 迁移计划

## 目标
将 StreamClip 从 ProcessBuilder 调用 ffmpeg 二进制文件的方式，改为使用 ffmpeg-kit AAR 库。

## 背景
- 当前方式：从 assets 复制 ffmpeg 二进制到内部存储，用 ProcessBuilder 执行命令
- 新方式：使用 ffmpeg-kit Java API，直接调用 FFmpeg 功能
- AAR 文件：`D:\downloads\ffmpeg-kit-6.0-arm64-release.aar`（6.8 MB，仅 arm64-v8a）

## 执行步骤

### Step 1: 添加 AAR 依赖
1. 创建 `app/libs/` 目录
2. 复制 AAR 文件到 `app/libs/ffmpeg-kit-6.0-arm64-release.aar`
3. 修改 `app/build.gradle.kts`：
   - 添加 `implementation(files("libs/ffmpeg-kit-6.0-arm64-release.aar"))`
   - 移除 `// No external FFmpeg library` 注释

### Step 2: 重写 FFmpegService.kt
- 移除 `getFFmpegBinaryPath()` 和相关 assets 复制逻辑
- 移除 `ProcessBuilder` 执行方式
- 使用 `FFmpegKit.executeAsync()` 或 `FFmpegKit.execute()`
- 进度回调改用 `StatisticsCallback`
- 结果判断改用 `ReturnCode.isSuccess()`

三个核心功能对应：
| 功能 | ffmpeg-kit 命令 |
|------|----------------|
| trimVideo | `-y -ss {start} -i {input} -t {duration} -c copy -avoid_negative_ts make_zero {output}` |
| mergeVideos | `-y -f concat -safe 0 -i {list.txt} -c copy {output}` |
| extractAudio | `-y -i {input} -vn -c:a {codec} {output}` |

### Step 3: 清理资源
- 删除 `app/src/main/assets/ffmpeg/` 目录（arm64-v8a、armeabi-v7a、x86、x86_64 子目录）
- 删除 `FFmpegService` 中的 `parseProgress()`（ffmpeg-kit 自带统计回调）

### Step 4: 构建验证
- 执行 `./gradlew assembleDebug`
- 安装 APK 测试三个功能：截取、合并、提取音频

## 文件变更清单
| 文件 | 操作 |
|------|------|
| `app/build.gradle.kts` | 修改：添加 AAR 依赖 |
| `app/src/main/java/.../FFmpegService.kt` | 重写：改用 ffmpeg-kit API |
| `app/src/main/assets/ffmpeg/` | 删除：移除二进制文件 |
| `app/libs/ffmpeg-kit-6.0-arm64-release.aar` | 新增：本地 AAR 文件 |

## 注意事项
- AAR 仅包含 arm64-v8a 架构，不支持 32 位 ARM 和 x86 模拟器
- 如需支持其他架构，需重新编译 ffmpeg-kit 或下载对应 AAR
- ffmpeg-kit 首次加载时会自动解压 so 库，无需手动处理

## 状态
- [x] Step 1: AAR 依赖
- [x] Step 2: FFmpegService 重写
- [x] Step 3: 资源清理
- [x] Step 4: 构建验证 (BUILD SUCCESSFUL in 45s)
- [x] Step 5: Debug 配置 (不同包名 + 蓝色图标)
