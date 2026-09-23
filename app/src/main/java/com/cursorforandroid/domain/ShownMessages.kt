package com.cursorforandroid.domain

/**
 * The published transcript's one hard rule for a Project coordinator's word to the user: a message shown with its
 * body stays shown. A newer read may move it to its own turn, grow it, give it in another copy, or read the same call
 * of the same turn in other words (a record corrected, a chat reloaded); none may take it off the screen.
 *
 * Every source a chat is drawn from knows the coordinator's messages in its own shape, or not at all — the account's
 * record by the `SendMessage` calls of a turn, a run's log and its stream by their events, the saved copy by what
 * it saved — and the transcript is rebuilt whole from whichever of them answered last. Until 0.3.86 nothing kept
 * what an earlier build of the list had shown: a record piece that failed this time, a log that carried the call in
 * no shape this app reads, a turn given another turn's run, each published the turn without its message, and the
 * message was gone until a read happened to bring it back (Bennett, 2026-09-23: "sometimes they come back, sometimes
 * they don't"). Here, whatever the sources say, the list published ([keep]) holds every message shown before it.
 *
 * A message is remembered where it was last shown: under the prompt (or the injected turn's row) that opens its turn,
 * after the row it followed. Taken off the list, it is put back there — after that row when it is still in the turn,
 * else ahead of the turn's footer, else at the turn's end — for as long as the turn is shown. The turn is found by
 * its row's id, and by its words only where the row's id was bound to change: a prompt sent from here whose copy the
 * server now gives ([standsIn]), or a list now built from another source (the record's turns and the run logs name
 * a prompt differently). By its words alone, an older "continue" paged out of the window would hand its reply to the
 * newest. A turn no longer shown (the window paged past it) shows none of its messages; a turn the chat was rewound
 * past — its place taken by another prompt — forgets them.
 */
class ShownMessages(
    private val capacity: Int = CAPACITY,
    /** A row id that stands for a prompt until the server's copy of it replaces the row (an echo sent from here). */
    private val standsIn: (String) -> Boolean = { false },
) {

    private class Shown(val sent: CoordinatorTranscript.Sent, val headId: String?, val head: String?, val afterId: String?, val source: String)

    /** By text, the latest shown last. */
    private val shown = LinkedHashMap<String, Shown>()

    /** The text a call last showed in its turn, by the turn's words and the call's id: its newer reading replaces it. */
    private val byCall = HashMap<String, String>()

    val size: Int get() = shown.size

    fun clear() {
        shown.clear()
        byCall.clear()
    }

    /**
     * [items] — built from [source] — with every message shown before and missing from them put back (see
     * [ShownMessages]); the same list when none is.
     */
    fun keep(items: List<TimelineItem>, source: String = ""): List<TimelineItem> {
        var present: MutableList<CoordinatorTranscript.Sent>? = null
        var headId: String? = null
        var head: String? = null
        var previous: String? = null
        for (item in items) {
            when (item) {
                is UserMessage, is SystemNotification -> { headId = item.id; head = headKey(item) }
                is ActivityGroup -> for (step in item.steps) {
                    val sent = CoordinatorTranscript.sentBy(item, step) ?: continue
                    (present ?: ArrayList<CoordinatorTranscript.Sent>().also { present = it }) += sent
                    shown.remove(sent.text)
                    reworded(sent, head)?.let { shown.remove(it) }
                    shown[sent.text] = Shown(sent, headId, head, previous, source)
                }
                else -> Unit
            }
            previous = item.id
        }
        while (shown.size > capacity) shown.remove(shown.keys.first())
        if (byCall.size > 2 * capacity) byCall.values.retainAll(shown.keys)
        if (shown.isEmpty()) return items
        val have = present.orEmpty()
        val missing = shown.values.filter { s -> have.none { it.sameAs(s.sent) } }
        if (missing.isEmpty()) return items

        val heads = items.indices.filter { items[it] is UserMessage || items[it] is SystemNotification }
        val headById = HashMap<String, Int>(heads.size * 2)
        val headByKey = HashMap<String, Int>(heads.size * 2)
        heads.forEach { i -> headById[items[i].id] = i; headByKey[headKey(items[i])!!] = i }
        val at = HashMap<Int, MutableList<TimelineItem>>()
        val forgotten = ArrayList<String>()
        for (s in missing) {
            val id = s.headId ?: continue
            val byId = headById[id]
            val h = when {
                byId != null && headKey(items[byId]) == s.head -> byId
                // Another prompt where this one stood: the chat was rewound past the turn.
                byId != null -> { forgotten += s.sent.text; continue }
                s.source != source || standsIn(id) -> s.head?.let { headByKey[it] } ?: continue
                else -> continue
            }
            val next = heads.firstOrNull { it > h } ?: items.size
            val section = items.subList(h, next)
            // The turn shows a message read leniently, or this one was: the two readings of one message need not read alike.
            val sentHere = CoordinatorTranscript.sent(section)
            if (sentHere.any { it.recovered } || (s.sent.recovered && sentHere.isNotEmpty())) continue
            val after = s.afterId?.let { a -> section.indexOfFirst { it.id == a } }?.takeIf { it >= 0 }
            val footer = section.indexOfLast { it is RunFooter }.takeIf { it > 0 }
            val index = h + (after?.plus(1) ?: footer ?: section.size)
            at.getOrPut(index) { ArrayList(1) } += ActivityGroup("${s.sent.group.id}$SHOWN_SUFFIX${s.sent.call.callId}", listOf(s.sent.call))
        }
        forgotten.forEach { shown.remove(it) }
        if (at.isEmpty()) return items
        val out = ArrayList<TimelineItem>(items.size + at.values.sumOf { it.size })
        for (i in 0..items.size) {
            at[i]?.let { out.addAll(it) }
            if (i < items.size) out += items[i]
        }
        return out
    }

    /** The text [sent]'s call showed before in the turn under [head], where it read otherwise then. */
    private fun reworded(sent: CoordinatorTranscript.Sent, head: String?): String? {
        val call = sent.call.callId.takeIf { it.isNotBlank() } ?: return null
        val before = byCall.put("$head\n$call", sent.text)?.takeIf { it != sent.text } ?: return null
        return before.takeIf { shown[it]?.let { s -> s.sent.call.callId == call && s.head == head } == true }
    }

    private fun headKey(item: TimelineItem): String? = when (item) {
        is UserMessage -> "u:" + CoordinatorTranscript.normalize(item.text)
        is SystemNotification -> "s:" + CoordinatorTranscript.normalize(item.raw)
        else -> null
    }

    companion object {
        /** How many messages are remembered: well past any window, the oldest let go first. */
        const val CAPACITY = 512
        /** What a group holding a message put back is named after its own group's id. */
        const val SHOWN_SUFFIX = "~shown-"
    }
}
