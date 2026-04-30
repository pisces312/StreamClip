package com.pisces312.streamclip.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 双轨日志收集器：内存缓冲区 + 外部文件
 * 应用崩溃时自动保存日志到文件，下次启动可查看
 */
object LogCollector {

    private const val TAG = "StreamClip"
    private const val MAX_MEMORY_LOGS = 500
    private const val LOG_FILE_NAME = "app_logs.txt"
    private const val CRASH_LOG_FILE_NAME = "crash_logs.txt"

    private val memoryLogs = ConcurrentLinkedQueue<LogEntry>()
    private var fileLogger: File? = null
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())

    data class LogEntry(
        val timestamp: Long,
        val level: String,
        val tag: String,
        val message: String
    ) {
        fun format(): String {
            return "${dateFormat.format(Date(timestamp))} [$level/$tag] $message"
        }
    }

    /**
     * 初始化日志收集器
     */
    fun init(context: Context) {
        val logDir = File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }
        fileLogger = File(logDir, LOG_FILE_NAME)

        // 检查是否有崩溃日志
        val crashLog = File(logDir, CRASH_LOG_FILE_NAME)
        if (crashLog.exists()) {
            log("INFO", TAG, "检测到上次崩溃日志: ${crashLog.absolutePath}")
        }

        log("INFO", TAG, "LogCollector initialized")
    }

    /**
     * 记录日志（同时写入内存和文件）
     */
    fun log(level: String, tag: String, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, tag, message)

        // 写入内存
        memoryLogs.offer(entry)
        if (memoryLogs.size > MAX_MEMORY_LOGS) {
            memoryLogs.poll()
        }

        // 写入文件
        fileLogger?.let { file ->
            try {
                file.appendText(entry.format() + "\n")
            } catch (e: Exception) {
                // 文件写入失败，仅保留内存日志
            }
        }

        // 同时输出到系统日志
        when (level) {
            "ERROR" -> Log.e(tag, message)
            "WARN" -> Log.w(tag, message)
            "INFO" -> Log.i(tag, message)
            "DEBUG" -> Log.d(tag, message)
            else -> Log.v(tag, message)
        }
    }

    fun d(tag: String, message: String) = log("DEBUG", tag, message)
    fun i(tag: String, message: String) = log("INFO", tag, message)
    fun w(tag: String, message: String) = log("WARN", tag, message)
    fun e(tag: String, message: String) = log("ERROR", tag, message)
    fun e(tag: String, message: String, throwable: Throwable) {
        log("ERROR", tag, "$message\n${throwable.stackTraceToString()}")
    }

    /**
     * 获取内存中的所有日志
     */
    fun getMemoryLogs(): List<LogEntry> = memoryLogs.toList()

    /**
     * 获取日志文件内容
     */
    fun getFileLogs(context: Context): String {
        return try {
            fileLogger?.readText() ?: ""
        } catch (e: Exception) {
            "读取日志文件失败: ${e.message}"
        }
    }

    /**
     * 获取崩溃日志内容
     */
    fun getCrashLogs(context: Context): String {
        val crashFile = File(context.getExternalFilesDir(null), "logs/$CRASH_LOG_FILE_NAME")
        return if (crashFile.exists()) {
            try {
                crashFile.readText()
            } catch (e: Exception) {
                "读取崩溃日志失败: ${e.message}"
            }
        } else {
            ""
        }
    }

    /**
     * 保存崩溃日志
     */
    fun saveCrashLog(context: Context, throwable: Throwable) {
        val crashFile = File(context.getExternalFilesDir(null), "logs/$CRASH_LOG_FILE_NAME")
        val timestamp = dateFormat.format(Date())
        val crashInfo = buildString {
            appendLine("=== 崩溃日志 $timestamp ===")
            appendLine("异常: ${throwable.javaClass.name}")
            appendLine("消息: ${throwable.message}")
            appendLine("堆栈:")
            appendLine(throwable.stackTraceToString())
            appendLine("=== 最近日志 ===")
            memoryLogs.forEach { appendLine(it.format()) }
            appendLine("=== END ===")
        }
        try {
            crashFile.writeText(crashInfo)
        } catch (e: Exception) {
            Log.e(TAG, "保存崩溃日志失败", e)
        }
    }

    /**
     * 清除所有日志
     */
    fun clearLogs(context: Context) {
        memoryLogs.clear()
        fileLogger?.let {
            try {
                it.writeText("")
            } catch (e: Exception) {
                Log.e(TAG, "清除日志失败", e)
            }
        }
        val crashFile = File(context.getExternalFilesDir(null), "logs/$CRASH_LOG_FILE_NAME")
        if (crashFile.exists()) {
            crashFile.delete()
        }
    }

    /**
     * 是否有崩溃日志
     */
    fun hasCrashLog(context: Context): Boolean {
        return File(context.getExternalFilesDir(null), "logs/$CRASH_LOG_FILE_NAME").exists()
    }

    /**
     * 删除崩溃日志标记
     */
    fun clearCrashLog(context: Context) {
        File(context.getExternalFilesDir(null), "logs/$CRASH_LOG_FILE_NAME").delete()
    }
}
