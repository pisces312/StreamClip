package com.pisces312.streamclip.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import com.pisces312.streamclip.util.LogCollector

object FileUtils {

    /**
     * 文件路径解析结果，包含读取方式信息
     */
    data class PathResult(
        val path: String,
        val isDirectRead: Boolean  // true=原地直读, false=缓存复制
    )

    /**
     * Get real file path from URI with read mode info
     */
    fun getPathResultFromUri(context: Context, uri: Uri): PathResult? {
        // 1. file:// scheme - 直接读取
        if (ContentResolver.SCHEME_FILE == uri.scheme) {
            uri.path?.let { return PathResult(it, true) }
        }

        // 2. content:// scheme
        if (ContentResolver.SCHEME_CONTENT == uri.scheme) {
            // 2a. 解析 ExternalStorage Document URI
            if (isExternalStorageDocument(uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":")
                if (split.size >= 2) {
                    val type = split[0]
                    val relativePath = split[1]
                    if ("primary".equals(type, ignoreCase = true)) {
                        val fullPath = Environment.getExternalStorageDirectory().toString() + "/" + relativePath
                        val file = File(fullPath)
                        if (file.exists()) {
                            return PathResult(fullPath, true)
                        }
                    }
                    // 可能是非主存储（SD卡等），尝试拼接
                    // /storage/type/relativePath
                    val altPath = "/storage/$type/$relativePath"
                    val altFile = File(altPath)
                    if (altFile.exists()) {
                        return PathResult(altPath, true)
                    }
                }
            }

            // 2b. 解析 Downloads Document URI
            if (isDownloadsDocument(uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                // format: msf:xxx or raw:xxx
                if (docId.startsWith("raw:")) {
                    val rawPath = docId.substring(4)
                    val file = File(rawPath)
                    if (file.exists()) {
                        return PathResult(rawPath, true)
                    }
                }
                // 尝试通过 ContentResolver 查询
                try {
                    val contentUri = Uri.parse("content://downloads/public_downloads").buildUpon()
                        .appendPath(docId).build()
                    val path = getDataColumn(context, contentUri, null, null)
                    if (path != null && File(path).exists()) {
                        return PathResult(path, true)
                    }
                } catch (_: Exception) {}
            }

            // 2c. 解析 Media Document URI (media document)
            if (isMediaDocument(uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":")
                if (split.size >= 2) {
                    val type = split[0]
                    val id = split[1]
                    val contentUri = when (type) {
                        "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                        "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        else -> MediaStore.Files.getContentUri("external")
                    }
                    val path = getDataColumn(context, contentUri, "_id=?", arrayOf(id))
                    if (path != null && File(path).exists()) {
                        return PathResult(path, true)
                    }
                }
            }

            // 2d. 直接通过 MediaStore 查询（非 document URI）
            val mediaPath = getDataColumn(context, uri, null, null)
            if (mediaPath != null && File(mediaPath).exists()) {
                return PathResult(mediaPath, true)
            }

            // 2e. 所有直读方式失败，复制到缓存
            val cachePath = copyUriToCache(context, uri)
            if (cachePath != null) {
                return PathResult(cachePath, false)
            }
        }

        return null
    }

    /**
     * Get real file path from URI (backward compatible)
     */
    fun getPathFromUri(context: Context, uri: Uri): String? {
        return getPathResultFromUri(context, uri)?.path
    }

    /**
     * Query the DATA column from a content URI
     */
    private fun getDataColumn(
        context: Context, uri: Uri, selection: String?, selectionArgs: Array<String>?
    ): String? {
        try {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                    val path = cursor.getString(index)
                    if (!path.isNullOrEmpty()) return path
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * Check if URI is from ExternalStorageDocumentsProvider
     * e.g. content://com.android.externalstorage.documents/document/primary%3ADCIM%2Fvideo.mp4
     */
    private fun isExternalStorageDocument(uri: Uri): Boolean {
        return "com.android.externalstorage.documents" == uri.authority
    }

    /**
     * Check if URI is from DownloadsDocumentsProvider
     */
    private fun isDownloadsDocument(uri: Uri): Boolean {
        return "com.android.providers.downloads.documents" == uri.authority
    }

    /**
     * Check if URI is from MediaDocumentsProvider
     */
    private fun isMediaDocument(uri: Uri): Boolean {
        return "com.android.providers.media.documents" == uri.authority
    }

    /**
     * Copy URI content to cache directory and return path
     */
    private fun copyUriToCache(context: Context, uri: Uri): String? {
        return try {
            val fileName = getFileNameFromUri(context, uri) ?: "temp_video"
            val cacheDir = File(context.cacheDir, "videos")
            cacheDir.mkdirs()
            val cacheFile = File(cacheDir, fileName)

            context.contentResolver.openInputStream(uri)?.use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            cacheFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Get file name from URI
     */
    fun getFileNameFromUri(context: Context, uri: Uri): String? {
        // Try ContentResolver query first
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (displayNameIndex >= 0) {
                    return cursor.getString(displayNameIndex)
                }
            }
        }
        return uri.lastPathSegment
    }

    /**
     * Get output directory for processed files
     */
    fun getOutputDir(context: Context): File {
        val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "StreamClip")
        } else {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "StreamClip")
        }
        dir.mkdirs()
        return dir
    }

