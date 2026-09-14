package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import kotlinx.coroutines.CancellationException
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.AgentScope
import com.cursorforandroid.domain.AgentSource
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.domain.PullRequestState
import com.cursorforandroid.domain.RunStatus
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** The account's pinned agents as the server reports them; [loaded] false means the server skipped the pinned state. */
data class PinnedIds(val ids: Set<String>, val loaded: Boolean)

/**
 * One composer from `ListBackgroundComposers`: the name and archive flag the desktop Agents window and the iOS
 * app read, and the chat's place among Cursor Projects as the Agents Window derives it (see
 * [BackgroundComposerApi.snapshot]). [archived] is null when the record omitted `isArchived`.
 */
data class ComposerSnapshot(
    val id: String,
    val name: String? = null,
    val archived: Boolean? = null,
    /**
     * A Project's root, decided the way the Agents Window decides it (Cursor 3.20.21 `workbench.glass.main.js`,
     * `CloudAgentRepository`): `isProject` is the record's `project_metadata` message being present at all —
     * `isProject: t.projectMetadata !== void 0 ? true : previous?.isProject` — and a root is `isProject` with no
     * `subagentParentId`, which the desktop takes as `cloudSubagentParent.parentAgentId || sideChatInfo.parentBcId ||
     * managerAgentId` (`_isProjectRoot`). `startedAsNewProject` is carried by the desktop as its own field and never
     * read for `isProject`; the appearance is read apart (`wQp`: icon and colour both non-empty, else the previous
     * look) and is no part of the flag. [record] carries the raw values of exactly those fields, for the diagnostics.
     */
    val isProject: Boolean = false,
    val projectAppearance: ProjectAppearance? = null,
    /** The raw values of the fields the desktop's predicate reads, as the record carried them (see [RecordFields]). */
    val record: RecordFields? = null,
    /** The chat this one hangs off, and how; null for a chat of its own. */
    val parent: AgentParent? = null,
    /** Where the chat was started, when the record said (see [AgentSource]). */
    val source: AgentSource? = null,
    /** The agent is waiting on an answer to a question it asked (`hasPendingInteraction`). */
    val hasPendingInteraction: Boolean = false,
    /**
     * The chat's execution status as the account's list reports it (`aiserver.v1.BackgroundComposerStatus`, read
     * with `include_status`): running, creating, finished, error or expired; null when the list did not say.
     */
    val status: RunStatus? = null,
) {
    /** Where the chat belongs by this record's own lineage facts (see [AgentScope.of]). */
    val scope: AgentScope get() = AgentScope.of(isProject, parent, source)

    /** True when the record says a turn is going: the account's word on the running set. */
    val isRunning: Boolean get() = status?.isActive == true
}

/**
 * The fields of an `aiserver.v1.BackgroundComposer` record that the desktop's Project predicate reads, raw: the
 * `project_metadata` message as JSON (null when absent — the one thing `isProject` turns on; `{}` when present and
 * empty), `manager_agent_id`, `cloud_subagent_parent.parent_agent_id`, `side_chat_info.parent_bc_id` (the three the
 * desktop folds into `subagentParentId`), `started_as_new_project` (carried, not read) and `source`. Kept with the
 * registry's entry and printed in the diagnostics export, so a Project the app draws that the desktop would not —
 * or the other way round — shows its record's own values.
 */
@kotlinx.serialization.Serializable
data class RecordFields(
    val projectMetadata: String? = null,
    val managerAgentId: String? = null,
    val subagentParentId: String? = null,
    val sideChatParentId: String? = null,
    val startedAsNewProject: Boolean = false,
    val source: String? = null,
) {
    /** The desktop's `subagentParentId`: the first of the subagent parent, the side-chat parent, the manager. */
    val desktopSubagentParentId: String? get() = subagentParentId ?: sideChatParentId ?: managerAgentId

    /** One line, the fields named as the proto names them. */
    fun describe(): String =
        "project_metadata=${projectMetadata ?: "absent"} manager_agent_id=${managerAgentId ?: "-"} cloud_subagent_parent=${subagentParentId ?: "-"} " +
            "side_chat_parent=${sideChatParentId ?: "-"} started_as_new_project=$startedAsNewProject source=${source ?: "-"}"
}

