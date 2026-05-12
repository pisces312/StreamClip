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
import com.pisces312.streamclip.fragment.SettingsFragment
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

        setupMenuButton()
        checkPermissions()
        setupViewPager()

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

    private fun setupMenuButton() {
        binding.btnMenu.setOnClickListener { view ->
            val popup = android.widget.PopupMenu(this, view, android.view.Gravity.END)
            popup.menuInflater.inflate(R.menu.menu_main, popup.menu)
            popup.setOnMenuItemClickListener { item -> handleMenuItem(item) }
            popup.show()
        }
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
            R.id.action_batch_tasks -> {
                startActivity(Intent(this, com.pisces312.streamclip.ui.BatchTaskActivity::class.java))
                true
            }
            R.id.action_settings -> {
                supportFragmentManager.beginTransaction()
                    .replace(android.R.id.content, SettingsFragment())
                    .addToBackStack(null)
                    .commit()
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
            R.id.action_donate -> {
                showDonateDialog()
                true
            }
            R.id.action_tab_order -> {
                startActivity(Intent(this, com.pisces312.streamclip.ui.TabOrderActivity::class.java))
                true
            }
            else -> false
        }
    }

    private fun showDonateDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_donate, null)
        val container = dialogView.findViewById<LinearLayout>(R.id.containerDonate)
        val ivAlipay = dialogView.findViewById<android.widget.ImageView>(R.id.ivDonateAlipay)
        val ivWechat = dialogView.findViewById<android.widget.ImageView>(R.id.ivDonateWechat)

        // 根据屏幕方向调整布局
        val orientation = resources.configuration.orientation
        container.orientation = if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            LinearLayout.HORIZONTAL
        } else {
            LinearLayout.VERTICAL
        }

        try {
            val bitmapAlipay = android.graphics.BitmapFactory.decodeStream(assets.open("donate-alipay.png"))
            ivAlipay.setImageBitmap(bitmapAlipay)
        } catch (e: Exception) {
            LogCollector.w("MainActivity", "加载支付宝二维码失败: ${e.message}")
        }
        try {
            val bitmapWechat = android.graphics.BitmapFactory.decodeStream(assets.open("donate-wechat.png"))
            ivWechat.setImageBitmap(bitmapWechat)
        } catch (e: Exception) {
            LogCollector.w("MainActivity", "加载微信二维码失败: ${e.message}")
        }
        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton(R.string.guide_ok, null)
            .show()
    }

    private fun showGuideDialog() {
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

    private fun showAboutDialog() {
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
        val message = buildString {
            appendLine(getString(R.string.app_name))
            appendLine(getString(R.string.about_version, versionName))
            appendLine()
            appendLine("v1.3.0 - 初始版本")
            appendLine("v1.3.1 - 修复GPS元数据，添加自定义命令")
            appendLine("v1.3.2 - 添加音频压缩，标签排序")
            appendLine("v1.4.0 - 添加二进制FFmpeg支持")
            appendLine()
            appendLine(getString(R.string.about_license_summary))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.title_about)
            .setMessage(message)
            .setPositiveButton(R.string.about_close, null)
            .setNeutralButton(R.string.about_licenses) { _, _ ->
                showLicensesDialog()
            }
            .show()
    }

    private fun showLicensesDialog() {
        val licenses = arrayOf(
            "FFmpeg (GPL v3.0)" to R.raw.license_ffmpeg,
            "FFmpegKit (GPL v3.0)" to R.raw.license_ffmpegkit,
            "x264 (GPL v2.0+)" to R.raw.license_x264,
            "x265 (GPL v2.0+)" to R.raw.license_x265,
            "cpu_features (Apache 2.0)" to R.raw.license_cpu_features
        )

        val items = licenses.map { it.first }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.about_licenses)
            .setItems(items) { _, which ->
                showLicenseText(licenses[which].second)
            }
            .setPositiveButton(R.string.about_close, null)
            .show()
    }

    private fun showLicenseText(resId: Int) {
        val text = try {
            resources.openRawResource(resId).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            getString(R.string.about_license_not_found)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.about_licenses)
            .setMessage(text)
            .setPositiveButton(R.string.about_close, null)
            .show()
    }

    private fun setupViewPager() {
        val order = TabOrderManager.getOrder(this)
        currentTabOrder = order
        val adapter = MainPagerAdapter(this, order)
        binding.viewPager.adapter = adapter

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            val tabId = order[position]
            tab.text = when (tabId) {
                "trim" -> "无损\n截取"
                "trim2" -> "截取\n2"
                "merge" -> "无损\n合并"
                "extract" -> "提取\n音频"
                "compress" -> "视频\n压缩"
                "audio_compress" -> "音频\n压缩"
                "custom" -> "自定义\n命令"
                "metadata" -> "元\n数据"
                else -> ""
            }
            TabOrderManager.TAB_ICONS[tabId]?.let { tab.setIcon(it) }
        }.attach()

        // 位置指示器
        val totalCount = order.size
        binding.tvTabIndicator.text = "1/$totalCount"
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.tvTabIndicator.text = "${position + 1}/$totalCount"
            }
        })
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
