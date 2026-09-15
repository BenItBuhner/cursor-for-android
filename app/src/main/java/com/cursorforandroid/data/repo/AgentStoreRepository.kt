package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.AgentStoreApi
import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.auth.SessionUnavailableException
import com.cursorforandroid.domain.AgentStoreKind
import com.cursorforandroid.domain.AgentStoreRef
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.ContextDocument
import com.cursorforandroid.domain.ContextEntry
import com.cursorforandroid.domain.ContextStores
import com.cursorforandroid.domain.RecentContextFile
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * The Agent Stores a chat's panel browses — the Project's Context and the user's own store — as the web's "Project"
 * and "All Files" tabs read them: which stores the account lists ([stores]), what a folder holds ([entries]), a
 * file's text ([document]), the Project's `notes.md` ([notes]) and the newest files across the stores with
 * thumbnails for the pictures ([recents]). Every read is an account-service call behind the `projects` capability, so
 * with Extended mode off each answers [VmRead.NotAvailable] without a call; the demo answers from its in-memory
 * stores. Listings are kept for [TTL_MS], file bodies for [FILE_TTL_MS]; [reset] forgets everything.
 */
class AgentStoreRepository(
    private val api: AgentStoreApi?,
    private val capabilities: suspend () -> Capabilities,
    private val isDemo: () -> Boolean = { false },
    private val demo: AgentStoreApi? = null,
    private val now: () -> Long = AppClock::now,
) {
    private class Cached<T>(val value: T, val at: Long)

    private val storeLists = HashMap<String, Cached<List<AgentStoreRef>>>()
    private val listings = HashMap<String, Cached<List<ContextEntry>>>()
    private val bodies = HashMap<String, Cached<String>>()
    private val locks = List(8) { Mutex() }

    private fun lock(key: String) = locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]

    /** The source every read goes to: the demo's stores in the demo, else the account's; null names why not. */
    private suspend fun source(): Pair<AgentStoreApi?, String?> {
        if (isDemo()) return if (demo != null) demo to null else null to NO_DEMO_STORE
        if (!capabilities().projects) return null to NEEDS_EXTENDED_MODE
        return if (api != null) api to null else null to NOT_WIRED
    }

    /** Every store the account lists for this user. */
    suspend fun stores(force: Boolean = false): VmRead<List<AgentStoreRef>> {
        val (source, refusal) = source()
        if (source == null) return VmRead.NotAvailable(refusal ?: NOT_WIRED)
        val key = "stores"
        if (!force) fresh(storeLists, key)?.let { return VmRead.Loaded(it) }
        return lock(key).withLock {
            if (!force) fresh(storeLists, key)?.let { return@withLock VmRead.Loaded(it) }
            read { source.stores() }.also { if (it is VmRead.Loaded) synchronized(storeLists) { storeLists[key] = Cached(it.value, now()) } }
        }
    }

    /**
     * The stores [agentId]'s panel roots: the Project's — the store whose source is [projectId] (the coordinator),
     * or the chat's own when it is nobody's worker — and the user's. A chat with no store of its own has only the user's.
     */
    suspend fun storesFor(agentId: String, projectId: String?, force: Boolean = false): VmRead<ContextStores> {
        val stores = when (val read = stores(force)) {
            is VmRead.Loaded -> read.value
            is VmRead.NotAvailable -> return read
            is VmRead.Failed -> return read
        }
        val owner = projectId ?: agentId
        val project = stores.firstOrNull { it.sourceId == owner } ?: stores.firstOrNull { it.sourceId == agentId }
        val user = stores.firstOrNull { it.kind == AgentStoreKind.USER }
        return VmRead.Loaded(ContextStores(project = project, user = user))
    }

    /** What [relativePath] ("" for the root) of [store] holds: folders first, then files, each by name. */
    suspend fun entries(store: AgentStoreRef, relativePath: String = "", force: Boolean = false): VmRead<List<ContextEntry>> {
        val (source, refusal) = source()
        if (source == null) return VmRead.NotAvailable(refusal ?: NOT_WIRED)
        val clean = relativePath.trim().trim('/')
        val key = "entries:${store.storeId}:$clean"
        if (!force) fresh(listings, key)?.let { return VmRead.Loaded(it) }
        return lock(key).withLock {
            if (!force) fresh(listings, key)?.let { return@withLock VmRead.Loaded(it) }
            read { source.entries(store.storeId, clean) }.also { if (it is VmRead.Loaded) synchronized(listings) { listings[key] = Cached(it.value, now()) } }
        }
    }

    /** The text of [relativePath] in [store], as a document the panel can show as a tab. */
    suspend fun document(store: AgentStoreRef, relativePath: String, force: Boolean = false): VmRead<ContextDocument> {
        val (source, refusal) = source()
        if (source == null) return VmRead.NotAvailable(refusal ?: NOT_WIRED)
        val clean = relativePath.trim().trim('/')
        val key = "file:${store.storeId}:$clean"
        if (!force) fresh(bodies, key, FILE_TTL_MS)?.let { return VmRead.Loaded(ContextDocument(store, clean, it)) }
        return lock(key).withLock {
            if (!force) fresh(bodies, key, FILE_TTL_MS)?.let { return@withLock VmRead.Loaded(ContextDocument(store, clean, it)) }
            when (val text = read { source.readFile(store.storeId, clean) }) {
                is VmRead.Loaded -> {
                    synchronized(bodies) { bodies[key] = Cached(text.value, now()) }
                    VmRead.Loaded(ContextDocument(store, clean, text.value))
                }
                is VmRead.NotAvailable -> text
                is VmRead.Failed -> text
            }
        }
    }

    /**
     * The Project's notes — the `notes.md` at the root of [store], which the web's "Project" tab renders — or null
     * when the store has none yet. The root is listed first so a store without notes is told apart from a failed read.
     */
    suspend fun notes(store: AgentStoreRef, force: Boolean = false): VmRead<ContextDocument?> {
        val root = when (val listing = entries(store, "", force)) {
            is VmRead.Loaded -> listing.value
            is VmRead.NotAvailable -> return listing
            is VmRead.Failed -> return listing
        }
        val notes = root.firstOrNull { !it.isDirectory && it.name.equals(NOTES_FILE, ignoreCase = true) } ?: return VmRead.Loaded(null)
        return when (val document = document(store, notes.relativePath, force)) {
            is VmRead.Loaded -> VmRead.Loaded(document.value)
            is VmRead.NotAvailable -> document
            is VmRead.Failed -> document
        }
    }

    /**
     * The newest [limit] files across [stores], walked [RECENTS_DEPTH] folders deep, with a URL for each picture the
     * account will presign (asked for by [agentId]); a picture the account would not presign, or any other file,
     * shows as a tile. A store whose listing fails is left out rather than failing the row.
     */
    suspend fun recents(agentId: String, stores: List<AgentStoreRef>, limit: Int = RECENTS_LIMIT, force: Boolean = false): VmRead<List<RecentContextFile>> {
        val (source, refusal) = source()
        if (source == null) return VmRead.NotAvailable(refusal ?: NOT_WIRED)
        val files = ArrayList<RecentContextFile>()
        for (store in stores) {
            var frontier = listOf("")
            repeat(RECENTS_DEPTH) {
                val next = ArrayList<String>()
                for (path in frontier) {
                    val listing = (entries(store, path, force) as? VmRead.Loaded)?.value ?: continue
                    listing.forEach { entry -> if (entry.isDirectory) next += entry.relativePath else files += RecentContextFile(store, entry) }
                }
                frontier = next
            }
        }
        val newest = files.sortedByDescending { it.entry.updatedAtMillis ?: 0L }.take(limit)
        val pictures = newest.filter { it.isImage }.groupBy { it.store }
        val urls = HashMap<Pair<String, String>, String>()
        pictures.forEach { (store, recent) ->
            val presigned = try {
                source.presignReads(agentId, store.storeId, recent.map { it.entry.relativePath })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                emptyMap()
            }
            presigned.forEach { (path, url) -> urls[store.storeId to path] = url }
        }
        return VmRead.Loaded(newest.map { recent -> recent.copy(thumbnailUrl = urls[recent.store.storeId to recent.entry.relativePath]) })
    }

    fun reset() {
        synchronized(storeLists) { storeLists.clear() }
        synchronized(listings) { listings.clear() }
        synchronized(bodies) { bodies.clear() }
    }

    private suspend fun <T> read(block: suspend () -> T): VmRead<T> = try {
        VmRead.Loaded(block())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        failure(t)
    }

    private fun <T> fresh(map: HashMap<String, Cached<T>>, key: String, ttl: Long = TTL_MS): T? = synchronized(map) {
        map[key]?.takeIf { now() - it.at < ttl }?.value
    }

    companion object {
        const val TTL_MS = 30_000L
        const val FILE_TTL_MS = 5 * 60_000L
        const val NOTES_FILE = "notes.md"
        const val RECENTS_LIMIT = 8
        const val RECENTS_DEPTH = 3

        const val NEEDS_EXTENDED_MODE = "Needs Extended mode: the Project's Context and your files are read through Cursor's account service."
        const val NOT_WIRED = "Context is not wired to the account service in this build."
        const val NO_DEMO_STORE = "The demo has no Context to show."
        const val ENDPOINT_CHANGED = "Cursor changed a private endpoint; Context is unavailable until the app is updated."

        /** The words a failed store read shows; a method Cursor no longer offers is named as such rather than as an error. */
        fun failure(t: Throwable): VmRead.Failed = when (t) {
            is SessionUnavailableException -> if (t.code == SessionUnavailableException.EXTENDED_MODE_OFF) VmRead.Failed(NEEDS_EXTENDED_MODE) else VmRead.Failed(t.message ?: "Cursor couldn't start a session for this key.")
            is ConnectRpcException -> when {
                t.httpCode == 404 || t.code == "unimplemented" -> VmRead.Failed(ENDPOINT_CHANGED, endpointChanged = true)
                t.code == "not_found" -> VmRead.Failed("The store has no such file any more.")
                t.code == "permission_denied" -> VmRead.Failed("Cursor would not open this store for this account.")
                else -> VmRead.Failed("Cursor refused (${t.message}).")
            }
            is IOException -> VmRead.Failed(t.userMessage())
            else -> VmRead.Failed(t.message ?: "Something went wrong.")
        }
    }
}
