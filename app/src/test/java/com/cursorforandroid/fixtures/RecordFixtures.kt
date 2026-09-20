package com.cursorforandroid.fixtures

import com.cursorforandroid.data.api.ConnectJsonClient
import com.cursorforandroid.data.api.HeadlessConversationApi
import com.cursorforandroid.data.api.HeadlessStep
import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.data.repo.HeadlessTranscript
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SystemNotifications
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.UserMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/**
 * The account's record fixtures (`FetchBackgroundComposer` pages) read the way the app reads them: served on the
 * wire and parsed by [HeadlessConversationApi], so what a test renders is what the parser makes of the record's
 * shapes, not a hand-built copy of it.
 */
object RecordFixtures {

    /** The steps of the fixture's record, every response parsed by the app's own reader. */
    fun steps(name: String): List<HeadlessStep> = steps(CoordinatorFixtures.json(name).getValue("responses").jsonArray)

    /** The steps of [responses] — a record built by a test — parsed by the app's own reader, served on the wire. */
    fun steps(responses: JsonArray): List<HeadlessStep> {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setBody("""{"accessToken":"s","refreshToken":"rt"}"""))
            server.enqueue(MockResponse().setBody("""{"responses":$responses,"totalResponses":${responses.size}}"""))
            server.start()
            val client = OkHttpClient()
            val base = server.url("/").toString()
            val api = HeadlessConversationApi(ConnectJsonClient(client, base), SessionTokenProvider(client, apiKeyProvider = { "key_abc" }, apiUrl = base, now = { 0L }))
            return runBlocking { api.fetch("bc-fixture", startIndex = 0, limit = responses.size).steps }
        } finally {
            server.shutdown()
        }
    }

    /**
     * The chat the fixture's record describes, as the conversation would hold it: each turn's prompt — an injected
     * turn as its row — the turn's items built through the record's accumulator, and a footer of [turnDurationMs]
     * for every finished turn (the record's timing), the turns [stepMs] apart from [firstAt].
     */
    fun items(name: String, firstAt: Long, stepMs: Long = 97_000L, turnDurationMs: Long = 12_000L): List<TimelineItem> {
        val turns = HeadlessTranscript.split(steps(name))
        val prompts = CoordinatorFixtures.json(name).getValue("responses").jsonArray
            .mapNotNull { it.jsonObject["humanMessage"]?.jsonObject?.get("createdAt")?.jsonPrimitive?.long }
        val out = ArrayList<TimelineItem>()
        turns.forEachIndexed { index, turn ->
            val at = prompts.getOrNull(index) ?: (firstAt + index * stepMs)
            turn.prompt?.let { prompt ->
                out += SystemNotifications.parse("rec-prompt-$index", prompt, at)?.items ?: listOf(UserMessage("rec-prompt-$index", prompt, at))
            }
            out += HeadlessTranscript.body(turn, "rec-$index")
            out += RunFooter("rec-footer-$index", "rec-$index", RunStatus.FINISHED, turnDurationMs, emptyList())
        }
        return out
    }
}
