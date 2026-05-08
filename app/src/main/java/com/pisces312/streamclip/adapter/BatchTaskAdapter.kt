package com.pisces312.streamclip.adapter

import android.view.LayoutInflater
import com.pisces312.streamclip.R
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pisces312.streamclip.databinding.ItemBatchTaskBinding
import com.pisces312.streamclip.model.BatchTaskItem
import com.pisces312.streamclip.model.TaskStatus
import java.io.File

class BatchTaskAdapter(
    private val onRetry: (String) -> Unit,
    private val onCancel: (String) -> Unit,
    private val onOpen: (String) -> Unit
) : ListAdapter<BatchTaskItem, BatchTaskAdapter.ViewHolder>(DiffCallback()) {

    inner class ViewHolder(val binding: ItemBatchTaskBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBatchTaskBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val task = getItem(position)
        val binding = holder.binding

        val ctx = holder.itemView.context
        binding.tvFileName.text = File(task.inputPath).name
        binding.progressBar.progress = task.progress
        binding.tvStatus.text = when (task.status) {
            TaskStatus.PENDING -> ctx.getString(R.string.task_status_pending)
            TaskStatus.RUNNING -> ctx.getString(R.string.task_status_running, task.progress)
            TaskStatus.PAUSED -> ctx.getString(R.string.task_status_paused)
            TaskStatus.COMPLETED -> ctx.getString(R.string.task_status_completed)
            TaskStatus.FAILED -> ctx.getString(R.string.task_status_failed, task.errorMessage?.let { ": $it" } ?: "")
            TaskStatus.CANCELLED -> ctx.getString(R.string.task_status_cancelled)
        }

        binding.progressBar.isVisible = task.status == TaskStatus.RUNNING || task.status == TaskStatus.PENDING
        binding.ivStatus.setImageResource(
            when (task.status) {
                TaskStatus.COMPLETED -> android.R.drawable.checkbox_on_background
                TaskStatus.FAILED -> android.R.drawable.ic_delete
                TaskStatus.CANCELLED -> android.R.drawable.ic_menu_close_clear_cancel
                else -> android.R.drawable.ic_menu_upload
            }
        )

        when (task.status) {
            TaskStatus.FAILED -> {
                binding.btnAction.setImageResource(android.R.drawable.ic_menu_revert)
                binding.btnAction.setOnClickListener { onRetry(task.id) }
                binding.btnAction.isVisible = true
            }
            TaskStatus.RUNNING, TaskStatus.PENDING -> {
                binding.btnAction.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                binding.btnAction.setOnClickListener { onCancel(task.id) }
                binding.btnAction.isVisible = true
            }
            TaskStatus.COMPLETED -> {
                binding.btnAction.setImageResource(android.R.drawable.ic_menu_gallery)
                binding.btnAction.setOnClickListener { onOpen(task.outputPath) }
                binding.btnAction.isVisible = true
            }
            else -> {
                binding.btnAction.isVisible = false
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<BatchTaskItem>() {
        override fun areItemsTheSame(old: BatchTaskItem, new: BatchTaskItem) = old.id == new.id
        override fun areContentsTheSame(old: BatchTaskItem, new: BatchTaskItem) = old == new
    }
}
