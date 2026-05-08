# StreamClip 多视频批处理队列设计文档

## 1. 需求概述

### 1.1 背景
当前 StreamClip 的"视频压缩"功能每次只能处理一个视频文件，用户需要：
1. 选择视频 → 2. 配置参数 → 3. 点击压缩 → 4. 等待完成 → 5. 重复步骤 1-4

当用户有多个视频需要同样参数压缩时（如旅行拍摄的几十个视频），重复操作效率极低。

### 1.2 目标
实现**多视频批处理压缩队列**，用户一次选择多个视频，配置一次参数，后台自动顺序执行，同时通过通知栏展示进度，处理完成后统一通知。

### 1.3 范围
- **Phase 1**：仅"视频压缩"功能支持批量队列
- **Phase 2**：扩展至"提取音频"、"自定义命令"
- **Phase 3**：任务持久化（应用重启后可恢复）

### 1.4 非目标
- 同一视频的并行压缩（见并行压缩调研结论，移动端无收益）
- 分片并行编码后合并（实现复杂度高，移动端不适用）

---

## 2. 架构总览

```
┌─────────────────────────────────────────────────────────────────────┐
│                           UI Layer                                   │
│  ┌─────────────┐  ┌──────────────┐  ┌───────────────────────────┐   │
│  │ Compress    │  │ BatchTask    │  │ Notification (System)     │   │
│  │ Fragment    │──│ Activity     │  │ • 进度通知                 │   │
│  │ (添加多选)   │  │ (任务列表)    │  │ • 完成通知                 │   │
│  └─────────────┘  └──────────────┘  └───────────────────────────┘   │
└────────────────────────┬────────────────────────────────────────────┘
                         │ Intent / Broadcast
┌────────────────────────▼────────────────────────────────────────────┐
│                      Service Layer                                   │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │              BatchTaskService (ForegroundService)              │   │
│  │  ┌─────────────┐  ┌──────────────┐  ┌─────────────────────┐  │   │
│  │  │TaskQueueMgr │  │ TaskExecutor │  │ NotificationManager │  │   │
│  │  │• enqueue()  │──│• execute()  │──│• showProgress()     │  │   │
│  │  │• dequeue()  │  │• cancel()   │  │• showComplete()     │  │   │
│  │  │• peek()     │  │             │  │                     │  │   │
│  │  └─────────────┘  └──────────────┘  └─────────────────────┘  │   │
│  └──────────────────────────────────────────────────────────────┘   │
└────────────────────────┬────────────────────────────────────────────┘
                         │ suspend function
┌────────────────────────▼────────────────────────────────────────────┐
│                     Data / Domain Layer                              │
│  ┌─────────────────┐  ┌─────────────────┐  ┌──────────────────┐    │
│  │ TaskRepository  │  │  FFmpegService  │  │  SettingsManager │    │
│  │ (SharedPrefs)   │  │  (existing)     │  │  (existing)      │    │
│  │ • save()        │  │  • executeCmd() │  │                  │    │
│  │ • load()        │  │  • getDuration()│  │                  │    │
│  │ • clear()       │  │                 │  │                  │    │
│  └─────────────────┘  └─────────────────┘  └──────────────────┘    │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.1 组件职责

| 组件 | 职责 | 类型 |
|------|------|------|
| `BatchTaskService` | 前台服务，持有任务队列，管理生命周期 | `ForegroundService` |
| `TaskQueueManager` | 内存任务队列，顺序调度执行 | Singleton / Service 内部 |
| `TaskExecutor` | 调用 FFmpegService 执行单个任务 | Service 内部 |
| `BatchNotificationManager` | 通知栏进度/完成展示 | Service 内部 |
| `TaskRepository` | 任务列表持久化（Phase 3） | Singleton |
| `BatchTaskActivity` | 任务列表 UI，展示所有任务状态 | `AppCompatActivity` |
| `CompressFragment` | 添加多选入口，提交任务到队列 | `Fragment`（修改） |

---

## 3. 数据模型

### 3.1 任务类型枚举

```kotlin
enum class TaskType {
    COMPRESS,      // 视频压缩
    EXTRACT_AUDIO, // 提取音频
    CUSTOM_COMMAND // 自定义命令
}
```

### 3.2 任务状态枚举

```kotlin
enum class TaskStatus {
    PENDING,       // 排队中
    RUNNING,       // 执行中
    PAUSED,        // 暂停（用户手动）
    COMPLETED,     // 完成
    FAILED,        // 失败（含错误信息）
    CANCELLED      // 已取消
}
```

### 3.3 任务数据类

```kotlin
data class BatchTaskItem(
    val id: String = UUID.randomUUID().toString(),
    val type: TaskType,
    val inputPath: String,           // 源文件绝对路径
    val outputPath: String,          // 输出文件绝对路径
    val config: TaskConfig,          // 任务配置
    val status: TaskStatus = TaskStatus.PENDING,
    val progress: Int = 0,           // 0-100
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val outputSizeBytes: Long = 0
)
```

### 3.4 任务配置数据类

复用现有 `CompressConfig`，但需可序列化：

```kotlin
data class TaskConfig(
    // 压缩配置（复用 CompressConfig 字段）
    val encoder: String = "h264_mediacodec",
    val bitrate: Int = 2000,
    val crf: Int = 23,
    val resolution: String = "original",
    val speed: String = "balanced",
    val preset: String = "medium",
    val audioEncoder: String = "copy",
    val isHardware: Boolean = true,
    val copyMetadata: Boolean = true,
    
    // 通用字段
    val taskType: TaskType = TaskType.COMPRESS,
    
    // 自定义命令专用
    val customCommand: String? = null
) : java.io.Serializable
```

### 3.5 批处理会话

```kotlin
data class BatchSession(
    val id: String = UUID.randomUUID().toString(),
    val taskIds: List<String>,
    val config: TaskConfig,
    val createdAt: Long = System.currentTimeMillis(),
    val totalTasks: Int = taskIds.size,
    val completedTasks: Int = 0,
    val failedTasks: Int = 0
)
```

---

## 4. 核心组件详细设计

### 4.1 BatchTaskService（前台服务）

#### 4.1.1 类定义

```kotlin
class BatchTaskService : Service() {
    
