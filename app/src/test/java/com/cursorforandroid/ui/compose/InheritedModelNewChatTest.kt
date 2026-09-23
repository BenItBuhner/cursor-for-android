package com.cursorforandroid.ui.compose

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.ComposerSnapshot
import com.cursorforandroid.data.api.RecordFields
import com.cursorforandroid.data.api.dto.ApiKeyInfoDto
import com.cursorforandroid.data.api.dto.ModelParamDto
import com.cursorforandroid.data.api.dto.ModelRefDto
import com.cursorforandroid.data.local.DraftStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.domain.AccountModel
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.domain.Repository
import com.cursorforandroid.fixtures.LiveModelCatalog
import com.cursorforandroid.util.AppClock
import com.cursorforandroid.util.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
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
 * The new-chat composer's inherited model, over a catalogue shaped like the live one: the account's newest chat of its
 * own, started by another client that wrote a slug, and a draft that kept one. Both open on the entry and parameters
 * picking the model by hand gives, and a launch sends the catalogue's id with them. A Project worker's slug — its
 * coordinator's choice — is not the account's default.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class InheritedModelNewChatTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val api = object : FakeCursorApi() {
        override suspend fun me() = ApiKeyInfoDto(apiKeyName = "test", userEmail = "bennett@example.com", userId = 7L)
    }
    private lateinit var graph: AppGraph

    @Before
    fun setUp() = runBlocking {
        api.modelItems = LiveModelCatalog.items
        api.addFinishedAgent(MINE, "Flaky login test", Triple("run-m", "Find the flaky login test.", "Found it."), firstRunAt = "2026-09-20T10:00:00.000Z")
        api.addFinishedAgent(PROJECT, "Polymarket bot", Triple("run-p", "Run the Project.", "Started."), firstRunAt = "2026-09-21T10:00:00.000Z")
        api.addFinishedAgent(WORKER, "OCR the frames", Triple("run-w", "Extract the frames.", "Done."), firstRunAt = "2026-09-22T10:00:00.000Z")
        graph = AppGraph(
            context,
            keyStore = SecureKeyStore(context) { context.getSharedPreferences("inherited-new-chat-keys", Context.MODE_PRIVATE) },
            real = CursorBackend(api, FakeRunStreamer(), isDemo = false),
        )
        graph.session.signIn("key_abc").getOrThrow()
        graph.prefs.rememberModel(null)
        graph.agents.refresh()
        graph.agents.applyAccountSnapshots(
            listOf(
                ComposerSnapshot(MINE, model = AccountModel("claude-opus-5-5-max-fast")),
                ComposerSnapshot(PROJECT, isProject = true, record = RecordFields(projectMetadata = "{}"), model = AccountModel("gpt-5.6-sol-high")),
                ComposerSnapshot(WORKER, record = RecordFields(managerAgentId = PROJECT), parent = AgentParent(PROJECT, AgentParentKind.PROJECT_WORKER), model = AccountModel("muse-spark-1.3-minimal")),
            ),
        )
    }

    @After
    fun tearDown() = runBlocking {
        graph.drafts.clear()
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun loaded(resume: String? = null): NewAgentViewModel {
        val vm = NewAgentViewModel(graph, resume = resume)
        runBlocking { withTimeout(10_000) { vm.state.first { it.models.isNotEmpty() && !it.isLoadingRepos && !it.isLoadingDevices } } }
        return vm
    }

    @Test
    fun `a new chat opens on the account's newest chat's slug as the hand-picked entry, and launches with it`() = runBlocking {
        val opus = LiveModelCatalog.model("claude-opus-5.5")
        val vm = loaded()
        val state = vm.state.value
        assertThat(state.selectedModel).isEqualTo(opus)
        assertThat(state.selectedVariant).isEqualTo(opus.variantWith(opus.variantWith(opus.defaultVariant, "effort", "max"), "fast", "true"))
        assertThat(state.modelLabel).isEqualTo("Claude Opus 5.5")

        vm.selectRepo(Repository("https://github.com/bennett/codex-poly-bot"))
        vm.setPrompt("Try the other approach")
        var opened: String? = null
        vm.launch(onOpen = { opened = it })
        withTimeout(10_000) { while (opened == null || api.createRequests.isEmpty()) delay(10) }
        assertThat(api.createRequests.single().model).isEqualTo(ModelRefDto("claude-opus-5.5", listOf(ModelParamDto("effort", "max"), ModelParamDto("fast", "true"))))
    }

    @Test
    fun `a draft that kept a slug reopens on the catalogue's entry`() = runBlocking {
        val now = AppClock.now()
        graph.drafts.write(
            DraftStore.Record(
                id = "draft-slug",
                createdAtMillis = now,
                updatedAtMillis = now,
                prompt = "Pick this back up",
                modelId = "gpt-5.6-sol-none-fast",
                modelChosen = true,
            ),
        )
        val vm = loaded(resume = "draft-slug")
        withTimeout(10_000) { vm.state.first { it.prompt == "Pick this back up" && it.selectedModel?.id == "gpt-5.6-sol" } }
        val state = vm.state.value
        assertThat(state.selectedVariant?.params?.toSet()).isEqualTo(setOf(ModelParam("effort", "none"), ModelParam("fast", "true")))
        assertThat(state.modelLabel).isEqualTo("GPT-5.6 Sol")
    }

    private companion object {
        const val MINE = "bc-mine"
        const val PROJECT = "bc-project"
        const val WORKER = "bc-worker"
    }
}
