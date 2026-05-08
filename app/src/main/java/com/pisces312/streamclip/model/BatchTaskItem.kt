package com.pisces312.streamclip.model

import java.util.UUID

data class BatchTaskItem(
    val id: String = UUID.randomUUID().toString(),
    val type: TaskType,
    val inputPath: String,
    val outputPath: String,
    val config: TaskConfig,
    val status: TaskStatus = TaskStatus.PENDING,
    val progress: Int = 0,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val outputSizeBytes: Long = 0
) : java.io.Serializable

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
