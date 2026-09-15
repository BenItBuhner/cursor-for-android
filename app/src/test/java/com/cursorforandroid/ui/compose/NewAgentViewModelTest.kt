package com.cursorforandroid.ui.compose

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.api.CursorApi
import com.cursorforandroid.data.api.dto.CreateAgentRequestDto
import com.cursorforandroid.data.api.dto.CreateAgentResponseDto
import com.cursorforandroid.data.demo.DemoBackendFactory
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.data.repo.LaunchIdempotency
import com.cursorforandroid.data.repo.LaunchRequest
import com.cursorforandroid.domain.DeviceTarget
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.components.PendingAttachment
import com.cursorforandroid.util.AppClock
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
import java.util.concurrent.CopyOnWriteArrayList

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

    /** Every create the composer sent, as the API received it. */
    private val created = CopyOnWriteArrayList<CreateAgentRequestDto>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // The demo's own catalogue and data, with the requests it is sent recorded.
        val (demoApi, demoStreamer) = DemoBackendFactory.create()
        val api = object : CursorApi by demoApi {
            override suspend fun createAgent(body: CreateAgentRequestDto): CreateAgentResponseDto {
                created += body
                return demoApi.createAgent(body)
            }
        }
        graph = AppGraph(
            ApplicationProvider.getApplicationContext<Context>(),
            demo = CursorBackend(api, demoStreamer, isDemo = true),
        )
        runBlocking {
            graph.session.enterDemo()
            // Shared Robolectric prefs outlive a single test; a previous pick must not look like this install's default.
            graph.prefs.rememberModel(null)
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun loaded(draftSaveDelayMs: Long = 400L): NewAgentViewModel {
        val vm = NewAgentViewModel(graph, draftSaveDelayMs)
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
    private fun loadedWithAgents(draftSaveDelayMs: Long = 400L): NewAgentViewModel {
        runBlocking { graph.agents.refresh() }
        return loaded(draftSaveDelayMs)
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

    /** Nothing picked here, nothing launched from here, no chat of the account's to read: the configured default, Auto. */
    @Test
    fun `a fresh install with no chats to read starts on Auto, never on a bare Model chip`() {
        val vm = loaded()
        assertThat(vm.state.value.selectedModel?.id).isEqualTo("auto-smart")
        assertThat(vm.state.value.modelLabel).isEqualTo("Auto")
        // Before the catalogue has answered the chip already says what the chat would start on.
        assertThat(NewAgentUiState().modelLabel).isEqualTo("Auto")
    }

    /**
     * With the account's chats on screen, a fresh install opens on the model of the account's newest chat — what the
     * desktop's picker would show — resolved to the catalogue's entry and the variant nearest the record's parameters.
     * The chip names the model; the variant's parameters (here 1M context, max effort) are the picker's to show.
     */
    @Test
    fun `a fresh install starts on the account's newest chat's model`() {
        val vm = loadedWithAgents()
        // The newest chat of the account's own with a record is "Cli exploration" (bc-demo-0004): Claude Fable 5.1, 1M context, max effort.
        assertThat(vm.state.value.selectedModel?.id).isEqualTo("claude-fable-5.1-thinking")
        assertThat(vm.state.value.selectedVariant?.displayName).isEqualTo("Claude Fable 5.1 1M Max")
        assertThat(vm.state.value.modelLabel).isEqualTo("Claude Fable 5.1")
    }

    /** The newer word wins: a pick made here after the account's newest chat stands; one made before it yields. */
    @Test
    fun `the device's last pick and the account's newest chat are weighed by recency`() = runBlocking<Unit> {
        val gemini = loaded().state.value.models.first { it.id == "gemini-3.8-flash" }
        graph.prefs.rememberModel(gemini.id, emptyMap(), nowMillis = AppClock.now())
        assertThat(loadedWithAgents().state.value.selectedModel?.id).isEqualTo("gemini-3.8-flash")

        // The same pick, dated from before the account's newest chat was started: the account's word is the newer one.
        graph.prefs.rememberModel(gemini.id, emptyMap(), nowMillis = AppClock.now() - 3 * 60 * 60 * 1000L)
        assertThat(loadedWithAgents().state.value.selectedModel?.id).isEqualTo("claude-fable-5.1-thinking")
    }

    @Test
    fun `a saved Default choice is restored as Auto`() {
        val first = loaded()
        first.selectModel(null, null)
        first.launchAndWait()

        val second = loaded()
        assertThat(second.state.value.selectedModel?.id).isEqualTo("auto-smart")
        assertThat(second.state.value.modelLabel).isEqualTo("Auto")
    }

    @Test
    fun `a model picked without launching is restored on the next composer`() = runBlocking<Unit> {
        val first = loaded()
        val composer = first.state.value.models.first { it.id == "composer-2.5" }
        val slow = composer.variants.first { !it.isDefault }
        first.selectModel(composer, slow)
        awaitUntil { graph.prefs.composerDefaults.first().modelId == "composer-2.5" }

        val second = loaded()
        assertThat(second.state.value.selectedModel?.id).isEqualTo("composer-2.5")
        assertThat(second.state.value.selectedVariant).isEqualTo(slow)
        assertThat(second.state.value.modelLabel).isEqualTo("Composer 2.5")
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
    fun `plan mode and multitask are one slot, whichever way round they are set`() {
        val vm = loaded()
        vm.setPrompt("/multitask fan the suites out")
        vm.setPlanMode(true)
        // Asking for a plan takes the command out of the prompt.
        assertThat(vm.state.value.planMode).isTrue()
        assertThat(vm.state.value.prompt).isEqualTo("fan the suites out")

        // A prompt that leads with the command — typed, picked, or from the "+" menu — puts the plan off.
        vm.setPrompt("/multitask fan the suites out")
        assertThat(vm.state.value.planMode).isFalse()
        assertThat(vm.state.value.prompt).isEqualTo("/multitask fan the suites out")

        // Shared-in text carrying the command does the same; text without it leaves a plan alone.
        vm.setPrompt("")
        vm.setPlanMode(true)
        vm.applyShare("plain text", emptyList())
        assertThat(vm.state.value.planMode).isTrue()
        vm.applyShare("/multitask more", emptyList())
        assertThat(vm.state.value.planMode).isFalse()
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
    fun `a never-launched composer starts from the repository's default branch, not from main`() {
        val vm = loaded()
        // Nothing here knows what a repository's default branch is called: /v1/repositories returns bare URLs.
        assertThat(vm.state.value.ref).isEmpty()
        vm.launchAndWait()
        assertThat(created.single().repos?.single()?.startingRef).isNull()

        // "main" chosen on purpose is a choice: it goes out as the starting ref and comes back next time.
        val second = loaded()
        second.setRef("main")
        second.launchAndWait("Do the other thing")
        assertThat(created.last().repos?.single()?.startingRef).isEqualTo("main")
        assertThat(loaded().state.value.ref).isEqualTo("main")
    }

    /**
     * The navigation stack survives being killed for memory but the composer's view model does not, so what was
     * typed is on disk — images included, by reference — together with the nonce the draft was going to go out
     * under. A draft sent after a restart is therefore the same chat the interrupted attempt would have created.
     */
    @Test
    fun `a draft outlives the process that was typing it, images and chat id included`() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val vm = loadedWithAgents(draftSaveDelayMs = 20)
        vm.selectRepo(vm.repo("cursor-for-android"))
        vm.setRef("cursor/cli-exploration-9c1d")
        vm.selectModel(vm.state.value.models.first { it.id == "composer-2.5" }, null)
        vm.setPlanMode(true)
        vm.setAutoCreatePr(true)
        vm.setPrompt("Half a thought")
        vm.addAttachments(listOf(PendingAttachment("picked-1", PromptImage(bytes, "image/png"), null)))
        awaitUntil { graph.drafts.read()?.images?.size == 1 }
        val saved = graph.drafts.read()!!
        assertThat(saved.prompt).isEqualTo("Half a thought")

        // The process is killed; the composer opens again on the same disk.
        val revived = loaded(draftSaveDelayMs = 20)
        val state = withTimeout(10_000) { revived.state.first { it.prompt.isNotEmpty() && it.models.isNotEmpty() && it.selectedRepo != null } }
        assertThat(state.attachments.single().image.bytes).isEqualTo(bytes)
        assertThat(state.attachments.single().image.mimeType).isEqualTo("image/png")
        assertThat(state.selectedRepo?.shortName).isEqualTo("cursor-for-android")
        assertThat(state.ref).isEqualTo("cursor/cli-exploration-9c1d")
        assertThat(state.selectedModel?.id).isEqualTo("composer-2.5")
        assertThat(state.planMode).isTrue()
        assertThat(state.autoCreatePr).isTrue()

        var opened: String? = null
        revived.launch(onOpen = { opened = it })
        awaitUntil { opened != null }
        val expected = LaunchIdempotency.agentId(
            LaunchRequest(
                prompt = "Half a thought",
                images = listOf(PromptImage(bytes, "image/png")),
                repoUrl = state.selectedRepo!!.url,
                ref = "cursor/cli-exploration-9c1d",
                modelId = "composer-2.5",
                modelParams = state.selectedVariant!!.params,
                autoCreatePr = true,
                planMode = true,
            ),
            saved.nonce,
        )
        assertThat(opened).isEqualTo(expected)
    }

    @Test
    fun `a composer that has been emptied, or sent, leaves no draft behind`() = runBlocking {
        val vm = loaded(draftSaveDelayMs = 20)
        vm.setPrompt("Half a thought")
        awaitUntil { graph.drafts.read() != null }
        vm.setPrompt("")
        awaitUntil { graph.drafts.read() == null }

        vm.setPrompt("Half a thought")
        awaitUntil { graph.drafts.read() != null }
        vm.launchAndWait("Half a thought")
        awaitUntil { graph.drafts.read() == null }
        assertThat(loaded().state.value.prompt).isEmpty()
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

    private val bennett = DeviceTarget.machine("bennett")
    private val codexUrl = "https://github.com/bennett/codex-poly-bot"

    /**
     * The demo's machine "bennett" is checked out at bennett/codex-poly-bot (`GET /v0/private-workers`: `repoUrl`,
     * `workspaceRootPath`). Picking it moves the repository there, refreshes the branch list for it and drops a branch
     * the new repository has never seen, exactly as picking the repository by hand would.
     */
    @Test
    fun `picking a machine moves the repository to its checkout and refreshes the branches`() {
        val vm = loadedWithAgents()
        vm.selectRepo(vm.repo("cursor-for-android"))
        vm.setRef("cursor/cli-exploration-9c1d")
        assertThat(vm.state.value.branches.map { it.name }).contains("cursor/cli-exploration-9c1d")

        vm.selectDevice(bennett)

        val s = vm.state.value
        assertThat(s.selectedDevice).isEqualTo(bennett)
        assertThat(s.selectedRepo?.shortName).isEqualTo("codex-poly-bot")
        assertThat(s.noRepo).isFalse()
        assertThat(s.deviceRepoUrl).isEqualTo(codexUrl)
        assertThat(s.deviceRepository?.slug).isEqualTo("bennett/codex-poly-bot")
        assertThat(s.repoFollowsDevice).isTrue()
        // The branch list is the new repository's, and the android branch did not come along.
        assertThat(s.branches.map { it.name }).contains("main")
        assertThat(s.branches.map { it.name }).doesNotContain("cursor/cli-exploration-9c1d")
        assertThat(s.ref).isEmpty()
        // The catalogue's own row stands for the machine's repository, so the picker shows it checked.
        assertThat(s.selectedRepo).isEqualTo(vm.repo("codex-poly-bot"))
    }

    @Test
    fun `going back to Cloud restores the repository and branch chosen there`() {
        val vm = loadedWithAgents()
        vm.selectRepo(vm.repo("cursor-for-android"))
        vm.setRef("cursor/cli-exploration-9c1d")
        vm.selectDevice(bennett)
        assertThat(vm.state.value.selectedRepo?.shortName).isEqualTo("codex-poly-bot")

        vm.selectDevice(DeviceTarget.Cloud)

        val s = vm.state.value
        assertThat(s.selectedDevice).isEqualTo(DeviceTarget.Cloud)
        assertThat(s.selectedRepo?.shortName).isEqualTo("cursor-for-android")
        assertThat(s.ref).isEqualTo("cursor/cli-exploration-9c1d")
        assertThat(s.deviceRepoUrl).isNull()
        assertThat(s.repoFollowsDevice).isFalse()
        assertThat(s.branches.map { it.name }).contains("cursor/cli-exploration-9c1d")
    }

    /** No repository on Cloud is a choice too, and comes back as one. */
    @Test
    fun `Cloud without a repository comes back without one`() {
        val vm = loadedWithAgents()
        vm.selectRepo(null)
        // The source chip names the choice the way the web composer does.
        assertThat(vm.state.value.repoLabel).isEqualTo("Start from scratch")
        vm.selectDevice(bennett)
        assertThat(vm.state.value.noRepo).isFalse()
        assertThat(vm.state.value.selectedRepo?.shortName).isEqualTo("codex-poly-bot")
        assertThat(vm.state.value.repoLabel).isEqualTo("codex-poly-bot")

        vm.selectDevice(DeviceTarget.Cloud)
        assertThat(vm.state.value.noRepo).isTrue()
        assertThat(vm.state.value.repoLabel).isEqualTo("Start from scratch")
        assertThat(vm.state.value.branches).isEmpty()
    }

    /**
     * A worker may serve more roots than the one it reports (`--worker-dir` once per checkout), so a repository picked
     * over the machine's stays — through a re-pick of the same machine — until the device changes.
     */
    @Test
    fun `a repository picked over the machine's stays until the device changes`() {
        val vm = loadedWithAgents()
        vm.selectRepo(vm.repo("cursor-for-android"))
        vm.selectDevice(bennett)
        vm.selectRepo(vm.repo("visual-engine"))
        assertThat(vm.state.value.repoFollowsDevice).isFalse()
        // The machine's own repository is still named, for the picker to explain.
        assertThat(vm.state.value.deviceRepoUrl).isEqualTo(codexUrl)

        vm.selectDevice(bennett)
        assertThat(vm.state.value.selectedRepo?.shortName).isEqualTo("visual-engine")

        // The demo's "gpu" pool pins no repository: a pick made here comes along to it...
        vm.selectDevice(DeviceTarget.pool("gpu"))
        assertThat(vm.state.value.selectedRepo?.shortName).isEqualTo("visual-engine")
        assertThat(vm.state.value.deviceRepoUrl).isNull()

        // ...and Cloud still comes back to what was chosen on Cloud.
        vm.selectDevice(DeviceTarget.Cloud)
        assertThat(vm.state.value.selectedRepo?.shortName).isEqualTo("cursor-for-android")
    }

    /** A machine's repository is the machine's: it does not travel to a device that pins none. */
    @Test
    fun `a device-driven repository goes back to Cloud's on a device that pins none`() {
        val vm = loadedWithAgents()
        vm.selectRepo(vm.repo("cursor-for-android"))
        vm.selectDevice(bennett)
        assertThat(vm.state.value.selectedRepo?.shortName).isEqualTo("codex-poly-bot")

        vm.selectDevice(DeviceTarget.pool("gpu"))

        assertThat(vm.state.value.selectedRepo?.shortName).isEqualTo("cursor-for-android")
        assertThat(vm.state.value.deviceRepoUrl).isNull()
        // Still the device's to set: should the fleet name the pool's repository later, the selection follows it.
        assertThat(vm.state.value.repoFollowsDevice).isTrue()
    }

    /**
     * The machine's word lands after the pick: no chat has run on it (the list is empty) and the fleet endpoints have
     * not answered yet, so the pick keeps the repository on screen — then moves it when `GET /v0/private-workers` says
     * where the machine is checked out.
     */
    @Test
    fun `a machine picked before the devices are listed takes its repository when they arrive`() = runBlocking {
        val vm = NewAgentViewModel(graph, 400L)
        // The launch defaults are on screen and the fleet fetch has started, but neither it nor the catalogue has answered.
        withTimeout(10_000) { vm.state.first { it.isLoadingDevices } }
        vm.selectDevice(bennett)
        assertThat(vm.state.value.deviceRepoUrl).isNull()
        assertThat(vm.state.value.repoFollowsDevice).isTrue()

        withTimeout(10_000) { vm.state.first { !it.isLoadingDevices && !it.isLoadingRepos && it.models.isNotEmpty() } }

        assertThat(vm.state.value.devices.first { it.target == bennett }.online).isTrue()
        assertThat(vm.state.value.deviceRepoUrl).isEqualTo(codexUrl)
        assertThat(vm.state.value.repoFollowsDevice).isTrue()
        // The fleet's word came first, the catalogue after: the selection is the catalogue's own row for the repository.
        assertThat(vm.state.value.selectedRepo).isEqualTo(vm.repo("codex-poly-bot"))
    }

    /**
     * Across restarts: the last launch's device comes back with the repository it is checked out at, and Cloud comes
     * back to the repository last launched on Cloud, not to the machine's.
     */
    @Test
    fun `a restored machine brings its repository, and Cloud its own last one`() {
        val onCloud = loadedWithAgents()
        onCloud.selectRepo(onCloud.repo("visual-engine"))
        onCloud.launchAndWait("On the cloud")

        val onMachine = loadedWithAgents()
        onMachine.selectDevice(bennett)
        assertThat(onMachine.state.value.selectedRepo?.shortName).isEqualTo("codex-poly-bot")
        onMachine.launchAndWait("On the machine")

        val revived = loadedWithAgents()
        assertThat(revived.state.value.selectedDevice).isEqualTo(bennett)
        assertThat(revived.state.value.selectedRepo?.shortName).isEqualTo("codex-poly-bot")
        assertThat(revived.state.value.repoFollowsDevice).isTrue()

        revived.selectDevice(DeviceTarget.Cloud)
        assertThat(revived.state.value.selectedRepo?.shortName).isEqualTo("visual-engine")
    }

    @Test
    fun `a launch on a machine sends the machine's repository as the target`() {
        val vm = loadedWithAgents()
        vm.selectRepo(vm.repo("cursor-for-android"))
        vm.selectDevice(bennett)
        vm.launchAndWait("Scale the fleet")
        val request = created.last()
        assertThat(request.env?.type).isEqualTo("machine")
        assertThat(request.env?.name).isEqualTo("bennett")
        assertThat(request.repos?.single()?.url).isEqualTo(codexUrl)
    }
}
