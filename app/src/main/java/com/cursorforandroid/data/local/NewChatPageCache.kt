package com.cursorforandroid.data.local

import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.NewChatHome
import com.cursorforandroid.domain.NewChatHomeChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The New Chat page as it was last drawn: whose it was, the layout chosen in Settings, whether Extended mode was on,
 * its Projects and which of them were hidden there — each with the chats inside it, which is what its working glyph counts. What
 * the first frame of the next launch draws while the settings, the list's own disk copy (much larger: every row with
 * its record) and the network are still on their way, so the page opens as it was left rather than empty.
 */
@Serializable
data class NewChatPageSnapshot(
    /** The account it was drawn for (see [NewChatPageCache.accountOf]); another account's launch draws nothing of it. */
    val account: String,
    /** Settings › New chat page as chosen, by its key; null while nothing has been chosen there. */
    val chosenHome: String? = null,
    val extendedMode: Boolean = false,
    /** Every Project the page had, first to last, the hidden ones among them. */
    val projects: List<CachedShortcutRow> = emptyList(),
    /** The Projects dragged below the page's "Hidden" line, which it leaves off. */
    val hiddenProjectIds: Set<String> = emptySet(),
) {
    val choice: NewChatHomeChoice get() = NewChatHomeChoice(NewChatHome.chosen(chosenHome))

    /**
     * Every Project the page had, the hidden ones among them: the page leaves [hiddenProjectIds] off itself, as it does
     * once the settings are read, so a page whose Projects are all hidden opens on its "hidden" row rather than none.
     */
    fun projectRows(): List<AgentRow> = projects.map(CachedShortcutRow::toRow)

    companion object {
        fun of(user: CursorUser, choice: NewChatHomeChoice, extendedMode: Boolean, projects: List<AgentRow>, hidden: Set<String> = emptySet()) = NewChatPageSnapshot(
            account = NewChatPageCache.accountOf(user),
            chosenHome = choice.chosen?.key,
            extendedMode = extendedMode,
            projects = projects.map { CachedShortcutRow.of(it, nested = false) },
            hiddenProjectIds = hidden.filterTo(HashSet()) { id -> projects.any { it.agent.id == id } },
        )
    }
}

/**
 * An [AgentRow] as a Project shortcut draws it and opens it. The Project's own chat is kept whole but for its account
 * record (the raw fields that placed it, read again with the list); the chats nested under it keep only what their
 * rows' state is read from, so a Project of a hundred workers stays a few kilobytes.
 */
@Serializable
data class CachedShortcutRow(
    val agent: Agent,
    val indicator: AgentIndicator,
    val isPinned: Boolean = false,
    val isUnread: Boolean = false,
    val launchedFromThisDevice: Boolean = false,
    val isSnoozed: Boolean = false,
    val snoozedAtMillis: Long? = null,
    val memberCount: Int? = null,
    val isPlaceholder: Boolean = false,
    val isStandIn: Boolean = false,
    val children: List<CachedShortcutRow> = emptyList(),
) {
    fun toRow(): AgentRow = AgentRow(
        agent = agent,
        indicator = indicator,
        isPinned = isPinned,
        isUnread = isUnread,
        launchedFromThisDevice = launchedFromThisDevice,
        isSnoozed = isSnoozed,
        snoozedAtMillis = snoozedAtMillis,
        children = children.map(CachedShortcutRow::toRow),
        isPlaceholder = isPlaceholder,
        memberCount = memberCount,
        isStandIn = isStandIn,
    )

    companion object {
        fun of(row: AgentRow, nested: Boolean): CachedShortcutRow = CachedShortcutRow(
            agent = if (nested) {
                row.agent.copy(record = null, summary = null, branches = emptyList(), modelParams = emptyList(), accountModel = null, modelDisplayName = null, modelId = null)
            } else {
                row.agent.copy(record = null)
            },
            indicator = row.indicator,
            isPinned = row.isPinned,
            isUnread = row.isUnread,
            launchedFromThisDevice = row.launchedFromThisDevice,
            isSnoozed = row.isSnoozed,
            snoozedAtMillis = row.snoozedAtMillis,
            memberCount = row.memberCount,
            isPlaceholder = row.isPlaceholder,
            isStandIn = row.isStandIn,
            children = row.children.map { of(it, nested = true) },
        )
    }
}

/**
 * Keeps [NewChatPageSnapshot] between launches, in the cache tree a sign-out wipes. [warm] starts the one read of the
 * file, off the main thread, as the application is created; the first screen's [seed] takes what it found, waiting
 * for it at most [maxWaitMs] — the read is a few kilobytes, long done by the time a launch's splash screen lifts — and
 * never reading the file itself. Within one process the snapshot last saved is what [seed] answers, so an activity
 * built again over a live process opens on the page as it was a moment ago.
 */
class NewChatPageCache(
    private val cache: JsonDiskCache,
    private val maxWaitMs: Long = MAX_WAIT_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val read = CompletableFuture<NewChatPageSnapshot?>()
    @Volatile private var started = false
    /** This process's word, once it has one: the last snapshot saved, or null after [forget]. */
    @Volatile private var latest: Held? = null

    private class Held(val snapshot: NewChatPageSnapshot?)

    /** Starts reading the file, once; for `Application.onCreate`. */
    fun warm() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch {
            read.complete(runCatching { cache.read(KEY, NewChatPageSnapshot.serializer(), VERSION)?.value }.getOrNull())
        }
    }

    /** The page as [user]'s account last left it, or null: none saved, another account's, or not read in time. */
    fun seed(user: CursorUser?): NewChatPageSnapshot? {
        user ?: return null
        val held = latest
        val snapshot = if (held != null) {
            held.snapshot
        } else {
            warm()
            runCatching { read.get(maxWaitMs, TimeUnit.MILLISECONDS) }.getOrNull()
        }
        return snapshot?.takeIf { it.account == accountOf(user) }
    }

    /**
     * Keeps [snapshot] as the page now stands: for this process at once, and on disk unless it is what the disk
     * already holds. A save taken before a sign-out's wipe lands nowhere.
     */
    suspend fun save(snapshot: NewChatPageSnapshot) {
        val token = cache.token()
        val held = latest
        if ((if (held != null) held.snapshot else read.getNow(null)) == snapshot) return
        if (cache.isStale(token)) return
        latest = Held(snapshot)
        cache.write(KEY, NewChatPageSnapshot.serializer(), VERSION, snapshot, token)
    }

    /** The account signed out: nothing of its page is drawn again, whatever the file read finds. */
    fun forget() {
        latest = Held(null)
    }

    companion object {
        private const val KEY = "page"
        private const val VERSION = 1
        const val MAX_WAIT_MS = 300L

        /** Whose page a snapshot is: the account's email, else the key's name. */
        fun accountOf(user: CursorUser): String = user.email ?: user.apiKeyName
    }
}