    companion object {
        const val ACTION_START = "com.pisces312.streamclip.action.START_BATCH"
        const val ACTION_STOP = "com.pisces312.streamclip.action.STOP_BATCH"
        const val ACTION_CANCEL_TASK = "com.pisces312.streamclip.action.CANCEL_TASK"
        const val ACTION_PAUSE = "com.pisces312.streamclip.action.PAUSE"
        const val ACTION_RESUME = "com.pisces312.streamclip.action.RESUME"
        const val EXTRA_TASK_IDS = "task_ids"
        const val EXTRA_CONFIG = "config"
        
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "batch_task_channel"
        
        @Volatile
        var isRunning = false
            private set
        
        fun start(context: Context, tasks: List<BatchTaskItem>) {
            val intent = Intent(context, BatchTaskService::class.java).apply {
                action = ACTION_START
                putParcelableArrayListExtra(EXTRA_TASK_IDS, ArrayList(tasks))
            }
            ContextCompat.startForegroundService(context, intent)
        }
        
        fun stop(context: Context) {
            val intent = Intent(context, BatchTaskService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueManager = TaskQueueManager()
    private lateinit var notificationManager: BatchNotificationManager
    
    override fun onCreate() {
        super.onCreate()
        notificationManager = BatchNotificationManager(this)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
            ACTION_CANCEL_TASK -> handleCancelTask(intent)
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
        }
        return START_NOT_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun handleStart(intent: Intent) {
        val tasks = intent.getParcelableArrayListExtra<BatchTaskItem>(EXTRA_TASK_IDS)
        if (tasks.isNullOrEmpty()) {
            stopSelf()
            return
        }
        
        isRunning = true
        queueManager.enqueueAll(tasks)
        
        // 启动前台服务
        val notification = notificationManager.createForegroundNotification(
            title = getString(R.string.batch_processing),
            content = getString(R.string.batch_queue_size, tasks.size)
        )
        startForeground(NOTIFICATION_ID, notification)
        
        // 开始处理队列
        serviceScope.launch {
            processQueue()
        }
    }
    
    private suspend fun processQueue() {
        while (isRunning && queueManager.hasPending()) {
            val task = queueManager.next() ?: break
            
            notificationManager.updateProgress(
                currentTask = task,
                completedCount = queueManager.completedCount,
                totalCount = queueManager.totalCount
            )
            
            val result = executeTask(task)
            
            when {
                result.success -> queueManager.markCompleted(task.id)
                result.isCancelled -> queueManager.markCancelled(task.id)
                else -> queueManager.markFailed(task.id, result.error)
            }
            
            // 发送广播更新 UI
            sendTaskUpdateBroadcast(task.id)
        }
        
        // 队列处理完毕
        val summary = queueManager.getSummary()
        notificationManager.showCompleteNotification(summary)
        stopForeground(STOP_FOREGROUND_REMOVE)
        isRunning = false
        stopSelf()
    }
    
    private suspend fun executeTask(task: BatchTaskItem): TaskResult {
        return try {
            val command = when (task.config.taskType) {
                TaskType.COMPRESS -> buildCompressCommand(task)
                TaskType.EXTRACT_AUDIO -> buildExtractCommand(task)
                TaskType.CUSTOM_COMMAND -> task.config.customCommand!!
            }
            
            val totalTimeMs = FFmpegService.getDurationMs(task.inputPath)
            
            FFmpegService.executeCommand(
                command = command,
                outputPath = task.outputPath,
                totalTimeMs = totalTimeMs,
                onProgress = { progress ->
                    queueManager.updateProgress(task.id, progress.percent)
                    notificationManager.updateTaskProgress(task.id, progress.percent)
                },
                onLog = { logLine ->
                    // 可选：收集日志用于失败时排查
                }
            ).let { result ->
                TaskResult(
                    success = result.success,
                    error = result.error,
                    isCancelled = false
                )
            }
        } catch (e: CancellationException) {
            TaskResult(success = false, error = null, isCancelled = true)
        } catch (e: Exception) {
            TaskResult(success = false, error = e.message, isCancelled = false)
        }
    }
    
    private fun handleStop() {
        serviceScope.cancel()
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    private fun handleCancelTask(intent: Intent) {
        val taskId = intent.getStringExtra("task_id") ?: return
        queueManager.markCancelled(taskId)
        // FFmpegService 不支持按任务取消，只能取消整个 session
        // Phase 2: 改进为支持单任务取消
    }
    
    private fun handlePause() {
        queueManager.pause()
    }
    
    private fun handleResume() {
        queueManager.resume()
        serviceScope.launch { processQueue() }
    }
    
    private fun sendTaskUpdateBroadcast(taskId: String) {
        val intent = Intent("com.pisces312.streamclip.TASK_UPDATE").apply {
            putExtra("task_id", taskId)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        isRunning = false
    }
}
```

#### 4.1.2 服务注册（AndroidManifest.xml）

```xml
<service
    android:name=".service.BatchTaskService"
    android:enabled="true"
    android:exported="false"
    android:foregroundServiceType="dataSync" />
```

### 4.2 TaskQueueManager

```kotlin
class TaskQueueManager {
    private val queue = ArrayDeque<BatchTaskItem>()
    private val allTasks = mutableMapOf<String, BatchTaskItem>()
    private val _taskFlow = MutableStateFlow<List<BatchTaskItem>>(emptyList())
    val taskFlow: StateFlow<List<BatchTaskItem>> = _taskFlow.asStateFlow()
    
    @Volatile
    private var isPaused = false
    
    val totalCount: Int get() = allTasks.size
    val completedCount: Int get() = allTasks.count { it.value.status == TaskStatus.COMPLETED }
    val pendingCount: Int get() = queue.size
    
    fun enqueueAll(tasks: List<BatchTaskItem>) {
        tasks.forEach { task ->
            allTasks[task.id] = task
            queue.addLast(task)
        }
        emitUpdate()
    }
    
    fun next(): BatchTaskItem? {
        if (isPaused) return null
        return queue.removeFirstOrNull()?.also { task ->
            allTasks[task.id] = task.copy(status = TaskStatus.RUNNING, startedAt = System.currentTimeMillis())
            emitUpdate()
        }
    }
    
    fun hasPending(): Boolean = queue.isNotEmpty()
    
    fun updateProgress(taskId: String, percent: Int) {
        allTasks[taskId]?.let { task ->
            allTasks[taskId] = task.copy(progress = percent.coerceIn(0, 100))
            emitUpdate()
        }
    }
    
    fun markCompleted(taskId: String) {
        allTasks[taskId]?.let { task ->
            allTasks[taskId] = task.copy(
                status = TaskStatus.COMPLETED,
                completedAt = System.currentTimeMillis(),
                progress = 100,
                outputSizeBytes = File(task.outputPath).length()
            )
            emitUpdate()
        }
    }
    
    fun markFailed(taskId: String, error: String?) {
        allTasks[taskId]?.let { task ->
            allTasks[taskId] = task.copy(
                status = TaskStatus.FAILED,
                completedAt = System.currentTimeMillis(),
                errorMessage = error
            )
            emitUpdate()
        }
    }
    
    fun markCancelled(taskId: String) {
        allTasks[taskId]?.let { task ->
            allTasks[taskId] = task.copy(status = TaskStatus.CANCELLED)
            emitUpdate()
        }
    }
    
    fun pause() { isPaused = true }
    fun resume() { isPaused = false }
    
    fun getSummary(): BatchSummary {
        val tasks = allTasks.values.toList()
        return BatchSummary(
            total = tasks.size,
            completed = tasks.count { it.status == TaskStatus.COMPLETED },
            failed = tasks.count { it.status == TaskStatus.FAILED },
            cancelled = tasks.count { it.status == TaskStatus.CANCELLED }
        )
    }
    
    private fun emitUpdate() {
        _taskFlow.value = allTasks.values.toList().sortedBy { it.createdAt }
    }
}

data class BatchSummary(
    val total: Int,
    val completed: Int,
    val failed: Int,
    val cancelled: Int
)

data class TaskResult(
    val success: Boolean,
    val error: String?,
    val isCancelled: Boolean
)
```

### 4.3 BatchNotificationManager

```kotlin
class BatchNotificationManager(private val context: Context) {
    
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) 
        as NotificationManager
    
    init {
        createChannel()
    }
    
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.batch_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.batch_notification_channel_desc)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    fun createForegroundNotification(title: String, content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, BatchTaskActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
    
    fun updateProgress(
        currentTask: BatchTaskItem,
        completedCount: Int,
        totalCount: Int
    ) {
        val progress = if (totalCount > 0) {
            (completedCount * 100 / totalCount)
        } else 0
        
        val fileName = File(currentTask.inputPath).name
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.batch_processing_progress, completedCount + 1, totalCount))
            .setContentText(fileName)
            .setSmallIcon(R.drawable.ic_notification)
            .setProgress(100, currentTask.progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                R.drawable.ic_pause,
                context.getString(R.string.pause),
                createActionPendingIntent(ACTION_PAUSE)
            )
            .addAction(
                R.drawable.ic_cancel,
                context.getString(R.string.cancel),
                createActionPendingIntent(ACTION_STOP)
            )
            .build()
        
        notificationManager.notify(BatchTaskService.NOTIFICATION_ID, notification)
    }
    
    fun updateTaskProgress(taskId: String, percent: Int) {
        // 高频更新节流：每 5% 或 2 秒更新一次
        // 实现略
    }
    
    fun showCompleteNotification(summary: BatchSummary) {
        val content = buildString {
            append(context.getString(R.string.batch_complete_summary, summary.completed, summary.total))
            if (summary.failed > 0) {
                append(" ")
                append(context.getString(R.string.batch_failed_count, summary.failed))
            }
        }
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.batch_complete))
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification_done)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0,
                    Intent(context, BatchTaskActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        
        notificationManager.notify(BatchTaskService.NOTIFICATION_ID + 1, notification)
    }
    
