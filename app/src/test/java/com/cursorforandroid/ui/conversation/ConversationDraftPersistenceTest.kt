package com.cursorforandroid.ui.conversation

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
import com.cursorforandroid.data.local.FollowUpStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.data.repo.SessionState
import com.cursorforandroid.domain.AgentMode
import com.cursorforandroid.domain.ModelChoice
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.ui.components.ModePills
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
 * The follow-up composer's draft is the whole composer: the text, the attachments, the mode pill and the model with
 * every parameter the picker set. Bennett's report was the model reverting while the text survived; each test here
 * leaves a chat the way the phone does — another chat opened, the process ended, a new build installed over the old
 * one's files, the account signed out and in — and asks for exactly what was left.
 *
 * A real (not demo) account: the demo's chats are never written to disk. The catalogue is the demo's, which is shaped
 * like the live one (Claude Fable 5.1's context × effort grid, Cursor Grok 4.6's effort × fast).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ConversationDraftPersistenceTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** The account `/v1/me` answers for; a test switches it to sign a different account in. */
    @Volatile private var email = BENNETT

    private val api = object : FakeCursorApi() {
        override suspend fun me(): ApiKeyInfoDto {
            meCalls++
            return ApiKeyInfoDto(apiKeyName = "test", userEmail = email, userId = if (email == BENNETT) 7L else 8L)
        }
    }

    private val stores = ArrayList<ViewModelStore>()
    private val keys: SharedPreferences get() = context.getSharedPreferences("draft-persistence-keys", Context.MODE_PRIVATE)

    @Before
    fun setUp() = runBlocking {
        api.modelItems = DemoBackendFactory.create().first.models().items
        api.addIdleAgent(AGENT, "Green screen", "run-0")
        api.addIdleAgent(OTHER, "Flaky test", "run-9", createdAt = "2026-04-13T18:31:00.000Z")
    }

    @After
    fun tearDown() = runBlocking {
        stores.forEach { it.clear() }
        FollowUpStore(context).clear()
        File(context.filesDir, "parked-drafts").deleteRecursively()
        keys.edit().clear().commit()
        Unit
    }

    /**
     * A process: a new graph on the same disk, as a cold start builds it, its session restored from the stored key —
     * or, the first time, signed in.
     */
    private fun process(signIn: Boolean = false): AppGraph = runBlocking {
        val graph = AppGraph(context, keyStore = SecureKeyStore(context) { keys }, real = CursorBackend(api, FakeRunStreamer(), isDemo = false))
        if (signIn) graph.session.signIn("key_abc").getOrThrow() else graph.session.restoreIfNeeded()
        check(graph.session.state.value is SessionState.SignedIn) { "The session did not come back: ${graph.session.state.value}" }
        graph.agents.refresh()
        graph
    }

    /** The chat's composer as its screen holds one, the catalogue loaded. */
    private fun AppGraph.open(agentId: String, store: ViewModelStore = ViewModelStore()): ConversationViewModel = runBlocking {
        stores += store
        val vm = ViewModelProvider(store, ConversationViewModel.Factory(this@open, agentId))[ConversationViewModel::class.java]
        withTimeout(20_000) { vm.modelPicker.first { it.models.isNotEmpty() && !it.isLoading } }
        vm
    }

    private fun ConversationViewModel.picker(condition: (FollowUpModelState) -> Boolean): FollowUpModelState =
        runBlocking { withTimeout(20_000) { modelPicker.first(condition) } }

    // Generous, as in FollowUpRepositoryTest: a loaded runner has been seen to take several times longer than a quiet one.
    private suspend fun awaitUntil(timeoutMs: Long = 20_000, condition: suspend () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(10)
    }

    /** Claude Fable 5.1 at 300K context and low effort: neither parameter the model's default. */
    private fun FollowUpModelState.claudeLowShort(): ModelChoice {
        val claude = models.first { it.id == "claude-fable-5.1-thinking" }
        return ModelChoice(claude, claude.variantWithParams(mapOf("context" to "300k", "effort" to "low")))
    }

    /** What the disk holds for [agentId], read as the next process would. */
    private fun onDisk(agentId: String = AGENT) = runBlocking { FollowUpStore(context).read(agentId)?.draft }

    @Test
    fun `the mode pill and the model, every parameter included, outlive the process that picked them`() {
        val first = process(signIn = true)
        val vm = first.open(AGENT)
        val picked = vm.modelPicker.value.claudeLowShort()
        vm.selectModel(picked.model, picked.variant)
        vm.setModePill(ModePills.Pill.Plan)
        vm.setDraft("Half a thought")
        // The app leaves the screen — the only state a process is ended in — and the process is ended there: no
        // screen is cleared, nothing more is written.
        first.flushDrafts()
        runBlocking { awaitUntil { onDisk()?.let { it.text == "Half a thought" && it.model != null && it.mode != null } == true } }

        val revived = process().open(AGENT)
        val picker = revived.picker { it.override != null }

        assertThat(picker.override).isEqualTo(picked)
        assertThat(picker.override!!.params).containsExactly(ModelParam("context", "300k"), ModelParam("effort", "low"))
        assertThat(picker.chipLabel).isEqualTo("Claude Fable 5.1")
        assertThat(picker.mode).isEqualTo(AgentMode.PLAN)
        assertThat(picker.modePill).isEqualTo(ModePills.Pill.Plan)
        assertThat(revived.draftText.value).isEqualTo("Half a thought")
    }

    @Test
    fun `a chat left for another opens again on its pick from its first frame`() {
        val graph = process(signIn = true)
        val firstVisit = ViewModelStore()
        val vm = graph.open(AGENT, firstVisit)
        val grok = vm.modelPicker.value.models.first { it.id == "cursor-grok-4.6" }
        val variant = grok.variantWithParams(mapOf("effort" to "medium", "fast" to "false"))
        vm.selectModel(grok, variant)
        vm.setModePill(ModePills.Pill.Plan)
        vm.setDraft("Keep this")

        // Another chat is opened in its place, and this one's screen goes away.
        firstVisit.clear()
        graph.open(OTHER).setDraft("Something else entirely")

        val back = ViewModelProvider(ViewModelStore().also { stores += it }, ConversationViewModel.Factory(graph, AGENT))[ConversationViewModel::class.java]
        // Read at once, before anything could have been fetched or collected: the first frame's chip and pill.
        val firstFrame = back.modelPicker.value
        assertThat(firstFrame.override).isEqualTo(ModelChoice(grok, variant))
        assertThat(firstFrame.chipLabel).isEqualTo("Cursor Grok 4.6")
        assertThat(firstFrame.mode).isEqualTo(AgentMode.PLAN)
        // The text follows once its attachments' previews (none here) have been decoded off the main thread.
        runBlocking { awaitUntil { back.draftText.value == "Keep this" } }
    }

    @Test
    fun `the pick is spent once the server has the message, whether or not the chat is still open`() {
        val graph = process(signIn = true)
        val store = ViewModelStore()
        val vm = graph.open(AGENT, store)
        val picked = vm.modelPicker.value.claudeLowShort()
        vm.selectModel(picked.model, picked.variant)
        vm.setDraft("Switch and go")
        vm.send()
        // The chat is left the moment send is tapped.
        store.clear()

        runBlocking {
            awaitUntil { api.runRequests.any { it.prompt.text == "Switch and go" } }
            awaitUntil { graph.followUps.state(AGENT).value.draft.model == null }
            awaitUntil { onDisk()?.model == null }
        }
        assertThat(api.runRequests.single { it.prompt.text == "Switch and go" }.model?.id).isEqualTo("claude-fable-5.1-thinking")
        // What the send took is gone; the chat's model is the one it switched to, so the chip still names it.
        val reopened = graph.open(AGENT)
        assertThat(reopened.modelPicker.value.override).isNull()
        assertThat(reopened.draftText.value).isEmpty()
    }

    /**
     * An update installed over the app: its files are the previous build's. 0.3.61 wrote `state.json` without a
     * schema, without the draft's mode or model, and with its images beside it; the new build reads every part of it,
     * sends the queued message it held, and writes the file back in its own schema.
     */
    @Test
    fun `a draft left by 0_3_61 is read by the new build, sent from where it stood, and rewritten in the current schema`() {
        val dir = File(File(context.filesDir, "followups"), AGENT).apply { mkdirs() }
        File(dir, "photo-1.png").writeBytes(byteArrayOf(9, 8, 7))
        File(dir, "state.json").writeText(
            """{"draft":{"text":"Typed on 0.3.61","images":[{"id":"content://media/photo/1","file":"photo-1.png","mimeType":"image/png"}]},""" +
                """"queue":[{"id":"queued-1","text":"Queued on 0.3.61","queuedAtMillis":1800000000000,"planMode":true,"modelId":"composer-2.5",""" +
                """"modelParams":[{"id":"fast","value":"false"}],"modelDisplayName":"Composer 2.5"}]}""",
        )

        val graph = process(signIn = true)
        val vm = graph.open(AGENT)

        runBlocking { awaitUntil { vm.draftText.value == "Typed on 0.3.61" && vm.pendingAttachments.value.size == 1 } }
        assertThat(vm.pendingAttachments.value.single().image.bytes.toList()).containsExactly(9.toByte(), 8.toByte(), 7.toByte()).inOrder()
        assertThat(vm.modelPicker.value.override).isNull()
        assertThat(vm.modelPicker.value.mode).isNull()
        // The queued message goes out as it was queued, on the model and mode it was queued with.
        runBlocking { awaitUntil { api.runRequests.any { it.prompt.text == "Queued on 0.3.61" } } }
        val sent = api.runRequests.single { it.prompt.text == "Queued on 0.3.61" }
        assertThat(sent.model?.id).isEqualTo("composer-2.5")
        assertThat(sent.model?.params?.map { it.id to it.value }).containsExactly("fast" to "false")
        assertThat(sent.mode).isEqualTo("plan")

        runBlocking { awaitUntil { File(dir, "state.json").readText().contains("\"schema\":${FollowUpStore.SCHEMA}") } }
        val rewritten = onDisk()!!
        assertThat(rewritten.text).isEqualTo("Typed on 0.3.61")
        assertThat(rewritten.images.single().image.bytes.toList()).containsExactly(9.toByte(), 8.toByte(), 7.toByte()).inOrder()
    }

    @Test
    fun `signing out parks the draft where only the same account's sign-in finds it again`() {
        val graph = process(signIn = true)
        val store = ViewModelStore()
        val vm = graph.open(AGENT, store)
        val picked = vm.modelPicker.value.claudeLowShort()
        vm.selectModel(picked.model, picked.variant)
        vm.setModePill(ModePills.Pill.Plan)
        vm.setDraft("Before signing out")
        store.clear()

        // Signed out the moment after typing, the last words still inside the save's debounce.
        runBlocking { graph.session.signOut() }
        // Nothing of it is left where the composers read.
        assertThat(onDisk()).isNull()

        // Another account signs in on the same device: it finds nothing.
        email = SOMEONE_ELSE
        runBlocking { graph.session.signIn("key_other").getOrThrow() }
        runBlocking { graph.agents.refresh() }
        val theirs = graph.open(AGENT)
        runBlocking { awaitUntil { graph.followUps.state(AGENT).value.restored } }
        assertThat(theirs.draftText.value).isEmpty()
        assertThat(theirs.modelPicker.value.override).isNull()
        stores.forEach { it.clear() }
        runBlocking { graph.session.signOut() }

        // The same account signs back in: everything it left is there.
        email = BENNETT
        runBlocking { graph.session.signIn("key_again").getOrThrow() }
        runBlocking { graph.agents.refresh() }
        val back = graph.open(AGENT)
        runBlocking { awaitUntil { back.draftText.value == "Before signing out" } }
        val picker = back.picker { it.override != null }
        assertThat(picker.override).isEqualTo(picked)
        assertThat(picker.mode).isEqualTo(AgentMode.PLAN)
    }

    private companion object {
        const val AGENT = "bc-draft-1"
        const val OTHER = "bc-draft-2"
        const val BENNETT = "bennett@example.com"
        const val SOMEONE_ELSE = "someone@example.com"
    }
}
