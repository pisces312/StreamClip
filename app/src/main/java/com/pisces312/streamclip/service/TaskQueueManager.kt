package com.pisces312.streamclip.service

import com.pisces312.streamclip.model.BatchSummary
import com.pisces312.streamclip.model.BatchTaskItem
import com.pisces312.streamclip.model.TaskStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object TaskQueueManager {
    private val queue = ArrayDeque<BatchTaskItem>()
    private val allTasks = mutableMapOf<String, BatchTaskItem>()
    private val _taskFlow = MutableStateFlow<List<BatchTaskItem>>(emptyList())
    val taskFlow: StateFlow<List<BatchTaskItem>> = _taskFlow.asStateFlow()

    @Volatile
    private var isPaused = false

    val totalCount: Int get() { synchronized(this) { return allTasks.size } }
    val completedCount: Int get() { synchronized(this) { return allTasks.count { it.value.status == TaskStatus.COMPLETED } } }
    val pendingCount: Int get() { synchronized(this) { return queue.size } }

    @Synchronized
    fun enqueueAll(tasks: List<BatchTaskItem>) {
        tasks.forEach { task ->
            allTasks[task.id] = task
            queue.addLast(task)
        }
        emitUpdate()
    }

    @Synchronized
    fun next(): BatchTaskItem? {
        if (isPaused) return null
        return queue.removeFirstOrNull()?.also { task ->
            allTasks[task.id] = task.copy(
                status = TaskStatus.RUNNING,
                startedAt = System.currentTimeMillis()
            )
            emitUpdate()
        }
    }

    @Synchronized
    fun hasPending(): Boolean = queue.isNotEmpty()

    @Synchronized
    fun updateProgress(taskId: String, percent: Int) {
        allTasks[taskId]?.let { task ->
            allTasks[taskId] = task.copy(progress = percent.coerceIn(0, 100))
            emitUpdate()
        }
    }

    fun markCompleted(taskId: String) {
        // Snapshot the output path under the lock, then do the file I/O outside it:
        // holding the object monitor during File.length() would stall every other
        // queue operation (batch progress updates come in hot from the statistics callback).
        val outputPath = synchronized(this) { allTasks[taskId]?.outputPath } ?: return
        val outputSize = try {
            java.io.File(outputPath).length()
        } catch (e: Exception) {
            0L
        }
        synchronized(this) {
            val task = allTasks[taskId] ?: return
            // Terminal-state guard: a cancelled task must never be flipped back to COMPLETED
            // by a late FFmpeg success callback (race: cancel clicked as encode finished).
            if (task.status == TaskStatus.CANCELLED) return
            // Defensive: a task reaching a terminal state must not linger in the queue.
            queue.removeAll { it.id == taskId }
            allTasks[taskId] = task.copy(
                status = TaskStatus.COMPLETED,
                completedAt = System.currentTimeMillis(),
                progress = 100,
                outputSizeBytes = outputSize
            )
            emitUpdate()
        }
    }

    @Synchronized
    fun markFailed(taskId: String, error: String?) {
        allTasks[taskId]?.let { task ->
            if (task.status == TaskStatus.CANCELLED) return
            // Defensive: a task reaching a terminal state must not linger in the queue.
            queue.removeAll { it.id == taskId }
            allTasks[taskId] = task.copy(
                status = TaskStatus.FAILED,
                completedAt = System.currentTimeMillis(),
                errorMessage = error
            )
            emitUpdate()
        }
    }

    @Synchronized
    fun markCancelled(taskId: String) {
        allTasks[taskId]?.let { task ->
            // A completed task cannot be un-completed by a late cancel.
            if (task.status == TaskStatus.COMPLETED) return
            // Also drop it from the pending queue, otherwise processQueue()
            // would still pick it up and overwrite the status back to RUNNING.
            queue.removeAll { it.id == taskId }
            allTasks[taskId] = task.copy(status = TaskStatus.CANCELLED)
            emitUpdate()
        }
    }

    fun pause() {
        synchronized(this) { isPaused = true }
    }
    fun resume() {
        synchronized(this) { isPaused = false }
    }

    @Synchronized
    fun getSummary(): BatchSummary {
        val tasks = allTasks.values.toList()
        return BatchSummary(
            total = tasks.size,
            completed = tasks.count { it.status == TaskStatus.COMPLETED },
            failed = tasks.count { it.status == TaskStatus.FAILED },
            cancelled = tasks.count { it.status == TaskStatus.CANCELLED }
        )
    }

    @Synchronized
    fun getTask(taskId: String): BatchTaskItem? = allTasks[taskId]

    @Synchronized
    fun getAllTasks(): List<BatchTaskItem> = allTasks.values.toList().sortedBy { it.createdAt }

    @Synchronized
    fun clearCompleted() {
        val toRemove = allTasks.values.filter {
            it.status == TaskStatus.COMPLETED || it.status == TaskStatus.CANCELLED
        }.map { it.id }
        toRemove.forEach { allTasks.remove(it) }
        emitUpdate()
    }

    /**
     * Re-enqueue a FAILED / CANCELLED task as a fresh PENDING copy.
     * Returns the new task so the caller can (re)start BatchTaskService —
     * enqueueing alone is not enough, the service may have already stopped itself.
     */
    @Synchronized
    fun retryTask(taskId: String): BatchTaskItem? {
        val task = allTasks[taskId] ?: return null
        if (task.status != TaskStatus.FAILED && task.status != TaskStatus.CANCELLED) return null
        val newTask = task.copy(
            id = java.util.UUID.randomUUID().toString(),
            status = TaskStatus.PENDING,
            progress = 0,
            errorMessage = null,
            createdAt = System.currentTimeMillis(),
            startedAt = null,
            completedAt = null
        )
        allTasks[newTask.id] = newTask
        queue.addLast(newTask)
        emitUpdate()
        return newTask
    }

    @Synchronized
    private fun emitUpdate() {
        _taskFlow.value = allTasks.values.toList().sortedBy { it.createdAt }
    }

    /** Test-only: reset the singleton state between unit tests. */
    @Synchronized
    fun clearForTesting() {
        queue.clear()
        allTasks.clear()
        isPaused = false
        emitUpdate()
    }
}
