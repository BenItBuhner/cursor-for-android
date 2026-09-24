package com.cursorforandroid.ui.conversation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.ComposerSnapshot
import com.cursorforandroid.data.api.RecordFields
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.ApiKeyInfoDto
import com.cursorforandroid.data.api.dto.ModelParamDto
import com.cursorforandroid.data.api.dto.ModelRefDto
import com.cursorforandroid.data.local.FollowUpStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.domain.AccountModel
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.AgentScope
import com.cursorforandroid.domain.DraftModel
import com.cursorforandroid.domain.ModelChoice
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.fixtures.LiveModelCatalog
import com.cursorforandroid.util.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The follow-up composer of a chat whose model was inherited — named by the account's record in whatever spelling the
 * client that started it used — over a catalogue shaped like the live one. The Project is Bennett's case: its
 * coordinator started a worker on `claude-opus-5-5-max-fast`, and the worker's chip read the slug. The chip, the
 * picker's selection and every parameter must be what picking the model by hand gives, and a follow-up must send what
 * it sent before: nothing, so the chat keeps its model.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class InheritedModelComposerTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val api = object : FakeCursorApi() {
        override suspend fun me() = ApiKeyInfoDto(apiKeyName = "test", userEmail = "bennett@example.com", userId = 7L)
    }
    private val streamer = FakeRunStreamer()
    private lateinit var graph: AppGraph
    private val opus: ModelOption = LiveModelCatalog.model("claude-opus-5.5")

    @Before
    fun setUp() = runBlocking {
        api.modelItems = LiveModelCatalog.items
        for ((id, name, run) in listOf(Triple(PROJECT, "Polymarket bot", "run-p"), Triple(WORKER, "OCR the evidence frames", "run-w"), Triple(MINE, "Flaky login test", "run-m"))) {
            api.addFinishedAgent(id, name, Triple(run, "Start on $name.", "Done."))
            // The finished turn's log, as the run stream replays it, so the chat reads as free for a follow-up.
            streamer.emit(run, RunStreamEvent.Assistant("Done."))
            streamer.emit(run, RunStreamEvent.Result(run, RunStatus.FINISHED, "Done.", 1_000, null))
            streamer.emit(run, RunStreamEvent.Done)
        }
        graph = AppGraph(
            context,
            keyStore = SecureKeyStore(context) { context.getSharedPreferences("inherited-model-keys", Context.MODE_PRIVATE) },
            real = CursorBackend(api, streamer, isDemo = false),
        )
        graph.session.signIn("key_abc").getOrThrow()
        graph.agents.refresh()
        // What the account's list says of them (Extended mode): the Project's coordinator on Opus 5.5 extra-high, the
        // worker it started on Opus 5.5 Max with Fast, each model as the coordinator spelled it.
        graph.agents.applyAccountSnapshots(
            listOf(
                ComposerSnapshot(PROJECT, isProject = true, record = RecordFields(projectMetadata = "{}"), model = AccountModel("claude-opus-5-5-xhigh")),
                ComposerSnapshot(
                    WORKER,
                    record = RecordFields(managerAgentId = PROJECT),
                    parent = AgentParent(PROJECT, AgentParentKind.PROJECT_WORKER),
                    model = AccountModel("claude-opus-5-5-max-fast"),
                ),
            ),
        )
    }

    @After
    fun tearDown() = runBlocking { FollowUpStore(context).clear() }

    private fun open(agentId: String): ConversationViewModel {
        val vm = ConversationViewModel(graph, agentId)
        vm.picker { it.models.isNotEmpty() && !it.isLoading }
        return vm
    }

    private fun ConversationViewModel.picker(condition: (FollowUpModelState) -> Boolean = { true }): FollowUpModelState =
        runBlocking { withTimeout(10_000) { modelPicker.first(condition) } }

    private fun ConversationViewModel.sendAndWait(text: String) = runBlocking {
        setDraft(text)
        send()
        withTimeout(10_000) { isSending.first { !it } }
        withTimeout(10_000) { while (api.runRequests.none { it.prompt.text == text }) kotlinx.coroutines.delay(10) }
    }

    /** Opus 5.5 picked in the picker, then Max, then Fast, the way a person does it. */
    private val maxFastByHand: ModelChoice
        get() = ModelChoice(opus, opus.variantWith(opus.variantWith(opus.defaultVariant, "effort", "max"), "fast", "true"))

    @Test
    fun `a Project worker opens on the model its coordinator named, as if it had been picked by hand`() {
        assertThat(graph.agents.agent(WORKER)!!.scope).isEqualTo(AgentScope.PROJECT_CHILD)
        val worker = open(WORKER).picker { it.current != null }

        assertThat(worker.current).isEqualTo(maxFastByHand)
        assertThat(worker.selected).isEqualTo(worker.current)
        assertThat(worker.override).isNull()
        assertThat(worker.currentAssumed).isFalse()
        assertThat(worker.currentDetail).isNull()
        assertThat(worker.chipLabel).isEqualTo("Claude Opus 5.5")
        // Every knob of the model sheet where a hand pick leaves it: Effort on Max, Fast on.
        val variant = worker.selected!!.variant!!
        assertThat(opus.axes.map { it.id }).containsExactly("effort", "fast").inOrder()
        assertThat(variant.param("effort")).isEqualTo("max")
        assertThat(variant.param("fast")).isEqualTo("true")

        // The same picks made by hand in another chat are the same selection, parameter for parameter.
        val other = open(MINE)
        val picked = maxFastByHand
        other.selectModel(picked.model, picked.variant)
        assertThat(other.picker { it.override != null }.selected).isEqualTo(worker.selected)
    }

    @Test
    fun `the Project's coordinator reads its own slug the same way`() {
        val coordinator = open(PROJECT).picker { it.current != null }
        assertThat(coordinator.current?.model).isEqualTo(opus)
        assertThat(coordinator.current?.params?.toSet()).isEqualTo(setOf(ModelParam("effort", "xhigh"), ModelParam("fast", "false")))
        assertThat(coordinator.chipLabel).isEqualTo("Claude Opus 5.5")
    }

    @Test
    fun `a follow-up from the worker sends what it sent before, no model, so the chat keeps the coordinator's`() {
        val vm = open(WORKER)
        vm.picker { it.current != null }
        vm.sendAndWait("Carry on")
        assertThat(api.runRequests.single { it.prompt.text == "Carry on" }.model).isNull()
    }

    @Test
    fun `a knob moved from the inherited selection sends the catalogue's id with every parameter`() {
        val vm = open(WORKER)
        // Effort moved to Low: the rest of the inherited selection (Fast on) stays, as it does in the picker.
        val current = vm.picker { it.current != null }.current!!
        vm.selectModel(opus, opus.variantWith(current.variant, "effort", "low"))
        vm.picker { it.override != null }
        vm.sendAndWait("Cheaper now")
        assertThat(api.runRequests.single { it.prompt.text == "Cheaper now" }.model)
            .isEqualTo(ModelRefDto("claude-opus-5.5", listOf(ModelParamDto("effort", "low"), ModelParamDto("fast", "true"))))
    }

    @Test
    fun `a slug no catalogue lists shows its readable name, is never blank, and the chat keeps it`() {
        graph.agents.patch(MINE) { it.copy(accountModel = AccountModel("claude-opus-6-max-fast")) }
        val vm = open(MINE)
        val picker = vm.picker { it.currentLabel == "Claude Opus 6" }
        assertThat(picker.current).isNull()
        assertThat(picker.selected).isNull()
        assertThat(picker.currentAssumed).isFalse()
        assertThat(picker.chipLabel).isEqualTo("Claude Opus 6")
        assertThat(picker.currentDetail).isEqualTo("Max · Fast · claude-opus-6-max-fast")

        vm.sendAndWait("Keep going")
        assertThat(api.runRequests.last { it.prompt.text == "Keep going" }.model).isNull()
    }

    @Test
    fun `before the catalogue answers the chip reads the name the slug spells, never the slug`() {
        api.failModels = IllegalStateException("offline")
        val picker = ConversationViewModel(graph, WORKER).picker { !it.isLoading && it.currentLabel != null }
        assertThat(picker.models).isEmpty()
        assertThat(picker.current).isNull()
        assertThat(picker.chipLabel).isEqualTo("Claude Opus 5.5")
    }

    @Test
    fun `a draft that kept a slug comes back on the catalogue's entry`() = runBlocking {
        graph.catalog.loadModels().getOrThrow()
        withTimeout(10_000) { graph.followUps.state(MINE).first { it.restored } }
        graph.followUps.setDraftModel(MINE, DraftModel("claude-opus-5-5-max-fast"))
        val picker = open(MINE).picker { it.override != null }
        assertThat(picker.override).isEqualTo(maxFastByHand)
        assertThat(picker.chipLabel).isEqualTo("Claude Opus 5.5")
    }

    private companion object {
        const val PROJECT = "bc-project"
        const val WORKER = "bc-worker"
        const val MINE = "bc-mine"
    }
}
