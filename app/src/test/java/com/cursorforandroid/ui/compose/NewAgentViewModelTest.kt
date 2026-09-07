package com.cursorforandroid.ui.compose

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
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
 * The composer remembers what the last agent was launched with. Runs against the demo backend, whose catalogue has
 * a model with a default "Fast" variant and a parameter-less sibling ("Composer 2.5").
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
        launch {}
        withTimeout(10_000) { state.first { !it.isLaunching && it.prompt.isEmpty() } }
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
    fun `a parameter-less variant is restored instead of the model's default variant`() {
        val first = loaded()
        val composer = first.state.value.models.first { it.id == "composer-2.5" }
        val plain = composer.variants.first { it.params.isEmpty() }
        first.selectModel(composer, plain)
        first.launchAndWait()

        val second = loaded()
        assertThat(second.state.value.selectedModel?.id).isEqualTo("composer-2.5")
        assertThat(second.state.value.selectedVariant).isEqualTo(plain)
        assertThat(second.state.value.modelLabel).isEqualTo("Composer 2.5")
    }

    @Test
    fun `selecting a model without a variant picks its default variant`() {
        val vm = loaded()
        val composer = vm.state.value.models.first { it.id == "composer-2.5" }
        vm.selectModel(composer, null)
        assertThat(vm.state.value.selectedVariant?.displayName).isEqualTo("Composer 2.5 Fast")
    }
}
