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
 * The composer remembers what the last agent was launched with. Runs against the demo backend, whose catalogue
 * mirrors the live one: "Composer 2.5" has two variants of the same name, `fast` on (the default) and off.
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

    /** The composer derives its branch list from the agent list, which the sidebar normally loads. */
    private fun loadedWithAgents(): NewAgentViewModel {
        runBlocking { graph.agents.refresh() }
        return loaded()
    }

    private fun NewAgentViewModel.repo(shortName: String) = state.value.repositories.first { it.shortName == shortName }

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
}
