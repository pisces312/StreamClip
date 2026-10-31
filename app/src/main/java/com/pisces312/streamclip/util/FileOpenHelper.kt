package com.pisces312.streamclip.util

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.core.content.FileProvider
import com.pisces312.streamclip.R
import java.io.File

/**
 * 打开 / 定位 / 分享本地文件。
 *
 * 三条硬约束（改动前必读）：
 * 1. `file://` 直传在 Android 7+ 会触发 FileUriExposedException，一律走 FileProvider。
 * 2. 不存在"在文件管理器中选中某个文件"的公开 API。一级方案（ACTION_VIEW + 目录
 *    document Uri）对普通应用会抛 SecurityException（ExternalStorageProvider 不放行，
 *    用 `adb shell am start` 测试成功是假阳性），实际生效的是二级降级：
 *    ACTION_OPEN_DOCUMENT + EXTRA_INITIAL_URI，即打开文件选择器并定位到目标目录。
 *    Android 11+ 的 DocumentsUI 还禁止进入 Android/data、Android/obb。
 * 3. 不用 resolveActivity() 预判：Android 11 包可见性会对隐式 Intent 返回假阴性，
 *    直接 startActivity + catch ActivityNotFoundException。
 */
object FileOpenHelper {

    private const val TAG = "FileOpenHelper"
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val MIME_VIDEO = "video/*"

    enum class RevealResult {
        OPENED,
        /** 目录在 Android/data、Android/obb 或应用私有路径下，系统文件管理器无法访问 */
        PRIVATE_DIR,
        /** 目录不存在或已被移动 */
        MISSING,
        /** 没有可用的文件管理器 */
        NO_HANDLER
    }

    /**
     * 用系统默认播放器打开视频。多个播放器且无默认时会自动弹系统选择器。
     */
    fun openVideo(context: Context, file: File): Boolean {
        if (!file.exists()) {
            toast(context, R.string.file_not_found)
            return false
        }
        val uri = providerUri(context, file) ?: return false
        return launch(context, viewIntent(uri, MIME_VIDEO), R.string.cannot_open_file)
    }

    /**
     * 打开用户选中的原始 content:// Uri（比缓存副本更贴近用户认知）。
     * 无可用处理器时回退到 [fallback] 指向的本地文件。
     */
    fun openVideoUri(context: Context, uri: Uri, fallback: File? = null): Boolean {
        // 外部 Intent 传进来的 file:// 若原样再次跨进程抛出，会触发 StrictMode 的
        // FileUriExposedException，必须换成 FileProvider Uri 或直接按路径打开。
        if (ContentResolver.SCHEME_FILE == uri.scheme) {
            val path = uri.path
            if (path != null && openVideo(context, File(path))) return true
            return fallback?.let { openVideo(context, it) } ?: false
        }
        if (launch(context, viewIntent(uri, MIME_VIDEO), null)) return true
        if (fallback != null) return openVideo(context, fallback)
        toast(context, R.string.cannot_open_file)
        return false
    }

    /**
     * 在系统文件管理器中打开文件所在目录。
     */
    fun revealInFileManager(context: Context, file: File): RevealResult {
        val dir = file.parentFile ?: return RevealResult.MISSING
        if (!dir.exists()) return RevealResult.MISSING

        val documentId = externalStorageDocumentId(dir) ?: return RevealResult.PRIVATE_DIR
        val dirUri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, documentId)

