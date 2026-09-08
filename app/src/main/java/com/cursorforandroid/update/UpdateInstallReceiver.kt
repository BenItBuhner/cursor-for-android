package com.cursorforandroid.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import com.cursorforandroid.appGraph
import com.cursorforandroid.domain.AppRelease

/**
 * Where `PackageInstaller` reports on a committed update session: user action pending (with the confirmation to
 * show), success, or one of the failure codes. Relayed to the update manager, which owns the state.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val versionCode = intent.getIntExtra(EXTRA_VERSION_CODE, -1)
        val confirmation = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            null
        }
        context.appGraph.updates.onInstallStatus(versionCode, status, message, confirmation)
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "com.cursorforandroid.action.UPDATE_INSTALL_STATUS"
        const val EXTRA_VERSION_CODE = "version_code"

        /** The status receiver for one session; the request code keeps sessions apart. */
        fun statusReceiver(context: Context, sessionId: Int, release: AppRelease): PendingIntent {
            val intent = Intent(context, UpdateInstallReceiver::class.java)
                .setAction(ACTION_INSTALL_STATUS)
                .putExtra(EXTRA_VERSION_CODE, release.versionCode)
            return PendingIntent.getBroadcast(context, sessionId, intent, statusPendingIntentFlags())
        }
    }
}
