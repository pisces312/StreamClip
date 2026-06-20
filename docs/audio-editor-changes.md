# 音频编辑器改进方案

> 针对 audio-editor-design.md 中描述的音频编辑器，三项改动的设计方案。

---

## 改动一：导出时选择目标路径并记住上次路径

### 现状

`AudioEditorActivity.exportAudio()` 直接将文件写到 `getExternalFilesDir(null)`，用户无法选择保存位置。

### 方案

使用 `ACTION_OPEN_DOCUMENT_TREE` 让用户选择目标目录，通过 `SharedPreferences` 持久化上次目录 URI。导出时自动在所选目录下创建文件，文件名 = 源文件名 + `_yyyymmdd-hhmmss` + 扩展名。

> 不使用 `ACTION_CREATE_DOCUMENT`（选单个文件），改用 `ACTION_OPEN_DOCUMENT_TREE`（选目录）。原因：用户选的是"保存到哪"，文件名由程序自动生成，体验更顺畅；且通过 `takePersistableUriPermission` 持久化目录访问权限，后续导出无需反复授权。

#### 1. 新增常量与状态

```kotlin
// AudioEditorActivity.kt companion
private const val PREF_NAME = "audio_editor_prefs"
private const val KEY_LAST_EXPORT_DIR = "last_export_dir"
private const val REQUEST_CODE_PICK_DIR = 1001

// 临时保存待执行的导出格式
private var pendingExportFormat: AudioEncoder.OutputFormat? = null
```

#### 2. 点击导出 → 检查是否有已保存目录

- **有已保存目录** → 直接导出，不弹选择器
- **无已保存目录** → 启动 `ACTION_OPEN_DOCUMENT_TREE` 让用户选择

```kotlin
private fun exportAudio(format: AudioEncoder.OutputFormat) {
    val prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
    val lastDirStr = prefs.getString(KEY_LAST_EXPORT_DIR, null)

    if (lastDirStr != null) {
        val dirUri = Uri.parse(lastDirStr)
        val docFile = DocumentFile.fromTreeUri(this, dirUri)
        if (docFile != null && docFile.canWrite()) {
            performExport(dirUri, format)
            return
        }
    }

    // 需要用户选择目录
    pendingExportFormat = format
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }
    startActivityForResult(intent, REQUEST_CODE_PICK_DIR)
}
```

#### 3. 目录选择回调 → 持久化权限 → 执行导出

```kotlin
override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == REQUEST_CODE_PICK_DIR && resultCode == RESULT_OK) {
        val treeUri = data?.data ?: return

        // 持久化 URI 权限
        try {
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) { /* 忽略 */ }

        // 记住目录
        getSharedPreferences(PREF_NAME, MODE_PRIVATE)
            .edit().putString(KEY_LAST_EXPORT_DIR, treeUri.toString()).apply()

        val format = pendingExportFormat ?: return
        performExport(treeUri, format)
    }
    pendingExportFormat = null
}
```

#### 4. 生成文件名 + 写入目标

文件名规则：`{源文件名}_{yyyymmdd-hhmmss}.{ext}`

