package com.cursorforandroid.data.update

import android.content.Intent
import com.cursorforandroid.domain.AppRelease
import java.io.File

/** What `PackageManager` can tell about an APK file before it is installed. */
class ApkInfo(
    val packageName: String,
    val versionCode: Long,
    /** SHA-256 digests (lowercase hex) of the certificates in the APK's signing lineage; empty when unavailable. */
    val signingSha256s: Set<String>,
    /** The archive's own `versionName`, when it declares one. */
    val versionName: String? = null,
)

/**
 * The device-side operations behind [UpdateManager]: package facts, connectivity, process visibility and the
 * `PackageInstaller` session itself. Kept behind an interface so the manager's state machine is exercised in
 * tests with a fake, while the Android implementation stays thin.
 */
interface UpdatePlatform {
    val installedVersionCode: Int
    val installedVersionName: String
    val applicationId: String
    val sdkInt: Int

    /** Whether the user has allowed this app to install packages ("Install unknown apps"). */
    fun canRequestInstalls(): Boolean

    fun isMeteredNetwork(): Boolean

    /** True while any of the app's screens is started; false in the background. */
    fun isAppVisible(): Boolean

    /** Digests of the installed build's signing certificates; empty when they cannot be read. */
    fun installedSigningSha256s(): Set<String>

    /** Parses [apk]; null when it is not a package Android can read. */
    fun inspect(apk: File): ApkInfo?

    /**
     * Writes [apk] into a new install session and commits it. Android reports the outcome asynchronously through
     * [UpdateManager.onInstallStatus]. Throws when the session cannot be created or written.
     *
     * [onSessionCreated] runs with the new session's id before it is committed, so a caller can record what the
     * verdict will be about while there is still no verdict to miss.
     */
    suspend fun install(apk: File, release: AppRelease, onSessionCreated: suspend (Int) -> Unit)

    /** Abandons install sessions this app still owns, e.g. one whose confirmation was never answered. */
    fun abandonSessions()

    /** Brings up the system's install confirmation; false when it could not be started. */
    fun startConfirmation(intent: Intent): Boolean

    /**
     * A notification inviting the user to install [release]; tapping it returns to the app. False when it could not
     * be shown at all (no permission, notifications or the channel switched off), which is not the same as unseen.
     */
    fun notifyReadyToInstall(release: AppRelease): Boolean

    fun cancelNotifications()
}