    private fun createActionPendingIntent(action: String): PendingIntent {
        val intent = Intent(context, BatchTaskService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            context, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
```

---

## 5. UI 设计

### 5.1 CompressFragment 修改（添加批量入口）

参照 MergeFragment 的多选实现（`StartActivityForResult` + `EXTRA_ALLOW_MULTIPLE` + `clipData`）。

#### 5.1.1 布局修改

在现有"选择视频"区域下方添加"批量选择"按钮和已选文件列表：

```xml
<!-- fragment_compress.xml 修改 -->
<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal">

    <Button
        android:id="@+id/btnSelectVideo"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:text="@string/select_video" />

    <Button
        android:id="@+id/btnSelectMultiple"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:text="@string/select_multiple"
        style="?attr/materialButtonOutlinedStyle" />
</LinearLayout>

<!-- 已选文件列表（参照 MergeFragment 的 recyclerView） -->
<androidx.recyclerview.widget.RecyclerView
    android:id="@+id/recyclerSelectedVideos"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:maxHeight="200dp"
    android:visibility="gone" />

<TextView
    android:id="@+id/tvBatchStatus"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:visibility="gone" />
```

#### 5.1.2 多选逻辑（参照 MergeFragment）

```kotlin
// CompressFragment.kt

// 已选批量文件列表（复用 MergeFragment 的 VideoListAdapter 或新建 BatchVideoListAdapter）
private val batchVideoUris = mutableListOf<Uri>()
private var batchVideoAdapter: BatchVideoListAdapter? = null

private val pickMultipleVideos = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
        result.data?.clipData?.let { clipData ->
            for (i in 0 until clipData.itemCount) {
                val uri = clipData.getItemAt(i).uri
                batchVideoUris.add(uri)
                if (i == 0) {
                    SettingsManager.setLastVideoDir(requireContext(), uri)
                }
            }
            updateBatchUi()
        } ?: result.data?.data?.let { uri ->
            batchVideoUris.add(uri)
            SettingsManager.setLastVideoDir(requireContext(), uri)
            updateBatchUi()
        }
    }
}

private fun setupBatchButtons() {
    binding.btnSelectMultiple.setOnClickListener {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                SettingsManager.getLastVideoDir(requireContext())?.let { uri ->
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
                }
            }
        }
        pickMultipleVideos.launch(intent)
    }
}

