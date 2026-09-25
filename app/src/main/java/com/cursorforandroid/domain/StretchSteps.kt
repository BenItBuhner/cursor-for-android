package com.cursorforandroid.domain

/**
 * The rows of an open [TranscriptRow.Stretch] as rows of the list's own. Through 0.3.93 an open stretch was one item of
 * the transcript's lazy list with every step in a column inside it: opening one of four hundred calls between two
 * thoughts of a few hundred kilobytes composed, measured and laid out all of it on the frame of the tap (832 ms of
 * main thread on the JVM, Bennett's phone stalling), and every frame of a live stretch left open laid it out again.
 * Listed here, its steps are items: the list composes the ones on screen and a few past them, and a step already
 * shown is neither recomposed nor measured again when the next one lands. A thought is listed in pieces (see
 * [ThoughtChunks]), so a trace being streamed lays out its newest lines alone.
 *
 * The other steps go [CHUNK] to an item: an item is a composition and a layout of its own, several times the cost of
 * one more line in a column, and a live stretch lands a dozen calls a frame. [CHUNK] steps in a row since the last
 * thought share an item, keyed by its first, so a call landing joins the last item — the ones before it skipped —
 * and only every [CHUNK]th starts a new one.
 *
 * The spacing is the column's: [TranscriptRow.Step.gapAbove], its paddings and [TranscriptRow.Step.endPad] carry what
 * its padding, its arrangement and a thought's own padding put around each item; steps sharing one are [GAP] apart.
 */
object StretchSteps {

    /** Space above an open stretch's first step and between two steps, and below its last, in dp — the stretch's column's. */
    const val FIRST_GAP = 6
    const val GAP = 2
    const val END_PAD = 4
    /** A thought's own padding above and below its text in an open stretch, in dp. */
    const val THOUGHT_PAD = 4
    /** Steps to an item, at most. */
    const val CHUNK = 32

    /** What a stretch shows below its summary line, when it is open: its steps, or a lone thought's text. */
    enum class Open { Closed, Steps, Thought }

    /**
     * [rows] with the steps of every open stretch listed after it. [open] says which are open. [previous] is the
     * last answer's steps by key: a step that says the same is that instance, so the list skips it.
     */
    fun list(rows: List<TranscriptRow>, open: (TranscriptRow.Stretch) -> Open, previous: Map<String, TranscriptRow.Step> = emptyMap()): Listed {
        var out: ArrayList<TranscriptRow>? = null
        val steps = HashMap<String, TranscriptRow.Step>()
        rows.forEachIndexed { index, row ->
            val stretch = row as? TranscriptRow.Stretch
            val how = stretch?.let(open) ?: Open.Closed
            if (how == Open.Closed) {
                out?.add(row)
                return@forEachIndexed
            }
            val list = out ?: ArrayList<TranscriptRow>(rows.size + 64).also { fresh -> fresh.addAll(rows.subList(0, index)); out = fresh }
            list += row
            val before = list.size
            when (how) {
                Open.Steps -> stepsOf(stretch!!, list)
                Open.Thought -> thoughtOf(stretch!!, list)
                Open.Closed -> Unit
            }
            for (i in before until list.size) {
                val step = list[i] as TranscriptRow.Step
                val kept = previous[step.key]?.takeIf { it == step } ?: step
                list[i] = kept
                steps[kept.key] = kept
            }
        }
        return Listed(out ?: rows, steps)
    }

    /** The rows listed, and their steps by key for the next answer (see [list]). */
    class Listed(val rows: List<TranscriptRow>, val steps: Map<String, TranscriptRow.Step>)

