package com.cursorforandroid

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.repo.SlashScope
import com.cursorforandroid.data.repo.SteeringRepository
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.QueueLoad
import com.cursorforandroid.domain.SlashCatalog
import com.cursorforandroid.domain.ToolPayload
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** What the graph builds and wipes under each mode: the api2 client exists only for Extended mode, and turning the mode off leaves nothing of it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AppGraphExtendedModeTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val repoScope = SlashScope.Repo("https://gitlab.com/acme/app", "main")

    @Test
    fun `off by default, a graph doing everything the account would be asked for never builds the api2 client`() = runBlocking<Unit> {
        val graph = AppGraph(app)
        assertThat(graph.extendedMode.isEnabled()).isFalse()

        // Every entry point the private calls used to sit behind.
        assertThat(graph.pins.sync().isSuccess).isTrue()
        assertThat(graph.slashCommands.load(repoScope)).isEqualTo(SlashCatalog.BUILT_IN)
        graph.pullRequests.refresh(listOf("https://gitlab.com/acme/app/-/merge_requests/1"))
        assertThat(graph.agents.rename("bc-1", "x").isFailure).isTrue()
        // The Projects' lineage reads: none is made, and the parents the list lacks are fetched through the public API alone.
        graph.projects.syncLineage(listOf("bc-1"))
        graph.projects.watchList()
        // The chat's controls: each is refused by name before anything is built to call.
        graph.steering.refreshQueue("bc-1")
        assertThat(graph.steering.steer("bc-1", "go").exceptionOrNull()?.message).isEqualTo(SteeringRepository.NEEDS_EXTENDED_MODE)
        assertThat(graph.steering.answerQuestion("bc-1", "call-1", listOf(ToolPayload.Question.Answer("q", listOf("a")))).exceptionOrNull()?.message).isEqualTo(SteeringRepository.NEEDS_EXTENDED_MODE)
        assertThat(graph.steering.pause("bc-1").isFailure).isTrue()
        assertThat(graph.steering.state("bc-1").value.queueLoad).isEqualTo(QueueLoad.Unavailable(SteeringRepository.NEEDS_EXTENDED_MODE))

        val built = graph.builtParts()
        assertThat(built).containsNoneOf("accountClient", "accountRpc", "sessionTokens", "accountAgents", "accountPullRequests", "accountSlashCommands", "projectApi", "steeringApi")
        assertThat(built).containsAtLeast("projects", "steering")
        // What stands in: GitHub's client, built by the catalog load (the tree read is decided per host, so nothing was asked of it here).
        assertThat(built).contains("gitHubSlashCommands")
    }

    @Test
    fun `on, the same work builds the api2 client and never GitHub's`() = runBlocking<Unit> {
        val graph = AppGraph(app)
        graph.extendedMode.acknowledge()
        assertThat(graph.extendedMode.enable()).isTrue()

        // No key is stored, so the session exchange refuses before any network; the client was built to try.
        graph.slashCommands.load(repoScope)
        // The chat's controls reach for the account too, and its refusal (no key) is the words the screen shows.
        assertThat(graph.steering.steer("bc-1", "go").exceptionOrNull()?.message).isEqualTo("Not signed in.")

        val built = graph.builtParts()
        assertThat(built).containsAtLeast("accountClient", "accountRpc", "sessionTokens", "accountSlashCommands", "steeringApi")
        assertThat(built).containsNoneOf("gitHub", "gitHubSlashCommands", "gitHubPullRequests")
    }

    @Test
    fun `turning it off wipes what the account service left, on disk and in the preferences, without building anything`() = runBlocking<Unit> {
        val graph = AppGraph(app)
        graph.extendedMode.acknowledge()
        graph.extendedMode.enable()
        val pullRequests = write(File(app.cacheDir, "cursor/pullrequests/states.json"))
        val slashCommands = write(File(app.cacheDir, "cursor/slashcommands/repo_x.json"))
        val agents = write(File(app.cacheDir, "cursor/agents/list.json"))
        graph.prefs.setPinnedIds(setOf("bc-1"))
        graph.prefs.setPendingPinChange("bc-2", pinned = true)
        graph.prefs.setPinsMigrated(true)
        val before = graph.builtParts()

        assertThat(graph.extendedMode.disable()).isTrue()

        assertThat(pullRequests.exists()).isFalse()
        assertThat(slashCommands.exists()).isFalse()
        // The agent list is not the account service's; it stays.
        assertThat(agents.exists()).isTrue()
        assertThat(graph.prefs.localAgentState.first().pinnedIds).containsExactly("bc-1", "bc-2")
        assertThat(graph.prefs.pendingPinChanges.first()).isEmpty()
        assertThat(graph.prefs.pinsMigrated.first()).isFalse()
        assertThat(graph.extendedMode.capabilities().anyExtended).isFalse()
        assertThat(graph.builtParts()).isEqualTo(before)
    }

    @Test
    fun `an upgraded install is wiped the same way and owed the notice, once`() = runBlocking<Unit> {
        val graph = AppGraph(app)
        // What an install signed in before the setting existed looks like: an account in the preferences, and the
        // pin sync's leftovers.
        graph.prefs.setCachedUser(CursorUser("key", "alex@example.com", "Alex", "Rivera", 7, profilePictureUrl = "https://pics.example/a.png"))
        graph.prefs.setPendingPinChange("bc-3", pinned = true)
        val pullRequests = write(File(app.cacheDir, "cursor/pullrequests/states.json"))

        graph.extendedMode.migrateInstall()

        assertThat(graph.extendedMode.isEnabled()).isFalse()
        assertThat(graph.extendedMode.noticePending.first()).isTrue()
        assertThat(pullRequests.exists()).isFalse()
        assertThat(graph.prefs.localAgentState.first().pinnedIds).containsExactly("bc-3")
        assertThat(graph.prefs.pendingPinChanges.first()).isEmpty()
        // The session was never restored here, so the picture stays cached until `/v1/me` re-describes the account
        // (the restore path is covered in ExtendedModeGatingTest); nothing was built to do it.
        assertThat(graph.builtParts()).isEmpty()

        graph.extendedMode.dismissNotice()
        graph.extendedMode.migrateInstall()
        assertThat(graph.extendedMode.noticePending.first()).isFalse()
    }

    @Test
    fun `a fresh install is owed no notice`() = runBlocking<Unit> {
        val graph = AppGraph(app)

        graph.extendedMode.migrateInstall()

        assertThat(graph.extendedMode.noticePending.first()).isFalse()
        assertThat(graph.prefs.extendedModeIntroduced.first()).isTrue()
        assertThat(graph.builtParts()).isEmpty()
    }

    private fun write(file: File): File {
        file.parentFile?.mkdirs()
        file.writeText("{}")
        return file
    }
}
