package com.cursorforandroid.data.update

import android.content.Intent
import com.cursorforandroid.domain.AppRelease
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** A scriptable device: what is installed, how it is signed, what the network and the screen are doing. */
class FakeUpdatePlatform(
    override var installedVersionCode: Int = 10099,
    override var installedVersionName: String = "0.1.0",
    override val applicationId: String = "com.cursorforandroid",
    override var sdkInt: Int = 35,
) : UpdatePlatform {
    var canInstall = true
    var metered = false
    var visible = false
    var signatures: Set<String> = setOf(GitHubFixtures.RELEASE_CERT_SHA256)

    /** What a downloaded file parses as. By default the file name (its versionCode) decides, as the manager names them. */
    var inspection: (File) -> ApkInfo? = { file -> ApkInfo(applicationId, file.nameWithoutExtension.toLong(), signatures) }

    // The manager appends from its own scope while tests assert; copy-on-write keeps the reads race-free.
    val installs = CopyOnWriteArrayList<Pair<File, AppRelease>>()
    var installError: Throwable? = null

    @Volatile
    var abandoned = 0
    val confirmations = CopyOnWriteArrayList<Intent>()
    var confirmationStarts = true
    val notified = CopyOnWriteArrayList<AppRelease>()

    @Volatile
    var cancelledNotifications = 0

    override fun canRequestInstalls() = canInstall
    override fun isMeteredNetwork() = metered
    override fun isAppVisible() = visible
    override fun installedSigningSha256s() = signatures
    override fun inspect(apk: File): ApkInfo? = inspection(apk)

    override fun install(apk: File, release: AppRelease) {
        installError?.let { throw it }
        installs += apk to release
    }

    override fun abandonSessions() {
        abandoned++
    }

    override fun startConfirmation(intent: Intent): Boolean {
        confirmations += intent
        return confirmationStarts
    }

    override fun notifyReadyToInstall(release: AppRelease) {
        notified += release
    }

    override fun cancelNotifications() {
        cancelledNotifications++
    }
}
