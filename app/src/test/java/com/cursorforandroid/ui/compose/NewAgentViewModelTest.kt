package com.cursorforandroid.ui.compose

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
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
 * The composer remembers what the last agent was launched with, and opens the chat it launches before the server has
 * answered. Runs against the demo backend, whose catalogue mirrors the live one — "Composer 2.5" has two variants of
 * the same name, `fast` on (the default) and off — and which answers a create after half a second, like a real server.
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
        runBlocking { withTimeout(10_000) { vm.state.first { it.models.isNotEmpty() && !it.isLoadingRepos } } }
        return vm
    }

    private fun NewAgentViewModel.launchAndWait() = runBlocking {
        setPrompt("Do the thing")
        launch(onOpen = {}, onFailed = {})
        withTimeout(10_000) { state.first { !it.isLaunching && it.prompt.isEmpty() } }
    }

    private suspend fun awaitUntil(timeoutMs: Long = 10_000, condition: suspend () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(10)
    }

    @Test
    fun `sending opens the chat on its prompt before the server has answered`() = runBlocking {
        val vm = loaded()
        vm.setPrompt("Do the thing")
        var opened: String? = null
        var failed: String? = null

        vm.launch(onOpen = { opened = it }, onFailed = { failed = it })
        awaitUntil { opened != null }

        // The chat is open and showing the prompt while the request is still in flight; the draft stays until it lands.
        val id = opened!!
        assertThat(vm.state.value.isLaunching).isTrue()
        assertThat(vm.state.value.prompt).isEqualTo("Do the thing")
        assertThat(graph.conversations.state(id).value.items.filterIsInstance<UserMessage>().single().text).isEqualTo("Do the thing")
        assertThat(graph.conversations.state(id).value.runStatus).isEqualTo(RunStatus.CREATING)
        assertThat(graph.agents.agent(id)?.name).isEqualTo("Do the thing")

        awaitUntil { !vm.state.value.isLaunching }
        assertThat(vm.state.value.prompt).isEmpty()
        assertThat(vm.state.value.error).isNull()
        assertThat(failed).isNull()
        assertThat(graph.agents.agent(id)?.latestRunId).isNotNull()
        assertThat(graph.conversations.state(id).value.activeRunId).isEqualTo(graph.agents.agent(id)?.latestRunId)
    }

    @Test
    fun `stopping the chat before the server has answered returns to the composer with the draft`() = runBlocking {
        val vm = loaded()
        vm.setPrompt("Do the thing")
        var opened: String? = null
        var failed: String? = null

        vm.launch(onOpen = { opened = it }, onFailed = { failed = it })
        awaitUntil { opened != null }
        graph.conversations.attach(opened!!)
        assertThat(graph.conversations.cancelActiveRun(opened!!).isSuccess).isTrue()

        awaitUntil { !vm.state.value.isLaunching }
        assertThat(failed).isEqualTo(opened)
        assertThat(vm.state.value.prompt).isEqualTo("Do the thing")
        assertThat(vm.state.value.error).isNull()
        assertThat(graph.conversations.state(opened!!).value.items).isEmpty()
        assertThat(graph.agents.agent(opened!!)).isNull()
    }

    @Test
    fun `a fresh install starts on the first recommended model and its default variant`() {
        val vm = loaded()
        assertThat(vm.state.value.selectedModel?.id).isEqualTo("claude-fable-5.1-thinking")
        assertThat(vm.state.value.selectedVariant?.displayName).isEqualTo("Claude Fable 5.1 1M Max")
        assertThat(vm.state.value.modelLabel).isEqualTo("Claude Fable 5.1 1M Max")
    }

    @Test
    fun `choosing Default is restored as Default, not as the first model`() {
        val first = loaded()
        first.selectModel(null, null)
        first.launchAndWait()

        val second = loaded()
        assertThat(second.state.value.selectedModel).isNull()
        assertThat(second.state.value.selectedVariant).isNull()
        assertThat(second.state.value.modelLabel).isEqualTo("Default model")
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
        assertThat(second.state.value.modelLabel).isEqualTo("Composer 2.5 · Fast off")
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

    @Test
    fun `selecting a model without a variant picks its default variant and labels same-named siblings apart`() {
        val vm = loaded()
        val composer = vm.state.value.models.first { it.id == "composer-2.5" }
        vm.selectModel(composer, null)
        assertThat(vm.state.value.selectedVariant?.isDefault).isTrue()
        assertThat(vm.state.value.modelLabel).isEqualTo("Composer 2.5 · Fast")
    }
}
