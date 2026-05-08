package com.pisces312.streamclip.ui

import android.content.Intent
import com.pisces312.streamclip.R
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.pisces312.streamclip.adapter.BatchTaskAdapter
import com.pisces312.streamclip.databinding.ActivityBatchTaskBinding
import com.pisces312.streamclip.service.BatchTaskService
import com.pisces312.streamclip.service.TaskQueueManager
import kotlinx.coroutines.launch

class BatchTaskActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBatchTaskBinding
    private lateinit var adapter: BatchTaskAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBatchTaskBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = BatchTaskAdapter(
            onRetry = { taskId ->
                TaskQueueManager.retryTask(taskId)
                refreshTasks()
            },
            onCancel = { taskId ->
                TaskQueueManager.markCancelled(taskId)
                refreshTasks()
            },
            onOpen = { outputPath ->
                try {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this,
                        "${packageName}.fileprovider",
                        java.io.File(outputPath)
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "video/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, getString(R.string.cannot_open_file), Toast.LENGTH_SHORT).show()
                }
            }
        )

        binding.recyclerTasks.layoutManager = LinearLayoutManager(this)
        binding.recyclerTasks.adapter = adapter

        binding.fabClear.setOnClickListener {
            TaskQueueManager.clearCompleted()
            refreshTasks()
        }

        observeTasks()
    }

    private fun observeTasks() {
        lifecycleScope.launch {
            TaskQueueManager.taskFlow.collect { tasks ->
                adapter.submitList(tasks)
                binding.emptyView.isVisible = tasks.isEmpty()
            }
        }
    }

    private fun refreshTasks() {
        val tasks = TaskQueueManager.getAllTasks()
        adapter.submitList(tasks)
        binding.emptyView.isVisible = tasks.isEmpty()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
