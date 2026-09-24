package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.faults.FaultServer.Route
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.NoticeCard
import com.cursorforandroid.domain.TranscriptPresenter
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.domain.UserMessage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * A regular chat (no Project) in Extended mode, its transcript the account's record: the record gives each turn's
 * prompt and tool calls and not the agent's reply, and the runs' logs have expired — what Bennett described on
 * 2026-09-20 as chats "empty except some tool calls here and there". The `/v0` transcript has every reply: it is
 * read once and each turn takes its own, paired by its prompt (see `ConversationRepository.fillTextFromTranscript`);
 * a turn whose log still answers is left to the log; a turn the record gives whole is left alone.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RegularChatTextTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private var rig: FaultRig? = null
    private val agentId = "bc-regular-chat"
    private val turns = 20
    private val firstAt = 1_800_000_000_000L - turns * 300_000L

    private fun prompt(i: Int) = "Prompt $i: please look at the composer's padding" + if (i % 7 == 0) " (again)" else ""
    private fun reply(i: Int) = "Reply $i: the padding comes from the composer's dock; I have adjusted it and the tests pass."

    /** A turn of the record: the prompt, one `read_file` call whole, [text] when the record carries the reply. */
    private fun recordTurn(i: Int, withText: Boolean): List<JsonObject> = buildList {
        add(buildJsonObject { put("humanMessage", buildJsonObject { put("text", prompt(i)); put("createdAt", (firstAt + i * 300_000L).toString()) }) })
        add(buildJsonObject { put("toolCall", buildJsonObject { put("tool", "CLIENT_SIDE_TOOL_V2_READ_FILE"); put("toolCallId", "toolu_$i"); put("name", "read_file"); put("rawArgs", """{"target_file":"app/src/Composer.kt"}""") }) })
        add(buildJsonObject { put("finalToolResult", buildJsonObject { put("toolCallId", "toolu_$i"); put("result", buildJsonObject { put("success", buildJsonObject { put("contents", "fun Composer() {}"); put("totalLines", 1) }) }) }) })
        if (withText) add(buildJsonObject { put("text", reply(i)) })
        add(buildJsonObject { put("text", ""); put("isMessageDone", true) })
    }

    @Before
    fun setUp() {
        server = FaultServer(rttMillis = 300L..600L).start()
        (1..turns).forEach { i ->
            val at = Instant.ofEpochMilli(firstAt + i * 300_000L).toString()
            server.runs["run-$i"] = RunDto(id = "run-$i", agentId = agentId, status = "FINISHED", createdAt = at, updatedAt = Instant.ofEpochMilli(firstAt + i * 300_000L + 40_000L).toString(), durationMs = 40_000L, result = null)
        }
        server.agents[agentId] = AgentDto(id = agentId, name = "Composer padding", status = "IDLE", createdAt = Instant.ofEpochMilli(firstAt).toString(), updatedAt = Instant.ofEpochMilli(firstAt + turns * 300_000L + 40_000L).toString(), latestRunId = "run-$turns", url = "https://cursor.com/agents/$agentId")
        server.v0[agentId] = V0AgentDto(id = agentId, name = "Composer padding", status = "FINISHED")
        server.transcripts[agentId] = (1..turns).flatMap { i -> listOf(V0ConversationMessageDto("run-$i-u", "user_message", prompt(i)), V0ConversationMessageDto("run-$i-a", "assistant_message", reply(i))) }
        // The record: every turn's prompt and call, the reply only in the two newest turns; the logs all expired.
        server.records[agentId] = (1..turns).flatMap { i -> recordTurn(i, withText = i >= turns - 1) }
        server.pageSize = 100
    }

    @After
    fun tearDown() {
        rig?.close()
        server.close()
    }

    private fun rig(): FaultRig = FaultRig(server.baseUrl, folder.newFolder("rig-${System.nanoTime()}"), readTimeoutMs = 20_000L, extended = true).also {
        it.now = 1_800_000_000_000L
        rig = it
    }

    private fun requests(): Map<Route, Int> = server.seen.groupingBy { it.route }.eachCount()

    @Test
    fun `a turn the record gives without its reply, its log gone, takes the reply from the transcript - once, its own`() = runBlocking<Unit> {
        val rig = rig()
        val conversations = rig.conversations
        conversations.attach(agentId)
        rig.awaitUntil(60_000) { conversations.state(agentId).value.let { !it.isLoading && it.items.isNotEmpty() } }
        // The logs are asked once each and found expired; then the transcript is read, once, and the replies land.
        rig.awaitUntil(60_000) { conversations.state(agentId).value.items.filterIsInstance<AssistantMessage>().size >= 10 }
        delay(1_500)
        val state = conversations.state(agentId).value
        assertThat(conversations.loadDiagnostics(agentId)!!.source).isEqualTo("record")
        assertThat(requests()[Route.Conversation]).isEqualTo(1)
        // Every turn of the window has its prompt, its call and its reply — the record's own for the two it carried,
        // the transcript's for the rest — and no notice that the turn's activity is gone.
        val rows = TranscriptPresenter().present(state.items, coordinatorMode = false, runActive = false).rows
        val replies = rows.filterIsInstance<TranscriptRow.Item>().map { it.item }.filterIsInstance<AssistantMessage>().map { it.markdown }
        assertThat(replies).containsExactlyElementsIn((turns - 9..turns).map { reply(it) }).inOrder()
        val prompts = state.items.filterIsInstance<UserMessage>().map { it.text }
        assertThat(prompts).containsExactlyElementsIn((turns - 9..turns).map { prompt(it) }).inOrder()
        assertThat(state.items.filterIsInstance<ActivityGroup>().flatMap { it.calls }.count { it.name == "read_file" }).isEqualTo(10)
        assertThat(state.items.filterIsInstance<NoticeCard>()).isEmpty()
        // Each reply stands under its own prompt: prompt, call, reply, footer, in turn order.
        val kinds = state.items.map { it::class.simpleName }
        (turns - 9..turns).forEach { i ->
            val at = state.items.indexOfFirst { it is UserMessage && it.text == prompt(i) }
            assertThat(kinds.subList(at, at + 4)).containsExactly("UserMessage", "ActivityGroup", "AssistantMessage", "RunFooter").inOrder()
            assertThat((state.items[at + 2] as AssistantMessage).markdown).isEqualTo(reply(i))
        }
        // The diagnostics say which turns the transcript answered for.
        val lines = conversations.loadDiagnostics(agentId)!!.runs
        assertThat(lines.count { it.trace == "shown" }).isEqualTo(10)
        val fromTranscript = state.items.filterIsInstance<AssistantMessage>().count { it.id.startsWith("rec-text-") }
        assertThat(fromTranscript).isEqualTo(8)
    }

    @Test
    fun `a turn whose log still answers is left to the log, and the transcript is not read for it`() = runBlocking<Unit> {
        // Every log retained: the record's missing replies come from the logs, whole, as before.
        (1..turns).forEach { i ->
            server.logs["run-$i"] = listOf(
                "status" to """{"runId":"run-$i","status":"RUNNING"}""",
                "tool_call" to """{"callId":"run-$i-c1","name":"read_file","status":"completed","args":{"target_file":"app/src/Composer.kt"}}""",
                "assistant" to """{"text":"${reply(i)}"}""",
                "result" to """{"runId":"run-$i","status":"FINISHED","text":"${reply(i)}","durationMs":40000}""",
            )
        }
        val rig = rig()
        val conversations = rig.conversations
        conversations.attach(agentId)
        rig.awaitUntil(60_000) { conversations.state(agentId).value.let { !it.isLoading && it.items.filterIsInstance<AssistantMessage>().size >= 10 } }
        delay(1_500)
        val state = conversations.state(agentId).value
        assertThat(requests()[Route.Conversation]).isNull()
        assertThat(state.items.filterIsInstance<AssistantMessage>().map { it.markdown }).containsExactlyElementsIn((turns - 9..turns).map { reply(it) }).inOrder()
        assertThat(state.items.filterIsInstance<AssistantMessage>().none { it.id.startsWith("rec-text-") }).isTrue()
    }
}
