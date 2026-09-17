package com.cursorforandroid.ui.components

import com.cursorforandroid.domain.TranscriptPerf

/**
 * The block parse of every finished message, once. A `MarkdownText` used to parse its markdown on its first
 * composition and again each time its row scrolled back into the list — on the main thread, on the frame the row
 * appeared. The parse is keyed by the text itself (a message's text is the same string for as long as the message
 * stands, so the lookup is a hash and an identity check) and bounded by what it holds: the newest few hundred
 * messages, a few megabytes of text, the least recently drawn let go first. A message still being written is not
 * kept here — its text changes with every delta — it has `IncrementalMarkdown`.
 *
 * Filled ahead of the screen by the presenter ([prime]), so the newest page composes onto parses already made off
 * the main thread.
 */
object MarkdownCache {
    private val entries = object : LinkedHashMap<String, List<MdBlock>>(128, 0.75f, true) {}
    private var chars = 0L

    /** The blocks of [markdown], parsed now when the cache lacks them. */
    fun parse(markdown: String): List<MdBlock> {
        cached(markdown)?.let { return it }
        val startedAt = System.nanoTime()
        val blocks = MarkdownParser.parse(markdown)
        TranscriptPerf.focused?.markdownParsed(System.nanoTime() - startedAt)
        put(markdown, blocks)
        return blocks
    }

    /** The blocks of [markdown] when they are cached, else null (and no parse). */
    fun cached(markdown: String): List<MdBlock>? {
        val hit = synchronized(this) { entries[markdown] }
        if (hit != null) TranscriptPerf.focused?.markdownHit()
        return hit
    }

    /** Parses [markdown] into the cache when it is not there yet, for a message about to be drawn. */
    fun prime(markdown: String) {
        if (markdown.isBlank() || markdown.length > MAX_ENTRY_CHARS) return
        if (synchronized(this) { entries.containsKey(markdown) }) return
        val startedAt = System.nanoTime()
        val blocks = MarkdownParser.parse(markdown)
        TranscriptPerf.focused?.markdownParsed(System.nanoTime() - startedAt)
        put(markdown, blocks)
    }

    private fun put(markdown: String, blocks: List<MdBlock>) {
        if (markdown.length > MAX_ENTRY_CHARS) return
        synchronized(this) {
            if (entries.put(markdown, blocks) == null) chars += markdown.length
            val iterator = entries.entries.iterator()
            while ((entries.size > MAX_ENTRIES || chars > MAX_CHARS) && iterator.hasNext()) {
                val eldest = iterator.next()
                chars -= eldest.key.length
                iterator.remove()
            }
        }
    }

    fun clear() = synchronized(this) { entries.clear(); chars = 0L }

    /** How many parses are held, for the diagnostics and tests. */
    val size: Int get() = synchronized(this) { entries.size }

    private const val MAX_ENTRIES = 512
    private const val MAX_CHARS = 4L shl 20
    /** A text longer than this is not worth holding twice (its blocks hold most of it again); it is parsed when drawn. */
    private const val MAX_ENTRY_CHARS = 200_000
}
