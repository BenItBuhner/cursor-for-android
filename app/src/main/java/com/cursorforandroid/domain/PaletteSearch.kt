package com.cursorforandroid.domain

/** A chat or Project the search palette can find, as the list has it. */
data class PaletteEntry(
    val agentId: String,
    val title: String,
    val repo: String?,
    val isProject: Boolean,
    val updatedAtMillis: Long,
)

/**
 * A stretch of a chat's transcript as this device keeps it: a prompt, a reply, a thought, a tool call's line. [itemId]
 * is the timeline item it is drawn in — a message's own id, or the activity group a thought or call sits in — so an
 * opened hit can be scrolled to; null when nothing in the transcript is known to carry it.
 */
class TranscriptPassage(val itemId: String?, val text: String) {
    /** [text] lower-cased once, when that keeps every index where it was (it almost always does); else null. */
    internal val folded: String? = text.lowercase().takeIf { it.length == text.length }

    fun indexOf(needle: String, from: Int = 0): Int = folded?.indexOf(needle, from) ?: text.indexOf(needle, from, ignoreCase = true)

    fun lastIndexOf(needle: String): Int = folded?.lastIndexOf(needle) ?: text.lastIndexOf(needle, ignoreCase = true)

    override fun equals(other: Any?): Boolean = other is TranscriptPassage && other.itemId == itemId && other.text == text
    override fun hashCode(): Int = 31 * (itemId?.hashCode() ?: 0) + text.hashCode()
    override fun toString(): String = "TranscriptPassage($itemId, ${text.take(40)})"
}

/** A line of text with one stretch of it to highlight: the match. */
data class Snippet(val text: String, val matchStart: Int, val matchEnd: Int)

/** Where in a chat's transcript the query was found: the newest place ([itemId], [needle]) and how many places in all. */
data class TranscriptHit(val itemId: String?, val needle: String, val matches: Int)

/** One row of the palette: the chat, what matched in its title (to highlight), and for a transcript match its snippet and hit. */
data class PaletteResult(
    val entry: PaletteEntry,
    val titleMatch: IntRange? = null,
    val snippet: Snippet? = null,
    val hit: TranscriptHit? = null,
)

/**
 * The search palette's matching (Ctrl+F): a chat's title, then its repository, then what its transcript says as kept
 * on this device. Every word typed must be in the title (or the title and the repository together) for the chat to
 * match by name; the transcript is searched for the words as typed, together, since a phrase is what a reader
 * remembers of a conversation. One row per chat, name matches first — a title that starts with the query above one
 * that only contains it, Projects a nose ahead of chats — then transcript matches; the most recently active first
 * among equals. A transcript match names its newest place, which is where a chat opens, with a snippet around it.
 */
object PaletteSearch {
    const val DEFAULT_LIMIT = 50

    fun search(
        query: String,
        entries: List<PaletteEntry>,
        transcripts: Map<String, List<TranscriptPassage>>,
        limit: Int = DEFAULT_LIMIT,
    ): List<PaletteResult> {
        val phrase = normalize(query)
        if (phrase.isEmpty()) return emptyList()
        val words = phrase.split(' ')
        val scored = ArrayList<Pair<Int, PaletteResult>>()
        for (entry in entries) {
            val title = entry.title.lowercase()
            val repo = entry.repo?.lowercase().orEmpty()
            val nameScore = when {
                words.all { it in title } -> when {
                    title.startsWith(phrase) -> 400
                    title.split(' ', '-', '_', '/', '.').any { it.startsWith(words.first()) } -> 350
                    else -> 300
                }
                words.all { it in title || it in repo } -> 200
                else -> 0
            }
            if (nameScore > 0) {
                val at = title.indexOf(words.first()).takeIf { it >= 0 && title.length == entry.title.length }
                val titleMatch = at?.let { it until it + words.first().length }
                scored += (nameScore + if (entry.isProject) 10 else 0) to PaletteResult(entry, titleMatch = titleMatch)
                continue
            }
            val passages = transcripts[entry.agentId] ?: continue
            val hit = transcriptHit(phrase, passages) ?: continue
            scored += 100 to hit.let { (passage, found) ->
                val at = passage.lastIndexOf(phrase)
                PaletteResult(entry, snippet = snippet(passage.text, at, at + phrase.length), hit = found)
            }
        }
        return scored
            .sortedWith(compareByDescending<Pair<Int, PaletteResult>> { it.first }.thenByDescending { it.second.entry.updatedAtMillis })
            .take(limit)
            .map { it.second }
    }

    /** The newest passage holding [phrase] and the hit it makes, with every occurrence in the transcript counted. */
    private fun transcriptHit(phrase: String, passages: List<TranscriptPassage>): Pair<TranscriptPassage, TranscriptHit>? {
        var newest: TranscriptPassage? = null
        var count = 0
        for (passage in passages) {
            var at = passage.indexOf(phrase)
            if (at < 0) continue
            newest = passage
            while (at >= 0) {
                count++
                at = passage.indexOf(phrase, at + phrase.length)
            }
        }
        return newest?.let { it to TranscriptHit(it.itemId, phrase, count) }
    }

    /** The query as matched: lower-cased, its runs of spaces one space, trimmed. */
    fun normalize(query: String): String = query.trim().lowercase().replace(WHITESPACE, " ")

    /**
     * About [width] characters of [text] around the match at [start] until [end], starting a word or so before it:
     * line breaks and runs of spaces as one space, an ellipsis where the text goes on.
     */
    fun snippet(text: String, start: Int, end: Int, lead: Int = SNIPPET_LEAD, width: Int = SNIPPET_WIDTH): Snippet {
        val from = if (start <= lead) 0 else text.lastIndexOf(' ', start - lead).let { if (it < 0 || it < start - lead * 2) start - lead else it + 1 }
        val to = minOf(text.length, maxOf(end, from + width))
        val before = collapse(text.substring(from, start)).trimStart()
        val match = collapse(text.substring(start, end))
        val after = collapse(text.substring(end, to)).trimEnd()
        val open = if (from > 0) "…" else ""
        val close = if (to < text.length) "…" else ""
        val lineStart = open.length + before.length
        return Snippet(open + before + match + after + close, lineStart, lineStart + match.length)
    }

    private fun collapse(s: String): String = s.replace(WHITESPACE, " ")

    private val WHITESPACE = Regex("\\s+")
    private const val SNIPPET_LEAD = 36
    private const val SNIPPET_WIDTH = 140
}
