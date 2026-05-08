package com.pisces312.streamclip.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.pisces312.streamclip.R
import com.pisces312.streamclip.model.BatchSummary
import com.pisces312.streamclip.model.BatchTaskItem
import com.pisces312.streamclip.ui.BatchTaskActivity
import java.io.File

class BatchNotificationManager(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
        as NotificationManager

    init {
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.batch_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.batch_notification_channel_desc)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun createForegroundNotification(title: String, content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, BatchTaskActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun updateProgress(
        currentTask: BatchTaskItem,
        completedCount: Int,
        totalCount: Int
    ) {
        val fileName = File(currentTask.inputPath).name
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(
                context.getString(
                    R.string.batch_processing_progress,
                    completedCount + 1,
                    totalCount
                )
            )
            .setContentText(fileName)
            .setSmallIcon(R.drawable.ic_notification)
            .setProgress(100, currentTask.progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                R.drawable.ic_notification,
                context.getString(R.string.pause),
                createActionPendingIntent(BatchTaskService.ACTION_PAUSE)
            )
            .addAction(
                R.drawable.ic_notification,
                context.getString(R.string.cancel),
                createActionPendingIntent(BatchTaskService.ACTION_STOP)
            )
            .build()

        notificationManager.notify(BatchTaskService.NOTIFICATION_ID, notification)
    }

    fun showCompleteNotification(summary: BatchSummary) {
        val content = buildString {
            append(
                context.getString(
                    R.string.batch_complete_summary,
                    summary.completed,
                    summary.total
                )
            )
            if (summary.failed > 0) {
                append(" ")
                append(context.getString(R.string.batch_failed_count, summary.failed))
            }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.batch_complete))
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0,
                    Intent(context, BatchTaskActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

        notificationManager.notify(BatchTaskService.NOTIFICATION_ID + 1, notification)
    }

    private fun createActionPendingIntent(action: String): PendingIntent {
        val intent = Intent(context, BatchTaskService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            context, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val CHANNEL_ID = "batch_task_channel"
    }
}
