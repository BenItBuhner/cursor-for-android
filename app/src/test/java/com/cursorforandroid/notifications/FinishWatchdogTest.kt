package com.cursorforandroid.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.RunGitBranchDto
import com.cursorforandroid.data.api.dto.RunGitDto
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.AgentRepository
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.data.repo.SessionManager
import com.cursorforandroid.data.repo.parseIsoMillis
import com.cursorforandroid.domain.LivePhase
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.TrackedRun
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The check that stands in for the live notification service when it is gone: it reads the records of the runs the
 * list calls running, announces the ones that are over — once — and says whether there is anything left to watch.
 * Robolectric only because [SessionManager] needs a Context for its stores.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class FinishWatchdogTest {

    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer()
    private var now = 1_800_000_000_000L
    private var serviceActive = false
    private var canShowLive = true
    private var postSucceeds = true
    private val announced = CopyOnWriteArrayList<TrackedRun>()
    private val remembered = mutableSetOf<String>()

    private lateinit var prefs: PreferencesStore
    private lateinit var session: SessionManager
    private lateinit var agents: AgentRepository

    @Before
    fun setUp() = runBlocking {
        AppClock.nowMillis = { now }
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = PreferencesStore(context)
        val backend = CursorBackend(api, streamer, isDemo = true)
        session = SessionManager(SecureKeyStore(context), prefs, backend, backend)
        session.enterDemo()
        agents = AgentRepository(session, prefs, AttachmentStore(context))
    }

    @After
    fun tearDown() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun watchdog() = FinishWatchdog(
        session = session,
        agents = agents,
        prefs = prefs,
        runRecord = { agentId, runId -> api.getRun(agentId, runId) },
        isServiceActive = { serviceActive },
        canShowLive = { canShowLive },
        announce = { announced += it; postSucceeds },
        nowProvider = { now },
        announced = remembered,
    )

    private fun finish(runId: String, status: String, result: String, durationMs: Long, at: String, git: RunGitDto? = null) {
        api.runs[runId] = api.runs.getValue(runId).copy(status = status, result = result, durationMs = durationMs, updatedAt = at, git = git)
    }

    @Test
    fun `announces the runs that finished while nothing followed them, once each, and keeps watching the rest`() = runBlocking {
        api.addRunningAgent("bc-1", "Update quick action pills", "run-1")
        api.addRunningAgent("bc-2", "Second agent", "run-2")
        agents.refresh()
        assertThat(agents.state.value.agents.count { it.isRunning }).isEqualTo(2)

        // The service is gone but the process is not; the first run finished on the server meanwhile.
        val git = RunGitDto(listOf(RunGitBranchDto("github.com/acme/app", "cursor/pills-1a2b", "https://github.com/acme/app/pull/7")))
        finish("run-1", "FINISHED", "Restyled the pills.", 185_000, "2026-04-13T18:33:05.000Z", git)
        val listReads = api.listAgentsCalls
        val recordReads = api.getRunCalls

        assertThat(watchdog().check()).isEqualTo(FinishWatchdog.Outcome.Watching(1))
        assertThat(announced.map { it.title }).containsExactly("Update quick action pills")
        with(announced.single()) {
            assertThat(agentId).isEqualTo("bc-1")
            assertThat(runId).isEqualTo("run-1")
            assertThat(status).isEqualTo(RunStatus.FINISHED)
            assertThat(phase).isEqualTo(LivePhase.Finished)
            assertThat(summary).isEqualTo("Restyled the pills.")
            assertThat(durationMs).isEqualTo(185_000L)
            // Nothing streamed, so no tool digest: the card's second line is the duration.
            assertThat(statsLine()).isEqualTo("Worked 3m 5s")
            assertThat(branch).isEqualTo("cursor/pills-1a2b")
            assertThat(prUrl).isEqualTo("https://github.com/acme/app/pull/7")
            assertThat(finishedAtMillis).isEqualTo(parseIsoMillis("2026-04-13T18:33:05.000Z"))
            assertThat(startedAtMillis).isEqualTo(parseIsoMillis("2026-04-13T18:30:00.000Z"))
        }
        // The row is settled too, so the list, the widget and the disk agree with the card.
        val row = agents.agent("bc-1")!!
        assertThat(row.isRunning).isFalse()
        assertThat(row.runStatus).isEqualTo(RunStatus.FINISHED)
        assertThat(row.prUrl).isEqualTo("https://github.com/acme/app/pull/7")
        // The list was fetched moments ago, so it was trusted: only the two records were read.
        assertThat(api.listAgentsCalls).isEqualTo(listReads)
        assertThat(api.getRunCalls - recordReads).isEqualTo(2)

        // Nothing changed: nothing is announced again, and the second run is still being watched.
        assertThat(watchdog().check()).isEqualTo(FinishWatchdog.Outcome.Watching(1))
        assertThat(announced).hasSize(1)

        // The second run fails; its card says so, and there is nothing left to watch.
        finish("run-2", "ERROR", "Build failed", 9_000, "2026-04-13T18:40:00.000Z")
        assertThat(watchdog().check()).isEqualTo(FinishWatchdog.Outcome.Done)
        assertThat(announced.map { it.title }).containsExactly("Update quick action pills", "Second agent").inOrder()
        assertThat(announced.last().status).isEqualTo(RunStatus.ERROR)
        assertThat(announced.last().summary).isEqualTo("Build failed")
    }

    @Test
    fun `a list the app fetched a while ago is fetched again first, and the rows it settles are announced from it`() = runBlocking {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        agents.refresh()
        val listReads = api.listAgentsCalls

        // Long enough for the list to count as stale; the run finished in the meantime.
        now += 11 * 60_000
        finish("run-1", "FINISHED", "Added it.", 65_000, "2026-04-13T18:31:05.000Z")
        assertThat(watchdog().check()).isEqualTo(FinishWatchdog.Outcome.Done)
        assertThat(api.listAgentsCalls).isEqualTo(listReads + 1)
        assertThat(announced.map { it.title }).containsExactly("Agent")
        assertThat(announced.single().summary).isEqualTo("Added it.")
        assertThat(announced.single().statsLine()).isEqualTo("Worked 1m 5s")
        assertThat(agents.agent("bc-1")!!.isRunning).isFalse()
    }

    @Test
    fun `a cancelled run is not announced, and a row that moved on to a newer run is left alone`() = runBlocking {
        api.addRunningAgent("bc-1", "Cancelled", "run-1")
        api.addRunningAgent("bc-2", "Followed up", "run-2")
        agents.refresh()
        now += 11 * 60_000
        finish("run-1", "CANCELLED", "", 3_000, "2026-04-13T18:30:03.000Z")
        // The user followed up on the second agent elsewhere: the fetch finds the row on a newer run, so the end of the
        // old one is old news and the new one is what is watched.
        finish("run-2", "FINISHED", "Old turn.", 5_000, "2026-04-13T18:30:05.000Z")
        api.runs["run-3"] = RunDto(id = "run-3", agentId = "bc-2", status = "RUNNING", createdAt = "2026-04-13T18:41:00.000Z", updatedAt = "2026-04-13T18:41:00.000Z")
        api.agents["bc-2"] = api.agents.getValue("bc-2").copy(latestRunId = "run-3", updatedAt = "2026-04-13T18:41:00.000Z")

        assertThat(watchdog().check()).isEqualTo(FinishWatchdog.Outcome.Watching(1))
        assertThat(announced).isEmpty()
        assertThat(agents.agent("bc-1")!!.runStatus).isEqualTo(RunStatus.CANCELLED)
        with(agents.agent("bc-2")!!) {
            assertThat(latestRunId).isEqualTo("run-3")
            assertThat(isRunning).isTrue()
        }
    }

    @Test
    fun `a record that cannot be read leaves the run watched`() = runBlocking {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        agents.refresh()
        api.runs.remove("run-1")
        assertThat(watchdog().check()).isEqualTo(FinishWatchdog.Outcome.Watching(1))
        assertThat(announced).isEmpty()
        assertThat(agents.agent("bc-1")!!.isRunning).isTrue()
    }

    @Test
    fun `defers to a service that is up, and does nothing when the feature is off or nothing runs`() = runBlocking {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        agents.refresh()
        finish("run-1", "FINISHED", "Done.", 1_000, "2026-04-13T18:30:01.000Z")
        val recordReads = api.getRunCalls

        serviceActive = true
        assertThat(watchdog().check()).isEqualTo(FinishWatchdog.Outcome.ServiceUp)
        serviceActive = false

        prefs.setLiveNotifications(false)
        assertThat(watchdog().check()).isEqualTo(FinishWatchdog.Outcome.Off)
        prefs.setLiveNotifications(true)
        assertThat(api.getRunCalls).isEqualTo(recordReads)
        assertThat(announced).isEmpty()

        // Back on: the finish is announced, and once nothing runs the check has nothing left to do.
        assertThat(watchdog().check()).isEqualTo(FinishWatchdog.Outcome.Done)
        assertThat(announced.map { it.title }).containsExactly("Agent")
        assertThat(watchdog().check()).isEqualTo(FinishWatchdog.Outcome.Done)
        assertThat(announced).hasSize(1)
    }

    /**
     * The job is persisted and re-arms itself, so without this a permission taken away while the app is in the
     * background leaves it checking every five minutes, for ever, to produce a card the system drops.
     */
    @Test
    fun `a card that could not appear is neither worked for nor counted as told`() = runBlocking<Unit> {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        agents.refresh()
        finish("run-1", "FINISHED", "Done.", 1_000, "2026-04-13T18:30:01.000Z")
        val recordReads = api.getRunCalls

        canShowLive = false
        assertThat(watchdog().check()).isEqualTo(FinishWatchdog.Outcome.Off)
        assertThat(api.getRunCalls).isEqualTo(recordReads)
        assertThat(announced).isEmpty()

        // Notifications are back on, but the system drops this card all the same.
        canShowLive = true
        postSucceeds = false
        assertThat(watchdog().check()).isEqualTo(FinishWatchdog.Outcome.Done)
        assertThat(announced).hasSize(1)

        // The next check to find the row running again — a list restored from before the finish — tells it, because
        // a card that was dropped was never told.
        postSucceeds = true
        agents.patch("bc-1") { it.copy(runStatus = RunStatus.RUNNING) }
        assertThat(watchdog().check()).isEqualTo(FinishWatchdog.Outcome.Done)
        assertThat(announced.map { it.title }).containsExactly("Agent", "Agent")
    }

    @Test
    fun `a snoozed chat is not announced when its run finishes`() = runBlocking {
        api.addRunningAgent("bc-1", "Noisy worker", "run-1")
        agents.refresh()
        prefs.snooze("bc-1", Long.MAX_VALUE, nowMillis = now)
        finish("run-1", "FINISHED", "Done in the background.", 4_000, "2026-04-13T18:30:04.000Z")
        assertThat(watchdog().check()).isEqualTo(FinishWatchdog.Outcome.Done)
        assertThat(announced).isEmpty()
    }
}
