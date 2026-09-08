package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/** The account's pinned agents as the server reports them; [loaded] false means the server skipped the pinned state. */
data class PinnedIds(val ids: Set<String>, val loaded: Boolean)

/** Server-side pins, the ones the desktop Agents window and the iOS app share. An interface so the repository can be faked. */
interface PinsApi {
    suspend fun pinnedIds(): PinnedIds
    suspend fun pin(ids: Collection<String>)
    suspend fun unpin(ids: Collection<String>)
}

/**
 * `aiserver.v1.BackgroundComposerService`, the account-level service behind cursor.com/agents and the first-party
 * apps ("background composer" is what a cloud agent is called there; its `bc_id` is the agent id the public API
 * uses). Only the pin surface is used: `ListBackgroundComposers` with `includePinnedState` returns the user's
 * `pinnedBcIds`, and `Pin` / `UnpinBackgroundComposers` change them. Calls carry the session token from
 * [SessionTokenProvider]; one `401` is answered by starting a new session and trying once more.
 */
class BackgroundComposerApi(
    private val rpc: ConnectJsonClient,
    private val tokens: SessionTokenProvider,
) : PinsApi {

    override suspend fun pinnedIds(): PinnedIds {
        val response = call(
            "ListBackgroundComposers",
            ListBackgroundComposersRequestDto(n = LIST_WINDOW, includeArchived = true, includePinnedState = true),
            ListBackgroundComposersRequestDto.serializer(),
            ListBackgroundComposersResponseDto.serializer(),
        )
        return PinnedIds(response.pinnedBcIds.toSet(), response.didLoadPinnedState)
    }

    override suspend fun pin(ids: Collection<String>) {
        if (ids.isEmpty()) return
        call("PinBackgroundComposers", BcIdsDto(ids.toList()), BcIdsDto.serializer(), EmptyResponseDto.serializer())
    }

    override suspend fun unpin(ids: Collection<String>) {
        if (ids.isEmpty()) return
        call("UnpinBackgroundComposers", BcIdsDto(ids.toList()), BcIdsDto.serializer(), EmptyResponseDto.serializer())
    }

    private suspend fun <I, O> call(method: String, body: I, requestSerializer: KSerializer<I>, responseSerializer: KSerializer<O>): O {
        val token = tokens.accessToken()
        return try {
            rpc.unary(SERVICE, method, token, body, requestSerializer, responseSerializer)
        } catch (e: ConnectRpcException) {
            if (!e.isUnauthenticated) throw e
            // The session lapsed or was revoked underneath us; a fresh one from the key settles which.
            tokens.invalidate()
            rpc.unary(SERVICE, method, tokens.accessToken(), body, requestSerializer, responseSerializer)
        }
    }

    // Request fields carry no defaults on purpose: CursorJson does not encode defaults, and every flag must be sent.

    @Serializable
    private data class ListBackgroundComposersRequestDto(val n: Int, val includeArchived: Boolean, val includePinnedState: Boolean)

    @Serializable
    private data class ListBackgroundComposersResponseDto(
        val pinnedBcIds: List<String> = emptyList(),
        val didLoadPinnedState: Boolean = false,
        val hasMore: Boolean = false,
    )

    @Serializable
    private data class BcIdsDto(val bcIds: List<String>)

    @Serializable
    private class EmptyResponseDto

    companion object {
        const val SERVICE = "aiserver.v1.BackgroundComposerService"

        /**
         * `pinnedBcIds` is the account's list, carried separately from the page of composers it rides along with
         * (the desktop keeps pins for agents its recent window no longer shows), so the page only has to exist. Sized
         * like the desktop sidebar's recent window anyway, so pins would still cover the recent agents if the server
         * ever scoped them to the page.
         */
        const val LIST_WINDOW = 50
    }
}
