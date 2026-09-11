package com.cursorforandroid.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import com.cursorforandroid.appGraph
import com.cursorforandroid.domain.AppRelease
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where `PackageInstaller` reports on a committed update session: user action pending (with the confirmation to
 * show), success, or one of the failure codes. Relayed to the update manager, which owns the state.
 *
 * The verdict outlives the process that committed the session, so this receiver is frequently what starts a new one.
 * That process knows nothing yet, so the broadcast is kept alive with `goAsync` while the manager reads back what was
 * recorded before the commit; handing the verdict to a manager that is still empty is how it used to be dropped.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val versionCode = intent.getIntExtra(EXTRA_VERSION_CODE, -1)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, intent.getIntExtra(EXTRA_SESSION_ID, -1))
        val confirmation = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            null
        }
        val updates = context.appGraph.updates
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                // Bounded: a receiver that never finishes is an ANR, and the state is on disk either way.
                withTimeoutOrNull(TIMEOUT_MS) { updates.onInstallStatus(versionCode, sessionId, status, message, confirmation) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "com.cursorforandroid.action.UPDATE_INSTALL_STATUS"
        const val EXTRA_VERSION_CODE = "version_code"
        /** The session this callback is for. `PackageInstaller` also fills in its own; this is the fallback. */
        const val EXTRA_SESSION_ID = "session_id"
        private const val TIMEOUT_MS = 8_000L

        /** The status receiver for one session; the request code keeps sessions apart. */
        fun statusReceiver(context: Context, sessionId: Int, release: AppRelease): PendingIntent {
            val intent = Intent(context, UpdateInstallReceiver::class.java)
                .setAction(ACTION_INSTALL_STATUS)
                .putExtra(EXTRA_VERSION_CODE, release.versionCode)
                .putExtra(EXTRA_SESSION_ID, sessionId)
            return PendingIntent.getBroadcast(context, sessionId, intent, statusPendingIntentFlags())
        }
    }
}
