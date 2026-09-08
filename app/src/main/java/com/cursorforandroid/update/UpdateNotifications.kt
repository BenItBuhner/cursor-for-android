package com.cursorforandroid.update

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
import com.cursorforandroid.domain.AppRelease

/**
 * The one notification the updater posts: a downloaded update that needs the user to confirm its installation
 * (Android 8–11, or Android 12+ until "Install unknown apps" is allowed). Tapping it returns to the app, which
 * brings up the system's confirmation.
 */
object UpdateNotifications {
    const val CHANNEL = "app_updates"
    const val READY_ID = 0x55504454
    /** Carried by the launch intent so [MainActivity] resumes the install instead of just opening. */
    const val ACTION_INSTALL_UPDATE = "com.cursorforandroid.action.INSTALL_UPDATE"

    private const val ACCENT = 0xFF81A1C1.toInt()

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_updates_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.channel_updates_description)
            },
        )
    }

    fun readyToInstall(context: Context, release: AppRelease): Notification {
        val open = PendingIntent.getActivity(
            context,
            READY_ID,
            Intent(context, MainActivity::class.java).setAction(ACTION_INSTALL_UPDATE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setColor(ACCENT)
            .setContentTitle(context.getString(R.string.notif_update_ready_title, release.versionName))
            .setContentText(context.getString(R.string.notif_update_ready_text))
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.notif_action_install), open)
            .build()
    }

    fun postReadyToInstall(context: Context, release: AppRelease) {
        // Checked inline (as the live notifications do) so lint's MissingPermission analysis can see it.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)
        try {
            NotificationManagerCompat.from(context).notify(READY_ID, readyToInstall(context, release))
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call; Settings still shows the update.
        }
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(READY_ID) }
    }
}
