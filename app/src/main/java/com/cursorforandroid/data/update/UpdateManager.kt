package com.cursorforandroid.data.update

import android.content.Intent
import android.content.pm.PackageInstaller
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.domain.AppRelease
import com.cursorforandroid.domain.AppVersion
import com.cursorforandroid.domain.UpdatePhase
import com.cursorforandroid.domain.UpdateState
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * What the last check learned, kept on disk so a fresh process shows it at once and the next check can be
 * conditional. [candidate] is the release worth moving to, or null when the build was current.
 */
@Serializable
data class CachedUpdateCheck(
    val etag: String?,
    val checkedAtMs: Long,
    val candidate: AppRelease?,
    /** The channel the decision was made for; a different one needs a fresh list rather than a `304`. */
    val includePreReleases: Boolean,
)

/**
 * What a finished download was proven to be, written only once its bytes matched the digest GitHub published and
 * the archive parsed as this package at the release's own versionCode. A file in the download directory without a
 * marker naming it proves nothing: the process may have died between the rename and the checks.
 */
@Serializable
data class VerifiedDownload(val versionCode: Int, val sizeBytes: Long, val sha256: String)

/**
 * A session handed to `PackageInstaller`, recorded before it is committed. The status callback can start the process
 * that receives it, in which case nothing is in memory to attribute it to; [handledStatus] keeps a redelivery of the
 * same verdict from being acted on twice.
 *
 * [committedAtMs] is when the session was *recorded*, which is a moment before the commit is issued; [commitIssued]
 * says whether the commit was reached at all. A process that died in between left a session no verdict will ever be
 * about, and a record that would otherwise look exactly like an install still in flight.
 */
@Serializable
data class PendingInstall(
    val sessionId: Int,
    val release: AppRelease,
    val committedAtMs: Long,
    val handledStatus: Int? = null,
    val commitIssued: Boolean = false,
)

class UpdateCache(private val cache: JsonDiskCache) {
    suspend fun read(): CachedUpdateCheck? = cache.read(KEY, CachedUpdateCheck.serializer(), VERSION)?.value

    suspend fun write(check: CachedUpdateCheck) {
        cache.write(KEY, CachedUpdateCheck.serializer(), VERSION, check)
    }

    suspend fun readVerified(): VerifiedDownload? = cache.read(VERIFIED_KEY, VerifiedDownload.serializer(), VERSION)?.value

    suspend fun writeVerified(download: VerifiedDownload) {
        cache.write(VERIFIED_KEY, VerifiedDownload.serializer(), VERSION, download)
    }

    suspend fun clearVerified() = cache.remove(VERIFIED_KEY)

    suspend fun readPending(): PendingInstall? = cache.read(PENDING_KEY, PendingInstall.serializer(), VERSION)?.value

    suspend fun writePending(install: PendingInstall) {
        cache.write(PENDING_KEY, PendingInstall.serializer(), VERSION, install)
    }

    suspend fun clearPending() = cache.remove(PENDING_KEY)

    /** Forgets the last check. The download marker and any committed session outlive it; they are about files, not lists. */
    suspend fun clear() = cache.remove(KEY)

    private companion object {
        const val KEY = "latest"
        const val VERIFIED_KEY = "verified-download"
        const val PENDING_KEY = "pending-install"
        const val VERSION = 1
    }
}

/**
 * Keeps the app current from its GitHub releases.
 *
 * A check reads the repository's release list (conditionally, so a repeat costs nothing), maps every `vX.Y.Z` tag
 * to the version scheme the build uses and picks the newest release above the installed versionCode — stable ones
 * only, unless pre-releases are switched on. The APK is downloaded to the cache directory and verified against the
 * SHA-256 GitHub published for it (the asset digest, or `SHA256SUMS.txt`), then parsed to confirm it is this
 * package, newer, and signed with the installed build's key. Installing hands the file to `PackageInstaller`; the
 * outcome comes back through [onInstallStatus]. On Android 12+ the update applies without a dialog once "Install
 * unknown apps" has been allowed (the installer is updating itself); earlier releases always confirm.
 *
 * Automatic mode, driven by the periodic job and the process lifecycle: check, download on an unmetered network,
 * then install while the app is not on screen and no agent is being streamed — or, where a confirmation is needed,
 * post a notification instead. Everything is single-flight; a second request while one is running is ignored.
 */