        // 一级：直接把目录 Uri 交给 DocumentsUI
        val direct = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(dirUri, DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (launch(context, direct, null)) return RevealResult.OPENED

        // 二级降级：部分定制 ROM 移除了对目录 Uri 的 ACTION_VIEW 支持，
        // 改用文件选择器定位到该目录（用户可直接返回）。
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = MIME_VIDEO
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, dirUri)
        }
        if (launch(context, picker, null)) return RevealResult.OPENED

        return RevealResult.NO_HANDLER
    }

    /**
     * 定位目录并给出用户可理解的反馈。失败时把路径写入剪贴板，避免用户拿不到信息。
     */
    fun revealWithFeedback(context: Context, file: File) {
        when (revealInFileManager(context, file)) {
            RevealResult.OPENED -> Unit
            RevealResult.MISSING -> toast(context, R.string.location_missing)
            RevealResult.PRIVATE_DIR -> {
                copyPathSilently(context, file.absolutePath)
                toast(context, R.string.location_private_dir)
            }
            RevealResult.NO_HANDLER -> {
                copyPathSilently(context, file.absolutePath)
                toast(context, R.string.location_failed)
            }
        }
    }

    fun copyPath(context: Context, path: String) {
        copyPathSilently(context, path)
        toast(context, R.string.path_copied)
    }

    fun shareVideo(context: Context, file: File): Boolean {
        if (!file.exists()) {
            toast(context, R.string.file_not_found)
            return false
        }
        val uri = providerUri(context, file) ?: return false
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_VIDEO
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, context.getString(R.string.share_video))
        return launch(context, chooser, R.string.cannot_open_file)
    }

    // ---------------------------------------------------------------- internals

    private fun providerUri(context: Context, file: File): Uri? = try {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (e: Exception) {
        LogCollector.w(TAG, "FileProvider mapping failed: ${e.message}, path=${file.absolutePath}")
        toast(context, R.string.cannot_open_file)
        null
    }

    private fun viewIntent(uri: Uri, mime: String) = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun launch(context: Context, intent: Intent, errorRes: Int?): Boolean {
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            LogCollector.w(TAG, "no activity for type=${intent.type}")
            errorRes?.let { toast(context, it) }
            false
        } catch (e: Exception) {
            LogCollector.w(TAG, "startActivity failed: ${e.message}")
            errorRes?.let { toast(context, it) }
            false
        }
    }

    /**
     * 把目录绝对路径转换成 ExternalStorageProvider 的 documentId。
     * 主存储：`primary:DCIM/Camera`；可移动存储：`1A2B-3C4D:Movies`。
     *
     * 关键：SAF 解析出来的路径常是 `/sdcard/...`（主存储的软链接），
     * 而 [Environment.getExternalStorageDirectory] 返回 `/storage/emulated/0`，
     * 直接字符串比较会误判成私有目录，因此同时尝试绝对路径、规范路径和别名映射。
     */
    @SuppressLint("SdCardPath") // /sdcard 是主存储的固定别名，无法用 API 获取
    private fun externalStorageDocumentId(dir: File): String? {
        val primaryRoot = Environment.getExternalStorageDirectory().absolutePath
        val absolute = dir.absolutePath
        val canonical = try {
            dir.canonicalPath
        } catch (e: Exception) {
            absolute
        }

        val candidates = linkedSetOf(absolute, canonical)
        for (p in listOf(absolute, canonical)) {
            if (p.startsWith("/sdcard/")) {
                candidates.add("$primaryRoot/" + p.removePrefix("/sdcard/"))
            }
        }

        for (path in candidates) {
            val relative = when {
                path == primaryRoot -> ""
                path.startsWith("$primaryRoot/") -> path.removePrefix("$primaryRoot/")
                else -> null
            }
            if (relative != null) {
                if (isRestricted(relative)) return null
                return if (relative.isEmpty()) "primary:" else "primary:$relative"
            }
        }

        // 可移动存储：/storage/XXXX-XXXX/...
        val pattern = Regex("^/storage/([^/]+)(?:/(.*))?$")
        val match = pattern.find(canonical) ?: pattern.find(absolute) ?: return null
        val volume = match.groupValues[1]
        if (volume == "emulated" || volume == "self") return null
        val rest = match.groupValues[2]
        if (isRestricted(rest)) return null
        return if (rest.isEmpty()) "$volume:" else "$volume:$rest"
    }

    private fun isRestricted(relativePath: String): Boolean {
        val p = relativePath.lowercase()
        return p == "android/data" || p.startsWith("android/data/") ||
            p == "android/obb" || p.startsWith("android/obb/")
    }

    private fun copyPathSilently(context: Context, path: String) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("path", path))
        } catch (e: Exception) {
            LogCollector.w(TAG, "clipboard failed: ${e.message}")
        }
    }

    private fun toast(context: Context, resId: Int) {
        Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
    }
}
