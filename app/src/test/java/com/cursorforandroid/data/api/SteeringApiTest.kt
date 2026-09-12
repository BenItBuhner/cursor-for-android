package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.domain.AgentMode
import com.cursorforandroid.domain.AgentSource
import com.cursorforandroid.domain.InteractionResolution
import com.cursorforandroid.domain.PendingFollowup
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.SteerOutcome
import com.cursorforandroid.domain.ToolPayload
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Base64

/** The conversation-control corner of `aiserver.v1.BackgroundComposerService` over Connect JSON, against a fake api2 that also plays the token exchange. */
class SteeringApiTest {

    private val server = MockWebServer()
    private lateinit var api: SteeringApi

    @Before
    fun setUp() {
        server.start()
        val client = OkHttpClient()
        val base = server.url("/").toString()
        api = SteeringApi(
            rpc = ConnectJsonClient(client, base),
            tokens = SessionTokenProvider(client, apiKeyProvider = { "key_abc" }, apiUrl = base, now = { 0L }),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `an answer goes out as SubmitInteractionResponseBackgroundComposer, one answer per question, and the outcome is read`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("""{"resolutionOutcome":"RESOLUTION_OUTCOME_SIGNAL_ENQUEUED"}"""))
        server.enqueue(MockResponse().setBody("""{"resolutionOutcome":2}"""))
        server.enqueue(MockResponse().setBody("{}"))

        val answers = listOf(
            ToolPayload.Question.Answer("q1", listOf("opt-b")),
            ToolPayload.Question.Answer("q2", emptyList(), "Ship it on Friday"),
            ToolPayload.Question.Answer("q3", listOf("x", "y"), "  "),
        )
        assertThat(api.answerQuestion("bc-1", "call-9", answers)).isEqualTo(InteractionResolution.SIGNAL_ENQUEUED)
        assertThat(api.answerQuestion("bc-1", "call-9", answers)).isEqualTo(InteractionResolution.NOT_PAUSED)
        assertThat(api.answerQuestion("bc-1", "call-9", answers)).isEqualTo(InteractionResolution.UNKNOWN)

        server.takeRequest() // the exchange
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/aiserver.v1.BackgroundComposerService/SubmitInteractionResponseBackgroundComposer")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer s")
        assertThat(request.getHeader("Connect-Protocol-Version")).isEqualTo("1")
        val body = request.json()
        assertThat(body["bcId"]?.jsonPrimitive?.content).isEqualTo("bc-1")
        assertThat(body["askQuestionToolCallId"]?.jsonPrimitive?.content).isEqualTo("call-9")
        val response = body["interactionResponse"]!!.jsonObject
        assertThat(response["id"]?.jsonPrimitive?.content).isEqualTo("call-9")
        val sent = response["askQuestionInteractionResponse"]!!.jsonObject["answers"]!!.jsonArray.map { it.jsonObject }
        assertThat(sent.map { it["questionId"]!!.jsonPrimitive.content }).containsExactly("q1", "q2", "q3").inOrder()
        assertThat(sent[0]["selectedOptionIds"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly("opt-b")
        assertThat(sent[0].containsKey("freeformText")).isFalse()
        assertThat(sent[1]["selectedOptionIds"]!!.jsonArray).isEmpty()
        assertThat(sent[1]["freeformText"]?.jsonPrimitive?.content).isEqualTo("Ship it on Friday")
        // Blank free text is no answer at all, not an empty string the agent has to read.
        assertThat(sent[2].containsKey("freeformText")).isFalse()
    }

    @Test
    fun `a follow-up is filed as AddAsyncFollowupBackgroundComposer with its mode, model and images, queued or sent now`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("""{"runId":"run-7"}"""))
        server.enqueue(MockResponse().setBody("{}"))

        val image = PromptImage(byteArrayOf(1, 2, 3), "image/PNG")
        val followup = AccountFollowup("  Explain the cache miss  ", listOf(image), mode = AgentMode.ASK, modelId = "claude-4", followupId = "fu-1")
        assertThat(api.addFollowup("bc-1", followup, synchronous = false)).isEqualTo("run-7")
        assertThat(api.addFollowup("bc-1", AccountFollowup("Stop and do this instead"), synchronous = true)).isNull()