    /**
     * Get output directory for audio files
     */
    fun getAudioOutputDir(context: Context): File {
        val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "StreamClip")
        } else {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "StreamClip")
        }
        dir.mkdirs()
        return dir
    }

    /**
     * Format file size to human readable string
     */
    fun formatFileSize(size: Long): String {
        val kb = 1024L
        val mb = kb * 1024
        val gb = mb * 1024

        return when {
            size >= gb -> String.format("%.2f GB", size.toDouble() / gb)
            size >= mb -> String.format("%.2f MB", size.toDouble() / mb)
            size >= kb -> String.format("%.2f KB", size.toDouble() / kb)
            else -> "$size B"
        }
    }

    /**
     * Format duration in milliseconds to MM:SS
     */
    fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    /**
     * Clean up cache files
     */
    fun cleanCache(context: Context) {
        val cacheDir = File(context.cacheDir, "videos")
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * Notify MediaScanner about a new file so it appears in gallery/file manager
     */
    fun scanFile(context: Context, file: File) {
        android.media.MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            null,
            null
        )
    }

    /**
     * Read file creation and modification times
     * Returns Pair<creationTime, modifiedTime> or null if failed
     */
    fun readFileTimes(path: String): Pair<FileTime?, FileTime?>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val p = Paths.get(path)
                val creationTime = Files.getAttribute(p, "creationTime") as? FileTime
                val modifiedTime = Files.getLastModifiedTime(p)
                LogCollector.d("FileUtils", "readFileTimes: creation=$creationTime, modified=$modifiedTime, path=$path")
                Pair(creationTime, modifiedTime)
            } catch (e: Exception) {
                LogCollector.w("FileUtils", "readFileTimes failed: ${e.message}, path=$path")
                null
            }
        } else {
            null
        }
    }

    /**
     * Apply creation and modification times to output file
     */
    fun applyFileTimes(outputPath: String, creationTime: FileTime?, modifiedTime: FileTime?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val p = Paths.get(outputPath)
                modifiedTime?.let { Files.setLastModifiedTime(p, it) }
                creationTime?.let { Files.setAttribute(p, "creationTime", it) }
                LogCollector.d("FileUtils", "applyFileTimes: success, creation=$creationTime, modified=$modifiedTime, path=$outputPath")
            } catch (e: Exception) {
                LogCollector.w("FileUtils", "applyFileTimes failed: ${e.message}, path=$outputPath")
            }
        }
    }

    /**
     * Apply shooting date (from metadata) as both creation and modification time.
     * Handles ISO 8601 and common date formats.
     */
    fun applyShootingDate(outputPath: String, shootingDate: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val instant = parseDateToInstant(shootingDate) ?: return
                val fileTime = FileTime.fromMillis(instant.toEpochMilli())
                val p = Paths.get(outputPath)
                Files.setAttribute(p, "creationTime", fileTime)
                Files.setLastModifiedTime(p, fileTime)
                LogCollector.d("FileUtils", "applyShootingDate: $shootingDate -> $fileTime, path=$outputPath")
            } catch (e: Exception) {
                LogCollector.w("FileUtils", "applyShootingDate failed: ${e.message}, path=$outputPath")
            }
        }
    }

    private fun parseDateToInstant(dateStr: String): java.time.Instant? {
        // Try ISO 8601 formats first
        val isoFormats = listOf(
            java.time.format.DateTimeFormatter.ISO_INSTANT,
            java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME
        )
        for (fmt in isoFormats) {
            try {
                return java.time.Instant.from(java.time.ZonedDateTime.parse(dateStr, fmt))
            } catch (_: Exception) { }
        }
        // Try common patterns: "yyyy-MM-dd HH:mm:ss" and "yyyy-MM-dd HH:mm:ss.SSSSSS"
        val patterns = listOf(
            "yyyy-MM-dd HH:mm:ss.SSSSSS",
            "yyyy-MM-dd HH:mm:ss.SSS",
            "yyyy-MM-dd HH:mm:ss"
        )
        for (pattern in patterns) {
            try {
                val formatter = java.time.format.DateTimeFormatter.ofPattern(pattern)
                val ldt = java.time.LocalDateTime.parse(dateStr, formatter)
                return ldt.atZone(java.time.ZoneId.systemDefault()).toInstant()
            } catch (_: Exception) { }
        }
        LogCollector.w("FileUtils", "parseDateToInstant: cannot parse '$dateStr'")
        return null
    }
}