    private fun stepsOf(stretch: TranscriptRow.Stretch, out: MutableList<TranscriptRow>) {
        val listed = stretch.listed
        val start = out.size
        var run = ArrayList<TranscriptRow.Entry>(CHUNK)
        var runGap = FIRST_GAP
        fun flush() {
            if (run.isEmpty()) return
            out += TranscriptRow.Step("step:${run.first().key}", stretch.key, run, null, runGap, 0, stretch.live)
            run = ArrayList(CHUNK)
        }
        listed.forEachIndexed { i, entry ->
            val gap = if (i == 0) FIRST_GAP else GAP
            if (entry is TranscriptRow.Entry.Thought) {
                flush()
                thoughtParts(stretch, entry, gap, THOUGHT_PAD, THOUGHT_PAD, out)
            } else {
                if (run.isEmpty()) runGap = gap
                run += entry
                if (run.size == CHUNK) flush()
            }
        }
        flush()
        if (out.size > start) {
            val last = out.last() as TranscriptRow.Step
            out[out.lastIndex] = last.copy(endPad = END_PAD)
        }
    }

    /** A lone thought, open: its text below its line, as `ThoughtDisclosure` pads it. */
    private fun thoughtOf(stretch: TranscriptRow.Stretch, out: MutableList<TranscriptRow>) {
        val entry = stretch.single as? TranscriptRow.Entry.Thought ?: return
        thoughtParts(stretch, entry, FIRST_GAP, 0, END_PAD, out)
    }

    private fun thoughtParts(stretch: TranscriptRow.Stretch, entry: TranscriptRow.Entry.Thought, gapAbove: Int, padTop: Int, padBottom: Int, out: MutableList<TranscriptRow>) {
        val parts = entry.parts
        parts.forEachIndexed { p, text ->
            out += TranscriptRow.Step(
                key = "step:${entry.key}#$p",
                stretchKey = stretch.key,
                entries = emptyList(),
                text = text,
                gapAbove = if (p == 0) gapAbove else 0,
                padBottom = if (p == parts.lastIndex) padBottom else 0,
                live = stretch.live,
                padTop = if (p == 0) padTop else 0,
            )
        }
    }
}

/**
 * A thought's text in pieces that, stacked with nothing between them, draw exactly the lines the whole would: each
 * cut is at a line break, which is dropped, the next piece starting on the line after it (a blank line included).
 * A piece is about [TARGET] characters, so laying one out is cheap and a thought still being written changes its last
 * piece or two alone; the cuts before them depend only on the text before them and do not move as it grows. A single
 * line longer than [MAX_LINE] — no line break in reach — is cut at a space, where it would otherwise have wrapped on.
 */
object ThoughtChunks {
    const val TARGET = 1_500
    const val MAX_LINE = 4_000
    private const val REMEMBERED = 16

    // Every publication makes the stretch's entries anew, a finished thought's with the same text: cut once, not per frame.
    private val cut = object : LinkedHashMap<String, List<String>>(REMEMBERED * 2, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > REMEMBERED
    }

    /** [text], trimmed, as [split] cuts it; the last few texts cut are remembered. */
    fun of(text: String): List<String> {
        if (text.length <= TARGET) return listOf(text.trim())
        synchronized(cut) { cut[text] }?.let { return it }
        return split(text.trim()).also { parts -> synchronized(cut) { cut[text] = parts } }
    }

    fun split(text: String): List<String> {
        if (text.length <= TARGET) return listOf(text)
        val out = ArrayList<String>(text.length / TARGET + 2)
        var start = 0
        while (start < text.length) {
            if (text.length - start <= TARGET) {
                out += text.substring(start)
                break
            }
            val reach = minOf(text.length, start + MAX_LINE)
            val forward = text.indexOf('\n', start + TARGET).takeIf { it in 0 until reach }
            if (forward != null) {
                out += text.substring(start, forward)
                start = forward + 1
                continue
            }
            val back = text.lastIndexOf('\n', start + TARGET - 1)
            if (back > start) {
                out += text.substring(start, back)
                start = back + 1
                continue
            }
            if (reach == text.length) {
                out += text.substring(start)
                break
            }
            val space = text.lastIndexOf(' ', reach - 1)
            if (space > start) {
                out += text.substring(start, space)
                start = space + 1
            } else {
                out += text.substring(start, reach)
                start = reach
            }
        }
        return out
    }
}

