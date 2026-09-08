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
): O {
    val token = tokens.accessToken()
    return try {
        unary(service, method, token, body, requestSerializer, responseSerializer)
    } catch (e: ConnectRpcException) {
        if (!e.isUnauthenticated) throw e
        tokens.invalidate()
        unary(service, method, tokens.accessToken(), body, requestSerializer, responseSerializer)
    }
}