private fun setupBatchRecyclerView() {
    batchVideoAdapter = BatchVideoListAdapter(batchVideoUris) { position ->
        batchVideoUris.removeAt(position)
        batchVideoAdapter?.notifyDataSetChanged()
        updateBatchUi()
    }
    binding.recyclerSelectedVideos.layoutManager = LinearLayoutManager(requireContext())
    binding.recyclerSelectedVideos.adapter = batchVideoAdapter
}

private fun updateBatchUi() {
    binding.recyclerSelectedVideos.isVisible = batchVideoUris.isNotEmpty()
    binding.tvBatchStatus.isVisible = batchVideoUris.isNotEmpty()
    binding.tvBatchStatus.text = "已选择 ${batchVideoUris.size} 个视频"
    
    // 统计直读/缓存数量（参照 MergeFragment 的 updateInputStatus）
    var directCount = 0
    var cacheCount = 0
    for (uri in batchVideoUris) {
        val result = FileUtils.getPathResultFromUri(requireContext(), uri)
        if (result != null) {
            if (result.isDirectRead) directCount++ else cacheCount++
        }
    }
    val parts = mutableListOf<String>()
    if (directCount > 0) parts.add("直读: $directCount")
    if (cacheCount > 0) parts.add("缓存: $cacheCount")
    if (cacheCount > 0) {
        binding.tvBatchStatus.setTextColor(0xFFFF9800.toInt())
    } else {
        binding.tvBatchStatus.setTextColor(0xFF4CAF50.toInt())
    }
    binding.tvBatchStatus.text = "已选择 ${batchVideoUris.size} 个视频 (${parts.joinToString(", ")})"
}