```kotlin
private fun performExport(dirUri: Uri, format: AudioEncoder.OutputFormat) {
    val decoded = decodedAudio ?: return
    val startMs = binding.waveformView.pixelsToMillisecs(startPos)
    val endMs = binding.waveformView.pixelsToMillisecs(endPos)
    val fadeIn = binding.sliderFadeIn.value
    val fadeOut = binding.sliderFadeOut.value

    // 文件名：源文件名_yyyymmdd-hhmmss.ext
    val baseName = audioFile?.nameWithoutExtension ?: "audio"
    val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
    val outputFileName = "${baseName}_${timestamp}.${format.extension}"

    // 在目标目录创建文件
    val dir = DocumentFile.fromTreeUri(this, dirUri)
    val outputFile = dir?.createFile(format.mimeType, outputFileName)
    if (outputFile == null) {
        Toast.makeText(this, "无法创建文件: $outputFileName", Toast.LENGTH_LONG).show()
        return
    }

    // FFmpeg 需要文件路径，先用临时文件编码，再复制到目标 URI
    val tempOutput = File(cacheDir, "export_temp_${timestamp}.${format.extension}")

    binding.progressBar.visibility = View.VISIBLE
    binding.tvStatus.text = "导出 ${format.displayName}..."
    binding.tvStatus.visibility = View.VISIBLE
    setExportButtonsEnabled(false)

    scope.launch {
        try {
            val enc = AudioEncoder()
            encoder = enc
            val config = AudioEncoder.EncodeConfig(
                format = format,
                fadeInSec = fadeIn,
                fadeOutSec = fadeOut
            )

            val result = withContext(Dispatchers.IO) {
                decoded.samples.rewind()
                enc.encode(
                    samples = decoded.samples,
                    sampleRate = decoded.sampleRate,
                    channels = decoded.channels,
                    numSamples = decoded.numSamples,
                    startTimeSec = startMs / 1000f,
                    endTimeSec = endMs / 1000f,
                    outputPath = tempOutput.absolutePath,
                    config = config
                )
            }

            if (result.success) {
                contentResolver.openOutputStream(outputFile.uri)?.use { out ->
                    tempOutput.inputStream().use { it.copyTo(out) }
                }
                tempOutput.delete()

                binding.tvStatus.text = "已保存: $outputFileName"
                Toast.makeText(this@AudioEditorActivity,
                    "导出成功: $outputFileName", Toast.LENGTH_LONG).show()
            } else {
                tempOutput.delete()
                binding.tvStatus.text = "失败: ${result.errorMessage}"
                Toast.makeText(this@AudioEditorActivity, "导出失败", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            tempOutput.delete()
            binding.tvStatus.text = "错误: ${e.message}"
            Toast.makeText(this@AudioEditorActivity, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            binding.progressBar.visibility = View.GONE
            setExportButtonsEnabled(true)
        }
    }
}
```

#### 5. 流程图

```
用户点击导出 MP3
       │
       ▼
  有已保存目录？──否──→ ACTION_OPEN_DOCUMENT_TREE
       │                    │
      是                 用户选择目录
       │                    │
       │              takePersistableUriPermission
       │              保存 URI 到 SharedPreferences
       │                    │
       ▼◄───────────────────┘
  在目录下创建文件（源名_yyyymmdd-hhmmss.mp3）
       │
       ▼
  FFmpeg 编码到临时文件 → 复制到目标 URI → 删除临时文件
```

#### 涉及文件

| 文件 | 改动 |
|------|------|
| `AudioEditorActivity.kt` | 新增 `pendingExportFormat`、`onActivityResult`、`performExport`，改造 `exportAudio` |
| 需新增 import | `java.text.SimpleDateFormat`、`java.util.Date`、`java.util.Locale`、`androidx.documentfile.provider.DocumentFile` |

---

## 改动二：放大后波形图加水平滚动条

### 现状

`WaveformView` 放大后波形超出屏幕，只能通过 fling 手势滚动，没有视觉指示器告知用户当前位置和剩余内容。

### 方案

在 `WaveformView` 底部绘制一个较粗的 scrollbar，方便手机触摸拖动。

#### 设计

```
┌──────────────────────────────────┐
│         波形区域 (上方)            │
│                                  │
│                                  │
├──────────────────────────────────┤
│  [████████░░░░░░░░░░░░░░░░]     │  ← scrollbar (8dp 高，易于触摸)
└──────────────────────────────────┘
```

- **高度**：8dp（约 24px @ xxhdpi），符合 Material Design 最小触摸目标建议，手机上易于拖动
- **可见条件**：波形总宽度 > View 宽度时才显示
- **thumb 位置和宽度**：按 `offset / totalWidth` 和 `viewWidth / totalWidth` 比例计算
- **可拖动**：拖动 thumb 直接改变 `offset`，波形同步滚动
- **样式**：track 半透明，thumb 亮色圆角矩形

#### 1. WaveformView 新增绘制逻辑

```kotlin
// 新增 Paint
private val scrollbarTrackPaint = Paint().apply {
    isAntiAlias = true
    color = context.getColor(R.color.waveform_scrollbar_track)
}
private val scrollbarThumbPaint = Paint().apply {
    isAntiAlias = true
    color = context.getColor(R.color.waveform_scrollbar_thumb)
}

// 尺寸：8dp 高，上下各 2dp padding
private val scrollbarHeight = 8 * density
private val scrollbarPadding = 2 * density
private val scrollbarTouchSlop = 8 * density  // 触摸区域比视觉区域大一些
```

#### 2. 绘制 scrollbar

