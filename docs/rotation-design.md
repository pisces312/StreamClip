# 视频旋转方案：MP4 tkhd Display Matrix

## 背景

StreamClip 需要支持视频旋转（0°/90°/180°/270°），且要求无损（不重编码）。

## 问题诊断

### 根因

ffmpeg 6.0+（ffmpeg-kit 8.1 内置）的 MOV/MP4 muxer（`movenc.c`）写入旋转信息时，**只从 stream side data（`AV_PKT_DATA_DISPLAYMATRIX`）读取**，不从 metadata 的 `rotate` key 读取。

相关源码（ffmpeg 6.0 `libavformat/movenc.c` 第 3305 行）：
```c
display_matrix = (uint32_t *)av_stream_get_side_data(st, AV_PKT_DATA_DISPLAYMATRIX, &display_matrix_size);
```

### 失败的 ffmpeg CLI 方案（均已实测验证）

| 方案 | 命令片段 | 结果 |
|------|---------|------|
| A: `-metadata:s:v:0 rotate=N` | `-i input -c copy -metadata:s:v:0 rotate=90 output` | ❌ 只写入普通 metadata tag，tkhd matrix 不变 |
| B: `-display_rotation`（输入） | `-display_rotation:0 90 -i input -c copy output` | ❌ 设置输入 side data，但 `-c copy` 不传递到输出 |
| C: `-display_rotation`（输出） | `-i input -display_rotation:0 90 output` | ❌ 报错：不是输出选项 |
| D: 重编码 + metadata | `-i input -c:v libx264 -metadata:s:v:0 rotate=90 output` | ❌ 仍然不写入 tkhd matrix |
| E: h264_metadata BSF | `-bsf:v h264_metadata=display_orientation=insert:rotate=90` | ⚠️ 写入 SEI NAL，但 ffprobe 不显示，播放器不识别 |

## 正确方案：`-noautorotate` + 修改 MP4 tkhd Display Matrix

### 关键发现：FFmpeg 自动旋转问题

FFmpeg 默认会根据源视频 tkhd 中的 display matrix 自动旋转像素（`autorotate`）。这导致：

1. 源视频 1080×1920 + 90° tkhd → FFmpeg 输出 1920×1080 像素（已旋转），无旋转标记
2. 代码再写入 90° tkhd 旋转 → 播放器双重旋转 → 画面压扁

**解决**：在 FFmpeg 命令中添加 `-noautorotate`，阻止自动旋转。像素保持原始方向，由 tkhd 矩阵控制最终显示。

### 原理

MP4/MOV 文件的 tkhd（Track Header）box 包含一个 3×3 display matrix（16.16 fixed point），控制视频的旋转/缩放/平移。在 ffmpeg 执行完成后，直接修改输出文件的 tkhd matrix 即可实现无损旋转。

### Display Matrix 格式

矩阵存储为 9 个 int32（big-endian）：
- 前 6 个元素：16.16 fixed point
- 后 3 个元素：2.30 fixed point

| 旋转角度 | Matrix (a, b, u, c, d, v, tx, ty, w) | width/height |
|----------|--------------------------------------|--------------|
| 0°（identity） | `0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000` | 不变 |
| 90° CW | `0, 0x10000, 0, -0x10000, 0, 0, height<<16, 0, 0x40000000` | 交换 |
| 180° | `-0x10000, 0, 0, 0, -0x10000, 0, width<<16, height<<16, 0x40000000` | 不变 |
| 270° CW | `0, -0x10000, 0, 0x10000, 0, 0, 0, width<<16, 0x40000000` | 交换 |

### ffprobe 验证结果

ffmpeg 用逆时针表示旋转角度（`-90°` = 顺时针 90°）：

| 旋转 | ffprobe 输出 |
|------|-------------|
| 0° | 无 side data（单位矩阵） |
| 90° CW | `Display Matrix: rotation of -90.00 degrees` |
| 180° | `Display Matrix: rotation of -180.00 degrees` |
| 270° CW | `Display Matrix: rotation of 90.00 degrees` |

## 实现

### 新增文件
- `app/src/main/java/com/pisces312/streamclip/util/Mp4RotationUtils.kt`

### 工作流程
1. ffmpeg 命令包含 `-noautorotate`（阻止 FFmpeg 自动旋转像素）
2. ffmpeg 成功后，调用 `Mp4RotationUtils.setRotation(outputPath, degrees)`
3. 工具类解析 ISOBMFF box 结构：`ftyp → moov → trak → tkhd`
4. 定位视频轨（`volume == 0 && width > 0 && height > 0`）
5. 写入旋转 matrix + 交换 width/height（90°/270°时）

### 旋转条件
- `rotation == -1`（保持不变）：不修改 tkhd，源视频旋转被 `-noautorotate` 保留
- `rotation >= 0`（0°/90°/180°/270°）：覆盖 tkhd，写入用户选择的旋转

### 修改的文件
| 文件 | 修改内容 |
|------|---------|
| `model/CompressConfig.kt` | 添加 `-noautorotate` 参数，阻止 FFmpeg 自动旋转 |
| `fragment/CompressFragment.kt` | ffmpeg 成功后调用 `Mp4RotationUtils.setRotation()`，条件 `rotation >= 0` |
| `service/BatchTaskService.kt` | 批量处理中也添加旋转后处理，条件 `rotation >= 0` |
| `util/Mp4RotationUtils.kt` | 直接修改 tkhd display matrix 的工具类 |

### 调用点
- **单压缩**：`CompressFragment.executeSingleCompress()` — ffmpeg 成功后
- **批量压缩**：`BatchTaskService.executeTask()` — ffmpeg 成功后

## 限制
- 仅支持 MP4/MOV 格式（`-f mov` 已在 `CompressConfig.toFFmpegCommand()` 中指定）
- 如果输出格式不是 ISOBMFF（如 MKV），此方法不适用

## 测试文件（Windows）
位于 `D:\Temp\`，原始视频 `video.mp4`（582×1280, H264, 无旋转）：

| 文件 | 旋转 | 大小 | ffprobe |
|------|------|------|---------|
| `video_rot0.mp4` | 0° | 28384KB | 无 side data（identity）✅ |
| `video_rot90.mp4` | 90° CW | 28384KB | rotation of -90.00° ✅ |
| `video_rot180.mp4` | 180° | 28384KB | rotation of -180.00° ✅ |
| `video_rot270.mp4` | 270° CW | 28384KB | rotation of 90.00° ✅ |

对比：ffmpeg 重编码方案 `video_rotate_F.mp4`（51593KB，翻倍且无旋转）❌

## Git 历史
1. `67fc8dc` — 初次尝试：`-metadata:s:v:0 rotate=N`（后发现无效）
2. `ea044f5` — 正确方案：直接修改 MP4 tkhd display matrix
3. 后续修复 — 添加 `-noautorotate` 解决 FFmpeg 自动旋转导致的双重旋转问题
