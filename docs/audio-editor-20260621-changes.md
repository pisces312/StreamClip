# AudioEditor 修改方案 (2026-06-21)

针对主人提出的三个问题，先记方案再实现。

---

## 改动 1：Bug — 选区还没播完就停

### 现象
选区内播放，未到选区终点就 UI 复位（光标跳回选区起点），但 audioTrack 实际还在播（用户听到的）。

### 根因（review 后）
之前我加了两条「选区结束」触发路径，两条并存：
- 路径 ①：`AudioPlayer.start()` 的 playThread 写完 samples 到 loopEndSample → 调 `listener.onCompletion()` → onPlaybackComplete 复位 UI
- 路径 ②：`selectionCompleteRunnable` (handler.postDelayed durationMs + 150) → 调 `onPlaybackComplete()`

**路径 ① 比路径 ② 早 50-150ms 触发**（playThread 写满 samples 时 audioTrack 内部 buffer 还没排空），导致：
- UI 提前复位（光标回选区起点）
- audioTrack 真正播完时，UI 早已「停了」
- 用户感知 = 「选区还没播完就停了 + 跳回选区起点」

### 修法
**让 playThread 不再触发 listener**（针对选区场景）；选区**只**走 selectionCompleteRunnable 路径。无选区播放**保留** playThread listener 触发（因为 selectionCompleteRunnable 不排全曲场景）。

具体改 3 处：

**A. `AudioPlayer.kt`** — 新增 `hasSelectionRange: Boolean` flag
```kotlin
private var hasSelectionRange = false
fun setPlaybackRange(startMsec: Int, endMsec: Int, hasSelectionRange: Boolean = true) {
    val wasPlaying = isPlaying()
    stop()
    playbackStart = (startMsec * (sampleRate / 1000.0)).toInt().coerceAtMost(numSamples)
    val endSample = (endMsec * (sampleRate / 1000.0)).toInt().coerceAtMost(numSamples)
    loopStartSample = playbackStart
    loopEndSample = endSample
    this.hasSelectionRange = hasSelectionRange
    audioTrack.setNotificationMarkerPosition(loopEndSample - 1 - loopStartSample)
    if (wasPlaying) start()
}
```

**B. `AudioPlayer.start()`** — playThread 写完后只无选区时触发 listener
```kotlin
if (naturalEnd && !hasSelectionRange) listener?.onCompletion()
```

**C. `AudioEditorActivity.startPlayback()`** — 全曲播放也走 setPlaybackRange 路径
- 有选区：`it.setPlaybackRange(startMs, endMs, hasSelectionRange = true)`
- 无选区：`it.setPlaybackRange(0, numSamplesMs, hasSelectionRange = false)`
- 全曲播放也排 selectionCompleteRunnable（统一 UI 复位机制）

### 影响面 review
| 已有功能 | 风险 | 说明 |
|---|---|---|
| 选区循环播放 (startLoopPlayback) | 0 | 走 setLooping(true) + onMarkerReached 路径，不动 |
| 全曲播放 | 低 | setPlaybackRange(0, numSamplesMs, false) 行为不变 |
| 手动暂停/停止 | 0 | pausePlayback/stopPlayback 不动这套 |
| seekTo | 0 | 不动 setPlaybackRange |
| Looper 护栏 | 0 | onPlaybackComplete 入口 thread guard 不动 |
| selectionCompleteRunnable 150ms 余量 | 0 | 保留 — audioTrack buffer 排空需要时间 |

### 验证用例
1. 选区 5s，播满 5s 才 UI 复位
2. 选区 0.5s，播满 0.5s
3. 无选区播放到文件末尾 UI 复位（行为不变）
4. 选区循环不受影响
5. 选区播放中手动暂停，selectionCompleteRunnable 触不到（isPlaying=false 挡住）
6. 150ms 余量不超出，音频干净

---

## 改动 2：自动缩放（防时间戳重叠）

### 现状
- 初始 zoom 由 `data.initialZoomLevel` 决定
- 时间戳：每 50px 一根，5s 一根
- **问题**：曲短时初始 zoom 让波形挤出屏幕，时间戳挤在一起重叠

### 方案
`loadOptimizedDecode` 成功后，根据曲长计算**最大可放大 zoom**：

```kotlin
// 1 秒至少占 50px（保证时间戳不重叠，5s 线之间 > 10px 间距）
private val MIN_PX_PER_SECOND = 50f

val numSamples = decoded.numSamples
val sampleRate = decoded.sampleRate
val durationSec = numSamples.toFloat() / sampleRate

val visibleWidth = binding.waveformView.width.takeIf { it > 0 }
    ?: resources.displayMetrics.widthPixels

val targetZoom = if (durationSec * MIN_PX_PER_SECOND <= visibleWidth) {
    // 短音频：放大但不超 1.5x 屏宽（保留一点滚动）
    visibleWidth * 1.5f / durationSec
} else {
    // 长音频：按最小密度
    MIN_PX_PER_SECOND
}

binding.waveformView.setZoomToPxPerSecond(targetZoom)
```

`WaveformView` 加公开方法 `setZoomToPxPerSecond(px: Float)`，对应到现有 zoomLevel 模型。

### 触发时机
- 每次 `loadAudio` 完成后调一次（在 `setWaveform(...)` 之后）
- 不影响用户后续缩放手势

### 影响面
- 缩放初始值变了；pinch/按钮缩放不变
- 不动 onDraw 逻辑

---

## 改动 3：删/留按钮始终显示，灰掉 disabled

### 现状
- `btnDeleteSelected`、`btnKeepOnly` 已在 click listener 注册
- 但**没看到**根据 hasSelection 启用/禁用的逻辑
- 位置：当前在工具栏/菜单（具体看 layout）

### 方案
**布局移动**：把两个按钮从工具栏**移到播放控制行下方**（play/stop/loop 下面），始终显示。

**布局改法**（activity_audio_editor.xml）：在播放按钮的 LinearLayout 下面加一行：
```xml
<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:gravity="center">
    <ImageButton android:id="@+id/btnDeleteSelected" .../>
    <ImageButton android:id="@+id/btnKeepOnly" .../>
    <ImageButton android:id="@+id/btnUndo" .../>
</LinearLayout>
```

**启用/禁用逻辑**（Kotlin）：封装 `updateSelectionActionsState()`，**所有改 hasSelection 的地方都调一次**：
```kotlin
private fun updateSelectionActionsState() {
    binding.btnDeleteSelected.isEnabled = hasSelection
    binding.btnKeepOnly.isEnabled = hasSelection
    binding.btnUndo.isEnabled = canUndo
}
```

**调用点**（review 后）：
- onCreate 末尾（初始 hasSelection=false，灰掉）
- 选区起始/结束拖动（onSelectionChanged）
- 选区创建/清除（setSelection / clearSelection）
- 撤销/重做后
- 音频加载完成（重置 hasSelection=false）

### 影响面
- 按钮位置变化可能挤压其他控件
- 旧版禁用逻辑（如果存在）要移除

---

## 实施顺序

1. **改动 1（核心 bug）** — 最关键，影响核心播放体验
2. **改动 3（按钮）** — 布局改完一起看效果
3. **改动 2（自动缩放）** — 数据相关，触发时机要稳

每步 build → 发主人测试 → 反馈再继续下一步。
