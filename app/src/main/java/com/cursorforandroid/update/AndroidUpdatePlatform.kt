package com.cursorforandroid.update

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.ConnectivityManager
import android.os.Build
import android.os.storage.StorageManager
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.cursorforandroid.BuildConfig
import com.cursorforandroid.data.update.ApkInfo
import com.cursorforandroid.data.update.UpdatePlatform
import com.cursorforandroid.domain.AppRelease
import java.io.File
import java.security.MessageDigest

/** The real device behind [UpdatePlatform]: `PackageManager`, `PackageInstaller`, connectivity and the process lifecycle. */
open class AndroidUpdatePlatform(context: Context) : UpdatePlatform {

    private val context = context.applicationContext
    private val packageManager: PackageManager get() = context.packageManager

    override val installedVersionCode: Int = BuildConfig.VERSION_CODE
    override val installedVersionName: String = BuildConfig.VERSION_NAME
    override val applicationId: String = BuildConfig.APPLICATION_ID
    override val sdkInt: Int = Build.VERSION.SDK_INT

    override fun canRequestInstalls(): Boolean = packageManager.canRequestPackageInstalls()

    override fun isMeteredNetwork(): Boolean =
        context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true

    override fun isAppVisible(): Boolean =
        runCatching { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }.getOrDefault(false)

    override fun installedSigningSha256s(): Set<String> =
        runCatching { PackageInfoCompat.getSignatures(packageManager, context.packageName) }.getOrNull().digests()

    private fun Collection<Signature>?.digests(): Set<String> = orEmpty().mapTo(LinkedHashSet()) { sha256Hex(it.toByteArray()) }

    /** Parses the archive with the same flags [installedSigningSha256s] reads the installed build with. */
    @Suppress("DEPRECATION")
    @SuppressLint("PackageManagerGetSignatures")
    override fun inspect(apk: File): ApkInfo? {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = runCatching { packageManager.getPackageArchiveInfo(apk.path, flags) }.getOrNull() ?: return null
        val packageName = info.packageName ?: return null
        return ApkInfo(
            packageName,
            PackageInfoCompat.getLongVersionCode(info),
            info.signingCertificates()?.asList().digests(),
            info.versionName,
        )
    }

    /**
     * A full-install session for our own package. On Android 12+ user action is waived: the installer is updating
     * itself, the APK targets a recent enough API and `UPDATE_PACKAGES_WITHOUT_USER_ACTION` is declared, so once
     * "Install unknown apps" is allowed the update applies quietly. When any of that does not hold, the system
     * answers the commit with `STATUS_PENDING_USER_ACTION` and a confirmation to show.
     */
    override suspend fun install(apk: File, release: AppRelease, onSessionCreated: suspend (Int) -> Unit) {
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(applicationId)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                // The commit below can replace this process before anything after it runs.
                onSessionCreated(sessionId)
                session.commit(UpdateInstallReceiver.statusReceiver(context, sessionId, release).intentSender)
            }
        } catch (t: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            throw t
        }
    }

    override fun abandonSessions() {
        val installer = packageManager.packageInstaller
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
    }

    override fun startConfirmation(intent: Intent): Boolean =
        runCatching { context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

    override fun notifyReadyToInstall(release: AppRelease): Boolean = UpdateNotifications.postReadyToInstall(context, release)

    override fun cancelNotifications() = UpdateNotifications.cancel(context)

    /** The current signers of a parsed archive, or the lineage for a single rotated signer. */
    @Suppress("DEPRECATION")
    private fun PackageInfo.signingCertificates(): Array<Signature>? = if (Build.VERSION.SDK_INT >= 28) {
        signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
    } else {
        signatures
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/**
 * Bytes an update download may count on landing in [dir], for
 * [com.cursorforandroid.data.update.GitHubReleasesClient]. `getAllocatableBytes` rather than `File.getUsableSpace`
 * because the download goes into the cache directory and the system will clear other apps' cached data to make room:
 * the usable figure alone would refuse updates on devices that do have space for them. Falls back to the usable
 * figure when the path is not on a volume the storage manager knows.
 */
internal fun allocatableBytes(context: Context, dir: File): Long = runCatching {
    val storage = context.getSystemService(StorageManager::class.java) ?: return@runCatching dir.usableSpace
    storage.getAllocatableBytes(storage.getUuidForPath(dir))
}.getOrElse { dir.usableSpace }

/** `PendingIntent` flags: `PackageInstaller` fills in the status extras, so the intent must stay mutable. */
internal fun statusPendingIntentFlags(): Int =
    PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
