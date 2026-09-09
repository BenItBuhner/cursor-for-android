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

class UpdateCache(private val cache: JsonDiskCache) {
    suspend fun read(): CachedUpdateCheck? = cache.read(KEY, CachedUpdateCheck.serializer(), VERSION)?.value

    suspend fun write(check: CachedUpdateCheck) {
        cache.write(KEY, CachedUpdateCheck.serializer(), VERSION, check)
    }

    suspend fun clear() = cache.clear()

    private companion object {
        const val KEY = "latest"
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
                platform.abandonSessions()
                val apk = apkFile(current.release)
                _state.value = if (apk.isFile) UpdateState.Downloaded(current.release, apk) else UpdateState.Available(current.release, now())
                if (apk.isFile) install()
            }
        }
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
            if (!agentsRunning()) install()
        } else if (prefs.notifiedUpdateVersionCode.first() != downloaded.release.versionCode) {
            prefs.setNotifiedUpdateVersionCode(downloaded.release.versionCode)
            platform.notifyReadyToInstall(downloaded.release)
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
        val total = release.apk.sizeBytes.takeIf { it > 0 } ?: -1L
        _state.value = UpdateState.Downloading(release, 0L, total)
        try {
            val expected = expectedSha256(release)
            // The transfer runs as a child so that "Cancel" can stop it alone, without cancelling whichever caller
            // (the Settings button, the periodic job) happens to be running this download.
            val actual = coroutineScope {
                val job = async {
                    client.download(release.apk.url, apk) { read, reported ->
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
            withContext(Dispatchers.IO) { validate(apk, release) }
            pruneDownloads(keep = release)
            _state.value = UpdateState.Downloaded(release, apk)
        } catch (e: CancellationException) {
            // A cancelled coroutine cannot suspend any more (withContext would throw at once), so plain file calls.
            apk.delete()
            _state.value = UpdateState.Available(release, now())
            // The caller itself is being torn down (the job was stopped, the scope closed): let that proceed. The
            // user pressing Cancel only ended the transfer; the caller carries on with the state restored.
            if (!currentCoroutineContext().isActive) throw e
        } catch (t: Throwable) {
            apk.delete()
            _state.value = UpdateState.Failed(UpdatePhase.Download, t.userMessage(), release)
        }
        return _state.value
    }

    /** Hands the downloaded APK to the package installer; [onInstallStatus] receives the outcome. */
    suspend fun install(): UpdateState {
        ensureRestored()
        val current = state.value
        val release = when (current) {
            is UpdateState.Downloaded -> current.release
            is UpdateState.Failed -> current.release?.takeIf { current.phase == UpdatePhase.Install }
            else -> null
        } ?: return current
        val apk = apkFile(release)
        if (!apk.isFile) {
            // The cache directory was cleared under us; the download has to happen again.
            _state.value = UpdateState.Available(release, now())
            return _state.value
        }
        try {
            withContext(Dispatchers.IO) { validate(apk, release) }
            prefs.setPendingUpdateVersionCode(release.versionCode)
            pendingConfirmation = null
            _state.value = UpdateState.Installing(release)
            withContext(Dispatchers.IO) { platform.install(apk, release) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            prefs.setPendingUpdateVersionCode(null)
            _state.value = UpdateState.Failed(UpdatePhase.Install, t.userMessage(), release)
        }
        return _state.value
    }

    /**
     * The package installer's verdict on a committed session, relayed by the status receiver. [versionCode] is the
     * release the session was for, so a stale verdict cannot be mistaken for the current one.
     */
    fun onInstallStatus(versionCode: Int, status: Int, message: String?, confirmation: Intent?) {
        val release = state.value.release?.takeIf { it.versionCode == versionCode } ?: return
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
                scope.launch { prefs.setPendingUpdateVersionCode(null) }
                platform.cancelNotifications()
                pruneDownloads(keep = null)
                _state.value = UpdateState.Installed(release.versionName)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                // The user declined the confirmation; the download stays ready for another go.
                pendingConfirmation = null
                scope.launch { prefs.setPendingUpdateVersionCode(null) }
                platform.cancelNotifications()
                _state.value = UpdateState.Downloaded(release, apkFile(release))
            }
            else -> {
                pendingConfirmation = null
                scope.launch { prefs.setPendingUpdateVersionCode(null) }
                platform.cancelNotifications()
                if (status == PackageInstaller.STATUS_FAILURE_INVALID) apkFile(release).delete()
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
                platform.cancelNotifications()
                _state.value = UpdateState.Installed(platform.installedVersionName)
                return
            }
            // Committed but never applied (declined, or the process died first): an orphaned session may remain.
            runCatching { platform.abandonSessions() }
        }
        val cached = cache.read() ?: return
        val includePre = includePreReleases.first()
        val candidate = cached.candidate?.takeIf { it.versionCode > platform.installedVersionCode && (includePre || !it.isPreRelease) }
        _state.value = stateFor(candidate, cached.checkedAtMs)
    }

    private fun newestOf(releases: List<GitHubReleaseDto>, includePreReleases: Boolean): AppRelease? =
        ReleaseCatalog.newest(releases.mapNotNull(ReleaseCatalog::toRelease), platform.installedVersionCode, includePreReleases)

    private fun stateFor(candidate: AppRelease?, checkedAtMs: Long): UpdateState {
        if (candidate == null) return UpdateState.UpToDate(checkedAtMs)
        val apk = apkFile(candidate)
        return if (apk.isFile) UpdateState.Downloaded(candidate, apk) else UpdateState.Available(candidate, checkedAtMs, signatureMismatch(candidate))
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

    /** The APK must be this package, newer than the installed build, and signed with a key it already trusts. */
    private fun validate(apk: File, release: AppRelease) {
        val info = platform.inspect(apk) ?: throw IOException("The downloaded file isn't a valid Android package.")
        if (info.packageName != platform.applicationId) {
            throw IOException("${release.versionName} is built as ${info.packageName}; this build is ${platform.applicationId} and can't be updated with it.")
        }
        if (info.versionCode <= platform.installedVersionCode) {
            throw IOException("The APK in release ${release.tagName} isn't newer than the installed build.")
        }
        val installed = platform.installedSigningSha256s()
        if (info.signingSha256s.isNotEmpty() && installed.isNotEmpty() && info.signingSha256s.none { it in installed }) {
            throw IOException(SIGNATURE_MISMATCH_MESSAGE)
        }
    }

    private fun installFailureMessage(status: Int, message: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_CONFLICT -> SIGNATURE_MISMATCH_MESSAGE
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked the installation" + (message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ".")
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This release isn't compatible with this device."
        PackageInstaller.STATUS_FAILURE_INVALID -> "The downloaded file isn't a valid update."
        PackageInstaller.STATUS_FAILURE_STORAGE -> "There isn't enough storage to install the update."
        else -> "Installation failed" + (message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ".")
    }

    private fun apkFile(release: AppRelease): File = File(downloadDir, "${release.versionCode}.apk")

    /** Deletes every downloaded APK except [keep]'s, so an abandoned or superseded download does not linger. */
    private fun pruneDownloads(keep: AppRelease?) {
        val keepName = keep?.let { apkFile(it).name }
        downloadDir.listFiles()?.forEach { if (it.name != keepName) it.delete() }
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
        const val SIGNATURE_MISMATCH_MESSAGE = "This release is signed with a different key than the installed build, so Android won't install it as an update. Uninstall the app first, or get it from the release page."
    }
}