```kotlin
override fun onDraw(canvas: Canvas) {
    // ... 已有绘制逻辑 ...

    drawScrollbar(canvas)
}

private fun drawScrollbar(canvas: Canvas) {
    val totalWidth = maxPos().toFloat()
    val viewWidth = measuredWidth.toFloat()
    if (totalWidth <= viewWidth) return  // 不需要滚动条

    val trackTop = measuredHeight - scrollbarHeight - scrollbarPadding
    val trackBottom = measuredHeight - scrollbarPadding
    val cornerRadius = scrollbarHeight / 2

    // track（整条槽）
    canvas.drawRoundRect(
        0f, trackTop, viewWidth, trackBottom,
        cornerRadius, cornerRadius,
        scrollbarTrackPaint
    )

    // thumb（当前位置）
    val thumbWidth = (viewWidth / totalWidth) * viewWidth
    val scrollRange = totalWidth - viewWidth
    val thumbLeft = if (scrollRange > 0) (offset / scrollRange) * (viewWidth - thumbWidth) else 0f
    canvas.drawRoundRect(
        thumbLeft.coerceIn(0f, viewWidth - thumbWidth), trackTop,
        (thumbLeft + thumbWidth).coerceIn(0f, viewWidth), trackBottom,
        cornerRadius, cornerRadius,
        scrollbarThumbPaint
    )
}
```

#### 3. 拖动 scrollbar

在 `onTouchEvent` 中增加 scrollbar 触摸判定，优先级高于波形触摸：

```kotlin
private var isDraggingScrollbar = false

override fun onTouchEvent(event: MotionEvent): Boolean {
    val totalWidth = maxPos().toFloat()
    val viewWidth = measuredWidth.toFloat()
    val scrollbarRegionTop = measuredHeight - scrollbarHeight - scrollbarPadding - scrollbarTouchSlop

    when (event.action) {
        MotionEvent.ACTION_DOWN -> {
            if (event.y >= scrollbarRegionTop && totalWidth > viewWidth) {
                isDraggingScrollbar = true
                handleScrollbarDrag(event.x)
                return true
            }
        }
        MotionEvent.ACTION_MOVE -> {
            if (isDraggingScrollbar) {
                handleScrollbarDrag(event.x)
                return true
            }
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
            if (isDraggingScrollbar) {
                isDraggingScrollbar = false
                return true
            }
        }
    }

    // 已有手势处理（缩放、fling、选区）
    scaleGestureDetector.onTouchEvent(event)
    if (gestureDetector.onTouchEvent(event)) return true
    longPressDetector.onTouchEvent(event)  // 改动三新增
    when (event.action) {
        MotionEvent.ACTION_DOWN -> listener?.waveformTouchStart(event.x)
        MotionEvent.ACTION_MOVE -> listener?.waveformTouchMove(event.x)
        MotionEvent.ACTION_UP -> listener?.waveformTouchEnd()
    }
    return true
}

private fun handleScrollbarDrag(x: Float) {
    val totalWidth = maxPos().toFloat()
    val viewWidth = measuredWidth.toFloat()
    val scrollRange = totalWidth - viewWidth
    if (scrollRange <= 0) return

    val ratio = (x / viewWidth).coerceIn(0f, 1f)
    offset = (ratio * scrollRange).toInt()
    invalidate()
    listener?.waveformDraw()
}
```

#### 4. Activity 侧联动

`waveformDraw()` 回调中更新时间显示：

```kotlin
override fun waveformDraw() {
    if (!isPlaying) {
        val currentMs = binding.waveformView.pixelsToMillisecs(
            binding.waveformView.getOffset()
        )
        binding.tvCurrentTime.text = formatTime(currentMs)
    }
}
```

#### 5. 新增颜色资源

```xml
<!-- res/values/colors.xml -->
<color name="waveform_scrollbar_track">#22FFFFFF</color>
<color name="waveform_scrollbar_thumb">#CCB2DFDB</color>  <!-- 高不透明度 teal_200 -->
```

#### 涉及文件

| 文件 | 改动 |
|------|------|
| `WaveformView.kt` | 新增 scrollbar 绘制 + 拖动逻辑 |
| `colors.xml` | 新增 2 个颜色 |
| `AudioEditorActivity.kt` | `waveformDraw` 回调更新时间显示 |

---

## 改动三：滑动选择区间 + 长按弹出删除菜单

### 现状

当前触摸逻辑是点击+拖动移动选区起点/终点，用于设定导出范围。没有区间删除功能。

### 需求

