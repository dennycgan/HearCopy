package com.dennycgan.hearcopy.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.dennycgan.hearcopy.R

/**
 * Notification channels, the capture session's foreground notification and
 * the authorisation-lost alert. Channel importance cannot change after
 * creation, so both channels are fixed now: `session` (low) and `alerts`
 * (high, authorisation lost and later Cap 2 banners).
 */
class CaptureNotifications(private val context: Context) {

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    /** Idempotent; called at process start. */
    fun createChannels() {
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

    /**
     * The session ended without the user ending it. Tapping opens the main
     * screen through the launcher intent, so the single instance receives
     * it in `onNewIntent`; the extra marks it as coming from this alert.
     */
    fun postAuthorisationLostAlert() {
        // Carries FLAG_ACTIVITY_NEW_TASK, needed to start from a notification.
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.putExtra(EXTRA_FROM_ALERT, true)
        val tap = PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val alert = Notification.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setContentTitle(context.getString(R.string.notification_lost_title))
            .setContentText(context.getString(R.string.notification_lost_text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        manager.notify(ALERT_NOTIFICATION_ID, alert)
    }

    fun cancelAuthorisationLostAlert() {
        manager.cancel(ALERT_NOTIFICATION_ID)
    }

    /** Whether [intent] was started by tapping the authorisation-lost alert. */
    fun isAlertIntent(intent: Intent?): Boolean = intent?.getBooleanExtra(EXTRA_FROM_ALERT, false) == true

    companion object {
        const val SESSION_NOTIFICATION_ID = 1
        const val ALERT_NOTIFICATION_ID = 2
        const val CHANNEL_SESSION = "session"
        const val CHANNEL_ALERTS = "alerts"
        private const val EXTRA_FROM_ALERT = "com.dennycgan.hearcopy.extra.FROM_ALERT"
    }
}
