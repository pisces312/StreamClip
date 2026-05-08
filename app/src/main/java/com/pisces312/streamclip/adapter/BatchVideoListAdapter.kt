package com.pisces312.streamclip.adapter

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pisces312.streamclip.databinding.ItemBatchVideoBinding

class BatchVideoListAdapter(
    private val onRemove: (Int) -> Unit
) : ListAdapter<Uri, BatchVideoListAdapter.ViewHolder>(DiffCallback()) {

    inner class ViewHolder(val binding: ItemBatchVideoBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBatchVideoBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val uri = getItem(position)
        holder.binding.tvVideoUri.text = uri.lastPathSegment ?: "Video ${position + 1}"
        holder.binding.btnRemove.setOnClickListener {
            onRemove(holder.bindingAdapterPosition)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Uri>() {
        override fun areItemsTheSame(old: Uri, new: Uri) = old == new
        override fun areContentsTheSame(old: Uri, new: Uri) = old == new
    }
}
