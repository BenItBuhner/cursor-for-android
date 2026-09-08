package com.cursorforandroid.ui.conversation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.repo.LaunchRequest
import com.cursorforandroid.domain.ModelChoice
import com.cursorforandroid.domain.ModelParam
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    /** The demo's running agent refuses a follow-up with `409 agent_busy`, like the API does. */
    @Test
    fun `a refused follow-up keeps the pick and the draft for the retry`() {
        val vm = open(RUNNING)
        val gemini = vm.picker().models.first { it.id == "gemini-3.8-flash" }
        vm.selectModel(gemini, null)

        vm.sendAndWait("Try it")

        assertThat(vm.draftText.value).isEqualTo("Try it")
        assertThat(vm.toastMessage.value).isNotNull()
        assertThat(vm.picker().override).isEqualTo(ModelChoice(gemini, gemini.defaultVariant))
        assertThat(vm.picker().chipLabel).isEqualTo("Gemini 3.8 Flash")
        assertThat(graph.agents.agent(RUNNING)?.modelId).isNull()
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
        /** "Codex-Poly-Bot Scaling": mid-run, so a follow-up is refused. */
        const val RUNNING = "bc-demo-0001"
    }
}
