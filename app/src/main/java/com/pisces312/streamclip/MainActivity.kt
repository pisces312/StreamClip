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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

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

        setSupportActionBar(binding.toolbar)

        checkPermissions()
        setupViewPager()

        // 检查是否有崩溃日志
        checkCrashLog()
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
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
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupViewPager() {
        val adapter = MainPagerAdapter(this)
        binding.viewPager.adapter = adapter

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.title_trim)
                1 -> getString(R.string.title_trim2)
                2 -> getString(R.string.title_merge)
                3 -> getString(R.string.title_extract)
                else -> ""
            }
        }.attach()
    }

    private fun checkPermissions() {
        // Android 11+: request MANAGE_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                AlertDialog.Builder(this)
                    .setTitle("需要文件访问权限")
                    .setMessage("StreamClip 需要访问所有文件才能读取视频和保存输出。请在设置中开启\"允许访问所有文件\"。")
                    .setPositiveButton("去设置") { _, _ ->
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName"))
                        startActivity(intent)
                    }
                    .setNegativeButton("取消", null)
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