private fun showBatchConfirmDialog() {
    if (batchVideoUris.isEmpty()) return
    
    val config = buildConfig()
    
    // 解析所有 URI 为路径
    val pathResults = batchVideoUris.mapNotNull { uri ->
        FileUtils.getPathResultFromUri(requireContext(), uri)
    }
    
    if (pathResults.isEmpty()) {
        Toast.makeText(requireContext(), getString(R.string.cannot_read_videos), Toast.LENGTH_SHORT).show()
        return
    }
    
    AlertDialog.Builder(requireContext())
        .setTitle(getString(R.string.batch_confirm_title))
        .setMessage(getString(R.string.batch_confirm_message, pathResults.size))
        .setPositiveButton(R.string.start) { _, _ ->
            val tasks = pathResults.map { pathResult ->
                val sourceFile = java.io.File(pathResult.path)
                val outputDir = SettingsManager.getOutputDir(requireContext(), sourceFile)
                val outputName = SettingsManager.getOutputFileName(
                    requireContext(), sourceFile.name, "compressed", "mp4"
                )
                BatchTaskItem(
                    type = TaskType.COMPRESS,
                    inputPath = pathResult.path,
                    outputPath = java.io.File(outputDir, outputName).absolutePath,
                    config = config.toTaskConfig()
                )
            }
            BatchTaskService.start(requireContext(), tasks)
            // 清空已选列表
            batchVideoUris.clear()
            updateBatchUi()
            // 跳转到任务列表页
            startActivity(Intent(requireContext(), BatchTaskActivity::class.java))
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}
```

#### 5.1.3 批量选择按钮处理

```kotlin
// 将原来的单文件选择按钮保留，批量选择作为新入口
// 或：单文件选择也走批处理队列（只提交1个任务）

// 方案 A：保持单文件压缩原逻辑不变，批量选择走新逻辑
binding.btnSelectMultiple.setOnClickListener { /* 上面已定义 */ }

// 方案 B（推荐）：单文件也走批处理队列，统一体验
// 在 btnCompress 点击时，如果 batchVideoUris 不为空，走批量逻辑；否则走单文件逻辑
binding.btnCompress.setOnClickListener {
    when {
        batchVideoUris.isNotEmpty() -> showBatchConfirmDialog()
        videoPath != null -> executeSingleCompress() // 原单文件逻辑
        else -> Toast.makeText(requireContext(), getString(R.string.please_select_video), Toast.LENGTH_SHORT).show()
    }
}
```

### 5.2 BatchTaskActivity（任务列表）

```xml
<!-- activity_batch_task.xml -->
<androidx.coordinatorlayout.widget.CoordinatorLayout>

    <com.google.android.material.appbar.AppBarLayout>
        <androidx.appcompat.widget.Toolbar
            android:id="@+id/toolbar"
            android:title="@string/batch_tasks" />
    </com.google.android.material.appbar.AppBarLayout>

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/recyclerTasks"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        app:layout_behavior="@string/appbar_scrolling_view_behavior" />

    <LinearLayout
        android:id="@+id/emptyView"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center"
        android:orientation="vertical"
        android:visibility="gone">
        <TextView
            android:text="@string/no_batch_tasks"
            android:textSize="18sp" />
    </LinearLayout>

    <com.google.android.material.floatingactionbutton.FloatingActionButton
        android:id="@+id/fabClear"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom|end"
        android:layout_margin="16dp"
        android:src="@drawable/ic_delete"
        app:layout_behavior="@string/hide_bottom_view_on_scroll_behavior" />

</androidx.coordinatorlayout.widget.CoordinatorLayout>
```

列表项布局：

```xml
<!-- item_batch_task.xml -->
<androidx.cardview.widget.CardView>
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:padding="12dp">

        <!-- 状态图标 -->
        <ImageView
            android:id="@+id/ivStatus"
            android:layout_width="40dp"
            android:layout_height="40dp" />

        <LinearLayout
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:orientation="vertical"
            android:layout_marginStart="12dp">

            <TextView
                android:id="@+id/tvFileName"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:textSize="14sp"
                android:maxLines="1"
                android:ellipsize="middle" />

            <ProgressBar
                android:id="@+id/progressBar"
                style="?android:attr/progressBarStyleHorizontal"
                android:layout_width="match_parent"
                android:layout_height="4dp"
                android:layout_marginTop="4dp" />

            <TextView
                android:id="@+id/tvStatus"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:textSize="12sp"
                android:layout_marginTop="2dp" />
        </LinearLayout>

        <!-- 操作按钮（重试/取消/打开） -->
        <ImageButton
            android:id="@+id/btnAction"
            android:layout_width="40dp"
            android:layout_height="40dp" />
    </LinearLayout>
</androidx.cardview.widget.CardView>
```

Activity 代码框架：

```kotlin
class BatchTaskActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityBatchTaskBinding
    private lateinit var adapter: BatchTaskAdapter
    
    private val viewModel: BatchTaskViewModel by viewModels()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBatchTaskBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupToolbar()
        setupRecyclerView()
        observeTasks()
        
        binding.fabClear.setOnClickListener {
            viewModel.clearCompleted()
        }
    }
    
    private fun observeTasks() {
        lifecycleScope.launch {
            viewModel.tasks.collect { tasks ->
                adapter.submitList(tasks)
                binding.emptyView.isVisible = tasks.isEmpty()
            }
        }
    }
}
```

### 5.3 ViewModel

```kotlin
class BatchTaskViewModel : ViewModel() {
    
