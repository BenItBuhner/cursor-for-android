package com.cursorforandroid.domain

/**
 * The account's running agents as a pass made for that purpose found them, apart from whatever pages the sidebar
 * happens to hold: the documented `/v0/agents` list carries one execution status per agent and is read newest
 * first for a few pages on every refresh ([ids], [pagesRead], [complete] when the pass reached the end of the list);
 * in Extended mode the account's own list, read with its statuses, says the same of every composer in its window
 * ([accountIds], including a Project's workers and side chats). The live tracking, the running count and the
 * notifications are reconciled with this set — an agent it names that the loaded pages do not hold is fetched by
 * id — so what runs is not a function of how far the reader has scrolled.
 */
data class RunningScan(
    val ids: Set<String> = emptySet(),
    val scannedAtMillis: Long = 0L,
    val pagesRead: Int = 0,
    /** The pass read the list to its end: nothing beyond the pages could be running unseen. */
    val complete: Boolean = false,
    /** The account list's running composers, when Extended mode has read it; null when it has not. */
    val accountIds: Set<String>? = null,
    val accountAtMillis: Long = 0L,
    /**
     * The account's latest word on each composer it has named — running or not, and when it said so — kept per
     * composer, since a detail read names one at a time and the list only its window: what the send decision reads
     * for one chat (see `SendGate`), where the whole-set reading above would speak for chats it never covered.
     */
    val accountWord: Map<String, AccountWord> = emptyMap(),
) {
    /** One composer's status as the account gave it, and when. */
    data class AccountWord(val running: Boolean, val atMillis: Long)

    val hasScanned: Boolean get() = scannedAtMillis > 0L || accountAtMillis > 0L

    /** Every agent either pass called running. */
    val all: Set<String> get() = if (accountIds == null) ids else ids + accountIds
}
