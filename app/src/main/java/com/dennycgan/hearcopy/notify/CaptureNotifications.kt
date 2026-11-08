package com.dennycgan.hearcopy.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.dennycgan.hearcopy.R

/**
 * Notification channels and the capture session's foreground notification.
 * Channel importance cannot change after creation, so both channels are
 * fixed now: `session` (low) and `alerts` (high, authorisation lost and
 * later Cap 2 banners).
 */
class CaptureNotifications(private val context: Context) {

    /** Idempotent; called at process start. */
    fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_SESSION,
                    context.getString(R.string.channel_session_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
                NotificationChannel(
                    CHANNEL_ALERTS,
                    context.getString(R.string.channel_alerts_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            ),
        )
    }

    fun sessionNotification(): Notification =
        Notification.Builder(context, CHANNEL_SESSION)
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setContentTitle(context.getString(R.string.notification_session_title))
            .setContentText(context.getString(R.string.notification_session_text))
            .setOngoing(true)
            .setShowWhen(false)
            .build()

    companion object {
        const val SESSION_NOTIFICATION_ID = 1
        const val CHANNEL_SESSION = "session"
        const val CHANNEL_ALERTS = "alerts"
    }
}
