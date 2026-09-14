package com.cursorforandroid.domain

import kotlinx.serialization.Serializable

/**
 * A Cursor Project's coordinator as the root registry knows it, whether or not the list holds its row. The registry
 * is fed by every source that names a root — the account's record of the chat (`projectMetadata`), a worker's record
 * naming it as manager, a membership answer, a coordinator's own transcript, an action taken here — and kept on disk
 * with the list, so the Projects group can list every Project the account has, not only the ones whose rows the
 * loaded pages happen to hold. A root leaves the registry only when the server says the chat is gone (a fetch by id
 * answers 404) or its own record no longer calls it a Project; silence never drops it.
 */
@Serializable
data class KnownRoot(
    val id: String,
    /** The Project's name as its record last gave it; null when only its id has been named so far. */
    val name: String? = null,
    val appearance: ProjectAppearance? = null,
    /** The record's archive flag: an archived Project is listed only while the Archived filter is on, like any archived chat. */
    val archived: Boolean = false,
    /** What named it (see [LineageSignal]); the account's record replaces a weaker word. */
    val signal: LineageSignal,
    /** When a source last named it, epoch millis; zero when unknown (a registry restored from an older disk copy). */
    val lastSeenMillis: Long = 0L,
) {
    /**
     * Merges a later word about the same root: names and looks are taken when given, and the signal kept is the one
     * closest to the root itself — its own record first, then an action taken here, then what its workers said of it.
     */
    fun merged(later: KnownRoot): KnownRoot = KnownRoot(
        id = id,
        name = later.name?.takeIf { it.isNotBlank() } ?: name,
        appearance = later.appearance ?: appearance,
        archived = if (later.signal == LineageSignal.ACCOUNT_RECORD) later.archived else archived,
        signal = if (rank(later.signal) >= rank(signal)) later.signal else signal,
        lastSeenMillis = maxOf(lastSeenMillis, later.lastSeenMillis),
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
