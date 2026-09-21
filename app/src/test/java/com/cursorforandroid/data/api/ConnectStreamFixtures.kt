package com.cursorforandroid.data.api

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.util.Base64

/**
 * The wire of a server-streaming Connect call in its JSON encoding, for the fakes that stand in for the account
 * service (connectrpc.com/docs/protocol): the request body one enveloped message, the response a run of enveloped
 * messages under `application/connect+json` closed by an end-of-stream envelope (flags `0b10`) carrying `{}` — or
 * `{"error": {code, message}}` for a stream that ends in an error.
 */
object ConnectStreamFixtures {

    /** The one request message of a streaming call, read out of its envelope. */
    fun requestJson(request: RecordedRequest): String {
        val body = request.body.readByteArray()
        require(body.size >= 5) { "a Connect stream request carries at least one envelope; got ${body.size} bytes" }
        val length = ((body[1].toInt() and 0xFF) shl 24) or ((body[2].toInt() and 0xFF) shl 16) or ((body[3].toInt() and 0xFF) shl 8) or (body[4].toInt() and 0xFF)
        return String(body, 5, length, Charsets.UTF_8)
    }

    /** Whether [request] is a Connect stream (as against a unary call of the same path). */
    fun isStream(request: RecordedRequest): Boolean = request.getHeader("Content-Type")?.startsWith("application/connect+json") == true

    /** A streaming answer: each of [messages] (JSON) in its envelope, then the end-of-stream envelope with [endStream]. */
    fun streamResponse(messages: List<String>, endStream: String = "{}"): MockResponse {
        val buffer = Buffer()
        messages.forEach { buffer.write(ConnectRpc.envelope(0, it.toByteArray(Charsets.UTF_8))) }
        buffer.write(ConnectRpc.envelope(ConnectRpc.END_STREAM, endStream.toByteArray(Charsets.UTF_8)))
        return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/connect+json").setBody(buffer)
    }

    /**
     * `StreamConversation`'s answer to a PREWARM read as the account gives it: the prefetched blobs first (in their
     * own message when [blobsFirst], else on the initial state), then `initial_state` with [conversationStateJson]
     * as its `cloud_agent_state.conversation_state`, then the end of the stream. [cloudAgentExtra] adds fields beside
     * the conversation state (`numPriorInteractionUpdates`, …).
     */
    fun prewarmResponse(
        conversationStateJson: String,
        blobs: List<Pair<String, ByteArray>> = emptyList(),
        blobsFirst: Boolean = true,
        cloudAgentExtra: String = "",
        workflowStatus: String = "CLOUD_AGENT_WORKFLOW_STATUS_RUNNING",
    ): MockResponse {
        val prefetched = blobs.joinToString(",") { (id, value) -> """{"id":"$id","value":"${Base64.getEncoder().encodeToString(value)}"}""" }
        val extra = if (cloudAgentExtra.isBlank()) "" else ",$cloudAgentExtra"
        val messages = ArrayList<String>()
        if (blobsFirst && blobs.isNotEmpty()) messages += """{"prefetchedBlobs":{"preFetchedBlobs":[$prefetched]}}"""
        val onState = if (!blobsFirst && blobs.isNotEmpty()) ""","preFetchedBlobs":[$prefetched]""" else ""
        messages += """{"initialState":{"blobId":"c3RhdGU=","cloudAgentState":{"conversationState":$conversationStateJson$extra},"workflowStatus":"$workflowStatus"$onState}}"""
        return streamResponse(messages)
    }

    /** A stream that ends in the server's error without a message: `{"error": {code, message}}` in the end-of-stream envelope. */
    fun errorStream(code: String, message: String): MockResponse =
        streamResponse(emptyList(), endStream = """{"error":{"code":"$code","message":${kotlinx.serialization.json.JsonPrimitive(message)}}}""")
}
