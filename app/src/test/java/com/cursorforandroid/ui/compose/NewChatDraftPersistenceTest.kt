package com.cursorforandroid.ui.compose

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.dto.ApiKeyInfoDto
import com.cursorforandroid.data.demo.DemoBackendFactory
import com.cursorforandroid.data.local.DraftStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.data.repo.LaunchIdempotency
import com.cursorforandroid.data.repo.LaunchRequest
import com.cursorforandroid.data.repo.NewChatDrafts
import com.cursorforandroid.data.repo.SessionState
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.ui.agents.DraftRow
import com.cursorforandroid.util.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.io.File

/**
 * The New Chat composer's drafts across what a phone does to them over an account: an update installed over 0.3.61's
 * single draft, and a sign-out the moment after typing followed by another account's sign-in and the same one's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NewChatDraftPersistenceTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Volatile private var email = BENNETT

    private val api = object : FakeCursorApi() {
        override suspend fun me(): ApiKeyInfoDto {
            meCalls++
            return ApiKeyInfoDto(apiKeyName = "test", userEmail = email, userId = if (email == BENNETT) 7L else 8L)
        }
    }

    private val stores = ArrayList<ViewModelStore>()
    private val keys: SharedPreferences get() = context.getSharedPreferences("new-chat-draft-keys", Context.MODE_PRIVATE)

    @Before
    fun setUp() = runBlocking {
        api.modelItems = DemoBackendFactory.create().first.models().items
        api.repositoryUrls = listOf(REPO)
    }

    @After
    fun tearDown() = runBlocking {
        stores.forEach { it.clear() }
        DraftStore(context).clear()
        File(context.filesDir, "parked-drafts").deleteRecursively()
        keys.edit().clear().commit()
        Unit
    }

    private fun process(): AppGraph = runBlocking {
        val graph = AppGraph(context, keyStore = SecureKeyStore(context) { keys }, real = CursorBackend(api, FakeRunStreamer(), isDemo = false))
        graph.session.signIn("key_abc").getOrThrow()
        check(graph.session.state.value is SessionState.SignedIn)
        graph
    }

    private fun AppGraph.composer(): NewAgentViewModel = runBlocking {
        val store = ViewModelStore().also { stores += it }
        val vm = ViewModelProvider(store, NewAgentViewModel.Factory(this@composer))[NewAgentViewModel::class.java]
        withTimeout(20_000) { vm.state.first { it.models.isNotEmpty() && !it.isLoadingRepos && !it.isLoadingDevices } }
        vm
    }

    private fun AppGraph.listed(vm: NewAgentViewModel?) = DraftRow.listed(newChatDrafts.state.value.drafts, open = vm?.draftId?.value)

    private suspend fun awaitUntil(timeoutMs: Long = 20_000, condition: suspend () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(10)
    }

    /**
     * An update installed over 0.3.61: its single draft — the prompt, an image beside it, the repository and branch,
     * the model it had picked, the switches, the nonce — is a draft in the sidebar of the new build's first start,
     * opens whole, and sent unchanged is the chat 0.3.61 would have created.
     */
    @Test
    fun `0_3_61's draft is in the new build's sidebar, opens whole, and is sent as the chat it would have been`() {
        val legacy = File(context.filesDir, "draft").apply { mkdirs() }
        File(legacy, "img-1").writeBytes(byteArrayOf(4, 2))
        File(legacy, "composer.json").writeText(
            """{"prompt":"Typed on 0.3.61","images":[{"file":"img-1","mimeType":"image/png"}],"repoUrl":"$REPO","ref":"cursor/fix-login",""" +
                """"modelId":"composer-2.5","modelParams":{"fast":"false"},"modelChosen":true,"planMode":true,"nonce":"n-0361"}""",
        )

        val graph = process()
        val vm = graph.composer()
        // A new start opens a fresh composer; the old draft is listed, not put in it.
        assertThat(vm.state.value.prompt).isEmpty()
        val row = graph.listed(vm).single()
        assertThat(row.title).isEqualTo("Typed on 0.3.61")
        assertThat(File(context.filesDir, "draft").exists()).isFalse()

        graph.newChatDrafts.request(NewChatDrafts.Request.Open(row.id))
        val state = runBlocking { withTimeout(20_000) { vm.state.first { it.prompt == "Typed on 0.3.61" && it.attachments.isNotEmpty() && it.selectedModel?.id == "composer-2.5" } } }
        assertThat(state.attachments.single().image.bytes).isEqualTo(byteArrayOf(4, 2))
        assertThat(state.selectedRepo?.url).isEqualTo(REPO)
        assertThat(state.ref).isEqualTo("cursor/fix-login")
        assertThat(state.selectedVariant?.params).containsExactly(ModelParam("fast", "false"))
        assertThat(state.planMode).isTrue()

        var opened: String? = null
        vm.launch(onOpen = { opened = it })
        runBlocking { awaitUntil { opened != null } }
        val expected = LaunchIdempotency.agentId(
            LaunchRequest(
                prompt = "Typed on 0.3.61",
                images = listOf(PromptImage(byteArrayOf(4, 2), "image/png")),
                repoUrl = REPO,
                ref = "cursor/fix-login",
                modelId = "composer-2.5",
                modelParams = listOf(ModelParam("fast", "false")),
                autoCreatePr = false,
                planMode = true,
                env = state.selectedDevice,
            ),
            "n-0361",
        )
        assertThat(opened).isEqualTo(expected)
    }

    @Test
    fun `signing out parks the new chat drafts where only the same account's sign-in finds them again`() {
        val graph = process()
        val vm = graph.composer()
        vm.setPrompt("Left for later")
        graph.newChatDrafts.request(NewChatDrafts.Request.Fresh)
        runBlocking { awaitUntil { vm.state.value.prompt.isEmpty() } }
        // The fresh composer is written into and the account signed out the moment after, inside the save's debounce,
        // the composer still alive under the screen the sign-out was asked from; the sign-out's screen change clears it.
        vm.setPrompt("Typed a moment ago")
        runBlocking { graph.session.signOut() }
        stores.forEach { it.clear() }
        stores.clear()

        assertThat(runBlocking { graph.drafts.list() }).isEmpty()

        email = SOMEONE_ELSE
        runBlocking { graph.session.signIn("key_other").getOrThrow() }
        runBlocking { graph.newChatDrafts.load() }
        assertThat(graph.listed(null)).isEmpty()
        runBlocking { graph.session.signOut() }

        email = BENNETT
        runBlocking { graph.session.signIn("key_again").getOrThrow() }
        runBlocking { graph.newChatDrafts.load() }
        assertThat(graph.listed(null).map { it.title }).containsExactly("Typed a moment ago", "Left for later")
    }

    private companion object {
        const val REPO = "https://github.com/acme/app"
        const val BENNETT = "bennett@example.com"
        const val SOMEONE_ELSE = "someone@example.com"
    }
}
