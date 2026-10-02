package com.cursorforandroid.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import com.cursorforandroid.R
import com.cursorforandroid.domain.SpotlightView

/**
 * The Spotlight notification: the chat's (or Project's) name, the step it is on, and — expanded — the subagents or
 * chats working under it and a tally. One action, Stop Spotlight, which swiping the card away also does.
 *
 * `BigTextStyle`, ongoing, not colorized and with a title, so Android 16 can promote it to a Live Update next to the
 * live notification's own; on earlier releases it is an ordinary silent ongoing card.
 */
object SpotlightRenderer {
    /** Id of the Spotlight notification (also its service's foreground notification). */
    const val SPOTLIGHT_ID = 0x53504F54

    private const val ACCENT = 0xFF81A1C1.toInt()

    fun spotlight(context: Context, view: SpotlightView): Notification {
        val stop = stopIntent(context)
        val builder = NotificationCompat.Builder(context, LiveNotifications.CHANNEL_SPOTLIGHT)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setColor(ACCENT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setRequestPromotedOngoing(true)
            .setContentTitle(view.title)
            .setContentText(view.step)
            .setSubText(context.getString(R.string.spotlight_label))
            .setWhen(view.startedAtMillis)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setContentIntent(LiveNotificationRenderer.openAgent(context, view.agentId))
            .setDeleteIntent(stop)
            .addAction(0, context.getString(R.string.spotlight_action_stop), stop)
        builder.setStyle(NotificationCompat.BigTextStyle().bigText(view.body ?: view.step))
        return builder.build()
    }

    private fun stopIntent(context: Context): PendingIntent = PendingIntent.getService(
        context,
        "spotlight-stop".hashCode(),
        SpotlightService.stopIntent(context),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