    private val queueManager = TaskQueueManager()
    
    val tasks: StateFlow<List<BatchTaskItem>> = queueManager.taskFlow
    
    fun clearCompleted() {
        // 清理已完成的任务记录
    }
    
    fun retryTask(taskId: String) {
        // 重试失败任务
    }
    
    fun cancelTask(taskId: String) {
        BatchTaskService.stop(/* context */)
    }
}
```

---

## 6. 状态机

```
                    ┌─────────┐
                    │  PENDING │
                    └────┬────┘
                         │ queueManager.next()
                         ▼
                    ┌─────────┐
         ┌─────────│ RUNNING │──────────┐
         │         └────┬────┘          │
         │              │                │
    cancel()      success          failure
         │              │                │
         ▼              ▼                ▼
   ┌──────────┐  ┌──────────┐  ┌──────────┐
   │CANCELLED │  │COMPLETED │  │  FAILED  │
   └──────────┘  └────┬─────┘  └────┬─────┘
                      │             │
                      │          retry()
                      │             │
                      │             ▼
                      │        ┌─────────┐
                      │        │ PENDING │
                      │        └─────────┘
                      │
                      ▼
                (clear/remove)
```

---

## 7. 错误处理与恢复

### 7.1 错误分类

| 错误类型 | 示例 | 处理策略 |
|---------|------|---------|
| 可重试 | 网络存储临时不可读 | 自动重试 1 次，失败后标记 FAILED |
| 配置错误 | 编码器不支持 | 标记 FAILED，记录错误日志 |
| 资源不足 | 存储空间不足 | 标记 FAILED，提示用户清理空间 |
| 用户取消 | 点击取消按钮 | 标记 CANCELLED，删除临时文件 |
| 系统 kill | 内存不足被系统回收 | Phase 3：持久化后恢复 |

### 7.2 错误重试

```kotlin
private suspend fun executeTaskWithRetry(task: BatchTaskItem, maxRetries: Int = 1): TaskResult {
    var lastError: TaskResult? = null
    repeat(maxRetries + 1) { attempt ->
        val result = executeTask(task)
        if (result.success || result.isCancelled) return result
        lastError = result
        if (attempt < maxRetries) delay(1000L * (attempt + 1))
    }
    return lastError!!
}
```

### 7.3 临时文件清理

```kotlin
private fun cleanupOnFailure(outputPath: String) {
    try {
        File(outputPath).deleteIfExists()
    } catch (e: Exception) {
        LogCollector.w("BatchTaskService", "Failed to cleanup: $outputPath")
    }
}
```

---

## 8. 与现有功能的集成

### 8.1 复用 FFmpegService

`FFmpegService.executeCommand()` 已为 `suspend` 函数，支持协程取消，可直接复用。无需修改。

### 8.2 复用 CompressConfig

添加扩展函数转换为 `TaskConfig`：

```kotlin
// CompressConfig.kt
fun CompressConfig.toTaskConfig(): TaskConfig = TaskConfig(
    encoder = encoder,
    bitrate = bitrate,
    crf = crf,
    resolution = resolution,
    speed = speed,
    preset = preset,
    audioEncoder = audioEncoder,
    isHardware = isHardware,
    copyMetadata = copyMetadata,
    taskType = TaskType.COMPRESS
)
```

### 8.3 复用 SettingsManager

输出目录和文件名生成逻辑直接复用 `SettingsManager`：

```kotlin
val outputDir = SettingsManager.getOutputDir(context, sourceFile)
val outputName = SettingsManager.getOutputFileName(context, sourceFile.name, "compressed", "mp4")
```

### 8.4 复用 FileUtils

文件扫描、时间戳恢复等逻辑直接复用：

```kotlin
FileUtils.scanFile(context, File(outputPath))
sourceFileTimes?.let { (creation, modified) ->
    FileUtils.applyFileTimes(outputPath, creation, modified)
}
```

### 8.5 MainActivity 添加入口

在 Toolbar 菜单添加"批处理任务"入口：

```kotlin
// menu_main.xml
<item
    android:id="@+id/action_batch_tasks"
    android:title="@string/batch_tasks"
    android:icon="@drawable/ic_batch"
    app:showAsAction="ifRoom" />