- 滑动选择一段区间后**保持选中**，不弹菜单
- 选中区间自动**循环播放**
- **长按**选中区域才弹出 BottomSheet 菜单（删除选中部分 / 删除以外的部分）
- 导出时只导出当前选中的一段（不支持多段拼接）

### 交互设计

#### 单模式设计（不切换模式）

不引入"裁剪模式/选择模式"切换——只有一种触摸模式，行为更直观：

| 手势 | 行为 |
|------|------|
| **触碰 + 向后滑动** | 以按下点为起点，向后扩展选区 |
| **触碰 + 向前滑动** | 以按下点为终点，向前扩展选区 |
| **抬起（有滑动）** | 保持选中，自动开始循环播放选中区间 |
| **长按选中区域内** | 弹出 BottomSheet 菜单（删除选中 / 删除以外的） |
| **长按选中区域外** | 不触发任何操作（等用户滑动开始新选择） |
| **点击（无滑动）** | 清除选区，设为播放位置 |
| **拖动选区边界** | 微调起点/终点（靠近边界 30px 内） |

#### 手势流程

```
1. 手指按下 ──→ 记录按下位置（可能是未来选区的起点或终点）
2. 手指滑动 ──→ 实时更新选区（向前或向后，按下点为锚点）
3. 手指抬起 ──→ 保持选区，开始循环播放该区间
4. 长按选区内 ──→ 弹出 BottomSheet（删除选中 / 删除以外 / 取消）
```

#### 长按与滑动手势无冲突

`GestureDetector.onLongPress` 的触发条件：手指按下后 500ms 内移动距离未超过 `touchSlop`（约 8dp）。因此：

| 场景 | 手指状态 | 长按触发？ | 原因 |
|------|---------|----------|------|
| 无选区，滑动选择新区间 | 在移动 | ❌ | 移动超过 touchSlop，不满足长按条件 |
| 有选区，拖动边界微调 | 在移动 | ❌ | 同上 |
| 有选区，按住选区内不动 | 未移动 | ✅ | 满足长按条件 → 弹出菜单 |
| 有选区，选区外按下不动 | 未移动 | ✅ 触发但忽略 | `onLongPress` 中检查 pos 不在选区内 → 不弹菜单 |

结论：滑动时手指必然移动超过 touchSlop，长按不会触发，**无冲突**。

#### 选区状态

```kotlin
// AudioEditorActivity
private var hasSelection = false
private var selectionStartPx = 0  // 选区起点（波形像素坐标）
private var selectionEndPx = 0    // 选区终点（波形像素坐标）
private var isLoopingSelection = false  // 是否正在循环播放选区
```

### 触摸逻辑改造

#### WaveformView 改造

新增长按检测和选区高亮绘制：

```kotlin
// 新增：长按检测
private val longPressDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
    override fun onLongPress(e: MotionEvent) {
        val pos = offset + e.x.toInt()
        listener?.waveformLongPress(pos)
    }
})

// 新增：选区高亮
private var highlightStart = -1
private var highlightEnd = -1

fun setHighlight(start: Int, end: Int) {
    highlightStart = minOf(start, end)
    highlightEnd = maxOf(start, end)
    invalidate()
}

fun clearHighlight() {
    highlightStart = -1
    highlightEnd = -1
    invalidate()
}

fun isPointInHighlight(pos: Int): Boolean {
    return highlightStart >= 0 && pos in highlightStart..highlightEnd
}
```

`WaveformListener` 新增回调：

```kotlin
interface WaveformListener {
    // ... 已有方法 ...
    fun waveformLongPress(pos: Int)
}
```

#### onDraw 新增选区高亮

```kotlin
private val highlightPaint = Paint().apply {
    isAntiAlias = false
    color = context.getColor(R.color.waveform_highlight)
    alpha = 80
}
private val highlightBorderPaint = Paint().apply {
    isAntiAlias = true
    strokeWidth = 2 * density
    color = context.getColor(R.color.waveform_highlight_border)
}

// onDraw 中，波形绘制后：
if (highlightStart >= 0 && highlightEnd > highlightStart) {
    val x1 = (highlightStart - offset).coerceIn(0, measuredWidth).toFloat()
    val x2 = (highlightEnd - offset).coerceIn(0, measuredWidth).toFloat()
    if (x2 > x1) {
        canvas.drawRect(x1, 0f, x2, measuredHeight.toFloat(), highlightPaint)
        canvas.drawLine(x1, 0f, x1, measuredHeight.toFloat(), highlightBorderPaint)
        canvas.drawLine(x2, 0f, x2, measuredHeight.toFloat(), highlightBorderPaint)
    }
}
```

