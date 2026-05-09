package com.pisces312.streamclip.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pisces312.streamclip.R

class TabOrderAdapter(
    private val items: MutableList<TabItem>
) : RecyclerView.Adapter<TabOrderAdapter.ViewHolder>() {

    data class TabItem(val id: String, val title: String, val iconRes: Int)

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivIcon: ImageView = itemView.findViewById(R.id.ivIcon)
        val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        val ivDrag: ImageView = itemView.findViewById(R.id.ivDrag)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tab_order, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.ivIcon.setImageResource(item.iconRes)
        holder.tvTitle.text = item.title
    }

    override fun getItemCount(): Int = items.size

    fun moveItem(fromPosition: Int, toPosition: Int) {
        val item = items.removeAt(fromPosition)
        items.add(toPosition, item)
        notifyItemMoved(fromPosition, toPosition)
    }

    fun getCurrentOrder(): List<String> = items.map { it.id }
}
