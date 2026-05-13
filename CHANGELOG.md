# StreamClip 更新日志

## [Unreleased]

### 修复
- **音频采样率默认改为"复制"**：避免 ffmpeg-kit 8.1 的 `libswresample` 在特定采样率转换场景下触发 native crash（SIGSEGV）。当源采样率 ≠ 目标采样率时，swresample 的 NEON 优化路径存在 segfault，去掉 `-ar` 参数即可绕过。

### 调整
- 视频压缩和音频压缩页面的采样率 spinner 默认选中 "复制"（索引 0，原为 44100 Hz）
- `CompressConfig.audioSampleRate` 默认值由 `"44100"` 改为 `"copy"`

---

## [1.5.0] - 2026-05-10

### 升级
- **FFmpeg Kit 升级至 8.0.0**：从 `com.arthenica` 迁移到 `com.antonkarpenko` fork，包含更多编解码器支持和 bug 修复
- **移除 smart-exception 依赖**：FFmpeg Kit 8.0.0 不再依赖此库，减少包体积

### 调整
- **ProGuard 规则同步更新**：混淆规则中的包名从 `com.arthenica.ffmpegkit` 更新为 `com.antonkarpenko.ffmpegkit`
- **错误处理调整**：适配新版本的 API 变更（移除 `failStackTrace`，使用 `output` 替代）

## [1.3.0] - 2026-05-08

### 修复
- **GPS 元数据丢失**：移除 `-movflags use_metadata_tags` 参数，改用 `-f mov` 强制格式，确保 Android 图库正确识别 GPS 信息

### 新增
- **自定义 FFmpeg 命令**：新增"自定义命令"页面，支持输入任意 FFmpeg 参数，选择输入文件和输出目录，带实时日志弹窗

### 优化
- 日志弹窗适配暗色主题

## [1.2.0] - 2026-05-07

### 新增
- 视频压缩功能（硬编码/软编码可选）
- 参数一致性检查
- 文件时间戳恢复
- GPS 元数据保留
- 实时 FFmpeg 日志弹窗
- 捐赠功能
- 使用指南