// MainActivity.kt
R.id.action_batch_tasks -> {
    startActivity(Intent(this, BatchTaskActivity::class.java))
    true
}
```

---

## 9. 实现步骤

### Phase 1：核心队列（2-3 天）

1. **Day 1**：数据模型 + TaskQueueManager
   - 创建 `TaskType`, `TaskStatus`, `BatchTaskItem`, `TaskConfig`, `BatchSummary`
   - 实现 `TaskQueueManager`（内存队列 + StateFlow）

2. **Day 1-2**：BatchTaskService
   - 创建 `BatchTaskService`（ForegroundService）
   - 实现 `processQueue()` 主循环
   - 实现任务执行逻辑（复用 FFmpegService）
   - AndroidManifest.xml 注册服务

3. **Day 2-3**：BatchNotificationManager
   - 通知渠道创建
   - 前台通知（进度条 + 操作按钮）
   - 完成通知
   - 通知节流优化

4. **Day 3**：CompressFragment 多选集成
   - 添加"批量选择"按钮
   - 多选文件选择器（`OpenMultipleDocuments`）
   - 确认对话框
   - 启动 BatchTaskService

### Phase 2：任务列表 UI（1-2 天）

5. **Day 4**：BatchTaskActivity
   - 创建 Activity + RecyclerView + Adapter
   - 状态图标 + 进度条 + 操作按钮
   - 空状态 + 加载状态
   - 与 TaskQueueManager 数据绑定

6. **Day 4-5**：ViewModel + 状态管理
   - `BatchTaskViewModel`
   - 本地广播接收 TaskQueueManager 更新
   - 清理已完成任务
   - 重试失败任务

7. **Day 5**：MainActivity 入口 + 菜单图标

### Phase 3：持久化与优化（可选，2 天）

8. **Day 6**：TaskRepository 持久化
   - SharedPreferences / DataStore 保存任务列表
   - Service 重启后恢复队列
   - 应用重启后恢复历史记录

9. **Day 7**：性能优化
   - 通知更新节流（每 2 秒 / 5% 更新一次）
   - 大列表 RecyclerView DiffUtil 优化
   - 内存泄漏检查

---

## 10. 风险评估

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| 前台服务被系统限制（Android 12+） | 后台处理中断 | 使用 `dataSync` 类型，显示明确通知 |
| 大视频队列内存不足 | OOM 崩溃 | 队列只保存路径，不加载视频；限制并发为 1 |
| 存储空间不足 | 任务失败 | 执行前检查可用空间，不足时跳过并提示 |
| 通知权限被拒（Android 13+） | 无法展示进度 | 引导用户开启通知权限，降级为仅 Toast |
| FFmpeg 单实例不支持取消单个任务 | 取消粒度粗 | Phase 2 改进：为每个任务分配独立 FFmpeg session |

---

## 11. API 参考

### 11.1 启动批处理

```kotlin
val tasks = listOf(
    BatchTaskItem(
        type = TaskType.COMPRESS,
        inputPath = "/sdcard/DCIM/video1.mp4",
        outputPath = "/sdcard/Movies/video1_compressed.mp4",
        config = TaskConfig(encoder = "h264_mediacodec", bitrate = 2000)
    ),
    // ...
)
BatchTaskService.start(context, tasks)
```

### 11.2 监听任务状态

```kotlin
// 在 Activity/Fragment 中
TaskQueueManager.taskFlow.collect { tasks ->
    // 更新 UI
}

