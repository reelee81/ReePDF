package hu.reelee81.reepdf

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

object PdfProcessingNotifications {
    const val NOTIFICATION_ID = 1

    private const val CHANNEL_ID = "PdfProcessingForegroundServiceChannel"

    fun createChannelIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.in_progress_my),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    fun build(context: Context, message: String, progress: Int): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_statusbar_icon_24)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        when {
            progress >= 0 -> builder
                .setProgress(100, progress, false)
                .setSubText(context.getString(R.string.progress_percent, progress))
            progress == PdfProcessingForegroundService.PROGRESS_INDETERMINATE -> builder.setProgress(0, 0, true)
        }
        return builder.build()
    }
}