#### Activity 触摸回调改造

```kotlin
private var touchDownPos = 0
private var isDraggingSelection = false
private var isAdjustingBoundary = false

override fun waveformTouchStart(x: Float) {
    val pos = binding.waveformView.getOffset() + x.toInt()
    touchDownPos = pos

    if (hasSelection) {
        val distToStart = Math.abs(pos - selectionStartPx)
        val distToEnd = Math.abs(pos - selectionEndPx)
        when {
            distToStart < 30 && distToStart <= distToEnd -> {
                isAdjustingBoundary = true
                selectionStartPx = pos.coerceIn(0, selectionEndPx - 1)
            }
            distToEnd < 30 -> {
                isAdjustingBoundary = true
                selectionEndPx = pos.coerceIn(selectionStartPx + 1, binding.waveformView.maxPos())
            }
            pos in selectionStartPx..selectionEndPx -> {
                // 选区内按下，等长按触发菜单
                isDraggingSelection = false
                isAdjustingBoundary = false
            }
            else -> {
                // 选区外，开始新选择
                isDraggingSelection = true
                selectionStartPx = pos
                selectionEndPx = pos
                binding.waveformView.setHighlight(selectionStartPx, selectionEndPx)
            }
        }
    } else {
        // 无选区，开始新的选择（按下点可能是起点或终点，取决于滑动方向）
        isDraggingSelection = true
        selectionStartPx = pos
        selectionEndPx = pos
        binding.waveformView.setHighlight(selectionStartPx, selectionEndPx)
    }
    updateDisplay()
}

override fun waveformTouchMove(x: Float) {
    val pos = binding.waveformView.getOffset() + x.toInt()
    if (isAdjustingBoundary) {
        if (Math.abs(pos - selectionStartPx) < Math.abs(pos - selectionEndPx)) {
            selectionStartPx = pos.coerceIn(0, selectionEndPx - 1)
        } else {
            selectionEndPx = pos.coerceIn(selectionStartPx + 1, binding.waveformView.maxPos())
        }
    } else if (isDraggingSelection) {
        // 向前或向后滑动均可：按下点为锚点，当前位置为另一端
        if (pos >= touchDownPos) {
            // 向后滑动 → 按下点为起点
            selectionStartPx = touchDownPos
            selectionEndPx = pos.coerceIn(0, binding.waveformView.maxPos())
        } else {
            // 向前滑动 → 按下点为终点
            selectionStartPx = pos.coerceIn(0, binding.waveformView.maxPos())
            selectionEndPx = touchDownPos
        }
    }
    binding.waveformView.setHighlight(selectionStartPx, selectionEndPx)
    updateDisplay()
}

override fun waveformTouchEnd() {
    if (isAdjustingBoundary) {
        isAdjustingBoundary = false
        startLoopPlayback()
    } else if (isDraggingSelection) {
        isDraggingSelection = false
        val distance = Math.abs(selectionEndPx - selectionStartPx)
        if (distance > 5) {
            // 有效选区 → 保持选中，循环播放
            hasSelection = true
            // 确保起点 < 终点
            if (selectionEndPx < selectionStartPx) {
                val tmp = selectionStartPx; selectionStartPx = selectionEndPx; selectionEndPx = tmp
            }
            startLoopPlayback()
        } else {
            // 点击（无滑动）→ 清除选区
            // 但如果按下点在已有选区内，说明用户想等长按，不做任何操作
            if (hasSelection && touchDownPos in selectionStartPx..selectionEndPx) {
                // 保持原选区不变
                return
            }
            hasSelection = false
            binding.waveformView.clearHighlight()
            stopLoopPlayback()
            player?.seekTo(binding.waveformView.pixelsToMillisecs(selectionStartPx))
        }
    }
}
```

### 循环播放选中区间

```kotlin
private fun startLoopPlayback() {
    player?.let { p ->
        val startMs = binding.waveformView.pixelsToMillisecs(selectionStartPx)
        val endMs = binding.waveformView.pixelsToMillisecs(selectionEndPx)
        p.setPlaybackRange(startMs, endMs)
        if (!isPlaying) {
            p.start()
            isPlaying = true
            binding.btnPlay.setImageResource(R.drawable.ic_pause)
            handler.post(updatePlayPosition)
        }
        isLoopingSelection = true
    }
}

private fun stopLoopPlayback() {
    isLoopingSelection = false
    if (isPlaying) {
        pausePlayback()
    }
}
```

