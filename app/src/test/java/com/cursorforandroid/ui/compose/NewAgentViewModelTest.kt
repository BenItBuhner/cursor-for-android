package com.cursorforandroid.ui.compose

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.api.CursorApi
import com.cursorforandroid.data.api.CursorApiException
import com.cursorforandroid.data.api.dto.CreateAgentRequestDto
import com.cursorforandroid.data.api.dto.CreateAgentResponseDto
import com.cursorforandroid.data.demo.DemoBackendFactory
import com.cursorforandroid.data.local.DraftStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.data.repo.LaunchIdempotency
import com.cursorforandroid.data.repo.LaunchRequest
import com.cursorforandroid.data.repo.NewChatDrafts
import com.cursorforandroid.domain.DeviceTarget
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.agents.DraftRow
import com.cursorforandroid.ui.components.PendingAttachment
import com.cursorforandroid.util.AppClock
import com.cursorforandroid.util.HeldDispatcher
import com.cursorforandroid.util.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Before
import org.junit.Rule
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

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private lateinit var graph: AppGraph

    /** Every create the composer sent, as the API received it. */
    private val created = CopyOnWriteArrayList<CreateAgentRequestDto>()

    /** Thrown by the next create the API receives, then cleared: the server refusing a launch. */
    @Volatile private var failNextCreate: Throwable? = null

    @Before
    fun setUp() {
        graph = process()
        // Shared Robolectric prefs outlive a single test; a previous pick must not look like this install's default.
        runBlocking { graph.prefs.rememberModel(null) }
    }

    /** A process on this test's disk: the demo's own catalogue and data, with the requests it is sent recorded. */
    private fun process(): AppGraph {
        val (demoApi, demoStreamer) = DemoBackendFactory.create()
        val api = object : CursorApi by demoApi {
            override suspend fun createAgent(body: CreateAgentRequestDto): CreateAgentResponseDto {
                created += body
                failNextCreate?.let { failNextCreate = null; throw it }
                return demoApi.createAgent(body)
            }
        }
        val graph = AppGraph(
            ApplicationProvider.getApplicationContext<Context>(),
            demo = CursorBackend(api, demoStreamer, isDemo = true),
        )
        runBlocking { graph.session.enterDemo() }
        return graph
    }


    private fun loaded(
        draftSaveDelayMs: Long = 400L,
        graph: AppGraph = this.graph,
        resume: String? = null,
        origin: String = DraftStore.ORIGIN_COMPOSER,
    ): NewAgentViewModel {
        val vm = NewAgentViewModel(graph, draftSaveDelayMs, resume = resume, origin = origin)
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

    /** The sidebar's drafts, as the shell lists them while the New Chat pane is on screen with [vm] in it. */
    private fun listed(vm: NewAgentViewModel) = DraftRow.listed(graph.newChatDrafts.state.value.drafts, open = vm.draftId.value)

    /**
     * The quick composer over the launcher has no screen of the app behind it to show the chat in, so its send waits
     * for the server: the chat opens only once it exists, and the draft — on screen and on disk — is cleared then.
     */
    @Test
    fun `a send waited out opens the chat only once the server has answered, and the draft is gone from disk`() = runBlocking {
        val vm = loaded(draftSaveDelayMs = 10, origin = DraftStore.ORIGIN_QUICK_COMPOSER)
        vm.setPrompt("Do the thing")
        awaitUntil { onDisk().singleOrNull()?.prompt == "Do the thing" }
        var opened: String? = null

        vm.launch(onOpen = { opened = it }, awaitServer = true)

        // Busy, and nothing opened, for as long as the server has not said.
        awaitUntil { vm.state.value.isLaunching }
        assertThat(opened).isNull()
        assertThat(vm.state.value.canLaunch).isFalse()
        awaitUntil { opened != null }
        assertThat(accepted(opened!!)).isTrue()
        awaitUntil { !vm.state.value.isLaunching && vm.state.value.prompt.isEmpty() }
        awaitUntil { onDisk().isEmpty() }
        assertThat(vm.state.value.error).isNull()
    }

    @Test
    fun `a send waited out and refused keeps the draft where it is, with the server's words under it`() = runBlocking {
        val vm = loaded(origin = DraftStore.ORIGIN_QUICK_COMPOSER)
        vm.setPrompt("Do the thing")
        failNextCreate = CursorApiException(429, "rate_limited", "Slow down.")
        var opened: String? = null

        vm.launch(onOpen = { opened = it }, awaitServer = true)
        awaitUntil { vm.state.value.error != null }

        assertThat(opened).isNull()
        assertThat(vm.state.value.prompt).isEqualTo("Do the thing")
        assertThat(vm.state.value.isLaunching).isFalse()
        assertThat(vm.state.value.error).isEqualTo("Rate limited by Cursor: Slow down. Try again in a moment.")
        assertThat(vm.state.value.canLaunch).isTrue()
        assertThat(created).hasSize(1)

        // Sent again as it stands, it goes through — and the composer clears, its draft with it.
        vm.launch(onOpen = { opened = it }, awaitServer = true)
        awaitUntil { opened != null }
        assertThat(created).hasSize(2)
        awaitUntil { vm.state.value.prompt.isEmpty() && !vm.state.value.isLaunching }
        assertThat(vm.state.value.error).isNull()
    }

    @Test
    fun `stopping a send being waited out returns the draft without a word`() = runBlocking {
        val vm = loaded(origin = DraftStore.ORIGIN_QUICK_COMPOSER)
        vm.setPrompt("Do the thing")
        var opened: String? = null

        vm.launch(onOpen = { opened = it }, awaitServer = true)
        awaitUntil { vm.state.value.isLaunching && graph.agents.state.value.agents.any { it.name == "Do the thing" } }
        vm.cancelLaunch()

        awaitUntil { !vm.state.value.isLaunching }
        assertThat(opened).isNull()
        assertThat(vm.state.value.prompt).isEqualTo("Do the thing")
        assertThat(vm.state.value.error).isNull()
        assertThat(vm.state.value.canLaunch).isTrue()
        assertThat(graph.agents.state.value.agents.none { it.name == "Do the thing" }).isTrue()
    }

    /** Leaving the quick composer keeps what was typed at once, not after typing has settled; Cancel throws it away. */
    @Test
    fun `keeping a draft writes it at once, and discarding it clears the screen and the disk`() = runBlocking {
        val vm = loaded(draftSaveDelayMs = 60_000, origin = DraftStore.ORIGIN_QUICK_COMPOSER)
        vm.setPrompt("Half a thou")
        delay(100)
        assertThat(onDisk()).isEmpty()

        vm.keepDraft()
        awaitUntil { onDisk().singleOrNull()?.prompt == "Half a thou" }

        vm.discardDraft()
        assertThat(vm.state.value.prompt).isEmpty()
        awaitUntil { onDisk().isEmpty() }
    }

    /** The quick composer has no sidebar to reopen a draft from: opened again, it picks up the one it was left with. */
    @Test
    fun `a quick composer opened again picks up the draft it was left with`() = runBlocking {
        val first = loaded(draftSaveDelayMs = 60_000, origin = DraftStore.ORIGIN_QUICK_COMPOSER)
        first.setPrompt("Half a thou")
        first.keepDraft()
        awaitUntil { onDisk().singleOrNull()?.prompt == "Half a thou" }

        val again = loaded(origin = DraftStore.ORIGIN_QUICK_COMPOSER)
        assertThat(again.state.value.prompt).isEqualTo("Half a thou")
        assertThat(again.draftId.value).isEqualTo(first.draftId.value)
    }

    @Test
    fun `a draft that comes back does not overwrite what is being written, and waits in the sidebar`() = runBlocking<Unit> {
        val vm = loaded(draftSaveDelayMs = 20)
        val id = vm.launchAndWait("Do the thing")
        vm.setPrompt("Then do the other th")
        graph.conversations.attach(id)
        assertThat(graph.conversations.cancelActiveRun(id).isSuccess).isTrue()
        awaitUntil { graph.agents.agent(id) == null }

        // The launch is gone, and given time to reach the composer it still leaves what the user is writing alone:
        // the draft it went out from is back in the sidebar instead.
        awaitUntil { listed(vm).any { it.title == "Do the thing" } }
        delay(300)
        assertThat(vm.state.value.prompt).isEqualTo("Then do the other th")
        vm.setPrompt("Then do the other thing")

        // Sent, the composer clears; the returned draft stays where it waits until it is asked for.
        var second: String? = null
        vm.launch(onOpen = { second = it })
        awaitUntil { second != null && !vm.state.value.isLaunching }
        assertThat(second).isNotEqualTo(id)
        awaitUntil { accepted(second!!) }
        assertThat(vm.state.value.prompt).isEmpty()
        assertThat(graph.conversations.state(second!!).value.items.filterIsInstance<UserMessage>().single().text).isEqualTo("Then do the other thing")
        assertThat(listed(vm).map { it.title }).containsExactly("Do the thing")
    }

    @Test
    fun `a returned draft opens from the sidebar and, sent again unchanged, is the same chat`() = runBlocking {
        val vm = loaded(draftSaveDelayMs = 20)
        val id = vm.launchAndWait("Do the thing")
        vm.setPrompt("Something else")
        graph.conversations.attach(id)
        assertThat(graph.conversations.cancelActiveRun(id).isSuccess).isTrue()
        awaitUntil { listed(vm).any { it.title == "Do the thing" } }

        val returned = listed(vm).single { it.title == "Do the thing" }
        graph.newChatDrafts.request(NewChatDrafts.Request.Open(returned.id))
        awaitUntil { vm.state.value.prompt == "Do the thing" }
        assertThat(vm.state.value.error).isNull()
        // What the composer held is a draft of its own now.
        awaitUntil { listed(vm).map { it.title } == listOf("Something else") }

        assertThat(vm.launchAndWait("Do the thing")).isEqualTo(id)
        awaitUntil { accepted(id) }
        awaitUntil { listed(vm).map { it.title } == listOf("Something else") }
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

    /** The drafts on disk, as a new process reads them. */
    private suspend fun onDisk() = graph.drafts.list()

    /**
     * The composer as the phone leaves it: a repository and branch on a team pool, a model with its variant, plan
     * mode, the PR switch, a line and an image. Written as the app leaves the screen.
     */
    private fun writeEverything(vm: NewAgentViewModel, bytes: ByteArray) {
        vm.selectDevice(DeviceTarget.pool("gpu"))
        vm.selectRepo(vm.repo("cursor-for-android"))
        vm.setRef("cursor/cli-exploration-9c1d")
        val grok = vm.state.value.models.first { it.id == "cursor-grok-4.6" }
        vm.selectModel(grok, grok.variantWithParams(mapOf("effort" to "medium", "fast" to "false")))
        vm.setPlanMode(true)
        vm.setAutoCreatePr(true)
        vm.setPrompt("Half a thought")
        vm.addAttachments(listOf(PendingAttachment("picked-1", PromptImage(bytes, "image/png"), null)))
    }

    private fun NewAgentUiState.assertEverything(bytes: ByteArray) {
        assertThat(prompt).isEqualTo("Half a thought")
        assertThat(attachments.single().image.bytes).isEqualTo(bytes)
        assertThat(attachments.single().image.mimeType).isEqualTo("image/png")
        assertThat(selectedDevice).isEqualTo(DeviceTarget.pool("gpu"))
        assertThat(selectedRepo?.shortName).isEqualTo("cursor-for-android")
        assertThat(ref).isEqualTo("cursor/cli-exploration-9c1d")
        assertThat(selectedModel?.id).isEqualTo("cursor-grok-4.6")
        assertThat(selectedVariant?.params?.associate { it.id to it.value }).containsExactly("effort", "medium", "fast", "false")
        assertThat(planMode).isTrue()
        assertThat(autoCreatePr).isTrue()
    }

    /**
     * The navigation stack survives being killed for memory but the composer's view model does not: the screen keeps
     * which draft was open, and what was typed is on disk — images included, by reference — with the device, the model
     * and its parameters, and the nonce it was going to go out under. Sent after the restart, it is the same chat the
     * interrupted attempt would have created.
     */
    @Test
    fun `a draft outlives the process that was typing it, device, model and chat id included`() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val vm = loadedWithAgents(draftSaveDelayMs = 20)
        writeEverything(vm, bytes)
        awaitUntil { onDisk().singleOrNull()?.images?.size == 1 }
        val saved = onDisk().single()
        assertThat(saved.id).isEqualTo(vm.draftId.value)

        // The process is killed; the composer opens again, in a new process on the same disk, on the draft it had open.
        val next = process()
        runBlocking { next.agents.refresh() }
        val revived = loaded(draftSaveDelayMs = 20, graph = next, resume = saved.id)
        val state = withTimeout(10_000) { revived.state.first { it.prompt.isNotEmpty() && it.selectedRepo != null && it.selectedModel?.id == "cursor-grok-4.6" } }
        state.assertEverything(bytes)
        assertThat(revived.draftId.value).isEqualTo(saved.id)

        var opened: String? = null
        revived.launch(onOpen = { opened = it })
        awaitUntil { opened != null }
        val expected = LaunchIdempotency.agentId(
            LaunchRequest(
                prompt = "Half a thought",
                images = listOf(PromptImage(bytes, "image/png")),
                repoUrl = state.selectedRepo!!.url,
                ref = "cursor/cli-exploration-9c1d",
                modelId = "cursor-grok-4.6",
                modelParams = state.selectedVariant!!.params,
                autoCreatePr = true,
                planMode = true,
                env = DeviceTarget.pool("gpu"),
            ),
            saved.nonce,
        )
        assertThat(opened).isEqualTo(expected)
    }

    /**
     * The app closed and started afresh: the composer is a new one, and what was left in the old one is a draft in
     * the sidebar, which opens with everything it was written with.
     */
    @Test
    fun `an app started afresh opens a fresh composer, and the draft left in the last one opens from the sidebar whole`() = runBlocking {
        val bytes = byteArrayOf(7, 7, 7)
        val vm = loadedWithAgents(draftSaveDelayMs = 20)
        writeEverything(vm, bytes)
        awaitUntil { onDisk().singleOrNull()?.images?.size == 1 }

        val next = process()
        runBlocking { next.agents.refresh() }
        val fresh = loaded(draftSaveDelayMs = 20, graph = next)
        assertThat(fresh.state.value.prompt).isEmpty()
        val rows = DraftRow.listed(next.newChatDrafts.state.value.drafts, open = fresh.draftId.value)
        assertThat(rows.map { it.title }).containsExactly("Half a thought")
        assertThat(rows.single().repoShortName).isEqualTo("cursor-for-android")

        next.newChatDrafts.request(NewChatDrafts.Request.Open(rows.single().id))
        val state = withTimeout(10_000) { fresh.state.first { it.prompt.isNotEmpty() && it.attachments.isNotEmpty() } }
        state.assertEverything(bytes)
    }

    /** 0.3.61 wrote a restored draft's model back as "never picked" on its first save: the next restart lost it. */
    @Test
    fun `a model picked for a draft survives one restart after another`() = runBlocking {
        val vm = loadedWithAgents(draftSaveDelayMs = 20)
        val grok = vm.state.value.models.first { it.id == "cursor-grok-4.6" }
        vm.selectModel(grok, grok.variantWithParams(mapOf("effort" to "low", "fast" to "true")))
        vm.setPrompt("Keep my model")
        awaitUntil { onDisk().singleOrNull()?.modelChosen == true }
        // Meanwhile a conversation's pick becomes the new-chat default: it must not stand in for the draft's own.
        graph.prefs.rememberModel("composer-2.5", mapOf("fast" to "false"), AppClock.now() + 60_000)
        val id = vm.draftId.value

        var next = process()
        repeat(2) {
            val revived = loaded(draftSaveDelayMs = 20, graph = next, resume = id)
            val state = withTimeout(10_000) { revived.state.first { it.prompt == "Keep my model" && it.selectedModel?.id == "cursor-grok-4.6" } }
            assertThat(state.selectedVariant?.params?.associate { it.id to it.value }).containsExactly("effort", "low", "fast", "true")
            // Written into once more, as a restored draft is.
            revived.setPrompt("Keep my model ")
            revived.setPrompt("Keep my model")
            awaitUntil { next.drafts.list().single().modelChosen && next.drafts.list().single().modelId == "cursor-grok-4.6" }
            next = process()
        }
    }

    @Test
    fun `starting a new chat keeps the one being written as a draft, and several drafts are kept side by side`() = runBlocking<Unit> {
        val vm = loadedWithAgents(draftSaveDelayMs = 20)
        vm.selectRepo(vm.repo("cursor-for-android"))
        vm.setPrompt("First idea")
        val first = vm.draftId.value

        graph.newChatDrafts.request(NewChatDrafts.Request.Fresh)
        awaitUntil { vm.draftId.value != first && vm.state.value.prompt.isEmpty() }
        vm.setPrompt("Second idea")
        val second = vm.draftId.value
        graph.newChatDrafts.request(NewChatDrafts.Request.Fresh)
        awaitUntil { vm.draftId.value != second && vm.state.value.prompt.isEmpty() }

        awaitUntil { listed(vm).size == 2 }
        // Most recent first.
        assertThat(listed(vm).map { it.title }).containsExactly("Second idea", "First idea").inOrder()
        assertThat(onDisk().map { it.id }).containsExactly(first, second)

        // An empty composer asked for a fresh one stays as it is.
        val empty = vm.draftId.value
        graph.newChatDrafts.request(NewChatDrafts.Request.Fresh)
        delay(200)
        assertThat(vm.draftId.value).isEqualTo(empty)

        // Opening one puts it back and leaves the other alone.
        graph.newChatDrafts.request(NewChatDrafts.Request.Open(first))
        awaitUntil { vm.state.value.prompt == "First idea" }
        assertThat(vm.state.value.selectedRepo?.shortName).isEqualTo("cursor-for-android")
        assertThat(listed(vm).map { it.title }).containsExactly("Second idea")
    }

    /**
     * After a process death with a chat on top, the New Chat pane's view model is created only when the pane is first
     * shown — after the tap on the draft that asked for it. The ask waits for the composer and is answered by it.
     */
    @Test
    fun `a draft asked for before the composer exists opens in it when it starts`() = runBlocking {
        val vm = loadedWithAgents(draftSaveDelayMs = 20)
        vm.selectRepo(vm.repo("cursor-for-android"))
        vm.setPrompt("Asked for from the drawer")
        awaitUntil { onDisk().isNotEmpty() }
        val id = vm.draftId.value

        val next = process()
        runBlocking { next.newChatDrafts.load() }
        next.newChatDrafts.request(NewChatDrafts.Request.Open(id))
        val revived = loaded(draftSaveDelayMs = 20, graph = next)

        val state = withTimeout(10_000) { revived.state.first { it.prompt.isNotEmpty() && it.selectedRepo != null } }
        assertThat(state.prompt).isEqualTo("Asked for from the drawer")
        assertThat(state.selectedRepo?.shortName).isEqualTo("cursor-for-android")
        assertThat(revived.draftId.value).isEqualTo(id)
        // Answered once: a composer started after it opens fresh.
        assertThat(next.newChatDrafts.takePending()).isNull()
    }

    @Test
    fun `deleting the draft the composer has open empties the composer onto a new one`() = runBlocking {
        val vm = loaded(draftSaveDelayMs = 20)
        vm.setPrompt("Never mind")
        awaitUntil { onDisk().isNotEmpty() }
        val id = vm.draftId.value

        graph.newChatDrafts.remove(id)

        awaitUntil { vm.state.value.prompt.isEmpty() && vm.draftId.value != id }
        assertThat(onDisk()).isEmpty()
        delay(200)
        assertThat(onDisk()).isEmpty()
    }

    @Test
    fun `a composer that has been emptied, or sent, leaves no draft behind`() = runBlocking {
        val vm = loaded(draftSaveDelayMs = 20)
        vm.setPrompt("Half a thought")
        awaitUntil { onDisk().isNotEmpty() }
        vm.setPrompt("")
        awaitUntil { onDisk().isEmpty() }

        vm.setPrompt("Half a thought")
        awaitUntil { onDisk().isNotEmpty() }
        val id = vm.launchAndWait("Half a thought")
        awaitUntil { accepted(id) }
        awaitUntil { onDisk().isEmpty() }
        assertThat(loaded().state.value.prompt).isEmpty()
    }

    /**
     * Emptying the composer removes its draft; that removal is the composer's own and does not come back to it as the
     * sidebar's "deleted", which empties a composer onto a new draft. #292's CI caught the notice handled after the
     * reader had typed again: the words wiped and nothing saved in their place. Forced here: the composer's own thread
     * is held from the emptying until the words typed after it have been saved, then let go.
     */
    @Test
    fun `words typed right after emptying the composer stay, however late it hears of the draft it emptied`() = runBlocking<Unit> {
        val vm = loaded(draftSaveDelayMs = 20)
        vm.setPrompt("Half a thought")
        awaitUntil { onDisk().isNotEmpty() }
        val id = vm.draftId.value
        val composerThread = HeldDispatcher()
        try {
            composerThread.hold = true
            mainDispatcher.set(composerThread.dispatcher)
            vm.setPrompt("")
            awaitUntil { onDisk().isEmpty() }
            vm.setPrompt("Half a thought, and the rest")
            awaitUntil { onDisk().any { it.prompt == "Half a thought, and the rest" } }
            composerThread.release()
            delay(200)
            // The words stay on screen, in the draft that was open, and that draft is the one on disk.
            assertThat(vm.state.value.prompt).isEqualTo("Half a thought, and the rest")
            assertThat(vm.draftId.value).isEqualTo(id)
            assertThat(onDisk().map { it.id to it.prompt }).containsExactly(id to "Half a thought, and the rest")
        } finally {
            mainDispatcher.set(UnconfinedTestDispatcher())
            composerThread.close()
        }
    }

    /**
     * Sending a draft that was listed in the sidebar: by the time the chat is on screen — the moment the shell lists
     * the draft again, as the New Chat pane is no longer on top — the chat's row is in the list and the draft is not,
     * and at no point from then on are both listed. Once the server has the chat the draft is gone from disk too.
     */
    @Test
    fun `sending a draft turns it into the chat, whose row is listed as the draft leaves and never beside it`() = runBlocking {
        val vm = loaded(draftSaveDelayMs = 20)
        vm.setPrompt("Parked idea")
        graph.newChatDrafts.request(NewChatDrafts.Request.Fresh)
        awaitUntil { vm.state.value.prompt.isEmpty() }
        val parked = listed(vm).single()
        graph.newChatDrafts.request(NewChatDrafts.Request.Open(parked.id))
        awaitUntil { vm.state.value.prompt == "Parked idea" }

        // Every state of the two lists from here, as the sidebar would read them with a chat on top.
        val both = CopyOnWriteArrayList<String>()
        var agentId: String? = null
        val watch = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            kotlinx.coroutines.flow.combine(graph.newChatDrafts.state, graph.agents.state) { d, a -> d to a }.collect { (d, a) ->
                val draftListed = DraftRow.listed(d.drafts, open = null).any { it.id == parked.id }
                val chatListed = agentId?.let { id -> a.agents.any { it.id == id } } == true
                if (draftListed && chatListed) both += "draft and chat at once"
            }
        }
        var atOpen: Pair<Boolean, Boolean>? = null
        vm.launch(onOpen = { id ->
            agentId = id
            atOpen = DraftRow.listed(graph.newChatDrafts.state.value.drafts, open = null).any { it.id == parked.id } to (graph.agents.agent(id) != null)
        })
        // Set on the launch's thread after the id: waiting on the id alone can read it before it is written.
        awaitUntil { atOpen != null }
        assertThat(atOpen).isEqualTo(false to true)
        awaitUntil { accepted(agentId!!) }
        awaitUntil { onDisk().none { it.id == parked.id } }
        watch.cancel()
        assertThat(both).isEmpty()
        assertThat(graph.newChatDrafts.record(parked.id)).isNull()
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
