package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.domain.AgentSource
import com.cursorforandroid.domain.PullRequestState
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The account's pinned agents as the server reports them; [loaded] false means the server skipped the pinned state. */
data class PinnedIds(val ids: Set<String>, val loaded: Boolean)

/**
 * What one read of the account's agent list says beyond the agents themselves: the pins, where each agent's pull
 * request stands (by `prUrl`, for the agents in the list window whose PR the account service has a status for), and
 * where each agent was started from (by agent id, for every agent in the window).
 */
data class AccountList(
    val pinned: PinnedIds,
    val pullRequests: Map<String, PullRequestState>,
    val sources: Map<String, AgentSource> = emptyMap(),
)

/** The account's agent list and its pins, the ones the desktop Agents window and the iOS app share. An interface so the repositories can be faked. */
interface PinsApi {
    suspend fun list(): AccountList
    suspend fun pin(ids: Collection<String>)
    suspend fun unpin(ids: Collection<String>)
}

/** Where one pull request stands according to the account service; null when it does not know. */
fun interface PullRequestStatusApi {
    suspend fun mergeStatus(prUrl: String): PullRequestState?
}

/**
 * `aiserver.v1.BackgroundComposerService`, the account-level service behind cursor.com/agents and the first-party
 * apps ("background composer" is what a cloud agent is called there; its `bc_id` is the agent id the public API
 * uses). Three corners of it are used: `ListBackgroundComposers` with `includePinnedState` returns the user's
 * `pinnedBcIds` and, per composer, the `prUrl` / `prStatus` the account keeps in step with the SCM and the `source`
 * the chat was started from (`aiserver.v1.BackgroundComposerSource`, what the Source filter of cursor.com/agents
 * cuts the list by); `Pin` / `UnpinBackgroundComposers` change the pins; `GetPullRequestMergeStatus` answers for one
 * pull request. Calls carry the session token from [SessionTokenProvider] (see [unaryWithSession]).
 */
class BackgroundComposerApi(
    private val rpc: ConnectJsonClient,
    private val tokens: SessionTokenProvider,
) : PinsApi, PullRequestStatusApi {

    override suspend fun list(): AccountList {
        val response = call(
            "ListBackgroundComposers",
            ListBackgroundComposersRequestDto(
                n = LIST_WINDOW,
                includeArchived = true,
                includeStatus = true,
                includePinnedState = true,
                includeHiddenSources = HIDDEN_SOURCES.map { it.wireName },
            ),
            ListBackgroundComposersRequestDto.serializer(),
            ListBackgroundComposersResponseDto.serializer(),
        )
        val pullRequests = LinkedHashMap<String, PullRequestState>()
        val sources = LinkedHashMap<String, AgentSource>()
        for (composer in response.composers) {
            if (composer.bcId.isNotBlank()) AgentSource.parse(composer.source?.contentOrNull)?.let { sources[composer.bcId] = it }
            // Keyed exactly as the public API names the same PR on the agent's run (`git.branches[].prUrl`).
            val url = composer.prUrl?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val state = pullRequestState(composer.prStatus, composer.isPrMerged) ?: continue
            pullRequests[url] = state
        }
        return AccountList(PinnedIds(response.pinnedBcIds.toSet(), response.didLoadPinnedState), pullRequests, sources)
    }

    override suspend fun pin(ids: Collection<String>) {
        if (ids.isEmpty()) return
        call("PinBackgroundComposers", BcIdsDto(ids.toList()), BcIdsDto.serializer(), EmptyResponseDto.serializer())
    }

    override suspend fun unpin(ids: Collection<String>) {
        if (ids.isEmpty()) return
        call("UnpinBackgroundComposers", BcIdsDto(ids.toList()), BcIdsDto.serializer(), EmptyResponseDto.serializer())
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
    )

    @Serializable
    private data class ListBackgroundComposersResponseDto(
        val composers: List<ComposerDto> = emptyList(),
        val pinnedBcIds: List<String> = emptyList(),
        val didLoadPinnedState: Boolean = false,
        val hasMore: Boolean = false,
    )

    /** The corner of `aiserver.v1.BackgroundComposer` read here; everything else the record carries is ignored. */
    @Serializable
    private data class ComposerDto(
        val bcId: String = "",
        val prUrl: String? = null,
        val isPrMerged: Boolean? = null,
        /** `aiserver.v1.PRStatus`: its name in proto3's JSON mapping, or its number when a server encodes enums that way. */
        val prStatus: JsonPrimitive? = null,
        /** `aiserver.v1.BackgroundComposerSource`, encoded the same way; absent for the zero value (`UNSPECIFIED`). */
        val source: JsonPrimitive? = null,
    )

    @Serializable
    private data class BcIdsDto(val bcIds: List<String>)

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
         * cursor.com/agents and the desktop Agents window hide until their Source filter is set to SDK. The public
         * list this app draws its rows from does not hide them, so they are asked for here or they would never learn
         * their source (nor their pins and pull request states).
         */
        val HIDDEN_SOURCES: List<AgentSource> = listOf(AgentSource.SDK)

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