`AudioPlayer` 已有 `setPlaybackRange` + `setNotificationMarkerPosition` 实现选区播放。循环播放需要监听播放完成 → 自动重启：

```kotlin
// AudioPlayer 新增循环播放支持
private var isLooping = false
private var loopStartSample = 0
private var loopEndSample = 0

fun setLooping(looping: Boolean) {
    isLooping = looping
}

fun setPlaybackRange(startMsec: Int, endMsec: Int) {
    // ... 已有逻辑 ...
    loopStartSample = playbackStart
    loopEndSample = endSample
    audioTrack.setNotificationMarkerPosition(loopEndSample - 1 - loopStartSample)
}

// 在 onMarkerReached 回调中：
override fun onMarkerReached(track: AudioTrack?) {
    if (isLooping) {
        // 循环：重置到选区起点继续播放
        stop()
        playbackStart = loopStartSample
        audioTrack.setNotificationMarkerPosition(loopEndSample - 1 - loopStartSample)
        start()
    } else {
        stop()
        listener?.onCompletion()
    }
}
```

### 长按 → BottomSheet 菜单

```kotlin
override fun waveformLongPress(pos: Int) {
    if (!hasSelection || pos !in selectionStartPx..selectionEndPx) {
        return  // 不在选区内，忽略
    }
    showSegmentActionMenu()
}

private fun showSegmentActionMenu() {
    val startMs = binding.waveformView.pixelsToMillisecs(selectionStartPx)
    val endMs = binding.waveformView.pixelsToMillisecs(selectionEndPx)
    val durationMs = endMs - startMs

    val bottomSheet = BottomSheetDialog(this)
    val view = layoutInflater.inflate(R.layout.bottom_sheet_segment_action, null)

    view.findViewById<TextView>(R.id.tvSelectionInfo).text =
        "选中: ${formatTime(startMs)} - ${formatTime(endMs)} (${formatTime(durationMs)})"

    view.findViewById<View>(R.id.action_delete_selected).setOnClickListener {
        performDeleteSelected(startMs, endMs)
        bottomSheet.dismiss()
    }

    view.findViewById<View>(R.id.action_delete_others).setOnClickListener {
        performKeepOnly(startMs, endMs)
        bottomSheet.dismiss()
    }

    view.findViewById<View>(R.id.action_cancel).setOnClickListener {
        bottomSheet.dismiss()
    }

    bottomSheet.setContentView(view)
    bottomSheet.show()
}
```

### 删除操作 = 调整导出范围

由于每次只导出一段，删除操作的本质是**调整 `startPos` / `endPos` 导出范围**，而非物理删除 PCM 数据：

```kotlin
/**
 * "删除选中部分"：将导出范围设为选中区间之外。
 * - 选中区间在中间 → 取前半段（startPos ~ selectionStart），用户可再手动调整
 * - 选中区间在开头 → 导出范围设为 selectionEnd ~ endPos
 * - 选中区间在结尾 → 导出范围设为 startPos ~ selectionStart
 */
private fun performDeleteSelected(delStartMs: Int, delEndMs: Int) {
    val currentStartMs = binding.waveformView.pixelsToMillisecs(startPos)
    val currentEndMs = binding.waveformView.pixelsToMillisecs(endPos)

    if (delStartMs <= currentStartMs && delEndMs >= currentEndMs) {
        Toast.makeText(this, "删除范围覆盖了全部内容", Toast.LENGTH_SHORT).show()
        return
    }

    if (delStartMs <= currentStartMs) {
        // 删开头 → 导出范围 = delEnd ~ end
        startPos = binding.waveformView.millisecsToPixels(delEndMs)
        // endPos 不变
    } else {
        // 删中间或结尾 → 导出范围 = start ~ delStart
        // endPos 设为删除区间起点
        endPos = binding.waveformView.millisecsToPixels(delStartMs)
        // startPos 不变
    }

    // 清除选区高亮
    hasSelection = false
    binding.waveformView.clearHighlight()
    stopLoopPlayback()
    updateDisplay()

    Toast.makeText(this, "已删除选中部分", Toast.LENGTH_SHORT).show()
}

/**
 * "删除以外的部分"：导出范围 = 选中区间。
 */
private fun performKeepOnly(keepStartMs: Int, keepEndMs: Int) {
    startPos = binding.waveformView.millisecsToPixels(keepStartMs)
    endPos = binding.waveformView.millisecsToPixels(keepEndMs)

    // 清除选区高亮
    hasSelection = false
    binding.waveformView.clearHighlight()
    stopLoopPlayback()
    updateDisplay()

    Toast.makeText(this, "已删除以外的部分", Toast.LENGTH_SHORT).show()
}
```

