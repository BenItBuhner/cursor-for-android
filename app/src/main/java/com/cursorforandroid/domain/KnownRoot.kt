package com.cursorforandroid.domain

import kotlinx.serialization.Serializable

/**
 * A Cursor Project's coordinator as the root registry knows it, whether or not the list holds its row, with the
 * evidence it stands on. The registry admits a root on the record's own word alone (see
 * [LineageSignal.isRootEvidence]): the record's Project flag ([flagged]), a worker's record or a membership answer
 * naming it as manager ([managerOf]), or an action taken here that made it one ([signal] = ACTION). A coordinator's
 * transcript, a `create_agent` call, a children list (ordinary chats have subagents and side chats too) or a source
 * (a "meta agent" is how a chat was started) put nothing in it — which is what kept ordinary chats out of the
 * Projects group again after 0.3.6 let them in. The registry is kept on disk with the list, so the Projects group
 * can list every Project the account has, not only the ones whose rows the loaded pages hold; each entry is
 * re-validated against the evidence it carries, and leaves when the evidence goes: the record no longer flagged and
 * no worker naming it, a membership answer with nothing in it and no worker record behind it, or the chat gone.
 */
@Serializable
data class KnownRoot(
    val id: String,
    /** The Project's name as its record last gave it; null when only its id has been named so far. */
    val name: String? = null,
    val appearance: ProjectAppearance? = null,
    /** The record's archive flag: an archived Project is listed only while the Archived filter is on, like any archived chat. */
    val archived: Boolean = false,
    /** What named it (see [LineageSignal]); root evidence only. */
    val signal: LineageSignal,
    /** When a source last named it, epoch millis; zero when unknown (a registry restored from an older disk copy). */
    val lastSeenMillis: Long = 0L,
    /** The record's own Project flag (`projectMetadata` / started as a new Project) was seen on it. */
    val flagged: Boolean = false,
    /** How many workers' records or membership rows named it as their manager the last time any did. */
    val managerOf: Int = 0,
) {
    /** The evidence holds: the record flags it, a worker names it, or an action here made it one. */
    val isEvidenced: Boolean get() = flagged || managerOf > 0 || signal == LineageSignal.ACTION

    /** The evidence in words, for the diagnostics. */
    val evidence: String
        get() = buildList {
            if (flagged) add("record flag")
            if (managerOf > 0) add("manager of $managerOf")
            if (signal == LineageSignal.ACTION) add("action here")
        }.joinToString(" + ").ifEmpty { "none" }

    /**
     * Merges a later word about the same root: names and looks are taken when given, the flag and the manager
     * count kept unless the later word is the same source's retraction, and the signal kept is the one closest to
     * the root itself — its own record first, then an action taken here, then what its workers said of it.
     */
    fun merged(later: KnownRoot): KnownRoot = KnownRoot(
        id = id,
        name = later.name?.takeIf { it.isNotBlank() } ?: name,
        appearance = later.appearance ?: appearance,
        archived = if (later.signal == LineageSignal.ACCOUNT_RECORD && later.flagged) later.archived else archived,
        signal = if (rank(later.signal) >= rank(signal)) later.signal else signal,
        lastSeenMillis = maxOf(lastSeenMillis, later.lastSeenMillis),
        flagged = flagged || later.flagged,
        managerOf = maxOf(managerOf, later.managerOf),
    )

    private companion object {
        fun rank(signal: LineageSignal): Int = when (signal) {
            LineageSignal.ACCOUNT_RECORD -> 5
            LineageSignal.ACTION -> 4
            LineageSignal.MEMBERSHIP -> 3
            LineageSignal.CHILDREN_LIST -> 2
            LineageSignal.COORDINATOR_CREATED -> 1
            LineageSignal.HIDDEN_SOURCE, LineageSignal.COORDINATOR_TRANSCRIPT -> 0
        }
    }
}