/**
 * What one read of the account's agent list says beyond the agents themselves: the pins, where each agent's pull
 * request stands (by `prUrl`, for the agents in the list window whose PR the account service has a status for),
 * where each agent was started from (by agent id, for every agent in the window), and the name / archive flag of
 * each composer in the window.
 */
data class AccountList(
    val pinned: PinnedIds,
    val pullRequests: Map<String, PullRequestState>,
    val sources: Map<String, AgentSource> = emptyMap(),
    val composers: List<ComposerSnapshot> = emptyList(),
    /** Where the next page of the account's list starts (see [PinsApi.listMore]); null when this page was the last. */
    val nextCursor: String? = null,
)

/** The account's agent list and its pins, the ones the desktop Agents window and the iOS app share. An interface so the repositories can be faked. */
/** One chat's account record by id, for a Project whose record the windowed list did not reach. */
interface ComposerRecordApi {
    suspend fun record(id: String): ComposerSnapshot?
}

/**
 * What a pass over the whole account list found for the root registry: every record that is a Project's, every
 * record that hangs off another chat (a worker's names its manager, a side chat's or subagent's its parent), how
 * many pages that took and whether the pass reached the end of the list.
 */
data class RootScan(
    val roots: List<ComposerSnapshot>,
    val children: List<ComposerSnapshot>,
    val pagesRead: Int,
    val complete: Boolean,
    /** Records the pages carried, for the diagnostics. */
    val records: Int = 0,
    /** What stopped the pass short of the end, when something did: the page that failed and why. */
    val failure: String? = null,
    /** The pass read as many pages as it was allowed and the list went on: nothing failed, and the next pass reads again. */
    val truncated: Boolean = false,
    /** Every record the pages carried, by id: what the registry is re-validated against (a record seen as neither a Project nor a manager). */
    val seenIds: Set<String> = emptySet(),
) {
    /** The coordinators the workers' records name, whether or not their own record was among the pages. */
    val managers: Set<String> get() = children.mapNotNullTo(LinkedHashSet()) { it.parent?.takeIf { p -> p.kind == AgentParentKind.PROJECT_WORKER }?.id }
}

/** The root discovery pass over the account list (see [RootScan]). */
interface RootScanApi {
    suspend fun scanRoots(maxPages: Int): RootScan
}

interface PinsApi {
    /** The newest page of the account's list, with the pins. */
    suspend fun list(): AccountList

    /** The page after the one that answered with [cursor]: the same reads, without the pins, for the rows the public list paged to. */
    suspend fun listMore(cursor: String): AccountList = list()
    suspend fun pin(ids: Collection<String>)
    suspend fun unpin(ids: Collection<String>)
}

/**
 * Archive, unarchive and rename as the first-party apps do them: `ArchiveBackgroundComposer` / `RenameBackgroundComposer`
 * on the account service. The public Cloud Agents API has archive / unarchive of its own, but those writes do not
 * always land on the flag the official sidebars read — which is why a chat archived here could stay visible on
 * cursor.com, and the other way around.
 */
interface ComposerLifecycleApi {
    suspend fun archive(id: String)
    suspend fun unarchive(id: String)
    suspend fun rename(id: String, name: String)
}

/** Where one pull request stands according to the account service; null when it does not know. */
fun interface PullRequestStatusApi {
    suspend fun mergeStatus(prUrl: String): PullRequestState?
}

