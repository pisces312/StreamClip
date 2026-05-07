package com.pisces312.streamclip.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pisces312.streamclip.R
import com.pisces312.streamclip.service.FFmpegService

class FfmpegLogAdapter : RecyclerView.Adapter<FfmpegLogAdapter.ViewHolder>() {

    private val logs = mutableListOf<FFmpegService.LogLine>()

    fun addLog(log: FFmpegService.LogLine) {
        logs.add(log)
        notifyItemInserted(logs.size - 1)
    }

    fun getAllLogs(): String = logs.joinToString("\n") { it.text }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ffmpeg_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(logs[position])
    }

    override fun getItemCount(): Int = logs.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvLog: TextView = itemView.findViewById(R.id.tvLog)

        fun bind(log: FFmpegService.LogLine) {
            tvLog.text = log.text
            tvLog.setTextColor(if (log.isError) 0xFFFF6B6B.toInt() else 0xFF00FF00.toInt())
        }
    }
}
