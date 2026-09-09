package com.cursorforandroid.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.cursorforandroid.R

/** Capability checks, posting and settings deep links for the live notification feature. */
object LiveNotifications {
    const val CHANNEL_LIVE = "live_agents"
    const val CHANNEL_FINISHED = "agent_finished"

    /** `Settings.ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS`; referenced by value because it only exists from Android 16 QPR1. */
    private const val ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS = "android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_LIVE, context.getString(R.string.channel_live_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_live_description)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_FINISHED, context.getString(R.string.channel_finished_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_finished_description)
            },
        )
    }

    /** Runtime permission on Android 13+; implicitly granted before that. */
    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun areEnabled(context: Context): Boolean = hasPermission(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * Whether the live notification would actually appear if it were posted now: the runtime permission, the
     * app-wide switch, and the channel's own switch, all three of which the user can change while the app is away.
     * Following runs in the background exists to produce that notification, so it is not worth a foreground service
     * and its streams when the answer is no.
     */
    fun canShowLive(context: Context): Boolean {
        if (!areEnabled(context)) return false
        // Absent until the channels are created, which happens before anything is posted; it will be importance LOW.
        val channel = runCatching { NotificationManagerCompat.from(context).getNotificationChannel(CHANNEL_LIVE) }
            .getOrNull() ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * Posts [notification] under [id] when the app may. A denied `POST_NOTIFICATIONS` (Android 13+) is not an error:
     * the notification is simply not shown, and the next state change tries again. Checked inline (not via
     * [hasPermission]) so lint's MissingPermission analysis can see it; the permission only exists from API 33, and
     * earlier releases report it as denied, hence the version guard.
     */
    fun post(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }

    /**
     * Drops the finished card for [agentId], if one is up. Opening that chat from inside the app is why: the
     * transcript already shows the reply, so the shade has nothing left to announce. Tapping the card itself is
     * handled by `setAutoCancel`; this is the same dismissal when the user gets there from the list instead.
     */
    fun cancelFinished(context: Context, agentId: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(LiveNotificationRenderer.finishedId(agentId)) }
    }

    /** Android 16 introduced Live Updates (promoted ongoing notifications). */
    val supportsLiveUpdates: Boolean get() = Build.VERSION.SDK_INT >= 36

    /**
     * Whether the OS will promote our notification: null when the platform has no Live Updates. Wrapped because
     * the query is absent on some Android 16 builds that predate the promotion pipeline.
     */
    fun canPostPromoted(context: Context): Boolean? {
        if (!supportsLiveUpdates) return null
        val manager = context.getSystemService(NotificationManager::class.java) ?: return null
        return runCatching { manager.canPostPromotedNotifications() }.getOrNull()
    }

    fun appNotificationSettingsIntent(context: Context): Intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The system page where the user allows Live Updates for this app, or null where no such page exists. */
    fun liveUpdatesSettingsIntent(context: Context): Intent? {
        if (!supportsLiveUpdates) return null
        val intent = Intent(ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent.takeIf { it.resolveActivity(context.packageManager) != null }
    }
}