/**
 * `aiserver.v1.BackgroundComposerService`, the account-level service behind cursor.com/agents and the first-party
 * apps ("background composer" is what a cloud agent is called there; its `bc_id` is the agent id the public API
 * uses). The corners used here: `ListBackgroundComposers` with `includePinnedState` returns the user's
 * `pinnedBcIds` and, per composer, the `name` / `isArchived` / `prUrl` / `prStatus` the account keeps, the
 * `source` the chat was started from (`aiserver.v1.BackgroundComposerSource`, what the Source filter of
 * cursor.com/agents cuts the list by) and its place among Cursor Projects (`projectMetadata`, `managerAgentId`,
 * `sideChatInfo`, `cloudSubagentParent`, see [snapshot]); `Pin` / `UnpinBackgroundComposers` change the pins;
 * `ArchiveBackgroundComposer` (with `unarchive`) and `RenameBackgroundComposer` are the official archive and
 * rename; `GetPullRequestMergeStatus` answers for one pull request. Calls carry the session token from
 * [SessionTokenProvider] (see [unaryWithSession]).
 */
class BackgroundComposerApi(
    private val rpc: ConnectJsonClient,
    private val tokens: SessionTokenProvider,
) : PinsApi, PullRequestStatusApi, ComposerLifecycleApi, ComposerRecordApi, RootScanApi {

    /**
     * Reads the account list page after page — up to [maxPages] of [LIST_WINDOW] — for the root registry: the
     * Projects' own records and every record that hangs off another chat. Independent of how far the sidebar has
     * paged; what the Projects group is drawn from, so a Project whose row no page holds is listed all the same.
     */
    override suspend fun scanRoots(maxPages: Int): RootScan {
        val roots = ArrayList<ComposerSnapshot>()
        val children = ArrayList<ComposerSnapshot>()
        val seen = HashSet<String>()
        var cursor: ListCursor? = null
        var pages = 0
        var complete = false
        var records = 0
        var failure: String? = null
        do {
            // Each page stands on its own: a page that fails leaves the ones before it read and the pass to be
            // finished later, rather than throwing away what the account already said.
            val response = try {
                page(cursor)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                failure = "page ${pages + 1}: ${t.message ?: t.javaClass.simpleName}"
                break
            }
            pages++
            var added = 0
            for (composer in response.composers) {
                val snap = snapshot(composer) ?: continue
                records++
                if (!seen.add(snap.id)) continue
                added++
                if (snap.scope == AgentScope.PROJECT_ROOT) roots += snap
                if (snap.parent != null) children += snap
            }
            when {
                !response.hasMore -> { cursor = null; complete = true }
                // A page that brought nothing new is the list read to its end whatever the flag said.
                added == 0 -> { cursor = null; complete = true }
                else -> {
                    cursor = response.cursor()
                    if (cursor == null) failure = "page $pages: the service has more but named no page cursor"
                }
            }
        } while (cursor != null && pages < maxPages)
        return RootScan(roots.distinctBy { it.id }, children.distinctBy { it.id }, pages, complete, records, failure, truncated = cursor != null && failure == null, seenIds = seen)
    }

    override suspend fun list(): AccountList = accountList(page(null), first = true)

    /**
     * The page after [cursor] — the one [AccountList.nextCursor] named: a token, or the activity offset the older
     * service pages by (see [ListCursor]). The list is windowed and a heavy account outgrows one window — a Project
     * with a hundred workers is one hundred rows of it — so the pages behind the first are read as the public list
     * pages, one for one, rather than a few up front and none after.
     */
    override suspend fun listMore(cursor: String): AccountList {
        val parsed = ListCursor.parse(cursor) ?: return list()
        return accountList(page(parsed), first = false)
    }

    private fun accountList(response: ListBackgroundComposersResponseDto, first: Boolean): AccountList {
        val pullRequests = LinkedHashMap<String, PullRequestState>()
        val sources = LinkedHashMap<String, AgentSource>()
        for (composer in response.composers) {
            if (composer.bcId.isNotBlank()) AgentSource.parse(composer.source?.contentOrNull)?.let { sources[composer.bcId] = it }
            // Keyed exactly as the public API names the same PR on the agent's run (`git.branches[].prUrl`).
            val url = composer.prUrl?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val state = pullRequestState(composer.prStatus, composer.isPrMerged) ?: continue
            pullRequests[url] = state
        }
        val composers = response.composers.mapNotNull { composer -> snapshot(composer) }.distinctBy { it.id }
        return AccountList(
            PinnedIds(if (first) response.pinnedBcIds.toSet() else emptySet(), first && response.didLoadPinnedState),
            pullRequests,
            sources,
            composers,
            nextCursor = if (response.hasMore && response.composers.isNotEmpty()) response.cursor()?.encode() else null,
        )
    }

    private suspend fun page(cursor: ListCursor?): ListBackgroundComposersResponseDto = call(
        "ListBackgroundComposers",
        ListBackgroundComposersRequestDto(
            n = LIST_WINDOW,
            includeArchived = true,
            includeStatus = true,
            includePinnedState = cursor == null,
            includeHiddenSources = HIDDEN_SOURCES.map { it.wireName },
            includeWorkers = true,
            includeSubagents = true,
            // Tokens are asked for from the first page: the service names the next page's token only when asked,
            // and a first page read without asking left every later page unreachable (the list read one page).
            usePageTokens = true,
            pageToken = (cursor as? ListCursor.Token)?.token,
            lastMessageActivityAtMsOffset = (cursor as? ListCursor.Offset)?.offset,
        ),
        ListBackgroundComposersRequestDto.serializer(),
        ListBackgroundComposersResponseDto.serializer(),
    )

    /**
     * One chat's record by id (`ListBackgroundComposers {bc_id}`), for a Project whose record the windowed list
     * did not reach: its Project flag and appearance, its manager, its side-chat or subagent parent. Null when the
     * service knows no such chat, or answered with another.
     */
    override suspend fun record(id: String): ComposerSnapshot? {
        val response = call(
            "ListBackgroundComposers",
            ListBackgroundComposersRequestDto(
                n = 1,
                includeArchived = true,
                includeStatus = false,
                includePinnedState = false,
                includeHiddenSources = HIDDEN_SOURCES.map { it.wireName },
                includeWorkers = true,
                includeSubagents = true,
                bcId = id,
            ),
            ListBackgroundComposersRequestDto.serializer(),
            ListBackgroundComposersResponseDto.serializer(),
        )
        return response.composers.firstOrNull { it.bcId.trim() == id }?.let { snapshot(it) }
    }

    /**
     * Where the next page starts: the page token the service named; failing that, the activity offset the older
     * service pages by — the oldest `lastMessageActivityAtMs` of this page, which is what the desktop's
     * `last_message_activity_at_ms_offset` carries; null when the page gave nothing to continue from.
     */
    private fun ListBackgroundComposersResponseDto.cursor(): ListCursor? {
        nextPageToken?.trim()?.takeIf { it.isNotEmpty() }?.let { return ListCursor.Token(it) }
        val offset = nextPageOffset?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
            ?: composers.mapNotNull { c -> c.lastMessageActivityAtMs?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } }.minOrNull()
            ?: return null
        return if (offset > 0) ListCursor.Offset(offset) else null
    }

    private sealed interface ListCursor {
        data class Token(val token: String) : ListCursor
        data class Offset(val offset: Long) : ListCursor

        /** The cursor as [AccountList.nextCursor] carries it between calls: its kind, then its value. */
        fun encode(): String = when (this) {
            is Token -> "token:$token"
            is Offset -> "offset:$offset"
        }

        companion object {
            fun parse(encoded: String): ListCursor? = when {
                encoded.startsWith("token:") -> encoded.removePrefix("token:").takeIf { it.isNotEmpty() }?.let(::Token)
                encoded.startsWith("offset:") -> encoded.removePrefix("offset:").toLongOrNull()?.takeIf { it > 0 }?.let(::Offset)
                else -> null
            }
        }
    }

    override suspend fun pin(ids: Collection<String>) {
        if (ids.isEmpty()) return
        call("PinBackgroundComposers", BcIdsDto(ids.toList()), BcIdsDto.serializer(), EmptyResponseDto.serializer())
    }

    override suspend fun unpin(ids: Collection<String>) {
        if (ids.isEmpty()) return
        call("UnpinBackgroundComposers", BcIdsDto(ids.toList()), BcIdsDto.serializer(), EmptyResponseDto.serializer())
    }

    override suspend fun archive(id: String) {
        call("ArchiveBackgroundComposer", ArchiveComposerDto(id, unarchive = false), ArchiveComposerDto.serializer(), EmptyResponseDto.serializer())
    }

    override suspend fun unarchive(id: String) {
        call("ArchiveBackgroundComposer", ArchiveComposerDto(id, unarchive = true), ArchiveComposerDto.serializer(), EmptyResponseDto.serializer())
    }

    override suspend fun rename(id: String, name: String) {
        call("RenameBackgroundComposer", RenameComposerDto(id, name), RenameComposerDto.serializer(), EmptyResponseDto.serializer())
    }

    override suspend fun mergeStatus(prUrl: String): PullRequestState? {
        val status = call("GetPullRequestMergeStatus", PrUrlDto(prUrl), PrUrlDto.serializer(), MergeStatusDto.serializer())
        return when {
            status.isMerged -> PullRequestState.Merged
            status.isClosed -> PullRequestState.Closed
            status.isDraft -> PullRequestState.Draft
            status.state.equals("open", ignoreCase = true) -> PullRequestState.Open
            status.state.equals("closed", ignoreCase = true) -> PullRequestState.Closed
            // No flag set and no state named: the service has not looked at this PR (or cannot).
            status.state.isBlank() -> null
            else -> PullRequestState.Open
        }
    }

    private suspend fun <I, O> call(method: String, body: I, requestSerializer: KSerializer<I>, responseSerializer: KSerializer<O>): O =
        rpc.unaryWithSession(SERVICE, method, tokens, body, requestSerializer, responseSerializer)

    // Request fields carry no defaults on purpose: CursorJson does not encode defaults, and every flag must be sent.

    @Serializable
    private data class ListBackgroundComposersRequestDto(
        val n: Int,
        val includeArchived: Boolean,
        val includeStatus: Boolean,
        val includePinnedState: Boolean,
        /** `include_hidden_sources`: sources the list leaves out unless asked, named as the proto spells them. */
        val includeHiddenSources: List<String>,
        /**
         * `include_workers` / `include_subagents`: the records of a Project's workers and of cloud subagents, which
         * the list leaves out unless asked (the Agents Window never asks; it lists them under their parents from
         * `ListWorkersForManager`). They are the records that carry `managerAgentId` and `cloudSubagentParent`, and
         * the public list this app draws its rows from lists those chats regardless — so without them the rows would
         * never learn whose they are, and would sit among the account's own chats.
         */
        val includeWorkers: Boolean,
        val includeSubagents: Boolean,
        /** Paging, one way or the other; left out (null) on the first page and where the service offered no cursor. */
        val usePageTokens: Boolean? = null,
        val pageToken: String? = null,
        val lastMessageActivityAtMsOffset: Long? = null,
        /** One chat by id (see [record]); left out for the list. */
        val bcId: String? = null,
    )

    @Serializable
    private data class ListBackgroundComposersResponseDto(
        val composers: List<ComposerDto> = emptyList(),
        val pinnedBcIds: List<String> = emptyList(),
        val didLoadPinnedState: Boolean = false,
        val hasMore: Boolean = false,
        val nextPageToken: String? = null,
        /** `int64`, which proto3's JSON mapping writes as a string; read either way. */
        val nextPageOffset: JsonPrimitive? = null,
    )

    /** The corner of `aiserver.v1.BackgroundComposer` read here; everything else the record carries is ignored. */
    @Serializable
    internal data class ComposerDto(
        val bcId: String = "",
        val name: String? = null,
        val isArchived: Boolean? = null,
        val prUrl: String? = null,
        val isPrMerged: Boolean? = null,
        /** `aiserver.v1.PRStatus`: its name in proto3's JSON mapping, or its number when a server encodes enums that way. */
        val prStatus: JsonPrimitive? = null,
        /** `aiserver.v1.BackgroundComposerSource`, encoded the same way; absent for the zero value (`UNSPECIFIED`). */
        val source: JsonPrimitive? = null,
        /** `aiserver.v1.BackgroundComposerStatus`, encoded the same way; present when `include_status` was asked. */
        val status: JsonPrimitive? = null,
        /**
         * `aiserver.v1.ProjectMetadata`, kept raw: its presence is the desktop's `isProject`, and its `appearance`
         * (icon and colour, both non-empty) the look; printed as it came in the diagnostics.
         */
        val projectMetadata: JsonObject? = null,
        /** The Project coordinator this chat works for, on a worker it created or adopted. */
        val managerAgentId: String? = null,
        /** `aiserver.v1.SideChatInfo`: the chat this one branched off as a side chat. */
        val sideChatInfo: SideChatInfoDto? = null,
        /** `aiserver.v1.CloudSubagentParentReference`: the agent that spawned this one as a cloud subagent. */
        val cloudSubagentParent: CloudSubagentParentDto? = null,
        /** The agent asked a question and waits on the answer. */
        val hasPendingInteraction: Boolean? = null,
        /** Created through the "New Project" flow; carried for the diagnostics, it is no Project flag on its own. */
        val startedAsNewProject: Boolean? = null,
        /** `int64`, a string in proto3's JSON; what the older service pages by (see `cursor`). */
        val lastMessageActivityAtMs: JsonPrimitive? = null,
    )

    @Serializable
    internal data class ProjectAppearanceDto(val icon: String = "", val colorId: String = "")

    @Serializable
    internal data class SideChatInfoDto(val parentBcId: String? = null, val seedTurnCount: Int? = null)

    @Serializable
    internal data class CloudSubagentParentDto(val parentAgentId: String? = null, val parentToolCallId: String? = null)

    @Serializable
    private data class BcIdsDto(val bcIds: List<String>)

    /** [unarchive] has no default so both `true` and `false` are encoded (`CursorJson` drops defaulted fields). */
    @Serializable
    private data class ArchiveComposerDto(val bcId: String, val unarchive: Boolean)

    @Serializable
    private data class RenameComposerDto(val bcId: String, val newName: String)

    @Serializable
    private data class PrUrlDto(val prUrl: String)

    @Serializable
    private data class MergeStatusDto(
        val isMerged: Boolean = false,
        val isClosed: Boolean = false,
        val isDraft: Boolean = false,
        val state: String = "",
        val mergeableState: String = "",
        val title: String = "",
    )

    @Serializable
    private class EmptyResponseDto

    companion object {
        const val SERVICE = "aiserver.v1.BackgroundComposerService"

        /**
         * How many composers the list read asks for. The pinned state is the account's list, separate from the page it
         * rides along with, but the pull request states are per composer, so the page is sized to cover the agents
         * the sidebar shows first (the public list is paged to 500; the rest are looked up one by one).
         */
        const val LIST_WINDOW = 200

        /**
         * Sources the account service leaves out of the list unless asked for: agents started by the SDK, which
         * cursor.com/agents and the desktop Agents window hide until their Source filter is set to SDK, and the chats
         * Cursor starts for another chat — side chats and subagents branched off a cloud agent, and its meta agents.
         * The public list this app draws its rows from hides none of them, so they are asked for here or they would
         * never learn their source (nor their pins and pull request states), nor — for the side chats and subagents —
         * whose they are.
         */
        val HIDDEN_SOURCES: List<AgentSource> = listOf(AgentSource.SDK, AgentSource.AS_SIDE_CHAT_FROM_CLOUD, AgentSource.AS_SUBAGENT_FROM_CLOUD, AgentSource.CLOUD_META_AGENT)

        /**
         * What the record says about names, archive and Cursor Projects, derived the way the Agents Window derives
         * it from the same record (Cursor 3.20): a chat is a Project when it carries `projectMetadata` — an empty
         * one included — unless a coordinator manages it, since a worker is never a Project itself; its parent is
         * the first of the agent that spawned it as a cloud subagent, the chat it branched off as a side chat and
         * the coordinator it works for; and the appearance counts only with both an icon and a colour set. A blank
         * link, or one naming the chat itself, is no link; a record without an id is no snapshot (null).
         */
        internal fun snapshot(composer: ComposerDto): ComposerSnapshot? {
            val id = composer.bcId.trim().takeIf { it.isNotEmpty() } ?: return null
            fun String?.link(): String? = this?.trim()?.takeIf { it.isNotEmpty() && it != id }
            val manager = composer.managerAgentId.link()
            val parent = composer.cloudSubagentParent?.parentAgentId.link()?.let { AgentParent(it, AgentParentKind.SUBAGENT) }
                ?: composer.sideChatInfo?.parentBcId.link()?.let { AgentParent(it, AgentParentKind.SIDE_CHAT) }
                ?: manager?.let { AgentParent(it, AgentParentKind.PROJECT_WORKER) }
            // The desktop's predicate, exactly: `isProject` is `project_metadata` present at all; a root is that with
            // no subagent parent, side-chat parent or manager. The look is read apart (`wQp`): icon and colour both
            // non-empty, else none.
            val metadata = composer.projectMetadata
            val appearance = (metadata?.get("appearance") as? JsonObject)?.let { a ->
                val icon = (a["icon"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                val colorId = (a["colorId"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (icon.isNotEmpty() && colorId.isNotEmpty()) ProjectAppearance(icon, colorId) else null
            }
            val isProject = metadata != null && parent == null
            val record = RecordFields(
                projectMetadata = metadata?.toString(),
                managerAgentId = manager,
                subagentParentId = composer.cloudSubagentParent?.parentAgentId.link(),
                sideChatParentId = composer.sideChatInfo?.parentBcId.link(),
                startedAsNewProject = composer.startedAsNewProject == true,
                source = composer.source?.contentOrNull,
            )
            return ComposerSnapshot(
                id = id,
                name = composer.name,
                archived = composer.isArchived,
                isProject = isProject,
                projectAppearance = appearance?.takeIf { isProject },
                record = record,
                parent = parent,
                source = AgentSource.parse(composer.source?.contentOrNull),
                hasPendingInteraction = composer.hasPendingInteraction == true,
                status = composerStatus(composer.status),
            )
        }

        /** `aiserver.v1.BackgroundComposerStatus` by name or number: RUNNING 1, FINISHED 2, ERROR 3, CREATING 4, EXPIRED 5. */
        fun composerStatus(raw: JsonPrimitive?): RunStatus? = when (raw?.contentOrNull?.uppercase()?.removePrefix("BACKGROUND_COMPOSER_STATUS_")) {
            "RUNNING", "1" -> RunStatus.RUNNING
            "FINISHED", "2" -> RunStatus.FINISHED
            "ERROR", "3" -> RunStatus.ERROR
            "CREATING", "4" -> RunStatus.CREATING
            "EXPIRED", "5" -> RunStatus.EXPIRED
            else -> null
        }

        /**
         * `aiserver.v1.PRStatus` as the list reports it. [isPrMerged] stands in when the status is unspecified or
         * missing, as it is on records the service has not classified yet.
         */
        fun pullRequestState(prStatus: JsonPrimitive?, isPrMerged: Boolean?): PullRequestState? {
            // A numeric primitive's content is its digits, so both encodings land in the same branches.
            val byStatus = when (prStatus?.contentOrNull?.uppercase()) {
                "PR_STATUS_OPEN", "1" -> PullRequestState.Open
                "PR_STATUS_DRAFT", "2" -> PullRequestState.Draft
                "PR_STATUS_MERGED", "3" -> PullRequestState.Merged
                "PR_STATUS_CLOSED", "4" -> PullRequestState.Closed
                else -> null
            }
            return byStatus ?: if (isPrMerged == true) PullRequestState.Merged else null
        }
    }
}
