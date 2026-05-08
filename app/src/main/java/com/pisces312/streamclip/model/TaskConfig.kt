package com.pisces312.streamclip.model

import java.io.Serializable

data class TaskConfig(
    val compressConfig: CompressConfig = CompressConfig(),
    val taskType: TaskType = TaskType.COMPRESS,
    val customCommand: String? = null
) : Serializable

fun CompressConfig.toTaskConfig(): TaskConfig = TaskConfig(
    compressConfig = this,
    taskType = TaskType.COMPRESS
)
