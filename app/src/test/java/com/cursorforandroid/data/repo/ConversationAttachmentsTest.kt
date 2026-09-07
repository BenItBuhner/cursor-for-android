package com.cursorforandroid.data.repo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.UserMessage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The bug this guards against: the transcript endpoint returns prompts as text only, so the images of a follow-up
 * used to vanish from its bubble as soon as history was reloaded. Native graphics so the previews really encode.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ConversationAttachmentsTest {

    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private lateinit var attachments: AttachmentStore
    private lateinit var conversations: ConversationRepository

    @Before
    fun setUp() = runBlocking {
        val prefs = PreferencesStore(context)
        attachments = AttachmentStore(context)
        val backend = CursorBackend(api, streamer, isDemo = true)
        val session = SessionManager(SecureKeyStore(context), prefs, backend, backend)
        session.enterDemo()
        val agents = AgentRepository(session, prefs, attachments)
        val hub = LiveRunHub(session, agents, pollIntervalMs = 50, releaseGraceMs = 50, scope = scope)
        conversations = ConversationRepository(session, agents, prefs, hub, attachments)
    }

    @After
    fun tearDown() = runBlocking {
        conversations.resetAll()
        scope.cancel()
        attachments.clear()
    }

    private suspend fun awaitUntil(timeoutMs: Long = 5_000, condition: suspend () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(10)
    }

    private fun png(width: Int, height: Int): PromptImage {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val out = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
        return PromptImage(out.toByteArray(), "image/png")
    }

    private fun state() = conversations.state("bc-1").value

    private fun prompts() = state().items.filterIsInstance<UserMessage>()

    @Test
    fun `images sent with a follow-up stay on its bubble after history is reloaded from the server`() = runBlocking {
        api.addFinishedAgent("bc-1", "Merge chat state sync", Triple("run-1", "Add a README", "Done."))
        conversations.attach("bc-1")
        awaitUntil { !state().isLoading && prompts().size == 1 }
        assertThat(prompts().single().attachments).isEmpty()

        val result = conversations.sendFollowUp("bc-1", "merge and chat states often fail to sync between these two", listOf(png(200, 400), png(300, 300)))
        assertThat(result.isSuccess).isTrue()
        val runId = api.agents.getValue("bc-1").latestRunId!!
        assertThat(runId).isNotEqualTo("run-1")

        // The bubble shows both screenshots as soon as the follow-up is sent…
        val sent = prompts().last()
        assertThat(sent.text).isEqualTo("merge and chat states often fail to sync between these two")
        assertThat(sent.attachments).hasSize(2)
        assertThat(sent.attachments.map { it.aspectRatio }).containsExactly(0.5f, 1f).inOrder()
        sent.attachments.forEach { assertThat(File(it.path).isFile).isTrue() }
        // …filed under the run the API created, not the scratch directory.
        sent.attachments.forEach { assertThat(it.path).contains("${File.separator}$runId${File.separator}") }

        // The server's transcript has the text only; the images must survive the swap to server-side history.
        assertThat(api.transcripts.getValue("bc-1").last().text).isEqualTo(sent.text)
        conversations.reload("bc-1")
        awaitUntil { !state().isLoading && prompts().size == 2 && prompts().last().id == "$runId-u" }
        val reloaded = prompts().last()
        assertThat(reloaded.text).isEqualTo(sent.text)
        assertThat(reloaded.attachments).isEqualTo(sent.attachments)
        assertThat(prompts().first().attachments).isEmpty()
    }

    @Test
    fun `a follow-up the server rejects leaves no bubble and no files behind`() = runBlocking {
        api.addFinishedAgent("bc-1", "Agent", Triple("run-1", "Add a README", "Done."))
        api.failCreateRun = true
        conversations.attach("bc-1")
        awaitUntil { !state().isLoading && prompts().size == 1 }

        val result = conversations.sendFollowUp("bc-1", "Fix these", listOf(png(20, 20)))
        assertThat(result.isFailure).isTrue()
        assertThat(prompts()).hasSize(1)
        assertThat(attachments.forAgent("bc-1")).isEmpty()
        assertThat(File(context.filesDir, "attachments/.staging").listFiles().orEmpty()).isEmpty()
    }
}
