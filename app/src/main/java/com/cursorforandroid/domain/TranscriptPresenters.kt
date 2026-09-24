package com.cursorforandroid.domain

/**
 * One [TranscriptPresenter] per chat, kept across the screens that show it. A presenter holds the last
 * presentation's segments — the rows of every turn as last cut — and answers an unchanged transcript from them, so
 * a screen reopening on a chat has its rows on the first frame instead of an empty list while the whole transcript
 * is cut again off the main thread (a long chat's is a noticeable blank). The few most recently opened chats keep
 * theirs; the rest start afresh, as every screen did until 0.3.47.
 */
class TranscriptPresenters(private val keep: Int = KEEP) {
    private val presenters = object : LinkedHashMap<String, TranscriptPresenter>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TranscriptPresenter>): Boolean = size > keep
    }

    @Synchronized
    fun forAgent(agentId: String): TranscriptPresenter = presenters.getOrPut(agentId) { TranscriptPresenter() }

    /** The chat's presenter when a screen has presented it before, else null. */
    @Synchronized
    fun warm(agentId: String): TranscriptPresenter? = presenters[agentId]?.takeIf { it.isWarm }

    @Synchronized
    fun forget(agentId: String) {
        presenters.remove(agentId)
    }

    private companion object {
        /** Chats whose presentation is kept: the ones a reader flips between. */
        const val KEEP = 6
    }
}
