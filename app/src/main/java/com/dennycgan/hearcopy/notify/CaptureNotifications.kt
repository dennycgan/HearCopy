package com.dennycgan.hearcopy.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import com.dennycgan.hearcopy.R

/**
 * Notification channels, the capture session's foreground notification,
 * the authorisation-lost alert and the transcription notification. Channel
 * importance cannot change after creation, so both channels are fixed:
 * `session` (low) and `alerts` (high: the alert and the transcription
 * banner).
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
     * The session is gone but the service waits for the last upload
     * (ADR-015): the foreground notification is replaced in place, so it no
     * longer offers the floating button. Low channel, so it is silent.
     */
    fun showSessionFinishing() {
        val finishing = Notification.Builder(context, CHANNEL_SESSION)
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setContentTitle(context.getString(R.string.notification_session_finishing_title))
            .setOngoing(true)
            .setShowWhen(false)
            .build()
        manager.notify(SESSION_NOTIFICATION_ID, finishing)
    }

    /**
     * The session ended without the user ending it. Tapping opens the main
     * screen through the launcher intent, so the single instance receives
     * it in `onNewIntent`; the extra marks it as coming from this alert.
     */
    fun postAuthorisationLostAlert(@StringRes text: Int = R.string.notification_lost_text) {
        // Carries FLAG_ACTIVITY_NEW_TASK, needed to start from a notification.
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.putExtra(EXTRA_FROM_ALERT, true)
        val tap = PendingIntent.getActivity(
            context,
            ALERT_REQUEST_CODE,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val alert = Notification.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setContentTitle(context.getString(R.string.notification_lost_title))
            .setContentText(context.getString(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        manager.notify(ALERT_NOTIFICATION_ID, alert)
    }

    fun cancelAuthorisationLostAlert() {
        manager.cancel(ALERT_NOTIFICATION_ID)
    }

    /**
     * A new clip is being transcribed. Any earlier transcription
     * notification is removed first: setOnlyAlertOnce keeps a notification
     * that is still showing from alerting again, and each clip should show
     * one banner.
     */
    fun showTranscribing() {
        manager.cancel(TRANSCRIPT_NOTIFICATION_ID)
        postTranscript(R.string.notification_transcribing_title, R.string.notification_transcribing_text, autoCancel = false)
    }

    /** Updates the clip's notification in place, without a second banner. */
    fun showTranscriptDone() {
        postTranscript(R.string.notification_done_title, R.string.notification_done_text, autoCancel = true)
    }

    /**
     * The clip failed; [reason] is a fixed failure string. [newClip]: no
     * notification was shown for this clip yet (a silent or failed capture),
     * so this one replaces the earlier clip's and alerts once.
     */
    fun showTranscriptFailed(@StringRes reason: Int, newClip: Boolean) {
        if (newClip) manager.cancel(TRANSCRIPT_NOTIFICATION_ID)
        postTranscript(R.string.notification_failed_title, reason, autoCancel = true)
    }

    fun cancelTranscript() {
        manager.cancel(TRANSCRIPT_NOTIFICATION_ID)
    }

    private fun postTranscript(@StringRes title: Int, @StringRes text: Int, autoCancel: Boolean) {
        val builder = Notification.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setContentTitle(context.getString(title))
            .setContentText(context.getString(text))
            .setStyle(Notification.BigTextStyle().bigText(context.getString(text)))
            .setOnlyAlertOnce(true)
            .setAutoCancel(autoCancel)
        // Own request code: the alert's PendingIntent has the same launcher
        // intent, and FLAG_UPDATE_CURRENT would otherwise share its extras.
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context,
                    TRANSCRIPT_REQUEST_CODE,
                    launch,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        manager.notify(TRANSCRIPT_NOTIFICATION_ID, builder.build())
    }

    /** Whether [intent] was started by tapping the authorisation-lost alert. */
    fun isAlertIntent(intent: Intent?): Boolean = intent?.getBooleanExtra(EXTRA_FROM_ALERT, false) == true

    companion object {
        const val SESSION_NOTIFICATION_ID = 1
        const val ALERT_NOTIFICATION_ID = 2
        const val TRANSCRIPT_NOTIFICATION_ID = 3
        private const val ALERT_REQUEST_CODE = 0
        private const val TRANSCRIPT_REQUEST_CODE = 1
        const val CHANNEL_SESSION = "session"
        const val CHANNEL_ALERTS = "alerts"
        private const val EXTRA_FROM_ALERT = "com.dennycgan.hearcopy.extra.FROM_ALERT"
    }
}
