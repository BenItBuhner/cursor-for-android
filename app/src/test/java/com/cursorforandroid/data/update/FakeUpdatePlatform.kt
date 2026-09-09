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

    /**
     * What a downloaded file parses as. By default the file name (its versionCode) decides, as the manager names them,
     * and the version name is the one the scheme derives that code from.
     */
    var inspection: (File) -> ApkInfo? = { file ->
        val code = file.name.removeSuffix(".part").removeSuffix(".apk").toLong()
        ApkInfo(applicationId, code, signatures, GitHubFixtures.versionNameFor(code))
    }

    // The manager appends from its own scope while tests assert; copy-on-write keeps the reads race-free.
    val installs = CopyOnWriteArrayList<Pair<File, AppRelease>>()
    val sessions = CopyOnWriteArrayList<Int>()
    var installError: Throwable? = null
    var nextSessionId = 1000

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

    /** Runs where the real platform copies the APK into the session and fsyncs it: seconds, on a large release. */
    var staging: (suspend () -> Unit)? = null

    /** Runs between the session being recorded and the commit — the window a process death leaves a session in. */
    var beforeCommit: (suspend () -> Unit)? = null

    /** Sessions the installer still has in hand: committed here, until they are abandoned. */
    private val active = CopyOnWriteArrayList<Int>()

    override suspend fun install(
        apk: File,
        release: AppRelease,
        canCommit: suspend () -> Boolean,
        onSessionCreated: suspend (Int) -> Unit,
    ): Boolean {
        installError?.let { throw it }
        val sessionId = nextSessionId++
        sessions += sessionId
        staging?.invoke()
        if (!canCommit()) return abandon()
        onSessionCreated(sessionId)
        beforeCommit?.invoke()
        if (!canCommit()) return abandon()
        active += sessionId
        installs += apk to release
        return true
    }

    private fun abandon(): Boolean {
        abandoned++
        return false
    }

    override fun abandonSessions() {
        abandoned++
        active.clear()
    }

    /** Which sessions were asked about, in order: the watchdog's question at the deadline. */
    val sessionQueries = CopyOnWriteArrayList<Int>()

    override fun isSessionActive(sessionId: Int): Boolean {
        sessionQueries += sessionId
        return sessionId in active
    }

    /** What the installer forgetting a committed session looks like: it is simply no longer there to ask about. */
    fun forgetSessions() = active.clear()

    override fun startConfirmation(intent: Intent): Boolean {
        confirmations += intent
        return confirmationStarts
    }

    /** False stands for a notification the system dropped: no permission, or notifications switched off. */
    var notificationsPost = true

    override fun notifyReadyToInstall(release: AppRelease): Boolean {
        if (!notificationsPost) return false
        notified += release
        return true
    }

    override fun cancelNotifications() {
        cancelledNotifications++
    }
}
