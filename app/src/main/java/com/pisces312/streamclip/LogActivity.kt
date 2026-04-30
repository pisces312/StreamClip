package com.pisces312.streamclip

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.pisces312.streamclip.databinding.ActivityLogBinding
import com.pisces312.streamclip.util.LogCollector

/**
 * 日志查看页面
 */
class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.title_logs)

        loadLogs()
    }

    private fun loadLogs() {
        val logs = StringBuilder()

        // 显示崩溃日志（如果有）
        val crashLogs = LogCollector.getCrashLogs(this)
        if (crashLogs.isNotEmpty()) {
            logs.appendLine("=== 崩溃日志 ===")
            logs.appendLine(crashLogs)
            logs.appendLine("\n=== 应用日志 ===")
        }

        // 显示文件日志
        val fileLogs = LogCollector.getFileLogs(this)
        if (fileLogs.isNotEmpty()) {
            logs.append(fileLogs)
        } else {
            // 如果没有文件日志，显示内存日志
            LogCollector.getMemoryLogs().forEach {
                logs.appendLine(it.format())
            }
        }

        binding.logTextView.text = logs.toString()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_log, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_copy -> {
                copyLogs()
                true
            }
            R.id.action_share -> {
                shareLogs()
                true
            }
            R.id.action_clear -> {
                clearLogs()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun copyLogs() {
        val logs = binding.logTextView.text.toString()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("StreamClip Logs", logs))
        Toast.makeText(this, R.string.logs_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareLogs() {
        val logs = binding.logTextView.text.toString()
        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, "StreamClip 日志")
            putExtra(android.content.Intent.EXTRA_TEXT, logs)
        }
        startActivity(android.content.Intent.createChooser(shareIntent, getString(R.string.share_logs)))
    }

    private fun clearLogs() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_logs_title)
            .setMessage(R.string.clear_logs_confirm)
            .setPositiveButton(R.string.yes) { _, _ ->
                LogCollector.clearLogs(this)
                binding.logTextView.text = ""
                Toast.makeText(this, R.string.logs_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }
}