        server.takeRequest()
        val queued = server.takeRequest()
        assertThat(queued.path).isEqualTo("/aiserver.v1.BackgroundComposerService/AddAsyncFollowupBackgroundComposer")
        val body = queued.json()
        assertThat(body["bcId"]?.jsonPrimitive?.content).isEqualTo("bc-1")
        assertThat(body["followup"]?.jsonPrimitive?.content).isEqualTo("Explain the cache miss")
        assertThat(body["synchronous"]?.jsonPrimitive?.content).isEqualTo("false")
        assertThat(body["followupSource"]?.jsonPrimitive?.content).isEqualTo("BACKGROUND_COMPOSER_SOURCE_API")
        assertThat(body["followupId"]?.jsonPrimitive?.content).isEqualTo("fu-1")
        assertThat(body["requestedModel"]!!.jsonObject["modelId"]?.jsonPrimitive?.content).isEqualTo("claude-4")
        val message = body["followupMessage"]!!.jsonObject
        assertThat(message["text"]?.jsonPrimitive?.content).isEqualTo("Explain the cache miss")
        assertThat(message["agentMode"]?.jsonPrimitive?.content).isEqualTo("AGENT_MODE_ASK")
        val userMessage = body["followupConversationAction"]!!.jsonObject["userMessageAction"]!!.jsonObject.also {
            assertThat(it["sendToInteractionListener"]?.jsonPrimitive?.content).isEqualTo("true")
        }["userMessage"]!!.jsonObject
        assertThat(userMessage["text"]?.jsonPrimitive?.content).isEqualTo("Explain the cache miss")
        assertThat(userMessage["mode"]?.jsonPrimitive?.content).isEqualTo("AGENT_MODE_ASK")
        assertThat(userMessage["messageId"]?.jsonPrimitive?.content).startsWith("msg-")
        val images = userMessage["selectedContext"]!!.jsonObject["selectedImages"]!!.jsonArray.map { it.jsonObject }
        assertThat(images).hasSize(1)
        assertThat(images[0]["data"]?.jsonPrimitive?.content).isEqualTo(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)))
        assertThat(images[0]["mimeType"]?.jsonPrimitive?.content).isEqualTo("image/png")
        assertThat(images[0]["uuid"]?.jsonPrimitive?.content).isNotEmpty()

        // Sent now: the interrupting form, and nothing the message does not carry.
        val now = server.takeRequest().json()
        assertThat(now["synchronous"]?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(now.containsKey("requestedModel")).isFalse()
        assertThat(now["followupMessage"]!!.jsonObject.containsKey("agentMode")).isFalse()
        val plain = now["followupConversationAction"]!!.jsonObject["userMessageAction"]!!.jsonObject["userMessage"]!!.jsonObject
        assertThat(plain.containsKey("mode")).isFalse()
        assertThat(plain.containsKey("selectedContext")).isFalse()
    }

    @Test
    fun `the queue is read as the account lists it, by name or by number, skipping records without an id`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(
            MockResponse().setBody(
                """{"pendingFollowups":[
                     {"followupId":"fu-1","text":"Then add tests","createdAtMs":"1700000000000","source":"BACKGROUND_COMPOSER_SOURCE_GLASS","isEditing":true},
                     {"followupId":"fu-2","text":"And a changelog line","createdAtMs":1700000001000,"source":6},
                     {"followupId":"fu-3"},
                     {"text":"orphan"}
                   ]}""",
            ),
        )

        val queue = api.listPending("bc-1")

        assertThat(queue).containsExactly(
            PendingFollowup("fu-1", "Then add tests", 1_700_000_000_000L, AgentSource.GLASS, isEditing = true),
            PendingFollowup("fu-2", "And a changelog line", 1_700_000_001_000L, AgentSource.API),
            PendingFollowup("fu-3", ""),
        ).inOrder()
        server.takeRequest()
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/aiserver.v1.BackgroundComposerService/ListPendingFollowups")
        assertThat(request.json().mapValues { it.value.jsonPrimitive.content }).containsExactly("bcId", "bc-1")
    }

    @Test
    fun `the queue's edits name the message, and a refusal the account spells out is an error`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        server.enqueue(MockResponse().setBody("""{"success":false,"errorMessage":"Followup already sent"}"""))

        api.updatePending("bc-1", "fu-1", " Then add tests too ")
        api.deletePending("bc-1", "fu-2")
        api.reorderPending("bc-1", "fu-1", "fu-3", insertAfter = true)
        api.submitPendingNow("bc-1", "fu-1")
        api.markEditing("bc-1", "fu-1", editing = true)
        val refused = runCatching { api.submitPendingNow("bc-1", "fu-9") }.exceptionOrNull()
        assertThat(refused).isInstanceOf(ConnectRpcException::class.java)
        assertThat(refused).hasMessageThat().isEqualTo("Followup already sent")

        server.takeRequest()
        val update = server.takeRequest()
        assertThat(update.path).isEqualTo("/aiserver.v1.BackgroundComposerService/UpdatePendingFollowup")
        val updateBody = update.json()
        assertThat(updateBody["followupId"]?.jsonPrimitive?.content).isEqualTo("fu-1")
        assertThat(updateBody["updatedMessage"]!!.jsonObject["text"]?.jsonPrimitive?.content).isEqualTo("Then add tests too")
        val delete = server.takeRequest()
        assertThat(delete.path).isEqualTo("/aiserver.v1.BackgroundComposerService/DeletePendingFollowup")
        assertThat(delete.json().mapValues { it.value.jsonPrimitive.content }).containsExactly("bcId", "bc-1", "followupId", "fu-2")
        val reorder = server.takeRequest()
        assertThat(reorder.path).isEqualTo("/aiserver.v1.BackgroundComposerService/ReorderPendingFollowup")
        assertThat(reorder.json().mapValues { it.value.jsonPrimitive.content }).containsExactly("bcId", "bc-1", "followupId", "fu-1", "targetFollowupId", "fu-3", "insertAfter", "true")
        val submit = server.takeRequest()
        assertThat(submit.path).isEqualTo("/aiserver.v1.BackgroundComposerService/SubmitPendingFollowupNow")
        assertThat(submit.json()["followupId"]?.jsonPrimitive?.content).isEqualTo("fu-1")
        val editing = server.takeRequest()
        assertThat(editing.path).isEqualTo("/aiserver.v1.BackgroundComposerService/MarkFollowupEditing")
        assertThat(editing.json().mapValues { it.value.jsonPrimitive.content }).containsExactly("bcId", "bc-1", "followupId", "fu-1", "editing", "true")
    }

    @Test
    fun `a steer and a promoted follow-up are injections into the running turn, with the outcome read by name or number`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("""{"outcome":"OUTCOME_QUEUED"}"""))
        server.enqueue(MockResponse().setBody("""{"outcome":2}"""))
        server.enqueue(MockResponse().setBody("""{"outcome":"OUTCOME_REJECTED"}"""))

        assertThat(api.steer("bc-1", " Use the v2 endpoint ", "run-9")).isEqualTo(SteerOutcome.QUEUED)
        assertThat(api.promoteFollowup("bc-1", "fu-1", "run-9")).isEqualTo(SteerOutcome.QUEUED_FOR_NEXT_TURN)
        assertThat(api.promoteFollowup("bc-1", "fu-1", null)).isEqualTo(SteerOutcome.REJECTED)

        server.takeRequest()
        val steer = server.takeRequest()
        assertThat(steer.path).isEqualTo("/aiserver.v1.BackgroundComposerService/InjectBackgroundComposerContext")
        val body = steer.json()
        assertThat(body["bcId"]?.jsonPrimitive?.content).isEqualTo("bc-1")
        assertThat(body["source"]?.jsonPrimitive?.content).isEqualTo("BACKGROUND_COMPOSER_SOURCE_API")
        assertThat(body.containsKey("promoteFollowupId")).isFalse()
        val action = body["injectContextAction"]!!.jsonObject
        assertThat(action["injectionId"]?.jsonPrimitive?.content).startsWith("inj-")
        assertThat(action["expectedRunId"]?.jsonPrimitive?.content).isEqualTo("run-9")
        assertThat(action["userContext"]!!.jsonObject["userMessage"]!!.jsonObject["text"]?.jsonPrimitive?.content).isEqualTo("Use the v2 endpoint")

        val promote = server.takeRequest().json()
        assertThat(promote["promoteFollowupId"]?.jsonPrimitive?.content).isEqualTo("fu-1")
        val promoteAction = promote["injectContextAction"]!!.jsonObject
        assertThat(promoteAction["expectedRunId"]?.jsonPrimitive?.content).isEqualTo("run-9")
        // A promotion carries no message of its own: the queued one is the message.
        assertThat(promoteAction.containsKey("userContext")).isFalse()
        assertThat(server.takeRequest().json()["injectContextAction"]!!.jsonObject.containsKey("expectedRunId")).isFalse()
    }

    @Test
    fun `pause, resume, a tool call's cancel and a wake name the chat and read the account's yes or no`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("""{"accepted":true}"""))
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("""{"signaled":true}"""))
        server.enqueue(MockResponse().setBody("""{"signaled":false}"""))

        api.pause("bc-1", "run-9")
        api.resume("bc-1")
        assertThat(api.cancelToolCall("bc-1", "call-3")).isTrue()
        assertThat(api.cancelToolCall("bc-1", "call-4")).isFalse()
        assertThat(api.wake("bc-1")).isTrue()
        assertThat(api.wake("bc-1")).isFalse()

        server.takeRequest()
        val pause = server.takeRequest()
        assertThat(pause.path).isEqualTo("/aiserver.v1.BackgroundComposerService/PauseBackgroundComposer")
        assertThat(pause.json().mapValues { it.value.jsonPrimitive.content }).containsExactly("bcId", "bc-1", "source", "BACKGROUND_COMPOSER_SOURCE_API", "runId", "run-9")
        val resume = server.takeRequest()
        assertThat(resume.path).isEqualTo("/aiserver.v1.BackgroundComposerService/ResumeBackgroundComposer")
        assertThat(resume.json().mapValues { it.value.jsonPrimitive.content }).containsExactly("bcId", "bc-1")
        val cancel = server.takeRequest()
        assertThat(cancel.path).isEqualTo("/aiserver.v1.BackgroundComposerService/CancelBackgroundComposerToolCall")
        assertThat(cancel.json().mapValues { it.value.jsonPrimitive.content }).containsExactly("bcId", "bc-1", "toolCallId", "call-3", "source", "BACKGROUND_COMPOSER_SOURCE_API")
        server.takeRequest()
        val wake = server.takeRequest()
        assertThat(wake.path).isEqualTo("/aiserver.v1.BackgroundComposerService/WakeBackgroundComposer")
        assertThat(wake.json().mapValues { it.value.jsonPrimitive.content }).containsExactly("bcId", "bc-1", "reason", "WAKE_BACKGROUND_COMPOSER_REASON_FOLLOWUP_COMPOSE")
    }

    @Test
    fun `a lapsed session is started again once, and a refusal keeps the account's reason`() = runBlocking<Unit> {
        server.enqueue(session("old"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"unauthenticated","message":"Error"}"""))
        server.enqueue(session("new"))
        server.enqueue(MockResponse().setBody("""{"pendingFollowups":[]}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"code":"permission_denied","message":"Error","details":[{"debug":{"details":{"detail":"Steering is not enabled for this team"}}}]}"""))

        assertThat(api.listPending("bc-1")).isEmpty()
        val refused = runCatching { api.steer("bc-1", "go", null) }.exceptionOrNull() as ConnectRpcException
        assertThat(refused.code).isEqualTo("permission_denied")
        assertThat(refused).hasMessageThat().isEqualTo("Steering is not enabled for this team")

        server.takeRequest()
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer old")
        server.takeRequest()
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer new")
    }

    @Test
    fun `the wire enums are read as the account spells them`() {
        assertThat(InteractionResolution.parse("RESOLUTION_OUTCOME_QUERY_ID_SIGNAL_ENQUEUED")).isEqualTo(InteractionResolution.QUERY_ID_SIGNAL_ENQUEUED)
        assertThat(InteractionResolution.parse("not_paused")).isEqualTo(InteractionResolution.NOT_PAUSED)
        assertThat(InteractionResolution.parse("1")).isEqualTo(InteractionResolution.SIGNAL_ENQUEUED)
        assertThat(InteractionResolution.parse("RESOLUTION_OUTCOME_UNSPECIFIED")).isEqualTo(InteractionResolution.UNKNOWN)
        assertThat(InteractionResolution.parse(null)).isEqualTo(InteractionResolution.UNKNOWN)
        assertThat(InteractionResolution.NOT_PAUSED.delivered).isFalse()
        assertThat(InteractionResolution.SIGNAL_ENQUEUED.delivered).isTrue()

        assertThat(AgentMode.parse("AGENT_MODE_DEBUG")).isEqualTo(AgentMode.DEBUG)
        assertThat(AgentMode.parse("ask")).isEqualTo(AgentMode.ASK)
        assertThat(AgentMode.parse("3")).isEqualTo(AgentMode.PLAN)
        assertThat(AgentMode.parse("AGENT_MODE_UNSPECIFIED")).isNull()
        assertThat(AgentMode.ASK.wireName).isEqualTo("AGENT_MODE_ASK")
        assertThat(AgentMode.DEBUG.number).isEqualTo(4)
        assertThat(AgentMode.ASK.needsAccountService).isTrue()
        assertThat(AgentMode.PLAN.needsAccountService).isFalse()
        assertThat(AgentMode.PLAN.publicName).isEqualTo("plan")
        assertThat(AgentMode.ofPlanMode(true)).isEqualTo(AgentMode.PLAN)
        assertThat(AgentMode.ofPlanMode(false)).isEqualTo(AgentMode.AGENT)
        assertThat(AgentMode.ofPlanMode(null)).isNull()
    }

    private fun session(token: String) = MockResponse().setBody("""{"accessToken":"$token","refreshToken":"rt"}""")

    private fun RecordedRequest.json() = Json.parseToJsonElement(body.readUtf8()).jsonObject
}
