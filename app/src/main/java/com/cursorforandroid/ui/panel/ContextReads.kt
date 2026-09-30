package com.cursorforandroid.ui.panel

import com.cursorforandroid.data.repo.AgentStoreRepository
import com.cursorforandroid.data.repo.VmRead
import com.cursorforandroid.domain.AgentStoreRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Project tab's Context reads for one chat's panel ([ContextPanelState]): which stores the chat has, the Project's
 * notes, the folders listed so far and the Recents row, read through [stores] on [scope]. [projectRootId] is the
 * Project the chat belongs to as the panel knows it at the time of the read.
 */
internal class ContextReads(
    private val agentId: String,
    private val stores: AgentStoreRepository,
    private val scope: CoroutineScope,
    private val projectRootId: () -> String?,
) {
    val state = MutableStateFlow(ContextPanelState())

    private var job: Job? = null
    private val folderJobs = HashMap<String, Job>()
    /** A [refresh] asked for while one was out, and whether it said the Project moved; the one out runs again for it. */
    private var again: Boolean? = null

    /**
     * Reads which stores the chat has, then — in parallel — the Project's notes, both roots' listings and the
     * Recents row. Idempotent while a read is out; [force] re-reads through the repository's cache. Once the stores
     * are read, a load without [force] is a [refresh] through the cache.
     */
    fun load(force: Boolean = false) {
        if (!force && state.value.stores is RemoteLoad.Loaded) return refresh()
        if (job?.isActive == true && !force) return
        job?.cancel()
        again = null
        state.update { it.copy(stores = RemoteLoad.Loading) }
        job = scope.launch {
            val read = stores.storesFor(agentId, projectRootId(), force)
            val found = when (read) {
                is VmRead.Loaded -> read.value
                is VmRead.NotAvailable -> {
                    state.update { it.copy(stores = RemoteLoad.Unsupported(read.reason), notes = RemoteLoad.Unsupported(read.reason), recents = RemoteLoad.Unsupported(read.reason)) }
                    return@launch
                }
                is VmRead.Failed -> {
                    state.update { it.copy(stores = RemoteLoad.Failed(read.message, retryable = !read.endpointChanged)) }
                    return@launch
                }
            }
            // The roots open at once, as the web's tree does; deeper folders as they are tapped.
            state.update { c -> c.copy(stores = RemoteLoad.Loaded(found), expandedFolders = c.expandedFolders + found.all.map { ContextPanelState.folderKey(it, "") }) }
            found.all.forEach { store -> listFolder(store, "", force) }
            val project = found.project
            if (project != null) {
                state.update { it.copy(notes = RemoteLoad.Loading) }
                launch { state.update { it.copy(notes = stores.notes(project, force).toLoad()) } }
            } else {
                state.update { it.copy(notes = RemoteLoad.Loaded(null)) }
            }
            state.update { it.copy(recents = RemoteLoad.Loading) }
            launch { state.update { it.copy(recents = stores.recents(agentId, found.all, force = force).toLoad()) } }
        }
    }

    /**
     * Reads what the tab shows again without taking it off the screen: the stores, the open folders, the notes and
     * Recents, each put in place only when it is read, so a failed read leaves what was shown. [moved] says the
     * Project's coordinator has done something since, so the roots are listed again whatever their age; otherwise
     * every read goes through the repository's cache. The notes' text is read again only when the root's listing
     * stamps the file anew. Nothing before the stores are read.
     */
    fun refresh(moved: Boolean = false) {
        if (state.value.stores !is RemoteLoad.Loaded) return
        if (job?.isActive == true) {
            again = again == true || moved
            return
        }
        job = scope.launch {
            var force = moved
            while (true) {
                reread(force)
                force = again ?: break
                again = null
            }
        }
    }

    private suspend fun reread(moved: Boolean) {
        val shown = (state.value.stores as? RemoteLoad.Loaded)?.value ?: return
        val found = (stores.storesFor(agentId, projectRootId()) as? VmRead.Loaded)?.value ?: shown
        if (found != shown) {
            val added = found.all.filter { store -> shown.all.none { it.storeId == store.storeId } }
            state.update { c -> c.copy(stores = RemoteLoad.Loaded(found), expandedFolders = c.expandedFolders + added.map { ContextPanelState.folderKey(it, "") }) }
        }
        val open = state.value.expandedFolders
        val folders = found.all.flatMap { store -> open.mapNotNull { key -> key.removePrefix("${store.storeId}:").takeIf { it != key }?.let { store to it } } }
        val changed = coroutineScope {
            folders.map { (store, path) -> async { relist(store, path, force = moved && path.isEmpty()) } }.awaitAll().any { it }
        }
        coroutineScope {
            launch {
                val project = found.project
                val notes = if (project == null) RemoteLoad.Loaded(null) else (stores.notes(project) as? VmRead.Loaded)?.let { RemoteLoad.Loaded(it.value) }
                if (notes != null) state.update { it.copy(notes = notes) }
            }
            if (changed || found != shown || state.value.recents !is RemoteLoad.Loaded) launch {
                (stores.recents(agentId, found.all) as? VmRead.Loaded)?.let { read -> state.update { it.copy(recents = RemoteLoad.Loaded(read.value)) } }
            }
        }
    }

    /** Lists an open folder again in place; true when what it holds changed. A folder being listed already is left to that. */
    private suspend fun relist(store: AgentStoreRef, path: String, force: Boolean): Boolean {
        val key = ContextPanelState.folderKey(store, path)
        if (folderJobs[key]?.isActive == true) return false
        val read = (stores.entries(store, path, force) as? VmRead.Loaded)?.value ?: return false
        val was = (state.value.listings[key] as? RemoteLoad.Loaded)?.value
        state.update { it.copy(listings = it.listings + (key to RemoteLoad.Loaded(read))) }
        return was != read
    }

    /** Opens or closes a folder of the tree; an opened folder not yet listed is listed, one listed before is listed again in place through the cache. */
    fun toggleFolder(store: AgentStoreRef, path: String) {
        val key = ContextPanelState.folderKey(store, path)
        val expanding = key !in state.value.expandedFolders
        state.update { it.copy(expandedFolders = if (expanding) it.expandedFolders + key else it.expandedFolders - key) }
        if (!expanding) return
        if (state.value.listings[key] !is RemoteLoad.Loaded) listFolder(store, path) else scope.launch { relist(store, path, force = false) }
    }

    private fun listFolder(store: AgentStoreRef, path: String, force: Boolean = false) {
        val key = ContextPanelState.folderKey(store, path)
        if (folderJobs[key]?.isActive == true && !force) return
        folderJobs[key]?.cancel()
        state.update { it.copy(listings = it.listings + (key to RemoteLoad.Loading)) }
        folderJobs[key] = scope.launch {
            val load = stores.entries(store, path, force).toLoad()
            state.update { it.copy(listings = it.listings + (key to load)) }
        }
    }
}
