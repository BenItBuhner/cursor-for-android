package com.cursorforandroid.data.api

import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.data.repo.HeadlessTranscript
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.CoordinatorTranscript
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.fixtures.CoordinatorFixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The account's record of a coordinator's turn (`FetchBackgroundComposer`, Extended mode), from the fixture shaped
 * as Cursor's desktop converts an `agent.v1.ToolCall` into a `ClientSideToolV2Call` record: `name` from its
 * model-facing table (`SendMessage`), `rawArgs = JSON.stringify(args)` (`{"text":{"content":…}}`), the legacy `tool`
 * enum UNSPECIFIED for `SendMessage` and SEND_TO_USER for `send_to_user`, and the prompts' `agentMode`.
 */
class CoordinatorRecordShapesTest {

    private val server = MockWebServer()
    private lateinit var api: HeadlessConversationApi

    @Before
    fun setUp() {
        server.start()
        val client = OkHttpClient()
        val base = server.url("/").toString()
        api = HeadlessConversationApi(
            rpc = ConnectJsonClient(client, base),
            tokens = SessionTokenProvider(client, apiKeyProvider = { "key_abc" }, apiUrl = base, now = { 0L }),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    private suspend fun record(): HeadlessPage {
        server.enqueue(MockResponse().setBody("""{"accessToken":"s","refreshToken":"rt"}"""))
        server.enqueue(MockResponse().setBody(CoordinatorFixtures.text("fetch_background_composer.json")))
        return api.fetch("bc-coord", startIndex = 0, limit = 50)
    }

    @Test
    fun `the record's SendMessage call is read by its name with the text under text-content`() = runBlocking<Unit> {
        val page = record()
        assertThat(page.totalResponses).isEqualTo(13)
        val send = page.steps.first { it.toolCall?.name == "SendMessage" }.toolCall!!
        assertThat(send.callId).isEqualTo("toolu_01SendA")
        assertThat(send.args!!.jsonObject.getValue("text").jsonObject.getValue("content").jsonPrimitive.content).startsWith("The keyboard worker merged #113")
        // The older tool, its enum value naming it too.
        val old = page.steps.first { it.toolCall?.name == "send_to_user" }.toolCall!!
        assertThat(old.args!!.jsonObject.getValue("message").jsonPrimitive.content).isEqualTo("PR #215 is merged.")
        // The prompts say the chat runs in Project mode.
        assertThat(page.steps.filter { it.userMessage != null }.map { it.projectMode }).containsExactly(true, true)
    }

    @Test
    fun `the record's turns render the coordinator's message as the message and say the chat is a Project's`() = runBlocking<Unit> {
        val turns = HeadlessTranscript.split(record().steps)
        assertThat(turns.map { it.prompt }).containsExactly("Start this Project.", "Where do we stand?").inOrder()
        assertThat(turns.all { it.projectMode }).isTrue()

        val run = RunDto(id = "run-1", agentId = "bc-coord", status = "FINISHED", createdAt = "2026-09-12T23:05:00.000Z", updatedAt = "2026-09-12T23:07:00.000Z", durationMs = 120_000, result = null)
        val items = HeadlessTranscript.trace(turns[0], run)
        val calls = items.filterIsInstance<ActivityGroup>().flatMap { it.calls }.associateBy { it.callId }
        val send = calls.getValue("toolu_01SendA")
        assertThat(send.kind).isEqualTo(ToolKind.Coordinator)
        assertThat(send.name).isEqualTo("SendMessage")
        assertThat((send.payload as ToolPayload.CoordinatorMessage).message).startsWith("The keyboard worker merged #113")
        assertThat((send.payload as ToolPayload.CoordinatorMessage).missing).isFalse()
        assertThat(send.argKeys).containsExactly("text")
        // A result without a payload of its own is a success, not an error.
        assertThat(send.isError).isFalse()
        val messaged = calls.getValue("toolu_01Msg").payload as ToolPayload.WorkerAction
        assertThat(messaged.kind).isEqualTo(ToolPayload.WorkerAction.Kind.Messaged)
        assertThat(messaged.worker?.agentId).isEqualTo("bc-24e35e9f-1a2d-5218-8e2e-7464ea74f671")
        assertThat(CoordinatorTranscript.hasCoordinatorContent(items)).isTrue()

        val older = HeadlessTranscript.trace(turns[1], run.copy(id = "run-0"))
        val oldCall = older.filterIsInstance<ActivityGroup>().single().calls.single()
        assertThat(oldCall.payload).isEqualTo(ToolPayload.CoordinatorMessage("PR #215 is merged."))
    }

    @Test
    fun `a record naming no tool is named from its enum or its typed params`() {
        val json = Json { ignoreUnknownKeys = true }
        val byEnum = HeadlessConversationApi.readToolCall(json.parseToJsonElement("""{"tool":"CLIENT_SIDE_TOOL_V2_SEND_TO_USER","toolCallId":"x","rawArgs":"{\"message\":\"hi\"}"}""").jsonObject)!!
        assertThat(byEnum.name).isEqualTo("send_to_user")
        assertThat(byEnum.args!!.jsonObject.getValue("message").jsonPrimitive.content).isEqualTo("hi")
        val byNumber = HeadlessConversationApi.readToolCall(json.parseToJsonElement("""{"tool":65,"toolCallId":"y","rawArgs":"{\"message\":\"hi\"}"}""").jsonObject)!!
        assertThat(byNumber.name).isEqualTo("send_to_user")
        val byParams = HeadlessConversationApi.readToolCall(json.parseToJsonElement("""{"tool":53,"toolCallId":"z","readFileV2Params":{"relativeWorkspacePath":"app/A.kt"}}""").jsonObject)!!
        assertThat(byParams.name).isEqualTo("read_file_v2")
        assertThat(byParams.args!!.jsonObject.getValue("relativeWorkspacePath").jsonPrimitive.content).isEqualTo("app/A.kt")
        val unspecified = HeadlessConversationApi.readToolCall(json.parseToJsonElement("""{"tool":"CLIENT_SIDE_TOOL_V2_UNSPECIFIED","toolCallId":"w"}""").jsonObject)!!
        assertThat(unspecified.name).isEmpty()
        assertThat(HeadlessConversationApi.readToolCall(json.parseToJsonElement("""{"name":"SendMessage"}""").jsonObject)).isNull()
    }

    /**
     * The coordinator's tool bodies are the content, and the record can carry a call in more than one shape (see
     * `record_coordinator_pages.json`, from Bennett's v0.3.21 chat whose updates went missing): `tool_call` pieces
     * with `is_streaming` and a `raw_args` fragment each, joined until they read as JSON; `streamed_back_tool_call`
     * with the whole; a call the record names by its model call id alone. A streamed call that never came whole says
     * its body is missing rather than vanishing.
     */
    @Test
    fun `a streamed call's pieces are joined, a streamed-back call is read, and one that never came whole says so`() = runBlocking<Unit> {
        val message = "All four shards rendered and their PRs are merged (#157\u2013#163)."
        val raw = """{"text":{"content":"$message"}}"""
        val pieces = raw.chunked(17)
        val record = buildString {
            append("""{"responses":[{"humanMessage":{"text":"So where we at rn","agentMode":"AGENT_MODE_PROJECT"}},""")
            pieces.forEachIndexed { index, piece ->
                append("""{"toolCall":{"tool":"CLIENT_SIDE_TOOL_V2_SEND_TO_USER","toolCallId":"toolu_pieces","name":"SendMessage","rawArgs":${JsonPrimitive(piece)},"isStreaming":true,"isLastMessage":${index == pieces.lastIndex}}},""")
            }
            append("""{"finalToolResult":{"toolCallId":"toolu_pieces","result":{"toolCallId":"toolu_pieces"}}},""")
            append("""{"streamedBackToolCall":{"tool":"CLIENT_SIDE_TOOL_V2_SEND_TO_USER","toolCallId":"toolu_back","name":"SendMessage","rawArgs":${JsonPrimitive("""{"text":{"content":"Streamed back."}}""")}}},""")
            append("""{"finalToolResult":{"toolCallId":"toolu_back","result":{}}},""")
            append("""{"toolCall":{"tool":"CLIENT_SIDE_TOOL_V2_UNSPECIFIED","modelCallId":"model_only","name":"createAgent","rawArgs":${JsonPrimitive("""{"title":"Render shard","message":"Render it."}""")}}},""")
            append("""{"finalToolResult":{"toolCallId":"model_only","result":{"agentId":"bc-w"}}},""")
            // Cut short: the first two pieces and no more.
            pieces.take(2).forEach { piece -> append("""{"toolCall":{"tool":"CLIENT_SIDE_TOOL_V2_SEND_TO_USER","toolCallId":"toolu_cut","name":"SendMessage","rawArgs":${JsonPrimitive(piece)},"isStreaming":true}},""") }
            append("""{"text":"","isMessageDone":true}],"totalResponses":${pieces.size + 8}}""")
        }
        server.enqueue(MockResponse().setBody("""{"accessToken":"s","refreshToken":"rt"}"""))
        server.enqueue(MockResponse().setBody(record))
        val page = api.fetch("bc-coord", startIndex = 0, limit = 100)

        // Each piece is a step of the same call, its fragment kept as text; the streamed-back call is a step too.
        val fragments = page.steps.filter { it.toolCall?.callId == "toolu_pieces" }.map { it.toolCall!! }
        assertThat(fragments).hasSize(pieces.size)
        assertThat(fragments.all { it.isStreaming && it.args == null && it.rawArgs != null }).isTrue()
        assertThat(fragments.last().isLastMessage).isTrue()
        val back = page.steps.single { it.toolCall?.callId == "toolu_back" }.toolCall!!
        assertThat(back.isStreaming).isTrue()
        assertThat(back.args!!.jsonObject.getValue("text").jsonObject.getValue("content").jsonPrimitive.content).isEqualTo("Streamed back.")
        assertThat(page.steps.single { it.toolCall?.callId == "model_only" }.toolCall!!.name).isEqualTo("createAgent")

        val turn = HeadlessTranscript.split(page.steps).single()
        val calls = HeadlessTranscript.body(turn, "rec-0").filterIsInstance<ActivityGroup>().flatMap { it.calls }.associateBy { it.callId }
        assertThat((calls.getValue("toolu_pieces").payload as ToolPayload.CoordinatorMessage)).isEqualTo(ToolPayload.CoordinatorMessage(message))
        assertThat((calls.getValue("toolu_back").payload as ToolPayload.CoordinatorMessage).message).isEqualTo("Streamed back.")
        assertThat(calls.getValue("model_only").payload).isInstanceOf(ToolPayload.WorkerAction::class.java)
        assertThat(calls.getValue("model_only").kind).isEqualTo(ToolKind.Coordinator)
        // The pieces that never came whole: the call stands with what the pieces held, read leniently and marked as
        // recovered (see MessageRecovery), rather than as nothing or as a body that is missing.
        val cut = calls.getValue("toolu_cut")
        assertThat(cut.payload).isEqualTo(ToolPayload.CoordinatorMessage("All four shard", recovered = true))
        assertThat(cut.kind).isEqualTo(ToolKind.Coordinator)
        assertThat(cut.truncated).isNull()
        // One step per call in the trace: the pieces did not multiply the row.
        assertThat(calls.keys).containsExactly("toolu_pieces", "toolu_back", "model_only", "toolu_cut")
    }

    @Test
    fun `the prompt's mode is read by name or by number`() {
        assertThat(HeadlessConversationApi.readProjectMode(JsonPrimitive("AGENT_MODE_PROJECT"))).isTrue()
        assertThat(HeadlessConversationApi.readProjectMode(JsonPrimitive("PROJECT"))).isTrue()
        assertThat(HeadlessConversationApi.readProjectMode(JsonPrimitive(6))).isTrue()
        assertThat(HeadlessConversationApi.readProjectMode(JsonPrimitive("AGENT_MODE_AGENT"))).isFalse()
        assertThat(HeadlessConversationApi.readProjectMode(JsonPrimitive(1))).isFalse()
        assertThat(HeadlessConversationApi.readProjectMode(null)).isFalse()
    }
}
