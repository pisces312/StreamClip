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

    @Synchronized
    fun markCompleted(taskId: String) {
        allTasks[taskId]?.let { task ->
            allTasks[taskId] = task.copy(
                status = TaskStatus.COMPLETED,
                completedAt = System.currentTimeMillis(),
                progress = 100,
                outputSizeBytes = java.io.File(task.outputPath).length()
            )
            emitUpdate()
        }
    }

    @Synchronized
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

    @Synchronized
    fun markCancelled(taskId: String) {
        allTasks[taskId]?.let { task ->
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

    @Synchronized
    fun retryTask(taskId: String) {
        allTasks[taskId]?.let { task ->
            if (task.status == TaskStatus.FAILED || task.status == TaskStatus.CANCELLED) {
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
            }
        }
    }

    @Synchronized
    private fun emitUpdate() {
        _taskFlow.value = allTasks.values.toList().sortedBy { it.createdAt }
    }
}
