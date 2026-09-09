package com.cursorforandroid.ui.compose

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.DeviceTarget
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.UserMessage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The composer remembers what the last agent was launched with, opens the chat it launches before the server has
 * answered and is free for the next one from that moment; a launch that does not go through brings its draft back.
 * Runs against the demo backend, whose catalogue mirrors the live one — "Composer 2.5" has two variants of the same
 * name, `fast` on (the default) and off — and which answers a create after half a second, like a real server.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NewAgentViewModelTest {

    private lateinit var graph: AppGraph

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        graph = AppGraph(ApplicationProvider.getApplicationContext<Context>())
        graph.session.enterDemo()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun loaded(): NewAgentViewModel {
        val vm = NewAgentViewModel(graph)
        runBlocking { withTimeout(10_000) { vm.state.first { it.models.isNotEmpty() && !it.isLoadingRepos && !it.isLoadingDevices } } }
        return vm
    }

    /** Sends and waits for the composer to be free again — which is before the server has answered, defaults saved. */
    private fun NewAgentViewModel.launchAndWait(prompt: String = "Do the thing"): String = runBlocking {
        setPrompt(prompt)
        var opened: String? = null
        launch(onOpen = { opened = it })
        withTimeout(10_000) { state.first { !it.isLaunching && it.prompt.isEmpty() } }
        opened!!
    }

    /** The demo backend answers a create after half a second; this is the answer having landed. */
    private fun accepted(agentId: String) = graph.agents.agent(agentId)?.latestRunId != null

    /** The composer derives its branch list from the agent list, which the sidebar normally loads. */
    private fun loadedWithAgents(): NewAgentViewModel {
        runBlocking { graph.agents.refresh() }
        return loaded()
    }

    private fun NewAgentViewModel.repo(shortName: String) = state.value.repositories.first { it.shortName == shortName }

    private suspend fun awaitUntil(timeoutMs: Long = 10_000, condition: suspend () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(10)
    }

    @Test
    fun `sending opens the chat on its prompt before the server has answered and leaves the composer free`() = runBlocking {
        val vm = loaded()
        vm.setPrompt("Do the thing")
        var opened: String? = null

        vm.launch(onOpen = { opened = it })
        awaitUntil { opened != null }

        // The chat is open and showing the prompt while the request is still in flight...
        val id = opened!!
        assertThat(accepted(id)).isFalse()
        assertThat(graph.conversations.state(id).value.items.filterIsInstance<UserMessage>().single().text).isEqualTo("Do the thing")
        assertThat(graph.conversations.state(id).value.runStatus).isEqualTo(RunStatus.CREATING)
        assertThat(graph.agents.agent(id)?.name).isEqualTo("Do the thing")

        // ...and the composer is already empty and ready for another chat, not waiting on this one.
        awaitUntil { !vm.state.value.isLaunching }
        assertThat(accepted(id)).isFalse()
        assertThat(vm.state.value.prompt).isEmpty()
        assertThat(vm.state.value.attachments).isEmpty()
        assertThat(vm.state.value.error).isNull()
        assertThat(vm.state.value.isFree).isTrue()

        awaitUntil { graph.conversations.state(id).value.activeRunId != null }
        assertThat(vm.state.value.prompt).isEmpty()
        assertThat(vm.state.value.error).isNull()
        assertThat(graph.conversations.state(id).value.activeRunId).isEqualTo(graph.agents.agent(id)?.latestRunId)
    }

    @Test
    fun `a second chat can be written and sent while the first is still waiting on the server`() = runBlocking {
        val vm = loaded()
        val first = vm.launchAndWait("Do the thing")
        assertThat(accepted(first)).isFalse()

        // Back on the New Chat pane, the composer takes a fresh draft as if nothing were in flight.
        vm.setPrompt("Then do the other thing")
        assertThat(vm.state.value.canLaunch).isTrue()
        val second = vm.launchAndWait("Then do the other thing")
        assertThat(second).isNotEqualTo(first)
        assertThat(graph.agents.agent(first)?.isRunning).isTrue()
        assertThat(graph.agents.agent(second)?.isRunning).isTrue()

        // Both land on their own, without the composer's involvement.
        awaitUntil { accepted(first) && accepted(second) }
        assertThat(graph.conversations.state(first).value.items.filterIsInstance<UserMessage>().single().text).isEqualTo("Do the thing")
        assertThat(graph.conversations.state(second).value.items.filterIsInstance<UserMessage>().single().text).isEqualTo("Then do the other thing")
        assertThat(vm.state.value.isFree).isTrue()
        assertThat(vm.state.value.error).isNull()
    }

    @Test
    fun `stopping the chat before the server has answered returns the draft to the composer`() = runBlocking {
        val vm = loaded()
        val id = vm.launchAndWait("Do the thing")
        assertThat(vm.state.value.isFree).isTrue()
        graph.conversations.attach(id)
        assertThat(graph.conversations.cancelActiveRun(id).isSuccess).isTrue()

        // Stopped on purpose: the draft is simply back, with nothing to explain.
        awaitUntil { vm.state.value.prompt == "Do the thing" }
        assertThat(vm.state.value.isLaunching).isFalse()
        assertThat(vm.state.value.error).isNull()
        assertThat(vm.state.value.canLaunch).isTrue()
        assertThat(graph.conversations.state(id).value.items).isEmpty()
        assertThat(graph.agents.agent(id)).isNull()

        // Sent again unchanged, it is the same chat: the id is minted from the draft and the nonce it went out under.
        assertThat(vm.launchAndWait("Do the thing")).isEqualTo(id)
        awaitUntil { accepted(id) }
    }

    @Test
    fun `a draft that comes back does not overwrite what is being written, and waits for the composer to be free`() = runBlocking {
        val vm = loaded()
        val id = vm.launchAndWait("Do the thing")
        vm.setPrompt("Then do the other th")
        graph.conversations.attach(id)
        assertThat(graph.conversations.cancelActiveRun(id).isSuccess).isTrue()
        awaitUntil { graph.agents.agent(id) == null }

        // The launch is gone, and given time to reach the composer it still leaves what the user is writing alone.
        delay(300)
        assertThat(vm.state.value.prompt).isEqualTo("Then do the other th")
        vm.setPrompt("Then do the other thing")

        // Sent, the composer clears — and the returned draft takes it at once, ready to be sent again.
        var second: String? = null
        vm.launch(onOpen = { second = it })
        awaitUntil { second != null && !vm.state.value.isLaunching && vm.state.value.prompt == "Do the thing" }
        assertThat(second).isNotEqualTo(id)
        assertThat(vm.state.value.error).isNull()
        assertThat(vm.state.value.canLaunch).isTrue()
        awaitUntil { accepted(second!!) }
        assertThat(vm.state.value.prompt).isEqualTo("Do the thing")
        assertThat(graph.conversations.state(second!!).value.items.filterIsInstance<UserMessage>().single().text).isEqualTo("Then do the other thing")
    }

    @Test
    fun `clearing the composer lets a waiting draft back in`() = runBlocking {
        val vm = loaded()
        val id = vm.launchAndWait("Do the thing")
        vm.setPrompt("Something else")
        graph.conversations.attach(id)
        assertThat(graph.conversations.cancelActiveRun(id).isSuccess).isTrue()
        awaitUntil { graph.agents.agent(id) == null }
        delay(300)
        assertThat(vm.state.value.prompt).isEqualTo("Something else")

        vm.setPrompt("")
        awaitUntil { vm.state.value.prompt == "Do the thing" }
        assertThat(vm.state.value.error).isNull()
    }

    /** The chip names the model; the variant's parameters (here 1M context, max effort) are the picker's to show. */
    @Test
    fun `a fresh install starts on the first recommended model and its default variant`() {
        val vm = loaded()
        assertThat(vm.state.value.selectedModel?.id).isEqualTo("claude-fable-5.1-thinking")
        assertThat(vm.state.value.selectedVariant?.displayName).isEqualTo("Claude Fable 5.1 1M Max")
        assertThat(vm.state.value.modelLabel).isEqualTo("Claude Fable 5.1")
    }

    @Test
    fun `a saved Default choice is restored as the first model`() {
        val first = loaded()
        first.selectModel(null, null)
        first.launchAndWait()

        val second = loaded()
        assertThat(second.state.value.selectedModel?.id).isEqualTo("claude-fable-5.1-thinking")
        assertThat(second.state.value.modelLabel).isEqualTo("Claude Fable 5.1")
    }

    @Test
    fun `pinning a model persists across composers`() = runBlocking<Unit> {
        val first = loaded()
        val grok = first.state.value.models.first { it.id == "cursor-grok-4.6" }
        first.togglePinnedModel(grok.id)
        awaitUntil { grok.id in first.state.value.pinnedModelIds }
        val second = loaded()
        awaitUntil { grok.id in second.state.value.pinnedModelIds }
        assertThat(second.state.value.pinnedModelIds).containsExactly(grok.id)
    }

    @Test
    fun `the non-default variant is restored instead of the model's default one`() {
        val first = loaded()
        val composer = first.state.value.models.first { it.id == "composer-2.5" }
        val slow = composer.variants.first { !it.isDefault }
        first.selectModel(composer, slow)
        first.launchAndWait()

        val second = loaded()
        assertThat(second.state.value.selectedModel?.id).isEqualTo("composer-2.5")
        assertThat(second.state.value.selectedVariant).isEqualTo(slow)
        assertThat(second.state.value.modelLabel).isEqualTo("Composer 2.5")
    }

    @Test
    fun `a parameter-less variant is restored instead of the model's default variant`() {
        val first = loaded()
        val gemini = first.state.value.models.first { it.id == "gemini-3.8-flash" }
        val bare = gemini.variants.single()
        assertThat(bare.params).isEmpty()
        first.selectModel(gemini, bare)
        first.launchAndWait()

        val second = loaded()
        assertThat(second.state.value.selectedModel?.id).isEqualTo("gemini-3.8-flash")
        assertThat(second.state.value.selectedVariant).isEqualTo(bare)
        assertThat(second.state.value.modelLabel).isEqualTo("Gemini 3.8 Flash")
    }

    /** Same-named siblings ("Composer 2.5" fast and slow) are told apart in the picker, never on the chip. */
    @Test
    fun `selecting a model without a variant picks its default variant and the chip names the model alone`() {
        val vm = loaded()
        val composer = vm.state.value.models.first { it.id == "composer-2.5" }
        vm.selectModel(composer, null)
        assertThat(vm.state.value.selectedVariant?.isDefault).isTrue()
        assertThat(vm.state.value.modelLabel).isEqualTo("Composer 2.5")
        vm.selectModel(composer, composer.variants.first { !it.isDefault })
        assertThat(vm.state.value.modelLabel).isEqualTo("Composer 2.5")
    }

    @Test
    fun `the branch picker lists what agents of the selected repository started from and pushed, newest first`() {
        val vm = loadedWithAgents()
        vm.selectRepo(vm.repo("cursor-for-android"))

        val branches = vm.state.value.branches
        assertThat(branches.map { it.name }).containsExactly(
            "main", "cursor/cli-exploration-9c1d", "cursor/release-process-1a2b", "cursor/mobile-experience-4e5f", "cursor/deps-bump-2f3a",
        ).inOrder()
        assertThat(branches.first().description).isEqualTo("Starting point of 4 agents")
        assertThat(branches[1].description).isEqualTo("Pushed by Cli exploration")

        // Another repository's agents are not offered for this one.
        vm.selectRepo(vm.repo("visual-engine"))
        assertThat(vm.state.value.branches.map { it.name }).containsNoneOf("cursor/cli-exploration-9c1d", "cursor/deps-bump-2f3a")
        assertThat(vm.state.value.branches.map { it.name }).contains("cursor/house-environment-7b3e")
    }

    @Test
    fun `the repository picker pins repositories with recent agent activity newest first`() {
        val vm = loadedWithAgents()
        // Demo chats from the last day cover every catalogue repository; none is older than a week, so the recent
        // block is the whole list, ordered by the newest chat in each repository — not the alphabetical catalogue.
        assertThat(vm.state.value.recentRepositories.map { it.shortName }).containsExactly(
            "cursor-for-android",
            "visual-engine",
            "codex-poly-bot",
            "market-replay",
            "cesium",
            "zen-parity",
        ).inOrder()
    }

    @Test
    fun `the branch list is empty without a repository`() {
        val vm = loadedWithAgents()
        vm.selectRepo(null)
        assertThat(vm.state.value.branches).isEmpty()
    }

    @Test
    fun `switching repositories keeps a branch the new one has and drops one it has never seen`() {
        val vm = loadedWithAgents()
        val android = vm.repo("cursor-for-android")
        val cesium = vm.repo("cesium")

        vm.selectRepo(android)
        vm.setRef("cursor/cli-exploration-9c1d")
        vm.selectRepo(cesium)
        // Cesium has no such branch: the agent starts from the repository's default branch instead.
        assertThat(vm.state.value.ref).isEmpty()

        vm.setRef("main")
        vm.selectRepo(android)
        assertThat(vm.state.value.ref).isEqualTo("main")

        // Re-selecting the same repository, or leaving repositories altogether, never touches a typed branch.
        vm.setRef("feature/typed-by-hand")
        vm.selectRepo(android)
        assertThat(vm.state.value.ref).isEqualTo("feature/typed-by-hand")
        vm.selectRepo(null)
        assertThat(vm.state.value.ref).isEqualTo("feature/typed-by-hand")
    }

    @Test
    fun `a share is drafted into the composer and appended under text already there`() {
        val vm = loaded()
        vm.applyShare("From Photos", emptyList())
        assertThat(vm.state.value.prompt).isEqualTo("From Photos")
        vm.applyShare("and this URL", emptyList())
        assertThat(vm.state.value.prompt).isEqualTo("From Photos\n\nand this URL")
        assertThat(vm.state.value.canLaunch).isTrue()
    }

    @Test
    fun `the branch launched from is restored, including the default branch`() {
        val first = loaded()
        first.setRef("develop")
        first.launchAndWait()
        assertThat(loaded().state.value.ref).isEqualTo("develop")

        val second = loaded()
        second.setRef("")
        second.launchAndWait()
        // Blank means "the repository's default branch", not "never launched": it must not come back as "main".
        assertThat(loaded().state.value.ref).isEmpty()
    }

    @Test
    fun `the device picker defaults to cloud and lists the demo machine and pool`() {
        val vm = loadedWithAgents()
        assertThat(vm.state.value.selectedDevice).isEqualTo(DeviceTarget.Cloud)
        assertThat(vm.state.value.deviceLabel).isEqualTo("Cloud")
        assertThat(vm.state.value.devices.map { it.target }).containsAtLeast(
            DeviceTarget.Cloud,
            DeviceTarget.machine("bennett"),
            DeviceTarget.pool("gpu"),
        )
        assertThat(vm.state.value.devices.none { it.target.label.equals("This device", ignoreCase = true) }).isTrue()
    }

    @Test
    fun `the device launched on is restored`() {
        val first = loaded()
        first.selectDevice(DeviceTarget.machine("bennett"))
        first.launchAndWait()
        val second = loaded()
        assertThat(second.state.value.selectedDevice).isEqualTo(DeviceTarget.machine("bennett"))
        assertThat(second.state.value.deviceLabel).isEqualTo("bennett")
    }
}
