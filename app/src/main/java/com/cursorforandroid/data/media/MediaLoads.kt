package com.cursorforandroid.data.media

import com.cursorforandroid.data.repo.AgentFileRepository
import com.cursorforandroid.domain.MediaLine
import com.cursorforandroid.domain.MediaRef
import com.cursorforandroid.domain.StorePath

/** Where a picture's read is, in the words the row waiting on it says. */
enum class MediaStage(val words: String) {
    STARTING("Starting"),
    STORE("Finding the Project's store"),
    LINK("Asking for the file's link"),
    DOWNLOAD("Downloading"),
    MACHINE("Reading it from the agent's machine"),
    FETCH("Fetching"),
    DECODE("Decoding"),
}

/**
 * The figures each chat asked [MediaLoader] for this process, where each one's read is and how it ended: what the
 * transcript diagnostics' `media:` section prints, so a report of pictures that never came says which source, which
 * step and how long. The newest [MAX_PER_CHAT] figures of the newest [MAX_CHATS] chats are kept.
 */
class MediaLoads(private val now: () -> Long = System::currentTimeMillis) {
    enum class State { LOADING, READY, FAILED, TIMED_OUT, LEFT }

    private class Load(val kind: String, val label: String) {
        var askedWidth = 0
        var askedHeight = 0
        var stage = MediaStage.STARTING
        var state = State.LOADING
        var startedAt = 0L
        var endedAt: Long? = null
        var decoded: String? = null
        var error: String? = null
        var attempts = 0
    }

    private val chats = object : LinkedHashMap<String, LinkedHashMap<String, Load>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LinkedHashMap<String, Load>>) = size > MAX_CHATS
    }

    fun started(chat: String, ref: MediaRef, width: Int, height: Int) = synchronized(chats) {
        val loads = chats.getOrPut(chat) {
            object : LinkedHashMap<String, Load>(16, 0.75f, false) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Load>) = size > MAX_PER_CHAT
            }
        }
        val load = loads.remove(ref.cacheKey) ?: Load(kindOf(ref), labelOf(ref))
        loads[ref.cacheKey] = load
        load.askedWidth = width
        load.askedHeight = height
        load.stage = MediaStage.STARTING
        load.state = State.LOADING
        load.startedAt = now()
        load.endedAt = null
        load.decoded = null
        load.error = null
        load.attempts++
    }

    fun stage(chat: String, ref: MediaRef, stage: MediaStage) = update(chat, ref) { it.stage = stage }

    fun ready(chat: String, ref: MediaRef, width: Int, height: Int) = update(chat, ref) {
        it.state = State.READY
        it.decoded = "${width}x$height"
        it.endedAt = now()
    }

    fun failed(chat: String, ref: MediaRef, problem: MediaProblem) = update(chat, ref) {
        it.state = if (problem is MediaProblem.TimedOut) State.TIMED_OUT else State.FAILED
        it.error = listOfNotNull(problem.title, problem.detail, problem.asked?.let { asked -> "asked $asked" }).joinToString(" · ")
        it.endedAt = now()
    }

    /** The row went away (scrolled off, the chat closed) before the read ended: the read was given up with it. */
    fun left(chat: String, ref: MediaRef) = update(chat, ref) {
        if (it.state == State.LOADING) {
            it.state = State.LEFT
            it.endedAt = now()
        }
    }

    /** The figures of [chat], in the order they were first asked for. */
    fun lines(chat: String): List<MediaLine> = synchronized(chats) {
        val at = now()
        chats[chat]?.values.orEmpty().map { load ->
            MediaLine(
                kind = load.kind,
                label = load.label,
                state = load.state.name.lowercase(),
                stage = load.stage.name.lowercase(),
                waitedMs = (load.endedAt ?: at) - load.startedAt,
                asked = "${load.askedWidth}x${load.askedHeight}",
                decoded = load.decoded,
                attempts = load.attempts,
                error = load.error,
            )
        }
    }

    fun clear() = synchronized(chats) { chats.clear() }

    private fun update(chat: String, ref: MediaRef, change: (Load) -> Unit) = synchronized(chats) {
        chats[chat]?.get(ref.cacheKey)?.let(change)
    }

    companion object {
        const val MAX_CHATS = 8
        const val MAX_PER_CHAT = 60

        /** The source of [ref] as the diagnostics name it; a link by its host alone. */
        fun kindOf(ref: MediaRef): String = when (ref) {
            is MediaRef.Remote -> "https:" + (runCatching { java.net.URI(ref.url).host }.getOrNull() ?: "?")
            is MediaRef.Artifact -> "artifact"
            is MediaRef.Store -> if (ref.ownerId == StorePath.SELF) "store:self" else "store"
            is MediaRef.Workspace -> if (AgentFileRepository.isInWorkspace(ref.path, null)) "workspace" else "machine"
            is MediaRef.Local -> "local"
            is MediaRef.Inline -> "data-uri"
            is MediaRef.Unavailable -> "unavailable"
        }

        private fun labelOf(ref: MediaRef): String = when (ref) {
            is MediaRef.Inline -> "${ref.mimeType ?: "image"} ${ref.bytes.size}B"
            else -> ref.label.ifBlank { "-" }
        }
    }
}
