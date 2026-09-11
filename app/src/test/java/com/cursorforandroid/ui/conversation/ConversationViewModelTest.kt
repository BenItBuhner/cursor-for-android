package com.cursorforandroid.ui.conversation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.SlashCommandApi
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.data.repo.LaunchRequest
import com.cursorforandroid.data.repo.SessionManager
import com.cursorforandroid.data.repo.SlashCommandRepository
import com.cursorforandroid.data.repo.SlashScope
import com.cursorforandroid.domain.ModelChoice
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.SlashCatalog
import com.cursorforandroid.domain.SlashCommand
import com.cursorforandroid.domain.SlashCommand.Kind
import com.cursorforandroid.domain.SlashCommand.Origin
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

/**
 * The follow-up composer's model picker, against the demo backend (whose catalogue mirrors the live one). Unlike the
 * home composer, a chat's model is not a preference: it is whatever this device last sent for that chat, kept on its
 * row, and unknown for chats started elsewhere.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ConversationViewModelTest {

    private lateinit var graph: AppGraph

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        graph = AppGraph(ApplicationProvider.getApplicationContext<Context>())
        graph.session.enterDemo()
        graph.agents.refresh()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Opens the chat and waits for the catalogue, without which the picker has nothing to show checked. */
    private fun open(agentId: String): ConversationViewModel {
        val vm = ConversationViewModel(graph, agentId)
        vm.picker { it.models.isNotEmpty() && !it.isLoading }
        return vm
    }

    /** The picker once [condition] holds, read through the flow: its sharing stops shortly after the last collector leaves. */
    private fun ConversationViewModel.picker(condition: (FollowUpModelState) -> Boolean = { true }): FollowUpModelState =
        runBlocking { withTimeout(10_000) { modelPicker.first(condition) } }

    private fun ConversationViewModel.sendAndWait(text: String) = runBlocking {
        setDraft(text)
        send()
        withTimeout(10_000) { isSending.first { !it } }
    }

    @Test
    fun `a chat started elsewhere has no known model and the picker offers the catalogue`() {
        val picker = open(IDLE).picker()
        assertThat(picker.currentLabel).isNull()
        assertThat(picker.current).isNull()
        assertThat(picker.override).isNull()
        assertThat(picker.selected).isNull()
        assertThat(picker.chipLabel).isEqualTo("Model")
        assertThat(picker.models.map { it.id }).containsAtLeast("claude-fable-5.1-thinking", "composer-2.5", "gemini-3.8-flash")
    }

    /** The chip names the model — "Composer 2.5" — and not the variant it was launched with; the picker shows that. */
    @Test
    fun `a chat launched here opens on the variant it was launched with, the chip naming the model alone`() = runBlocking {
        val composer = graph.catalog.loadModels().getOrThrow().first { it.id == "composer-2.5" }
        val slow = composer.variants.first { !it.isDefault }
        val request = LaunchRequest(prompt = "Do the thing", repoUrl = null, ref = null, modelId = composer.id, modelParams = slow.params, autoCreatePr = false, planMode = false)
        val agent = graph.agents.launch(request, "Composer 2.5").getOrThrow().agent

        val picker = open(agent.id).picker { it.current != null }
        assertThat(picker.current).isEqualTo(ModelChoice(composer, slow))
        assertThat(picker.selected).isEqualTo(picker.current)
        assertThat(picker.override).isNull()
        assertThat(picker.chipLabel).isEqualTo("Composer 2.5")
    }

    /** Earlier versions recorded the variant's parameters in the label; the catalogue's name for the model replaces it. */
    @Test
    fun `a chat recorded with a parameter-laden label reads as the model's name`() {
        val claude = open(IDLE).picker().models.first { it.id == "claude-fable-5.1-thinking" }
        val low = claude.variants.first { it.param("effort") == "low" && it.param("context") == "300k" }
        graph.agents.patch(IDLE) { it.copy(modelId = claude.id, modelParams = low.params, modelDisplayName = "Claude Fable 5.1 · 300K context · Low effort") }
        val picker = open(IDLE).picker { it.current != null }
        assertThat(picker.current).isEqualTo(ModelChoice(claude, low))
        assertThat(picker.currentLabel).isEqualTo("Claude Fable 5.1")
        assertThat(picker.chipLabel).isEqualTo("Claude Fable 5.1")
    }

    /** The id says which model the chat runs on; a variant the catalogue has since dropped does not unsettle that. */
    @Test
    fun `a chat whose recorded variant the catalogue no longer lists is still identified by its model id`() {
        val claude = open(IDLE).picker().models.first { it.id == "claude-fable-5.1-thinking" }
        val gone = listOf(ModelParam("context", "1m"), ModelParam("effort", "ultra"))
        graph.agents.patch(IDLE) { it.copy(modelId = claude.id, modelParams = gone, modelDisplayName = "Claude Fable 5.1") }
        val picker = open(IDLE).picker { it.current != null }
        assertThat(picker.current?.model).isEqualTo(claude)
        // The nearest variant stands in for the one that is gone: same context, the API's default effort.
        assertThat(picker.current?.variant).isEqualTo(claude.variantWithParams(mapOf("context" to "1m", "effort" to "max")))
        assertThat(picker.selected).isEqualTo(picker.current)
        assertThat(picker.chipLabel).isEqualTo("Claude Fable 5.1")
    }

    /** Rows saved before the id and parameters were kept only carry the label; it is enough to find the entry. */
    @Test
    fun `a chat recorded by label alone is matched to the catalogue by that label`() {
        graph.agents.patch(IDLE) { it.copy(modelDisplayName = "GPT-5.6 High") }
        val picker = open(IDLE).picker { it.current != null }
        assertThat(picker.current?.model?.id).isEqualTo("gpt-5.6")
        assertThat(picker.chipLabel).isEqualTo("GPT-5.6")
    }

    @Test
    fun `a label the catalogue cannot place is shown as is with nothing checked`() {
        graph.agents.patch(IDLE) { it.copy(modelDisplayName = "Default model") }
        val picker = open(IDLE).picker { it.currentLabel != null }
        assertThat(picker.current).isNull()
        assertThat(picker.selected).isNull()
        assertThat(picker.chipLabel).isEqualTo("Default model")
    }

    /** A model the catalogue has dropped: its recorded name still shows, less any parameters an earlier version appended. */
    @Test
    fun `a model the catalogue no longer lists keeps its recorded name on the chip`() {
        graph.agents.patch(IDLE) { it.copy(modelId = "claude-legacy-3", modelParams = listOf(ModelParam("fast", "true")), modelDisplayName = "Claude Legacy 3 · Fast") }
        val picker = open(IDLE).picker { it.currentLabel != null }
        assertThat(picker.current).isNull()
        assertThat(picker.chipLabel).isEqualTo("Claude Legacy 3")
    }

    @Test
    fun `picking a model remembers it for the new-chat composer`() = runBlocking {
        val vm = open(IDLE)
        val composer = vm.picker().models.first { it.id == "composer-2.5" }
        val slow = composer.variants.first { !it.isDefault }
        vm.selectModel(composer, slow)
        withTimeout(10_000) {
            while (graph.prefs.composerDefaults.first().modelId != "composer-2.5") delay(10)
        }
        val defaults = graph.prefs.composerDefaults.first()
        assertThat(defaults.modelId).isEqualTo("composer-2.5")
        assertThat(defaults.modelParams).isEqualTo(slow.params.associate { it.id to it.value })
        assertThat(defaults.modelChosen).isTrue()
    }

    @Test
    fun `a pick labels the chip and becomes the chat's model once the follow-up is accepted`() {
        val vm = open(IDLE)
        val composer = vm.picker().models.first { it.id == "composer-2.5" }
        vm.selectModel(composer, null)
        val picked = vm.picker { it.override != null }
        assertThat(picked.override).isEqualTo(ModelChoice(composer, composer.defaultVariant))
        assertThat(picked.selected).isEqualTo(picked.override)
        assertThat(picked.chipLabel).isEqualTo("Composer 2.5")

        vm.sendAndWait("Try it with Composer")

        assertThat(vm.toastMessage.value).isNull()
        val after = vm.picker { it.override == null && it.current != null }
        assertThat(after.current).isEqualTo(ModelChoice(composer, composer.defaultVariant))
        assertThat(after.currentLabel).isEqualTo("Composer 2.5")
        assertThat(after.chipLabel).isEqualTo("Composer 2.5")
        val row = graph.agents.agent(IDLE)!!
        assertThat(row.modelId).isEqualTo("composer-2.5")
        assertThat(row.modelParams).isEqualTo(composer.defaultVariant!!.params)
        assertThat(row.modelDisplayName).isEqualTo("Composer 2.5")
    }

    @Test
    fun `picking the current-model row drops the pick`() {
        val vm = open(IDLE)
        val gemini = vm.picker().models.first { it.id == "gemini-3.8-flash" }
        vm.selectModel(gemini, null)
        assertThat(vm.picker { it.override != null }.chipLabel).isEqualTo("Gemini 3.8 Flash")
        vm.selectModel(null, null)
        assertThat(vm.picker { it.override == null }.chipLabel).isEqualTo("Model")
    }

    /**
     * The demo's running agent would refuse a follow-up with `409 agent_busy`, like the API does; so while it runs,
     * a send joins the queue above the composer instead, carrying the pick, and goes out when the turn ends.
     */
    @Test
    fun `a follow-up sent mid-turn is queued with its pick rather than refused`() {
        val vm = open(RUNNING)
        val gemini = vm.picker().models.first { it.id == "gemini-3.8-flash" }
        vm.selectModel(gemini, null)
        vm.setPlanMode(true)

        vm.sendAndWait("Try it")

        assertThat(vm.draftText.value).isEmpty()
        assertThat(vm.toastMessage.value).isNull()
        val queued = runBlocking { withTimeout(5_000) { vm.queue.first { it.isNotEmpty() } } }.single()
        assertThat(queued.text).isEqualTo("Try it")
        assertThat(queued.modelId).isEqualTo("gemini-3.8-flash")
        assertThat(queued.modelDisplayName).isEqualTo("Gemini 3.8 Flash")
        assertThat(queued.planMode).isTrue()
        assertThat(queued.error).isNull()
        // Nothing reached the server: the row still knows no model.
        assertThat(graph.agents.agent(RUNNING)?.modelId).isNull()
        // The pick stays for the next message too, as on the desktop.
        assertThat(vm.picker().override).isEqualTo(ModelChoice(gemini, gemini.defaultVariant))
    }

    @Test
    fun `editing a queued follow-up puts it back in the composer and queues the draft in its place`() {
        val vm = open(RUNNING)
        vm.sendAndWait("First thought")
        val queued = runBlocking { withTimeout(5_000) { vm.queue.first { it.isNotEmpty() } } }.single()
        vm.setDraft("Second thought")

        vm.editQueued(queued.id)

        runBlocking { withTimeout(5_000) { vm.draftText.first { it == "First thought" } } }
        assertThat(graph.followUps.state(RUNNING).value.queue.map { it.text }).containsExactly("Second thought")
        assertThat(runBlocking { withTimeout(5_000) { vm.toastMessage.first { it != null } } }).isEqualTo("Your draft was queued in its place.")
    }

    @Test
    fun `removing a queued follow-up takes it away`() {
        val vm = open(RUNNING)
        vm.sendAndWait("Never mind")
        val queued = runBlocking { withTimeout(5_000) { vm.queue.first { it.isNotEmpty() } } }.single()

        vm.removeQueued(queued.id)

        runBlocking { withTimeout(5_000) { vm.queue.first { it.isEmpty() } } }
    }

    /** The composer is a place to think: leaving the chat and coming back finds the text and the images as they were. */
    @Test
    fun `the draft survives leaving the chat`() {
        val first = open(IDLE)
        first.setDraft("Half a thought")
        first.addAttachments(listOf(PendingAttachment("img-1", PromptImage(byteArrayOf(1, 2, 3), "image/png"), null)))

        val second = ConversationViewModel(graph, IDLE)

        runBlocking { withTimeout(5_000) { second.draftText.first { it == "Half a thought" } } }
        val restored = runBlocking { withTimeout(5_000) { second.pendingAttachments.first { it.isNotEmpty() } } }.single()
        assertThat(restored.id).isEqualTo("img-1")
        assertThat(restored.image.bytes.toList()).isEqualTo(listOf<Byte>(1, 2, 3))
    }

    @Test
    fun `a sent follow-up leaves no draft behind`() {
        val vm = open(IDLE)
        vm.sendAndWait("Off you go")
        assertThat(vm.toastMessage.value).isNull()
        assertThat(graph.followUps.state(IDLE).value.draft.isEmpty).isTrue()
        assertThat(ConversationViewModel(graph, IDLE).draftText.value).isEmpty()
    }

    @Test
    fun `a share is drafted into the follow-up composer`() {
        val vm = open(IDLE)
        vm.setDraft("Already writing")
        vm.applyShare("shared screenshot notes", emptyList())
        assertThat(vm.draftText.value).isEqualTo("Already writing\n\nshared screenshot notes")
    }

    /**
     * A busy agent's follow-up is queued; anything else the server refuses comes back to the composer. The composer
     * stays editable while it is in flight, so the one that came back must not undo what was typed since.
     */
    @Test
    fun `a refused follow-up does not overwrite a draft typed while it was in flight`() = runBlocking {
        val vm = open(ARCHIVED)
        vm.setDraft("Try it")
        vm.send()
        vm.setDraft("Actually, do this instead")
        withTimeout(10_000) { vm.isSending.first { !it } }

        assertThat(vm.draftText.value).isEqualTo("Actually, do this instead")
        assertThat(vm.toastMessage.value).isNotNull()
    }

    /**
     * The follow-up composer's pending-inventory loop must force a fresh fetch on each retry; otherwise half the
     * attempts return the cached copy before its TTL has expired.
     */
    @Test
    fun `pending inventory retries force a fresh fetch each time`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val api = object : SlashCommandApi {
            val agentCalls = mutableListOf<Triple<String, String?, String?>>()

            override suspend fun forRepository(repoUrl: String, ref: String?) = SlashCatalog()

            override suspend fun forAgent(agentId: String, repoUrl: String?, ref: String?): SlashCatalog {
                agentCalls += Triple(agentId, repoUrl, ref)
                return SlashCatalog(listOf(SlashCommand("release", "Cut a release", Kind.Command, Origin.Project)), pending = true)
            }

            override suspend fun global() = emptyList<SlashCommand>()
        }
        val cursorApi = FakeCursorApi()
        val backend = CursorBackend(cursorApi, FakeRunStreamer(), isDemo = false)
        val session = SessionManager(SecureKeyStore(context), PreferencesStore(context), backend, CursorBackend(cursorApi, FakeRunStreamer(), isDemo = true))
        val repo = SlashCommandRepository(session, api, cache = null)
        val scope = SlashScope.Agent(IDLE, null, null)
        var now = 1_800_000_000_000L
        AppClock.nowMillis = { now }

        var catalog = repo.load(scope)
        var retries = 0
        while (catalog.pending && retries++ < 8) {
            now += SlashCommandRepository.PENDING_TTL_MS + 1
            catalog = repo.load(scope, force = true)
        }

        assertThat(api.agentCalls).hasSize(9)
        AppClock.nowMillis = System::currentTimeMillis
    }

    @Test
    fun `plan mode is not asked for until toggled, then shows on the chip`() {
        val vm = open(IDLE)
        assertThat(vm.picker().planMode).isNull()
        vm.setPlanMode(true)
        assertThat(vm.picker { it.planMode == true }.chipLabel).isEqualTo("Model · Plan")
        vm.setPlanMode(false)
        assertThat(vm.picker { it.planMode == false }.chipLabel).isEqualTo("Model")
    }

    private companion object {
        /** "Revenue Scaling Pipeline Research": finished, so it takes a follow-up. */
        const val IDLE = "bc-demo-0002"
        /** "Codex-Poly-Bot Scaling": mid-run, so a follow-up is queued behind the run. */
        const val RUNNING = "bc-demo-0001"
        /** "Rendezvous registry cleanup": archived, so a follow-up is refused outright. */
        const val ARCHIVED = "bc-demo-0016"
    }
}
