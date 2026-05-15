package com.pisces312.streamclip.adapter

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.pisces312.streamclip.databinding.ItemBatchVideoBinding

class BatchVideoListAdapter(
    private val onRemove: (Int) -> Unit
) : RecyclerView.Adapter<BatchVideoListAdapter.ViewHolder>() {

    private val items = mutableListOf<Uri>()

    inner class ViewHolder(val binding: ItemBatchVideoBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun getItemCount() = items.size

    fun setItems(newItems: List<Uri>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun getItems(): List<Uri> = items.toList()

    fun removeAt(position: Int) {
        if (position in items.indices) {
            items.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBatchVideoBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val uri = items[position]
        holder.binding.tvVideoUri.text = uri.lastPathSegment ?: "Video ${position + 1}"
        holder.binding.btnRemove.setOnClickListener {
            onRemove(position)
        }
    }
}
