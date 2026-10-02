package com.cursorforandroid.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.cursorforandroid.MainActivity
import com.cursorforandroid.R
import com.cursorforandroid.domain.UsageReset

/**
 * The one notification usage posts: a remaining-percent meter returned to 100%. Not a setting — it always
 * fires when a refresh sees that crossing, if the system will show it.
 */
object UsageNotifications {
    const val CHANNEL = "usage"
    const val RESET_ID = 0x55535253
    /** Opens Settings on the usage group. */
    const val ACTION_OPEN_SETTINGS = "com.cursorforandroid.action.OPEN_SETTINGS"

    private const val ACCENT = 0xFF81A1C1.toInt()

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_usage_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.channel_usage_description)
            },
        )
    }

    fun resetCard(context: Context, reset: UsageReset): Notification {
        val open = PendingIntent.getActivity(
            context,
            RESET_ID,
            Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = context.getString(
            when {
                reset.included && reset.api -> R.string.notif_usage_reset_both
                reset.included -> R.string.notif_usage_reset_included
                else -> R.string.notif_usage_reset_api
            },
        )
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setColor(ACCENT)
            .setContentTitle(context.getString(R.string.notif_usage_reset_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    fun postReset(context: Context, reset: UsageReset): Boolean {
        if (!reset.any) return false
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        ensureChannel(context)
        if (!canShow(context)) return false
        return try {
            NotificationManagerCompat.from(context).notify(RESET_ID, resetCard(context, reset))
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun canShow(context: Context): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        val channel = runCatching { manager.getNotificationChannelCompat(CHANNEL) }.getOrNull() ?: return true
        return channel.importance != NotificationManagerCompat.IMPORTANCE_NONE
    }
}
