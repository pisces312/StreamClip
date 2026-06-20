package com.pisces312.streamclip

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.pisces312.streamclip.util.CrashHandler
import com.pisces312.streamclip.util.LogCollector
import com.google.android.material.tabs.TabLayoutMediator
import com.pisces312.streamclip.adapter.MainPagerAdapter
import com.pisces312.streamclip.databinding.ActivityMainBinding
import com.pisces312.streamclip.util.TabOrderManager
import androidx.viewpager2.widget.ViewPager2

class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentTabOrder: List<String> = emptyList()

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化日志收集器和崩溃处理器
        LogCollector.init(this)
        CrashHandler(this).install()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkPermissions()
        setupViewPager()
        setupTabLongPress()
        setupVersionDisplay()

        // 检查是否有崩溃日志
        checkCrashLog()
    }

    override fun onResume() {
        super.onResume()
        val newOrder = TabOrderManager.getOrder(this)
        if (newOrder != currentTabOrder) {
            setupViewPager()
        }
    }

    private fun setupViewPager() {
        val order = TabOrderManager.getOrder(this)
        currentTabOrder = order
        val adapter = MainPagerAdapter(this, order)
        binding.viewPager.adapter = adapter

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            val tabId = order[position]
            tab.text = getTabText(tabId)
            TabOrderManager.TAB_ICONS[tabId]?.let { tab.setIcon(it) }
        }.attach()

        val totalCount = order.size
        binding.tvTabIndicator.text = "1/$totalCount"
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.tvTabIndicator.text = "${position + 1}/$totalCount"
            }
        })
    }

    private fun setupTabLongPress() {
        binding.tabLayout.post {
            for (i in 0 until binding.tabLayout.tabCount) {
                binding.tabLayout.getTabAt(i)?.view?.setOnLongClickListener {
                    val intent = Intent(this, com.pisces312.streamclip.ui.TabOrderActivity::class.java)
                    val currentTabs = (0 until binding.tabLayout.tabCount).mapNotNull { idx ->
                        binding.tabLayout.getTabAt(idx)?.text?.toString()
                    }.toTypedArray()
                    intent.putExtra("current_tabs", currentTabs)
                    startActivity(intent)
                    true
                }
            }
        }
    }

    private fun setupVersionDisplay() {
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
        binding.tvVersion.text = "v$versionName"
    }

    private fun getTabText(tabId: String): String = when (tabId) {
        "settings" -> getString(R.string.title_menu)
        "trim" -> getString(R.string.title_trim)
        "trim2" -> getString(R.string.title_trim2)
        "merge" -> getString(R.string.title_merge)
        "extract" -> getString(R.string.title_extract)
        "compress" -> getString(R.string.title_compress)
        "native_compress" -> getString(R.string.title_native_compress)
        "audio_compress" -> getString(R.string.title_audio_compress)
        "audio_editor" -> getString(R.string.title_audio_editor)
        "custom" -> getString(R.string.title_custom)
        "metadata" -> getString(R.string.title_metadata)
        else -> ""
    }

    private fun checkCrashLog() {
        if (LogCollector.hasCrashLog(this)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.crash_detected)
                .setMessage(R.string.view_crash_log)
                .setPositiveButton(R.string.yes) { _, _ ->
                    startActivity(Intent(this, LogActivity::class.java))
                }
                .setNegativeButton(R.string.no) { _, _ ->
                    LogCollector.clearCrashLog(this)
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun handleMenuItem(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                val order = TabOrderManager.getOrder(this)
                val index = order.indexOf("settings")
                if (index >= 0) {
                    binding.viewPager.currentItem = index
                }
                true
            }
            R.id.action_logs -> {
                startActivity(Intent(this, LogActivity::class.java))
                true
            }
            R.id.action_help -> {
                showGuideDialog()
                true
            }
            R.id.action_about -> {
                showAboutDialog()
                true
            }
            R.id.action_tab_order -> {
                val intent = Intent(this, com.pisces312.streamclip.ui.TabOrderActivity::class.java)
                val currentTabs = (0 until binding.tabLayout.tabCount).mapNotNull { idx ->
                    binding.tabLayout.getTabAt(idx)?.text?.toString()
                }.toTypedArray()
                intent.putExtra("current_tabs", currentTabs)
                startActivity(intent)
                true
            }
            else -> false
        }
    }


    internal fun showGuideDialog() {
        val message = buildString {
            appendLine(getString(R.string.guide_h264_recommend))
            appendLine(getString(R.string.guide_h264_archive))
            appendLine(getString(R.string.guide_h264_balanced))
            appendLine(getString(R.string.guide_h264_quick))
            appendLine()
            appendLine(getString(R.string.guide_hevc_recommend))
            appendLine(getString(R.string.guide_hevc_high))
            appendLine(getString(R.string.guide_hevc_size))
            appendLine(getString(R.string.guide_hevc_space))
            appendLine()
            appendLine(getString(R.string.guide_hw_recommend))
            appendLine(getString(R.string.guide_hw_1080p))
            appendLine(getString(R.string.guide_hw_720p))
            appendLine(getString(R.string.guide_hw_speed))
            appendLine()
            appendLine(getString(R.string.guide_general_title))
            appendLine(getString(R.string.guide_general_compat))
            appendLine(getString(R.string.guide_general_size))
            appendLine(getString(R.string.guide_general_speed))
            appendLine()
            appendLine(getString(R.string.guide_merge_tip_title))
            appendLine(getString(R.string.guide_merge_tip))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.guide_title)
            .setMessage(message)
            .setPositiveButton(R.string.guide_ok, null)
            .show()
    }

    internal fun showDonateDialog() {
        if (BuildConfig.DISTRIBUTION == "store") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/pisces312/StreamClip")))
            return
        }

        val scrollView = android.widget.ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }

        val donateTitle = androidx.appcompat.widget.AppCompatTextView(this).apply {
            text = "如果这个软件对你有帮助，欢迎支持维护："
            textSize = 16f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(donateTitle)

        // QR Code images from assets
        val qrContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 16)
        }

        fun loadQrImage(assetName: String): android.widget.ImageView {
            return android.widget.ImageView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(400, 400).apply {
                    marginEnd = 16
                }
                try {
                    assets.open(assetName).use { stream ->
                        setImageBitmap(android.graphics.BitmapFactory.decodeStream(stream))
                    }
                } catch (_: Exception) {
                    // ignore
                }
                setOnClickListener {
                    AlertDialog.Builder(context)
                        .setView(android.widget.ImageView(context).apply {
                            try {
                                assets.open(assetName).use { stream ->
                                    setImageBitmap(android.graphics.BitmapFactory.decodeStream(stream))
                                }
                            } catch (_: Exception) {}
                        })
                        .setPositiveButton(R.string.about_close, null)
                        .show()
                }
            }
        }

        qrContainer.addView(loadQrImage("donate-alipay.png"))
        qrContainer.addView(loadQrImage("donate-wechat.png"))
        layout.addView(qrContainer)

        val qrHint = androidx.appcompat.widget.AppCompatTextView(this).apply {
            text = "支付宝/微信扫码"
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(0, 16, 0, 0)
        }
        layout.addView(qrHint)

        scrollView.addView(layout)

        AlertDialog.Builder(this)
            .setView(scrollView)
            .setPositiveButton(R.string.about_close, null)
            .show()
    }

    internal fun showAboutDialog() {
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
        val changelog = try {
            assets.open("CHANGELOG.md").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        }
        val historyText = if (changelog != null) {
            parseChangelogHistory(changelog)
        } else {
            "v1.3.0 - 初始版本\nv1.3.1 - 修复GPS元数据，添加自定义命令\nv1.3.2 - 添加音频压缩，标签排序\nv1.4.0 - 添加二进制FFmpeg支持"
        }

        val scrollView = android.widget.ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }

        // === 1. GitHub 链接 ===
        val githubLink = androidx.appcompat.widget.AppCompatTextView(this).apply {
            text = "GitHub: https://github.com/pisces312/StreamClip"
            setTextIsSelectable(true)
            setTextColor(androidx.core.content.ContextCompat.getColor(context, com.google.android.material.R.color.design_default_color_primary))
            paint.isUnderlineText = true
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/pisces312/StreamClip")))
            }
        }
        layout.addView(githubLink)

        // === 2. 版本信息 ===
        val versionView = androidx.appcompat.widget.AppCompatTextView(this).apply {
            text = buildString {
                appendLine()
                appendLine(getString(R.string.app_name_with_version, versionName))
            }
            setTextIsSelectable(true)
        }
        layout.addView(versionView)

        // === 3. 更新日志 ===
        val historyView = androidx.appcompat.widget.AppCompatTextView(this).apply {
            text = buildString {
                appendLine()
                appendLine(historyText)
            }
            setTextIsSelectable(true)
        }
        layout.addView(historyView)

        scrollView.addView(layout)

        AlertDialog.Builder(this)
            .setTitle(R.string.title_about)
            .setView(scrollView)
            .setPositiveButton(R.string.about_close, null)
            .setNeutralButton(R.string.about_licenses) { _, _ ->
                showLicensesDialog()
            }
            .show()
    }

    private fun parseChangelogHistory(changelog: String): String {
        val lines = changelog.lines()
        val result = mutableListOf<String>()
        var inVersionSection = false
        var currentVersion = ""
        var currentSummary = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trim()
            when {
                // Match version header like "## [2.1.0] - 2026-05-13" or "## [Unreleased]"
                trimmed.startsWith("## [") -> {
                    // Save previous version if exists
                    if (currentVersion.isNotEmpty() && currentSummary.isNotEmpty()) {
                        result.add("$currentVersion - ${currentSummary.joinToString(", ")}")
                    }
                    // Parse new version
                    val versionMatch = Regex("""## \[(.+?)\]""").find(trimmed)
                    currentVersion = versionMatch?.groupValues?.get(1) ?: ""
                    currentSummary = mutableListOf()
                    inVersionSection = currentVersion != "Unreleased"
                }
                // Match category headers like "### 新增", "### 修复", etc.
                trimmed.startsWith("### ") && inVersionSection -> {
                    // Category header, skip
                }
                // Match list items under a version
                trimmed.startsWith("- ") && inVersionSection && currentVersion.isNotEmpty() -> {
                    val item = trimmed.removePrefix("- ").trim()
                    // Extract bold text as summary, or first sentence
                    val boldMatch = Regex("""\*\*(.+?)\*\*""").find(item)
                    val summary = if (boldMatch != null) {
                        boldMatch.groupValues[1]
                    } else {
                        item.take(40) + if (item.length > 40) "…" else ""
                    }
                    if (summary.isNotEmpty() && !currentSummary.contains(summary)) {
                        currentSummary.add(summary)
                    }
                }
                trimmed.isEmpty() -> {
                    // Empty line, continue
                }
                else -> {
                    // Other content, ignore
                }
            }
        }
        // Save last version
        if (currentVersion.isNotEmpty() && currentSummary.isNotEmpty()) {
            result.add("$currentVersion - ${currentSummary.joinToString(", ")}")
        }
        return result.joinToString("\n")
    }

    private fun showLicensesDialog() {
        val licenses = arrayOf(
            Triple("FFmpeg (GPL v3.0)", R.raw.license_ffmpeg, "https://github.com/FFmpeg/FFmpeg"),
            Triple("FFmpegKit (LGPL-3.0)", R.raw.license_ffmpegkit, "https://github.com/pisces312/ffmpeg-kit"),
            Triple("x264 (GPL v2.0+)", R.raw.license_x264, "https://github.com/mirror/x264"),
            Triple("x265 (GPL v2.0+)", R.raw.license_x265, "https://github.com/videolan/x265"),
            Triple("cpu_features (Apache 2.0)", R.raw.license_cpu_features, "https://github.com/google/cpu_features"),
            Triple("GPL 合规声明", 0, "https://github.com/pisces312/StreamClip/blob/main/GPL_COMPLIANCE.md")
        )

        val items = licenses.map { it.first }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.about_licenses)
            .setItems(items) { _, which ->
                val (_, resId, url) = licenses[which]
                showLicenseDetailDialog(licenses[which].first, resId, url)
            }
            .setPositiveButton(R.string.about_close, null)
            .show()
    }

    private fun showLicenseDetailDialog(name: String, resId: Int, url: String) {
        val text = if (resId != 0) {
            try {
                resources.openRawResource(resId).bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                getString(R.string.about_license_not_found)
            }
        } else {
            ""  // 外部链接，无本地文本
        }

        val scrollView = android.widget.ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }

        // 源码地址链接
        val urlView = androidx.appcompat.widget.AppCompatTextView(this).apply {
            this.text = url
            setTextIsSelectable(true)
            setTextColor(androidx.core.content.ContextCompat.getColor(context, com.google.android.material.R.color.design_default_color_primary))
            paint.isUnderlineText = true
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            setPadding(0, 0, 0, 24)
        }
        layout.addView(urlView)

        // 许可证文本（仅当 resId 不为 0 时显示）
        if (text.isNotEmpty()) {
            val textView = androidx.appcompat.widget.AppCompatTextView(this).apply {
                this.text = text
                setTextIsSelectable(true)
            }
            layout.addView(textView)
        }

        scrollView.addView(layout)

        AlertDialog.Builder(this)
            .setTitle(name)
            .setView(scrollView)
            .setPositiveButton(R.string.about_close, null)
            .show()
    }



    private fun checkPermissions() {
        // Android 11+: request MANAGE_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.permission_required)
                    .setMessage(R.string.permission_storage_desc)
                    .setPositiveButton(R.string.go_to_settings) { _, _ ->
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName"))
                        startActivity(intent)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .setCancelable(false)
                    .show()
            }
        }

        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }
}
