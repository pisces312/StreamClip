package com.pisces312.streamclip.ui

import com.pisces312.streamclip.R
import android.os.Bundle
import com.pisces312.streamclip.BaseActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.pisces312.streamclip.adapter.BatchTaskAdapter
import com.pisces312.streamclip.databinding.ActivityBatchTaskBinding
import com.pisces312.streamclip.service.BatchTaskService
import com.pisces312.streamclip.service.TaskQueueManager
import com.pisces312.streamclip.util.FileOpenHelper
import kotlinx.coroutines.launch

class BatchTaskActivity : BaseActivity() {

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
                // Enqueue alone is not enough: the service may have already stopped
                // itself when the queue drained, so (re)start it with the new task.
                TaskQueueManager.retryTask(taskId)?.let { task ->
                    BatchTaskService.start(this, listOf(task))
                }
                refreshTasks()
            },
            onCancel = { taskId ->
                try {
                    if (BatchTaskService.isRunning) {
                        // Real cancellation: kills the running coroutine + FFmpeg session.
                        BatchTaskService.cancelTask(this, taskId)
                    } else {
                        // Service already gone — just fix the stored status / dequeue.
                        TaskQueueManager.markCancelled(taskId)
                    }
                } catch (e: Exception) {
                    // startService can throw if the app somehow left the foreground.
                    TaskQueueManager.markCancelled(taskId)
                }
                refreshTasks()
            },
            onOpen = { outputPath ->
                FileOpenHelper.openVideo(this, java.io.File(outputPath))
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
