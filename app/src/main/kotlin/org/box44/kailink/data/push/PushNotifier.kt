package org.box44.kailink.data.push

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import org.box44.kailink.MainActivity
import org.box44.kailink.R

/**
 * Android-side rendering of push notifications (compiled here,
 * behavior observable only on device — see docs/features/push.md).
 *
 * The payload ([PushNotificationPayload]) is pure and JVM-tested;
 * this class contains only the thin Android translation:
 * - notification channel (idempotent, needed once per process),
 * - POST_NOTIFICATIONS check (API 33+; manifest permission declared,
 *   runtime user consent is a device concern),
 * - notification with tap intent into [MainActivity].
 */
class PushNotifier(private val context: Context) {

    fun ensureChannel() {
        val manager = notificationManager()
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.push_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.push_notification_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    /** Shows the notification; without permission (API 33+) a silent no-op. */
    fun show(payload: PushNotificationPayload) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel()
        notificationManager().notify(notificationId(payload), build(payload))
    }

    internal fun notificationId(payload: PushNotificationPayload): Int =
        payload.roomId?.hashCode() ?: FALLBACK_NOTIFICATION_ID

    private fun build(payload: PushNotificationPayload): Notification {
        val tapIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(payload.title)
            .setContentText(payload.text)
            .setStyle(Notification.BigTextStyle().bigText(payload.text))
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .build()
    }

    private fun notificationManager(): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val CHANNEL_ID = "kailink_push"
        const val FALLBACK_NOTIFICATION_ID = 1
    }
}
