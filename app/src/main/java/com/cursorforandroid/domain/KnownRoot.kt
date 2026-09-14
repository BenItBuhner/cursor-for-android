package com.cursorforandroid.domain

import com.cursorforandroid.data.api.RecordFields
import kotlinx.serialization.Serializable

/**
 * A Cursor Project's coordinator as the root registry knows it, whether or not the list holds its row, with the
 * evidence it stands on. The registry admits a root on three words and no other (see [LineageSignal.isRootEvidence]):
 *
 *  - the record's Project flag as the desktop reads it ([flagged], the record's raw fields in [record]): the
 *    `project_metadata` message present on a record with no subagent parent, side-chat parent or manager (Cursor
 *    3.20.21 `workbench.glass.main.js`, `CloudAgentRepository` / `_isProjectRoot`); `startedAsNewProject` is no flag,
 *    and the appearance is no part of it;
 *  - `ListWorkersForManager` answering with at least one active worker ([membershipWorkers]);
 *  - an explicit action in this app that made it a Project's coordinator — a worker created under it or a chat
 *    adopted into it ([signal] = ACTION).
 *
 * A worker's record naming it as manager makes it a candidate for the membership read ([namedBy], information, not
 * evidence); a coordinator's transcript, a `create_agent` call, a children list (ordinary chats have subagents and
 * side chats too), a source, a pin, or the appearance editor put nothing in it. The registry is kept on disk with
 * the list; each entry is re-validated against the evidence it carries and leaves when the evidence goes.
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
    /** The record's Project flag was seen on it (the desktop's predicate). */
    val flagged: Boolean = false,
    /** The raw values of the fields the predicate read, as the record carried them when it flagged; null when the entry predates them. */
    val record: RecordFields? = null,
    /** Active workers the last `ListWorkersForManager` answer named. */
    val membershipWorkers: Int = 0,
    /** Workers' records naming it as manager the last time any did: a candidate for the membership read, not evidence. */
    val namedBy: Int = 0,
) {
    /**
     * The evidence holds: the record flags it, a membership answer names a worker, or an action here made it one.
     * [flagged] is only ever set from a record read by the desktop's predicate; an entry restored from an older
     * build's disk is held to [record] as well (see `AgentRepository.restoreFromCache`).
     */
    val isEvidenced: Boolean get() = flagged || membershipWorkers > 0 || signal == LineageSignal.ACTION

    /** The evidence an entry from the disk is held to: a flag with its record's fields, a membership count, or an action. */
    val isEvidencedStrictly: Boolean get() = (flagged && record != null) || membershipWorkers > 0 || signal == LineageSignal.ACTION

    /** The evidence in words, for the diagnostics: exactly what admitted the root, then what is known beside it. */
    val evidence: String
        get() {
            val held = buildList {
                if (flagged) add("record flag project_metadata=${record?.projectMetadata ?: "?"}")
                if (membershipWorkers > 0) add("membership $membershipWorkers")
                if (signal == LineageSignal.ACTION) add("action here")
            }
            val beside = buildList {
                if (namedBy > 0) add("named by $namedBy worker record${if (namedBy == 1) "" else "s"} (not evidence)")
            }
            return (held.ifEmpty { listOf("none") } + beside).joinToString(" + ")
        }

    /**
     * Merges a later word about the same root: names and looks are taken when given; the flag follows the record's
     * later word, the membership count the later answer's, the record-naming count the later round's; the signal
     * kept is the one closest to the root itself — its own record first, then an action taken here, then what its
     * workers said of it.
     */
    fun merged(later: KnownRoot): KnownRoot = KnownRoot(
        id = id,
        name = later.name?.takeIf { it.isNotBlank() } ?: name,
        appearance = later.appearance ?: appearance,
        archived = if (later.signal == LineageSignal.ACCOUNT_RECORD && later.flagged) later.archived else archived,
        signal = if (rank(later.signal) >= rank(signal)) later.signal else signal,
        lastSeenMillis = maxOf(lastSeenMillis, later.lastSeenMillis),
        flagged = if (later.signal == LineageSignal.ACCOUNT_RECORD) later.flagged else flagged,
        record = if (later.signal == LineageSignal.ACCOUNT_RECORD) later.record ?: record else record,
        membershipWorkers = if (later.signal == LineageSignal.MEMBERSHIP) later.membershipWorkers else membershipWorkers,
        namedBy = maxOf(namedBy, later.namedBy),
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
