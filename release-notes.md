## StreamClip v1.5.0

### 重大升级
- **FFmpeg Kit 升级至 8.0.0**：从 `com.arthenica` 迁移到 `com.antonkarpenko` fork
  - 包含更多编解码器支持
  - 内置 bug 修复和改进
  - 包名变更：`com.arthenica.ffmpegkit` → `com.antonkarpenko.ffmpegkit`

### 依赖清理
- **移除 smart-exception 依赖**：FFmpeg Kit 8.0.0 不再依赖此库，有效减少 APK 体积

### 适配调整
- 同步更新 ProGuard 混淆规则
- 适配 FFmpeg Kit 8.0.0 API 变更（错误处理）

### 下载
- `StreamClip-v1.5.0-arm64-signed.apk`：适用于 arm64-v8a 设备的签名 Release 版本
