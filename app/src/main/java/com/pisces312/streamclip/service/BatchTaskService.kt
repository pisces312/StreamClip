package com.pisces312.streamclip.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.pisces312.streamclip.R
import com.pisces312.streamclip.model.BatchTaskItem
import com.pisces312.streamclip.model.TaskConfig
import com.pisces312.streamclip.model.TaskResult
import com.pisces312.streamclip.model.TaskStatus
import com.pisces312.streamclip.model.TaskType
import com.pisces312.streamclip.util.LogCollector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class BatchTaskService : Service() {

    companion object {
        const val ACTION_START = "com.pisces312.streamclip.action.START_BATCH"
        const val ACTION_STOP = "com.pisces312.streamclip.action.STOP_BATCH"
        const val ACTION_CANCEL_TASK = "com.pisces312.streamclip.action.CANCEL_TASK"
        const val ACTION_PAUSE = "com.pisces312.streamclip.action.PAUSE"
        const val ACTION_RESUME = "com.pisces312.streamclip.action.RESUME"
        const val EXTRA_TASKS = "tasks"

        const val NOTIFICATION_ID = 1001

        @Volatile
        var isRunning = false
            private set

        fun stop(context: Context) {
            val intent = Intent(context, BatchTaskService::class.java).apply {
                action = ACTION_STOP
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun start(context: Context, tasks: List<BatchTaskItem>) {
            val intent = Intent(context, BatchTaskService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TASKS, ArrayList(tasks))
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancelTask(context: Context, taskId: String) {
            val intent = Intent(context, BatchTaskService::class.java).apply {
                action = ACTION_CANCEL_TASK
                putExtra("task_id", taskId)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private var serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var notificationManager: BatchNotificationManager
    @Volatile
    private var isQueueProcessing = false

    // Store running task jobs for individual cancellation
    private val runningTaskJobs = ConcurrentHashMap<String, Job>()

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
        @Suppress("DEPRECATION")
        val tasks = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(EXTRA_TASKS, ArrayList::class.java) as? ArrayList<BatchTaskItem>
        } else {
            intent.getSerializableExtra(EXTRA_TASKS) as? ArrayList<BatchTaskItem>
        }

        if (tasks.isNullOrEmpty()) {
            stopSelf()
            return
        }

        isRunning = true
        // Recreate scope if it was cancelled by a previous stop
        if (!serviceScope.isActive) {
            serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
        TaskQueueManager.enqueueAll(tasks)

        val notification = notificationManager.createForegroundNotification(
            title = getString(R.string.batch_processing),
            content = getString(R.string.batch_queue_size, tasks.size)
        )
        startForeground(NOTIFICATION_ID, notification)

        serviceScope.launch {
            processQueue()
        }
    }

    private suspend fun processQueue() {
        if (isQueueProcessing) return
        isQueueProcessing = true
        try {
            while (isRunning && TaskQueueManager.hasPending()) {
                val task = TaskQueueManager.next() ?: break

                notificationManager.updateProgress(
                    currentTask = task,
                    completedCount = TaskQueueManager.completedCount,
                    totalCount = TaskQueueManager.totalCount
                )

                // Launch task in a separate coroutine for cancellation support
                val taskJob = serviceScope.launch {
                    val result = executeTaskWithRetry(task)

                    when {
                        result.success -> TaskQueueManager.markCompleted(task.id)
                        result.isCancelled -> TaskQueueManager.markCancelled(task.id)
                        else -> TaskQueueManager.markFailed(task.id, result.error)
                    }
                }

                // Store the job for individual cancellation
                runningTaskJobs[task.id] = taskJob

                // Wait for task to complete
                taskJob.join()

                // Remove from running jobs
                runningTaskJobs.remove(task.id)
            }

            val summary = TaskQueueManager.getSummary()
            notificationManager.showCompleteNotification(summary)
            stopForeground(STOP_FOREGROUND_REMOVE)
            isRunning = false
        } finally {
            isQueueProcessing = false
            stopSelf()
        }
    }

    private suspend fun executeTaskWithRetry(
        task: BatchTaskItem,
        maxRetries: Int = 1
    ): TaskResult {
        var lastError: TaskResult? = null
        repeat(maxRetries + 1) { attempt ->
            val result = executeTask(task)
            if (result.success || result.isCancelled) return result
            lastError = result
            if (attempt < maxRetries) delay(1000L * (attempt + 1))
        }
        return lastError!!
    }

    private suspend fun executeTask(task: BatchTaskItem): TaskResult {
        return try {
            val command = when (task.config.taskType) {
                TaskType.COMPRESS -> buildCompressCommand(task)
                TaskType.EXTRACT_AUDIO -> buildExtractCommand(task)
                TaskType.CUSTOM_COMMAND -> task.config.customCommand ?: return TaskResult(success = false, error = "Custom command is null", isCancelled = false)
            }

            val totalTimeMs = FFmpegService.getDurationMs(task.inputPath)
            val sourceFileTimes = com.pisces312.streamclip.util.FileUtils.readFileTimes(task.inputPath)

            FFmpegService.executeCommand(
                command = command,
                outputPath = task.outputPath,
                totalTimeMs = totalTimeMs,
                onProgress = { progress ->
                    TaskQueueManager.updateProgress(task.id, progress.percent)
                    notificationManager.updateProgress(
                        currentTask = task.copy(progress = progress.percent),
                        completedCount = TaskQueueManager.completedCount,
                        totalCount = TaskQueueManager.totalCount
                    )
                },
                onLog = { }
            ).let { result ->
                if (result.success) {
                    com.pisces312.streamclip.util.FileUtils.scanFile(
                        this,
                        java.io.File(task.outputPath)
                    )
                    sourceFileTimes?.let { (creation, modified) ->
                        com.pisces312.streamclip.util.FileUtils.applyFileTimes(task.outputPath, creation, modified)
                    }
                } else {
                    cleanupOnFailure(task.outputPath)
                }
                TaskResult(
                    success = result.success,
                    error = result.error,
                    isCancelled = false
                )
            }
        } catch (e: CancellationException) {
            cleanupOnFailure(task.outputPath)
            TaskResult(success = false, error = null, isCancelled = true)
        } catch (e: Exception) {
            cleanupOnFailure(task.outputPath)
            TaskResult(success = false, error = e.message, isCancelled = false)
        }
    }

    private fun buildCompressCommand(task: BatchTaskItem): String {
        val info = FFmpegService.probeVideoInfo(task.inputPath)
        return task.config.compressConfig.toFFmpegCommand(
            task.inputPath, task.outputPath,
            info?.colorSpace ?: "", info?.colorPrimaries ?: "", info?.colorTransfer ?: ""
        )
    }

    private fun buildExtractCommand(task: BatchTaskItem): String {
        return "-i \"${task.inputPath}\" -vn -c:a copy \"${task.outputPath}\""
    }

    private fun cleanupOnFailure(outputPath: String) {
        try {
            java.io.File(outputPath).delete()
        } catch (e: Exception) {
            LogCollector.w("BatchTaskService", "Failed to cleanup: $outputPath")
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
        TaskQueueManager.markCancelled(taskId)

        // Cancel the running coroutine if it exists
        runningTaskJobs[taskId]?.cancel()
        runningTaskJobs.remove(taskId)
    }

    private fun handlePause() {
        TaskQueueManager.pause()
    }

    private fun handleResume() {
        TaskQueueManager.resume()
        if (!isQueueProcessing) {
            if (!serviceScope.isActive) {
                serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            }
            serviceScope.launch { processQueue() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        isRunning = false
    }
}
