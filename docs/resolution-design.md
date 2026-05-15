# StreamClip 压缩分辨率设计文档

## 1. 需求概述

视频压缩功能提供分辨率调整选项，支持将视频按指定倍率缩小。设计需区分**单文件选择**和**多文件批量选择**两种场景。

## 2. 缩小倍率定义

| 倍率 ID | 倍率值 | 显示标签 | 百分比 |
|---------|--------|----------|--------|
| `scale_1_5` | 1.5 | 缩小 1.5× | 约 67% |
| `scale_2_25` | 2.25 | 缩小 2.25× | 约 44% |
| `scale_3_0` | 3.0 | 缩小 3.0× | 约 33% |

百分比计算公式：`percent = 100 / factor`（取整）

## 3. 分场景显示逻辑

### 3.1 单文件选择（单选）

已知源分辨率，可计算缩小后的实际像素值。

**显示格式：** `{最终宽度}×{最终高度} ({缩小倍率标签})`

**示例（源分辨率 1920×1080）：**

| 选项 | 显示文字 |
|------|----------|
| 复制 | 复制 |
| 缩小 1.5× | 1280×720 (缩小 1.5×) |
| 缩小 2.25× | 853×480 (缩小 2.25×) |
| 缩小 3.0× | 640×360 (缩小 3.0×) |

**计算规则：**
- `最终宽度 = floor(源宽度 / 倍率 / 2) * 2`（取整到偶数，满足 ffmpeg scale 要求）
- `最终高度 = floor(源高度 / 倍率 / 2) * 2`
- 旋转不影响计算（宽高各自独立缩放）

### 3.2 多文件选择（批量）

多个视频分辨率可能不同，无法计算统一的最终分辨率，仅显示倍率。

**显示格式：** `{缩小倍率标签} (约 {百分比}%)`

**示例：**

| 选项 |
|------|
| 复制 |
| 缩小 1.5× (约 67%) |
| 缩小 2.25× (约 44%) |
| 缩小 3.0× (约 33%) |

## 4. 技术实现

### 4.1 相关代码

**`CompressFragment.kt` — `updateResolutionOptions(MediaInfo?)`**

```kotlin
private fun updateResolutionOptions(info: MediaInfo? = null) {
    val options = mutableListOf<String>()
    options.add(getString(R.string.resolution_copy))

    if (info != null) {
        // 单文件：显示计算后的最终分辨率
        val w = info.displayWidth
        val h = info.displayHeight
        options.addAll(CompressConfig.SCALE_FACTORS.map { sf ->
            val finalW = ((w / sf.factor) / 2).toInt() * 2
            val finalH = ((h / sf.factor) / 2).toInt() * 2
            "${finalW}×${finalH} (${sf.label})"
        })
    } else {
        // 多文件：只显示倍率
        options.addAll(CompressConfig.SCALE_FACTORS.map { sf ->
            val percent = (100.0 / sf.factor).toInt()
            "${sf.label} (约 ${percent}%)"
        })
    }
    // 更新 spinner adapter...
}
```

**`CompressConfig.kt` — 倍率定义**

```kotlin
data class ScaleFactor(
    val id: String,      // 如 "scale_1_5"
    val factor: Float,    // 如 1.5f
    val label: String     // 如 "缩小 1.5×"
)

val SCALE_FACTORS = listOf(
    ScaleFactor("scale_1_5", 1.5f, "缩小 1.5×"),
    ScaleFactor("scale_2_25", 2.25f, "缩小 2.25×"),
    ScaleFactor("scale_3_0", 3.0f, "缩小 3.0×")
)
```

### 4.2 FFmpeg 缩放命令

在 `toFFmpegCommand()` 中生成 scale filter：

```kotlin
if (resolution != "original") {
    val scaleFactor = SCALE_FACTORS.find { it.id == resolution }
    if (scaleFactor != null) {
        filters.add("scale=iw/${scaleFactor.factor}:ih/${scaleFactor.factor}")
    }
}
```

生成的 FFmpeg 参数示例：`vf scale=iw/1.5:ih/1.5`

## 5. 行为总结

| 场景 | 选择"复制" | 选择"缩小 1.5×" |
|------|-----------|----------------|
| 单文件 1920×1080 | 不缩放 | 缩放至 1280×720 |
| 多文件（分辨率不同） | 不缩放 | 各视频按自身分辨率缩放至 67% |
