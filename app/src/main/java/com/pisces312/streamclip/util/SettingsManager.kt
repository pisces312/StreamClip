package com.pisces312.streamclip.util

import android.content.Context
import android.content.SharedPreferences

object SettingsManager {

    private const val PREFS_NAME = "streamclip_settings"
    private const val KEY_OUTPUT_PATH = "output_path"
    private const val KEY_USE_SOURCE_DIR = "use_source_dir"
    private const val KEY_ADD_TIMESTAMP = "add_timestamp"
    private const val KEY_LAST_VIDEO_DIR = "last_video_dir"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 是否使用原视频所在目录作为输出目录（默认true）
     */
    fun isUseSourceDir(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_USE_SOURCE_DIR, true)
    }

    fun setUseSourceDir(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_USE_SOURCE_DIR, value).apply()
    }

    /**
     * 是否在文件名中添加时间戳后缀（默认true）
     */
    fun isAddTimestamp(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ADD_TIMESTAMP, true)
    }

    fun setAddTimestamp(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ADD_TIMESTAMP, value).apply()
    }

    /**
     * 自定义输出目录（当useSourceDir为false时使用）
     */
    fun getCustomOutputPath(context: Context): String? {
        return getPrefs(context).getString(KEY_OUTPUT_PATH, null)
    }

    fun setCustomOutputPath(context: Context, path: String?) {
        getPrefs(context).edit().putString(KEY_OUTPUT_PATH, path).apply()
    }

    /**
     * 获取实际输出目录
     * 如果选择"使用源目录"但源文件在缓存/应用私有目录中，则回退到 Movies/StreamClip
     */
    fun getOutputDir(context: Context, sourceFile: java.io.File? = null): java.io.File {
        return when {
            isUseSourceDir(context) && sourceFile != null -> {
                val parentDir = sourceFile.parentFile
                // 检查源文件是否在缓存或应用私有目录中
                val isCacheOrPrivate = parentDir != null && (
                    parentDir.absolutePath.contains("/cache/") ||
                    parentDir.absolutePath.contains("/cache") ||
                    parentDir.absolutePath.contains(context.packageName)
                )
                if (isCacheOrPrivate) {
                    // 源文件在缓存/私有目录，回退到公共目录
                    FileUtils.getOutputDir(context)
                } else {
                    parentDir ?: FileUtils.getOutputDir(context)
                }
            }
            else -> {
                val customPath = getCustomOutputPath(context)
                if (!customPath.isNullOrEmpty()) {
                    java.io.File(customPath).apply { mkdirs() }
                } else {
                    FileUtils.getOutputDir(context)
                }
            }
        }
    }

    /**
     * 生成输出文件名
     */
    fun generateOutputFileName(baseName: String, extension: String, context: Context): String {
        val timestamp = if (isAddTimestamp(context)) {
            "_${System.currentTimeMillis()}"
        } else {
            ""
        }
        return "${baseName}${timestamp}.${extension}"
    }

    /**
     * 获取输出文件名（不含路径）
     */
    fun getOutputFileName(context: Context, sourceFileName: String?, operation: String, extension: String): String {
        val baseName = sourceFileName?.substringBeforeLast(".") ?: operation
        return generateOutputFileName(baseName, extension, context)
    }

    /**
     * 保存上次选择的视频目录URI
     */
    fun setLastVideoDir(context: Context, uri: android.net.Uri) {
        getPrefs(context).edit().putString(KEY_LAST_VIDEO_DIR, uri.toString()).apply()
    }

    /**
     * 获取上次选择的视频目录URI
     */
    fun getLastVideoDir(context: Context): android.net.Uri? {
        val uriString = getPrefs(context).getString(KEY_LAST_VIDEO_DIR, null)
        return uriString?.let { android.net.Uri.parse(it) }
    }

    /**
     * 清除上次选择的视频目录
     */
    fun clearLastVideoDir(context: Context) {
        getPrefs(context).edit().remove(KEY_LAST_VIDEO_DIR).apply()
    }

    /**
     * 获取应用缓存大小（字节）
     */
    fun getCacheSize(context: Context): Long {
        var size = 0L
        // 应用缓存目录
        size += getDirSize(context.cacheDir)
        // 外部缓存目录
        context.externalCacheDir?.let { size += getDirSize(it) }
        // 日志目录
        val logDir = java.io.File(context.getExternalFilesDir(null), "logs")
        if (logDir.exists()) {
            size += getDirSize(logDir)
        }
        return size
    }

    /**
     * 清除应用缓存
     */
    fun clearCache(context: Context) {
        // 清除缓存目录
        context.cacheDir?.let { clearDir(it) }
        context.externalCacheDir?.let { clearDir(it) }
        // 清除日志
        LogCollector.clearLogs(context)
    }

    /**
     * 计算目录大小
     */
    private fun getDirSize(dir: java.io.File): Long {
        var size = 0L
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { file ->
                size += if (file.isDirectory) getDirSize(file) else file.length()
            }
        } else if (dir.isFile) {
            size = dir.length()
        }
        return size
    }

    /**
     * 清空目录（保留目录本身）
     */
    private fun clearDir(dir: java.io.File) {
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    clearDir(file)
                    file.delete()
                } else {
                    file.delete()
                }
            }
        }
    }

    /**
     * 格式化文件大小
     */
    fun formatSize(size: Long): String {
        return when {
            size >= 1024 * 1024 * 1024 -> String.format("%.2f GB", size / (1024.0 * 1024.0 * 1024.0))
            size >= 1024 * 1024 -> String.format("%.2f MB", size / (1024.0 * 1024.0))
            size >= 1024 -> String.format("%.2f KB", size / 1024.0)
            else -> "$size B"
        }
    }
}