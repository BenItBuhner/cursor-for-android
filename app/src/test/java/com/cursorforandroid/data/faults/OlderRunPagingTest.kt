package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.faults.FaultServer.Route
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.UserMessage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * A long chat's run records on the documented path are read as far as the reader is about to scroll, not the whole
 * list: a cold open reads the newest page and one page behind it, where it used to read eight behind it at once
 * alongside the window's trace replays; each scroll up that nears the end of what is in hand reads one more. Every
 * prompt the window shows still has its own run. Over the app's real clients against [FaultServer], 300–900 ms a
 * round trip.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OlderRunPagingTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private lateinit var rig: FaultRig

    @Before
    fun setUp() {
        server = FaultServer().start()
        rig = FaultRig(server.baseUrl, folder.newFolder("rig"))
        server.addIdleAgent("bc-1", "Agent", runId(TURNS - 1))
        val messages = ArrayList<V0ConversationMessageDto>()
        repeat(TURNS) { i ->
            val runId = runId(i)
            val at = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(600L * i).toString()
            server.runs[runId] = RunDto(id = runId, agentId = "bc-1", status = "FINISHED", createdAt = at, updatedAt = at, durationMs = 30_000, result = "Reply $i")
            messages += V0ConversationMessageDto("$runId-u", "user_message", "Prompt $i")
            messages += V0ConversationMessageDto("$runId-a", "assistant_message", "Reply $i")
        }
        server.transcripts["bc-1"] = messages
    }

    @After
    fun tearDown() {
        rig.close()
        server.close()
    }

    private val state get() = rig.conversations.state("bc-1").value
    private fun footers() = state.items.count { it is RunFooter }
    private fun prompts() = state.items.filterIsInstance<UserMessage>().map { it.text }
    private fun olderPages() = server.requests(Route.ListRuns).count { it.path.contains("cursor=") }

    @Test
    fun `a cold open of a long chat reads one page of older runs, and each scroll up that nears their end one more`() = runBlocking<Unit> {
        rig.agents.refresh()
        rig.conversations.attach("bc-1")
        rig.awaitUntil { !state.isLoading && state.traceStatus.pending == 0 && footers() == WINDOW }
        rig.awaitUntil { olderPages() >= 1 }
        // Long enough for the eight pages the open used to read one after another to have gone out.
        delay(QUIET_MS)
        assertThat(olderPages()).isEqualTo(1)
        assertThat(prompts().first()).isEqualTo("Prompt ${TURNS - WINDOW}")
        assertThat(state.hasOlder).isTrue()

        // A page and a first page's worth of runs in hand: scrolls up to a window of a hundred need no more of them.
        var window = WINDOW
        while (window < FIRST_PAGE + PAGE - 2 * WINDOW) {
            window += WINDOW
            scrollUp(to = window)
        }
        assertThat(olderPages()).isEqualTo(1)
        // The next nears the end of them: one page more behind it, and the window's every prompt has its run.
        window += WINDOW
        scrollUp(to = window)
        rig.awaitUntil { olderPages() >= 2 }
        delay(QUIET_MS)
        assertThat(olderPages()).isEqualTo(2)
        assertThat(footers()).isEqualTo(window)
        assertThat(prompts()).containsExactlyElementsIn((TURNS - window until TURNS).map { "Prompt $it" }).inOrder()
    }

    /** The reader's scroll up, settled: the window [to] turns wide, each with its run, and their traces read. */
    private suspend fun scrollUp(to: Int) {
        rig.conversations.loadOlder("bc-1")
        rig.awaitUntil { !state.isLoadingOlder && footers() == to && state.traceStatus.pending == 0 }
    }

    private companion object {
        const val TURNS = 1_200
        /** The repository's window, its run list's first page and the pages behind it. */
        const val WINDOW = 10
        const val FIRST_PAGE = 20
        const val PAGE = 100
        const val QUIET_MS = 6_000L
        fun runId(i: Int) = "run-%04d".format(i)
    }
}
