package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import kotlinx.serialization.KSerializer

/**
 * A unary call authenticated with the account session from [tokens]. One `401` is answered by starting a new
 * session and trying once more: the session lapsed or was revoked underneath us, and a fresh one from the key
 * settles which.
 */
suspend fun <I, O> ConnectJsonClient.unaryWithSession(
    service: String,
    method: String,
    tokens: SessionTokenProvider,
    body: I,
    requestSerializer: KSerializer<I>,
    responseSerializer: KSerializer<O>,
    /** Whether a rate limit is waited out and the call made once more (see `ApiThrottle.call`); off for a call with a fallback. */
    retryRefusals: Boolean = true,
    /** The throttle's lane the call waits in (see `ApiThrottle.Lane`). */
    lane: ApiThrottle.Lane = ApiThrottle.Lane.CONTROL,
): O {
    val token = tokens.accessToken()
    return try {
        unary(service, method, token, body, requestSerializer, responseSerializer, retryRefusals, lane)
    } catch (e: ConnectRpcException) {
        if (!e.isUnauthenticated) throw e
        tokens.invalidate()
        unary(service, method, tokens.accessToken(), body, requestSerializer, responseSerializer, retryRefusals, lane)
    }
}

/** [ConnectJsonClient.serverStream] with the account session, renewed once on `unauthenticated` (see [unaryWithSession]). */
suspend fun <I> ConnectJsonClient.serverStreamWithSession(
    service: String,
    method: String,
    tokens: SessionTokenProvider,
    body: I,
    requestSerializer: KSerializer<I>,
    retryRefusals: Boolean = false,
    onMessage: (kotlinx.serialization.json.JsonObject) -> Boolean,
): Int {
    val token = tokens.accessToken()
    return try {
        serverStream(service, method, token, body, requestSerializer, retryRefusals, onMessage)
    } catch (e: ConnectRpcException) {
        if (!e.isUnauthenticated) throw e
        tokens.invalidate()
        serverStream(service, method, tokens.accessToken(), body, requestSerializer, retryRefusals, onMessage)
    }
}
