package com.cursorforandroid.data.update

import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.domain.AppVersion
import com.cursorforandroid.domain.ReleaseNotes
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * What the last look at GitHub for the installed version's release left on disk. [notes] is null when the release
 * had no readable notes at the time — a tag with no release, or a release whose curated notes were still to be
 * pasted in above the generated ones — and [fetchedAtMs] says when that was, so the question is asked again after a
 * while rather than on every start.
 */
@Serializable
data class CachedReleaseNotes(val versionName: String, val fetchedAtMs: Long, val notes: ReleaseNotes?)

/**
 * The installed version's release notes, for the What's new page and the two surfaces that lead to it.
 *
 * The notes come from the GitHub release whose tag is the installed build's own version (see
 * [GitHubReleasesClient.releaseByTag]), read through [ReleaseNotesFormat], and are kept on disk: once a version's
 * notes have been read they are never asked for again, so a version costs one anonymous request in all. A release
 * that had nothing curated yet is asked about again after [NO_NOTES_RETRY_MS]; a request that failed (no network,
 * GitHub's rate limit) is retried after [FAILED_RETRY_MS] at the earliest, and never before the next start otherwise.
 * A CI build — a version with build metadata after `+` — is never released as such, so it asks nothing at all.
 *
 * Whether the notes are *unread* is this device's: the version whose page was last opened is in the preferences,
 * and [unread] carries the notes only while the installed version is not that one. Device-level like the updater,
 * so signing out changes nothing here.
 */
class WhatsNewRepository(
    private val client: GitHubReleasesClient,
    private val prefs: PreferencesStore,
    private val cache: JsonDiskCache,
    /** `BuildConfig.VERSION_NAME`; a parameter so a test can install any version it likes. */
    val installedVersionName: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val now: () -> Long = AppClock::now,
) {
    private val _notes = MutableStateFlow<ReleaseNotes?>(null)

    /** The installed version's notes once they are known; null before the first read, and for a version that has none. */
    val notes: StateFlow<ReleaseNotes?> = _notes.asStateFlow()

    /** The installed version's notes while its page has not been opened on this device: what both surfaces show. Null hides them. */
    val unread: Flow<ReleaseNotes?> = combine(notes, prefs.whatsNewReadVersion) { current, read -> current?.takeIf { it.versionName != read } }

    private val busy = Mutex()

    /** When GitHub was last asked in this process, for the retry interval after a failure; null while it has not been. */
    @Volatile
    private var lastAttemptMs: Long? = null

    /** The tag the installed version was released under. */
    val tagName: String get() = "v$installedVersionName"

    /** [refreshNow] on the repository's own scope: the app coming forward. */
    fun refresh(): Job = scope.launch { refreshNow() }

    /**
     * Settles [notes] for the installed version: from the disk when it has them, from GitHub when it does not and the
     * intervals allow. Never throws for a fetch that failed; the notes simply stay as they were.
     */
    suspend fun refreshNow() = busy.withLock {
        val cached = cache.read(CACHE_KEY, CachedReleaseNotes.serializer(), CACHE_VERSION)?.value?.takeIf { it.versionName == installedVersionName }
        if (cached?.notes != null) {
            _notes.value = cached.notes
            return@withLock
        }
        val at = now()
        // A recent "nothing there" stands; a clock that went backwards does not keep it standing forever.
        if (cached != null && at - cached.fetchedAtMs in 0 until NO_NOTES_RETRY_MS) return@withLock
        lastAttemptMs?.let { if (at - it in 0 until FAILED_RETRY_MS) return@withLock }
        // A `0.3.38-dev.42+gabc1234` build has no release to look for; the tag it would ask about never exists.
        if (AppVersion.parse(installedVersionName)?.build != null) return@withLock
        lastAttemptMs = at
        val dto = try {
            client.releaseByTag(tagName)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return@withLock
        }
        val notes = dto?.let(ReleaseNotesFormat::from)
        cache.write(CACHE_KEY, CachedReleaseNotes.serializer(), CACHE_VERSION, CachedReleaseNotes(installedVersionName, at, notes))
        _notes.value = notes
    }

    /** The page was opened for the installed version: the row and the card go, until the next version is installed. */
    suspend fun markRead() = prefs.setWhatsNewReadVersion(installedVersionName)

    companion object {
        const val CACHE_KEY = "notes"
        const val CACHE_VERSION = 1

        /** The curated notes are pasted in a few minutes after the workflow publishes the release; a device that looked in between looks again soon. */
        const val NO_NOTES_RETRY_MS = 6 * 60 * 60 * 1000L

        /** The same interval the updater's foreground check keeps, and for the same reason: the anonymous quota is per address and per hour. */
        const val FAILED_RETRY_MS = 60 * 60 * 1000L
    }
}
