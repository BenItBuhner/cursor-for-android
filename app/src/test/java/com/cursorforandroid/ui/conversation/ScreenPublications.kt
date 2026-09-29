package com.cursorforandroid.ui.conversation

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.ThinkingBlock
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.time.Instant

/**
 * The real conversation screen over a long chat whose newest reply streams, one publication a delta: the screen, its
 * view model and the presenter are the app's own, and the chat's states are published straight into the repository's
 * entry for it (read by reflection), so a transcript of thousands of turns costs no paging to reach. Each turn is a
 * prompt, a thought with twelve reads, a reply and a footer; the newest turn's reply grows a word a publication, every
 * other item the instance it was, as the repository keeps them. The first turn sets a goal and the transcript's
 * refresh has failed, so the goal strip and a load notice stand over the composer.
 */
internal class ScreenPublications(private val turns: Int, private val agentId: String = "bc-publications") {
    val api = FakeCursorApi()
    private val streamer = FakeRunStreamer()
    lateinit var graph: AppGraph
        private set
    private lateinit var entryState: MutableStateFlow<ConversationState>
    private lateinit var base: ConversationState
    private lateinit var prefix: List<TimelineItem>
    private var words = StringBuilder("Working through the newest module. ")

    fun seed(now: Long) {
        api.addFinishedAgent(agentId, "Long chat", Triple("run-1", "Prompt", "Reply"), firstRunAt = Instant.ofEpochMilli(now - 3_600_000L).toString())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fake = CursorBackend(api, streamer, isDemo = true)
        graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }, demo = fake)
        runBlocking {
            graph.session.enterDemo()
            graph.agents.refresh()
        }
        prefix = transcript(turns, now)
        base = ConversationState(
            agentId,
            isLoading = false,
            runStatus = RunStatus.RUNNING,
            isStreaming = true,
            transcriptError = "HTTP 502",
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    fun compose(rule: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>) {
        rule.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    ConversationScreen(graph, agentId, onBack = {})
                }
            }
        }
        rule.waitUntil(60_000) { !graph.conversations.state(agentId).value.isLoading }
        rule.waitForIdle()
        entryState = entryState()
    }

    /** The view model the screen composed, from the activity's store. */
    fun viewModel(rule: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>): ConversationViewModel =
        @Suppress("RestrictedApi")
        rule.activity.viewModelStore["conversation-$agentId"] as ConversationViewModel

    /** Publishes the chat with its newest reply one word longer. */
    fun publish() {
        words.append("word").append(words.length).append(' ')
        entryState.value = base.copy(items = ArrayList<TimelineItem>(prefix.size + 1).apply { addAll(prefix); add(AssistantMessage("a-live", words.toString(), isStreaming = true)) })
    }

    val published: ConversationState get() = entryState.value

    /**
     * Idles the main thread until [viewModel] has presented the last state published. Compose's `waitUntil` does not
     * run the paused main looper, where the presentation lands from its thread; `waitForIdle` does.
     */
    fun settle(rule: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>, viewModel: ConversationViewModel) {
        val published = published
        val deadline = System.nanoTime() + 120_000_000_000L
        rule.waitForIdle()
        while (viewModel.presented.value.state !== published) {
            check(System.nanoTime() < deadline) { "the last state published was never presented" }
            Thread.sleep(5)
            rule.waitForIdle()
        }
    }

    fun shows(rule: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>, tag: String): Boolean =
        rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Suppress("UNCHECKED_CAST")
    private fun entryState(): MutableStateFlow<ConversationState> {
        val repo = graph.conversations
        val entry = repo.javaClass.getDeclaredMethod("entry", String::class.java).apply { isAccessible = true }.invoke(repo, agentId)
        return entry.javaClass.getDeclaredField("state").apply { isAccessible = true }.get(entry) as MutableStateFlow<ConversationState>
    }

    companion object {
        /** [count] turns, the newest one's prompt and work without its reply (see [publish]). */
        fun transcript(count: Int, now: Long): List<TimelineItem> {
            val out = ArrayList<TimelineItem>(count * 4)
            for (t in 0 until count) {
                val at = now - (count - t) * 600_000L
                out += UserMessage("u$t", "Prompt $t: refactor the ${t}th module and list the files touched.", at)
                val reads = (0 until 12).map { k -> ToolCall("c$t-$k", "read_file", ToolKind.Read, ToolCall.STATUS_COMPLETED, "File$k.kt") }
                val goal = if (t == 0) listOf(ToolCall("goal-0", "createGoal", ToolKind.Other, ToolCall.STATUS_COMPLETED, "", payload = ToolPayload.GoalChange(ToolPayload.GoalChange.Action.Set, objective = "Refactor every module."))) else emptyList()
                out += ActivityGroup("g$t", goal + listOf(ThinkingBlock("Reading module $t before changing it.", 2)) + reads)
                if (t == count - 1) break
                out += AssistantMessage("a$t", "Reply $t\n\nRefactored the module.\n\n- `File1.kt`: split the class\n- `File2.kt`: moved the helper")
                out += RunFooter("f$t", "run$t", RunStatus.FINISHED, 30_000L, emptyList(), endedAtMillis = at + 30_000L)
            }
            return out
        }
    }
}
