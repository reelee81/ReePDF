package hu.reelee81.reepdf

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

object PdfProcessingNotifications {
    const val NOTIFICATION_ID = 1

    private const val CHANNEL_ID = "PdfProcessingProgressChannel"

    private const val OLD_CHANNEL_ID = "PdfProcessingForegroundServiceChannel"

    fun createChannelIfNeeded(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.in_progress_my),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        channel.setSound(null, null)
        channel.enableVibration(false)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
        runCatching { manager?.deleteNotificationChannel(OLD_CHANNEL_ID) }
    }

    fun areOn(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return ProcessingServiceGate.notificationsOn(
            manager.areNotificationsEnabled(),
            manager.getNotificationChannel(CHANNEL_ID)?.importance
        )
    }

    fun build(context: Context, message: String, progress: Int): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_statusbar_icon_24)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(returnIntent(context))
        when {
            progress >= 0 -> builder
                .setProgress(100, progress, false)
                .setSubText(context.getString(R.string.progress_percent, progress))
            progress == PdfProcessingForegroundService.PROGRESS_INDETERMINATE -> builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun returnIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, NotificationReturn::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
}