### 新增布局 `bottom_sheet_segment_action.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:padding="16dp"
    android:background="@color/gray_800">

    <TextView
        android:id="@+id/tvSelectionInfo"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:textColor="@color/teal_200"
        android:textSize="14sp"
        android:paddingBottom="12dp" />

    <TextView
        android:id="@+id/action_delete_selected"
        android:layout_width="match_parent"
        android:layout_height="56dp"
        android:text="🗑  删除选中部分"
        android:textColor="@color/white"
        android:textSize="15sp"
        android:gravity="center_vertical"
        android:background="?attr/selectableItemBackground" />

    <TextView
        android:id="@+id/action_delete_others"
        android:layout_width="match_parent"
        android:layout_height="56dp"
        android:text="✂️  删除以外的部分（只保留选中）"
        android:textColor="@color/white"
        android:textSize="15sp"
        android:gravity="center_vertical"
        android:background="?attr/selectableItemBackground" />

    <TextView
        android:id="@+id/action_cancel"
        android:layout_width="match_parent"
        android:layout_height="56dp"
        android:text="取消"
        android:textColor="#AAAAAA"
        android:textSize="15sp"
        android:gravity="center_vertical"
        android:background="?attr/selectableItemBackground" />

</LinearLayout>
```

### 新增颜色资源

```xml
<color name="waveform_highlight">#33B2DFDB</color>          <!-- 半透明 teal_200 -->
<color name="waveform_highlight_border">#B2DFDB</color>      <!-- teal_200 -->
```

#### 涉及文件

| 文件 | 改动 |
|------|------|
| `AudioEditorActivity.kt` | 新增选区管理、循环播放、长按菜单、`performDeleteSelected`、`performKeepOnly`、触摸回调改造 |
| `WaveformView.kt` | 新增 `longPressDetector`、`setHighlight`/`clearHighlight`、选区高亮绘制、`waveformLongPress` 回调 |
| `AudioPlayer.kt` | 新增 `isLooping` 循环播放支持 |
| `bottom_sheet_segment_action.xml` | 新增底部操作菜单布局 |
| `colors.xml` | 新增 `waveform_highlight`、`waveform_highlight_border` 颜色 |

---

## 实施优先级

| 顺序 | 改动 | 复杂度 | 说明 |
|------|------|--------|------|
| 1 | 改动一：导出选择目录 + 记忆 | ⭐⭐ | 独立改动，不涉及其他模块 |
| 2 | 改动二：波形滚动条 | ⭐⭐ | 独立改动，WaveformView 内部 |
| 3 | 改动三：滑动选择 + 长按删除 | ⭐⭐⭐ | 涉及 WaveformView + AudioEditorActivity + AudioPlayer |

改动一和二互相独立，可并行开发。改动三的触摸逻辑较复杂，需仔细处理手势冲突。

---

## 风险点

1. **SAF 目录权限持久性**：`takePersistableUriPermission` 在设备重启后仍有效，但用户可能在系统设置中撤销权限。代码中已做 `canWrite()` 检查，失败时回退到选择器
2. **长按触发范围**：长按仅在已有选区内、手指未移动时触发。滑动选择时手指必然移动超过 touchSlop，长按不会同时触发，**无手势冲突**。唯一边界情况：用户在选区内按下后手抖移动了几个像素（超过 touchSlop 但小于 5px 选区阈值），长按不会触发，此时 `waveformTouchEnd` 中判断按下点在选区内 → 保持原选区不变，等用户重试
3. **循环播放间隙**：`AudioTrack` 的 `stop()` → `start()` 之间可能有微小间隙。如果对无缝循环有高要求，可考虑用 `AudioTrack.MODE_STATIC` 预加载选区 PCM，但会占用更多内存
4. **删除操作的语义**：由于只导出一段，"删除选中"在选中区间位于中间时只能保留前半段。UI 上应给用户明确提示（Toast 已包含）