class UpdateManager(
    private val client: GitHubReleasesClient,
    private val prefs: PreferencesStore,
    private val cache: UpdateCache,
    private val platform: UpdatePlatform,
    /** Where downloaded APKs live (`cacheDir/updates`); anything in it is a verified download named by versionCode. */
    private val downloadDir: File,
    /** Whether an agent is being streamed live; a silent install would kill that connection. */
    private val agentsRunning: () -> Boolean,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val now: () -> Long = AppClock::now,
    /** How long the app has to stay off screen and idle before a silent install is committed. */
    private val backgroundIdleMs: Long = BACKGROUND_IDLE_MS,
    /** Waits out a duration. A seam so the install watchdog can be driven without waiting ten real minutes. */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val autoUpdate: Flow<Boolean> get() = prefs.autoUpdate

    /** The user's choice, or the installed build's own channel while they have not made one. */
    val includePreReleases: Flow<Boolean> = prefs.includePreReleases.map { it ?: installedIsPreRelease }

    private val installedIsPreRelease: Boolean get() = AppVersion.parse(platform.installedVersionName)?.isPreRelease == true

    /** One network or install operation at a time. */
    private val busy = Mutex()

    /** The transfer inside [download], so that cancelling it stops the download and nothing else. */
    @Volatile
    private var transfer: Job? = null

    /** The system's confirmation Intent from a `STATUS_PENDING_USER_ACTION`, launched when the user is around. */
    @Volatile
    private var pendingConfirmation: Intent? = null

    /** Waits out [PENDING_INSTALL_TTL_MS] on a committed session, so that a verdict that never comes is not forever. */
    @Volatile
    private var watchdog: Job? = null

    // A failure here (an unreadable cache file, say) must not poison every later await; the state simply starts Idle.
    private val restored: Deferred<Unit> = scope.async(start = CoroutineStart.LAZY) { runCatching { restore() }.getOrDefault(Unit) }

    /** Rebuilds the state from what the previous process left behind; runs once, before any other operation. */
    suspend fun ensureRestored() = restored.await()

    // ---- manual entry points (Settings) -----------------------------------------------------------------------------

    fun checkNow() = launchExclusive { check() }

    /** The user asked for the download; if they leave before it lands, it is applied or announced as usual. */
    fun downloadNow() = launchExclusive {
        download()
        if (autoUpdate.first()) installOrNotifyInBackground()
    }

    fun installNow() = launchExclusive { install() }

    /** Retries whatever failed last: the check, the download or the install. */
    fun retry() = launchExclusive {
        when ((state.value as? UpdateState.Failed)?.phase) {
            UpdatePhase.Check -> check()
            UpdatePhase.Download -> download()
            UpdatePhase.Install -> install()
            null -> Unit
        }
    }

    fun cancelDownload() {
        if (state.value is UpdateState.Downloading) transfer?.cancel()
    }

    suspend fun setAutoUpdate(enabled: Boolean) = prefs.setAutoUpdate(enabled)

    /** Switching channel invalidates the cached decision and re-checks (after whatever is in flight, if anything). */
    suspend fun setIncludePreReleases(include: Boolean) {
        prefs.setIncludePreReleases(include)
        cache.clear()
        checkNow()
    }

    /**
     * From the "ready to install" notification, or an install left half-way by a previous process: shows the
     * pending confirmation again if there is one, otherwise starts the install over from the downloaded file.
     */
    fun resumePendingInstall() = launchExclusive {
        val confirmation = pendingConfirmation
        val current = state.value
        when {
            confirmation != null && current is UpdateState.Installing -> if (!platform.startConfirmation(confirmation)) install()
            current is UpdateState.Downloaded -> install()
            current is UpdateState.Installing -> {
                // The session belonged to a process that is gone; its confirmation cannot be recovered.
                giveUpOnSession(current.release)
                if (state.value is UpdateState.Downloaded) install()
            }
        }
    }

    /**
     * The way out of an `Installing` state that is not going anywhere: a session whose verdict never arrived, or one
     * a dead process left behind. The download stays ready, so installing again is one tap away.
     */
    fun cancelInstall() = launchExclusive {
        val current = state.value as? UpdateState.Installing ?: return@launchExclusive
        giveUpOnSession(current.release)
    }

    // ---- automatic mode (job + process lifecycle) --------------------------------------------------------------------

    /** The periodic job: check, download on Wi-Fi, install or notify. */
    suspend fun runScheduled() = exclusive {
        if (!autoUpdate.first()) return@exclusive
        if (state.value is UpdateState.Installing) return@exclusive
        check()
        downloadIfUnmetered()
        installOrNotifyInBackground()
    }

    /**
     * The app came to the foreground: a check every few hours (cheap, thanks to the ETag), and whether or not one
     * was due, the download a metered network deferred last time can happen now that the phone may be on Wi-Fi.
     */
    suspend fun onAppStarted() = exclusive {
        if (!autoUpdate.first()) return@exclusive
        if (state.value is UpdateState.Installing) return@exclusive
        val last = prefs.updateLastCheckedAt.first() ?: 0L
        if (now() - last >= FOREGROUND_CHECK_INTERVAL_MS) check()
        downloadIfUnmetered()
    }

    /** The app left the screen: a downloaded update can now be applied without pulling the rug from under the user. */
    suspend fun onAppStopped() = exclusive {
        if (!autoUpdate.first()) return@exclusive
        installOrNotifyInBackground()
    }

    private suspend fun downloadIfUnmetered() {
        val available = state.value as? UpdateState.Available ?: return
        if (available.signatureMismatch || platform.isMeteredNetwork()) return
        download()
    }

    private suspend fun installOrNotifyInBackground() {
        val downloaded = state.value as? UpdateState.Downloaded ?: return
        if (platform.isAppVisible()) return
        val silent = platform.sdkInt >= SILENT_SELF_UPDATE_SDK && platform.canRequestInstalls()
        if (silent) {
            if (!agentsRunning()) install(background = true)
        } else if (prefs.notifiedUpdateVersionCode.first() != downloaded.release.versionCode) {
            // Recorded only once it was really posted: a card that notifications were off for has not been shown, and
            // the next pass must offer it again rather than treat this release as announced forever.
            if (platform.notifyReadyToInstall(downloaded.release)) prefs.setNotifiedUpdateVersionCode(downloaded.release.versionCode)
        }
    }

    // ---- the operations ---------------------------------------------------------------------------------------------

    /** Reads the release list and settles on [UpdateState.UpToDate], [UpdateState.Available] or [UpdateState.Downloaded]. */
    suspend fun check(): UpdateState {
        ensureRestored()
        // A committed session is in the installer's hands; its state must not be overwritten by a list refresh.
        if (state.value is UpdateState.Installing) return state.value
        val previous = cache.read()
        val includePre = includePreReleases.first()
        _state.value = UpdateState.Checking
        try {
            // A 304 only means "the list you saw is unchanged"; the decision made from it must be for the same channel.
            val etag = previous?.etag?.takeIf { previous.includePreReleases == includePre }
            // Eligibility is only knowable after mapping, so the client keeps paging while this says no.
            val fetch = client.listReleases(etag) { seen -> newestOf(seen, includePre) != null }
            val checkedAt = now()
            val (candidate, newEtag) = when (fetch) {
                GitHubReleasesClient.ReleasesFetch.Unchanged -> previous?.candidate to etag
                is GitHubReleasesClient.ReleasesFetch.Changed -> newestOf(fetch.releases, includePre) to fetch.etag
            }
            // A candidate from a 304 was newer than the build that made the decision; it may not be newer than this one.
            val offered = candidate?.takeIf { it.versionCode > platform.installedVersionCode }
            cache.write(CachedUpdateCheck(newEtag, checkedAt, offered, includePre))
            prefs.setUpdateLastCheckedAt(checkedAt)
            pruneDownloads(keep = offered)
            _state.value = stateFor(offered, checkedAt)
        } catch (e: CancellationException) {
            _state.value = previous?.let { stateFor(it.candidate, it.checkedAtMs) } ?: UpdateState.Idle
            throw e
        } catch (t: Throwable) {
            _state.value = UpdateState.Failed(UpdatePhase.Check, t.userMessage(), previous?.candidate)
        }
        return _state.value
    }

    /** Downloads the offered release's APK, verifies it and settles on [UpdateState.Downloaded]. */
    suspend fun download(): UpdateState {
        ensureRestored()
        val current = state.value
        val release = when (current) {
            is UpdateState.Available -> if (current.signatureMismatch) return current else current.release
            is UpdateState.Failed -> current.release?.takeIf { current.phase != UpdatePhase.Install }
            else -> null
        } ?: return current
        val apk = apkFile(release)
        val partial = partialFile(release)
        val total = release.apk.sizeBytes.takeIf { it > 0 } ?: -1L
        _state.value = UpdateState.Downloading(release, 0L, total)
        try {
            val expected = expectedSha256(release)
            // The transfer runs as a child so that "Cancel" can stop it alone, without cancelling whichever caller
            // (the Settings button, the periodic job) happens to be running this download.
            val actual = coroutineScope {
                val job = async {
                    // Written under a name nothing trusts, and bounded by the size the release declares for the asset.
                    client.download(release.apk.url, partial, release.apk.sizeBytes) { read, reported ->
                        _state.value = UpdateState.Downloading(release, read, if (reported > 0) reported else total)
                    }
                }
                transfer = job
                try {
                    job.await()
                } finally {
                    transfer = null
                }
            }
            if (expected != null && !expected.equals(actual, ignoreCase = true)) {
                throw IOException("The download didn't match the checksum GitHub published for it.")
            }
            withContext(Dispatchers.IO) { validate(partial, release) }
            // Every check has passed, so the file may now carry the name the rest of the app reads, and the marker
            // that says what was proven about it. A crash before this leaves only a `.part` file, which is ignored.
            withContext(Dispatchers.IO) { moveIntoPlace(partial, apk) }
            cache.writeVerified(VerifiedDownload(release.versionCode, apk.length(), actual.lowercase()))
            pruneDownloads(keep = release)
            _state.value = UpdateState.Downloaded(release, apk)
        } catch (e: CancellationException) {
            // A cancelled coroutine cannot suspend any more (withContext would throw at once), so plain file calls.
            partial.delete()
            _state.value = UpdateState.Available(release, now())
            // The caller itself is being torn down (the job was stopped, the scope closed): let that proceed. The
            // user pressing Cancel only ended the transfer; the caller carries on with the state restored.
            if (!currentCoroutineContext().isActive) throw e
        } catch (t: Throwable) {
            partial.delete()
            apk.delete()
            cache.clearVerified()
            _state.value = UpdateState.Failed(UpdatePhase.Download, t.userMessage(), release)
        }
        return _state.value
    }

    /** Hands the downloaded APK to the package installer; [onInstallStatus] receives the outcome. */
    suspend fun install(): UpdateState = install(background = false)

    /**
     * [background] is an install the app decided on rather than the user: it may only be committed while the app is
     * off screen with nothing streaming, and it has to still be true after [backgroundIdleMs] of that.
     */
    private suspend fun install(background: Boolean): UpdateState {
        ensureRestored()
        val current = state.value
        val release = when (current) {
            is UpdateState.Downloaded -> current.release
            is UpdateState.Failed -> current.release?.takeIf { current.phase == UpdatePhase.Install }
            else -> null
        } ?: return current
        val marker = cache.readVerified()?.takeIf { it.versionCode == release.versionCode }
        val apk = apkFile(release)
        if (!apk.isFile || marker == null || marker.sizeBytes != apk.length()) {
            // The cache directory was cleared under us, or the file outlived what proved it: download it again.
            apk.delete()
            _state.value = UpdateState.Available(release, now())
            return _state.value
        }
        try {
            withContext(Dispatchers.IO) {
                // These are the bytes about to be handed to the installer, so they are the ones checked, rather than
                // whatever was checked when they were written.
                val digest = sha256Of(apk)
                if (!digest.equals(marker.sha256, ignoreCase = true)) {
                    throw IOException("The downloaded update has changed on disk since it was verified.")
                }
                validate(apk, release)
            }
            if (background && !awaitInstallWindow()) {
                _state.value = UpdateState.Downloaded(release, apk)
                return _state.value
            }
            prefs.setPendingUpdateVersionCode(release.versionCode)
            pendingConfirmation = null
            _state.value = UpdateState.Installing(release)
            var recorded: PendingInstall? = null
            val committed = withContext(Dispatchers.IO) {
                platform.install(
                    apk,
                    release,
                    // Staging the APK is the slow part, and the window the decision was made in can close during
                    // it. A user-initiated install has no window to lose.
                    canCommit = { !background || installWindowOpen() },
                ) { sessionId ->
                    // On disk before the session is committed: the verdict can arrive in a process that has nothing
                    // in memory about this install, including one the verdict itself started.
                    val record = PendingInstall(sessionId, release, now())
                    recorded = record
                    cache.writePending(record)
                }
            }
            if (!committed) {
                // The gate closed while the bytes were being staged; the session is abandoned and nothing was
                // committed, so there is no verdict to wait for and the download is simply still ready.
                prefs.setPendingUpdateVersionCode(null)
                cache.clearPending()
                _state.value = UpdateState.Downloaded(release, apk)
                return _state.value
            }
            // Reached only when the commit did not replace this process on the spot: from here the record says a
            // verdict is genuinely owed, and the watchdog gives up on it if none arrives.
            recorded?.let {
                val issued = it.copy(commitIssued = true)
                cache.writePending(issued)
                armInstallWatchdog(issued)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            prefs.setPendingUpdateVersionCode(null)
            cache.clearPending()
            _state.value = UpdateState.Failed(UpdatePhase.Install, t.userMessage(), release)
        }
        return _state.value
    }

    /**
     * Whether the app is settled enough to be replaced under itself: off screen with no run being streamed, still so
     * after a moment of it. The user can come back, or a stream can start, between a decision and a commit; this is
     * the last look before the point of no return.
     */
    private suspend fun awaitInstallWindow(): Boolean {
        if (!installWindowOpen()) return false
        delay(backgroundIdleMs)
        return installWindowOpen()
    }

    private fun installWindowOpen(): Boolean = !platform.isAppVisible() && !agentsRunning()

    /**
     * Gives a committed session [PENDING_INSTALL_TTL_MS] to produce a verdict. `PackageInstaller` is not obliged to
     * send one — a session the system dropped, or one this process only thinks it committed, produces nothing at all
     * — and until something moves the state on, `Installing` blocks every check, download and install there is.
     *
     * At the deadline the installer is asked about that exact session. One still making progress is left to it: it
     * is a real install, and Settings offers the user a way out of it in the meantime. Anything else is abandoned
     * and the verified download is offered again.
     */
    private fun armInstallWatchdog(pending: PendingInstall) {
        watchdog?.cancel()
        watchdog = scope.launch {
            sleep((pending.committedAtMs + PENDING_INSTALL_TTL_MS - now()).coerceAtLeast(0L))
            busy.withLock {
                val current = state.value
                if (current !is UpdateState.Installing || current.awaitingConfirmation) return@withLock
                val record = cache.readPending() ?: return@withLock
                if (record.sessionId != pending.sessionId || record.handledStatus != null) return@withLock
                if (runCatching { platform.isSessionActive(record.sessionId) }.getOrDefault(false)) return@withLock
                giveUpOnSession(current.release)
            }
        }
    }

    /**
     * Drops a session that will not finish and goes back to what is actually true: the release is downloaded and
     * verified, or — when the file did not survive — merely available.
     */
    private suspend fun giveUpOnSession(release: AppRelease) {
        // Not cancelled here: this can be the watchdog's own coroutine. A watchdog left over from a session that is
        // already gone finds the state moved on and does nothing, and arming a new one replaces it.
        pendingConfirmation = null
        runCatching { platform.abandonSessions() }
        cache.clearPending()
        prefs.setPendingUpdateVersionCode(null)
        platform.cancelNotifications()
        val apk = verifiedApk(release)
        _state.value = if (apk != null) UpdateState.Downloaded(release, apk) else UpdateState.Available(release, now())
    }

    /**
     * The package installer's verdict on a committed session, relayed by the status receiver. [versionCode] and
     * [sessionId] say which install it is about, so a stale verdict cannot be mistaken for the current one and a
     * redelivery of one already acted on is ignored. The release comes from the record written before the commit when
     * this process holds nothing in memory about it — the verdict may be what started it.
     */
    suspend fun onInstallStatus(versionCode: Int, sessionId: Int, status: Int, message: String?, confirmation: Intent?) {
        ensureRestored()
        val pending = cache.readPending()
        // A pending confirmation may legitimately arrive again (the user came back to it); a verdict may not be acted
        // on twice, and the installer is free to redeliver one.
        val terminal = status != PackageInstaller.STATUS_PENDING_USER_ACTION
        if (terminal && pending?.sessionId == sessionId && pending.handledStatus == status) return
        val release = state.value.release?.takeIf { it.versionCode == versionCode }
            ?: pending?.release?.takeIf { it.versionCode == versionCode }
            ?: return
        if (terminal && pending != null && pending.sessionId == sessionId) cache.writePending(pending.copy(handledStatus = status))
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                pendingConfirmation = confirmation
                _state.value = UpdateState.Installing(release, awaitingConfirmation = true)
                if (confirmation == null) return
                // From the background an activity cannot be started; the notification brings the user back to it.
                if (platform.isAppVisible() && platform.startConfirmation(confirmation)) return
                platform.notifyReadyToInstall(release)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Rare for a self-update (the process is usually gone by now); the next start sees the pending record.
                pendingConfirmation = null
                prefs.setPendingUpdateVersionCode(null)
                platform.cancelNotifications()
                pruneDownloads(keep = null)
                _state.value = UpdateState.Installed(release.versionName)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                // The user declined the confirmation; the download stays ready for another go.
                pendingConfirmation = null
                prefs.setPendingUpdateVersionCode(null)
                platform.cancelNotifications()
                _state.value = UpdateState.Downloaded(release, apkFile(release))
            }
            else -> {
                pendingConfirmation = null
                prefs.setPendingUpdateVersionCode(null)
                platform.cancelNotifications()
                if (status == PackageInstaller.STATUS_FAILURE_INVALID) discard(release)
                _state.value = UpdateState.Failed(UpdatePhase.Install, installFailureMessage(status, message), release)
            }
        }
    }

    // ---- internals --------------------------------------------------------------------------------------------------

    private suspend fun restore() {
        val pending = prefs.pendingUpdateVersionCode.first()
        if (pending != null) {
            prefs.setPendingUpdateVersionCode(null)
            if (pending == platform.installedVersionCode) {
                // This process is the update; the previous one committed the session and was replaced.
                pruneDownloads(keep = null)
                cache.clear()
                cache.clearPending()
                platform.cancelNotifications()
                _state.value = UpdateState.Installed(platform.installedVersionName)
                return
            }
            val committed = cache.readPending()?.takeIf {
                it.handledStatus == null && it.release.versionCode == pending && now() - it.committedAtMs < PENDING_INSTALL_TTL_MS
            }
            // A record written before a commit that was never reached names a session nothing will ever report on;
            // only one the installer still has in hand can be waiting for a verdict.
            if (committed != null && (committed.commitIssued || runCatching { platform.isSessionActive(committed.sessionId) }.getOrDefault(false))) {
                // The session was committed by a process that is gone and its verdict has not been seen. It may be
                // moments away — this process may have been started to receive it — so the session is left alone and
                // the state says what it is waiting for. The watchdog gives up on it if nothing ever arrives.
                _state.value = UpdateState.Installing(committed.release)
                armInstallWatchdog(committed)
                return
            }
            // Committed but never applied (declined, or the process died first): an orphaned session may remain.
            cache.clearPending()
            runCatching { platform.abandonSessions() }
        }
        val cached = cache.read() ?: return
        val includePre = includePreReleases.first()
        val candidate = cached.candidate?.takeIf { it.versionCode > platform.installedVersionCode && (includePre || !it.isPreRelease) }
        _state.value = stateFor(candidate, cached.checkedAtMs)
    }

    private fun newestOf(releases: List<GitHubReleaseDto>, includePreReleases: Boolean): AppRelease? =
        ReleaseCatalog.newest(releases.mapNotNull(ReleaseCatalog::toRelease), platform.installedVersionCode, includePreReleases)

    private suspend fun stateFor(candidate: AppRelease?, checkedAtMs: Long): UpdateState {
        if (candidate == null) return UpdateState.UpToDate(checkedAtMs)
        val apk = verifiedApk(candidate)
        return if (apk != null) UpdateState.Downloaded(candidate, apk) else UpdateState.Available(candidate, checkedAtMs, signatureMismatch(candidate))
    }

    /**
     * The downloaded APK for [release], but only when the marker written after its checks names exactly it. A file
     * that is not vouched for is deleted: it may be the one a crash left behind between the rename and the checks.
     */
    private suspend fun verifiedApk(release: AppRelease): File? {
        val apk = apkFile(release)
        if (!apk.isFile) return null
        val marker = cache.readVerified()
        if (marker?.versionCode == release.versionCode && marker.sizeBytes == apk.length()) return apk
        apk.delete()
        return null
    }

    /**
     * The release notes name the certificate the APK was signed with; when it is not among the installed build's,
     * Android will refuse the update and there is no point downloading it.
     */
    private fun signatureMismatch(release: AppRelease): Boolean {
        val published = release.signingCertSha256 ?: return false
        val installed = platform.installedSigningSha256s()
        return installed.isNotEmpty() && published !in installed
    }

    /** The digest to check the download against: GitHub's for the asset, else the release's `SHA256SUMS.txt`. */
    private suspend fun expectedSha256(release: AppRelease): String? {
        release.apk.sha256?.let { return it }
        val url = release.checksumsUrl ?: return null
        val sums = runCatching { ReleaseCatalog.parseChecksums(client.text(url)) }
            .getOrElse { throw IOException("Couldn't fetch the checksums that verify the download.") }
        return sums[release.apk.name] ?: throw IOException("The release's checksums don't list ${release.apk.name}.")
    }

    /**
     * The APK must be this package, exactly the build the release's tag names, newer than the installed one, and
     * signed with a key it already trusts. The tag is all the state and the UI know a release by, so an archive whose
     * own version disagrees with it is not the thing being offered — and a mispackaged, far higher versionCode would
     * install happily and then block every real update after it.
     */
    private fun validate(apk: File, release: AppRelease) {
        val info = platform.inspect(apk) ?: throw IOException("The downloaded file isn't a valid Android package.")
        if (info.packageName != platform.applicationId) {
            throw IOException("${release.versionName} is built as ${info.packageName}; this build is ${platform.applicationId} and can't be updated with it.")
        }
        if (info.versionCode != release.versionCode.toLong()) {
            throw IOException("The APK in release ${release.tagName} is build ${info.versionCode}, not the ${release.versionCode} that tag stands for.")
        }
        if (info.versionName != null && !sameVersionName(info.versionName, release.versionName)) {
            throw IOException("The APK in release ${release.tagName} calls itself ${info.versionName}.")
        }
        if (info.versionCode <= platform.installedVersionCode) {
            throw IOException("The APK in release ${release.tagName} isn't newer than the installed build.")
        }
        val installed = platform.installedSigningSha256s()
        if (info.signingSha256s.isNotEmpty() && installed.isNotEmpty() && info.signingSha256s.none { it in installed }) {
            throw IOException(SIGNATURE_MISMATCH_MESSAGE)
        }
    }

    /** SemVer build metadata (`+g1a2b3c4`) identifies the build, not the version, so the two may differ by it. */
    private fun sameVersionName(archive: String, tag: String): Boolean =
        archive.substringBefore('+') == tag.substringBefore('+')

    private fun installFailureMessage(status: Int, message: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_CONFLICT -> SIGNATURE_MISMATCH_MESSAGE
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked the installation" + (message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ".")
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This release isn't compatible with this device."
        PackageInstaller.STATUS_FAILURE_INVALID -> "The downloaded file isn't a valid update."
        PackageInstaller.STATUS_FAILURE_STORAGE -> "There isn't enough storage to install the update."
        else -> "Installation failed" + (message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ".")
    }

    private fun apkFile(release: AppRelease): File = File(downloadDir, "${release.versionCode}.apk")

    /** Where a download is written while it is still only bytes; [apkFile]'s name means "checked". */
    private fun partialFile(release: AppRelease): File = File(downloadDir, "${release.versionCode}.apk.part")

    /** Deletes every downloaded APK except [keep]'s, so an abandoned or superseded download does not linger. */
    private suspend fun pruneDownloads(keep: AppRelease?) {
        val keepName = keep?.let { apkFile(it).name }
        downloadDir.listFiles()?.forEach { if (it.name != keepName) it.delete() }
        if (keep == null) cache.clearVerified()
    }

    /** Forgets a file and what vouched for it, when the installer says it is not something to keep trying. */
    private suspend fun discard(release: AppRelease) {
        apkFile(release).delete()
        cache.clearVerified()
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** rename(2) replaces atomically on Linux; the fallback covers file systems where it does not. */
    private fun moveIntoPlace(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: IOException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Something the user asked for: runs on the manager's scope once whatever is in flight has finished. Never
     * dropped — the automatic pass that runs when the app comes forward briefly holds the lock at the very moment a
     * notification tap or a Settings button arrives, and a request that vanished then would look like a dead button.
     * Each operation re-reads the state when its turn comes, so one that no longer applies is a no-op.
     */
    private fun launchExclusive(block: suspend () -> Unit) {
        scope.launch {
            busy.withLock {
                ensureRestored()
                block()
            }
        }
    }

    /** An automatic pass: runs [block] in the caller's coroutine unless another operation is in flight, in which case it is skipped. */
    private suspend fun exclusive(block: suspend () -> Unit) {
        if (!busy.tryLock()) return
        try {
            ensureRestored()
            block()
        } finally {
            busy.unlock()
        }
    }

    companion object {
        /** Android 12: `setRequireUserAction(USER_ACTION_NOT_REQUIRED)` lets an app update itself without a prompt. */
        const val SILENT_SELF_UPDATE_SDK = 31
        const val FOREGROUND_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
        /** Long enough that leaving a screen and coming straight back is not an install window. */
        const val BACKGROUND_IDLE_MS = 5_000L
        /** How long a committed session's verdict is still expected; after that the session is treated as orphaned. */
        const val PENDING_INSTALL_TTL_MS = 10 * 60 * 1000L
        const val SIGNATURE_MISMATCH_MESSAGE = "This release is signed with a different key than the installed build, so Android won't install it as an update. Uninstall the app first, or get it from the release page."
    }
}
