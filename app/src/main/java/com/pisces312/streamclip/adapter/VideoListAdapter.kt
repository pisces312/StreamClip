package com.pisces312.streamclip.adapter

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.pisces312.streamclip.databinding.ItemVideoBinding

class VideoListAdapter(
    private val videos: List<Uri>,
    private val onRemove: (Int) -> Unit
) : RecyclerView.Adapter<VideoListAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemVideoBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemVideoBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val uri = videos[position]
        holder.binding.tvVideoUri.text = uri.lastPathSegment ?: "Video ${position + 1}"
        holder.binding.btnRemove.setOnClickListener {
            onRemove(position)
        }
    }

    override fun getItemCount(): Int = videos.size
}