// 或通过本地广播
LocalBroadcastManager.getInstance(context)
    .registerReceiver(receiver, IntentFilter("com.pisces312.streamclip.TASK_UPDATE"))
```

### 11.3 取消任务

```kotlin
BatchTaskService.stop(context)           // 停止全部
// Phase 2: 支持单任务取消
```

---

## 12. 新增文件清单

| 路径 | 说明 |
|------|------|
| `service/BatchTaskService.kt` | 前台服务，核心调度 |
| `service/TaskQueueManager.kt` | 内存任务队列管理 |
| `service/BatchNotificationManager.kt` | 通知管理 |
| `model/BatchTaskItem.kt` | 任务数据类 |
| `model/TaskConfig.kt` | 任务配置数据类 |
| `model/TaskType.kt` | 任务类型枚举 |
| `model/TaskStatus.kt` | 任务状态枚举 |
| `ui/BatchTaskActivity.kt` | 任务列表 Activity |
| `ui/BatchTaskViewModel.kt` | 任务列表 ViewModel |
| `adapter/BatchTaskAdapter.kt` | 任务列表 Adapter |
| `adapter/BatchVideoListAdapter.kt` | 批量选择时展示已选文件的 Adapter（参照 VideoListAdapter） |
| `layout/activity_batch_task.xml` | 任务列表布局 |
| `layout/item_batch_task.xml` | 任务列表项布局 |
| `layout/item_batch_video.xml` | 已选文件列表项布局（参照 MergeFragment 的 item_video.xml） |
| `drawable/ic_notification*.xml` | 通知图标 |
| `drawable/ic_batch.xml` | 批处理菜单图标 |

### 修改文件

| 路径 | 修改内容 |
|------|---------|
| `fragment/CompressFragment.kt` | 添加多选按钮和批量提交逻辑 |
| `model/CompressConfig.kt` | 添加 `toTaskConfig()` 扩展 |
| `MainActivity.kt` | 添加批处理菜单入口 |
| `AndroidManifest.xml` | 注册 BatchTaskService |
| `menu/menu_main.xml` | 添加批处理菜单项 |
| `strings.xml` | 添加批处理相关字符串 |

---

*文档版本: v1.0*
*日期: 2026-05-08*
*作者: Nix*
