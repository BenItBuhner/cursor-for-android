package com.cursorforandroid.ui.conversation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.local.DraftStore
import com.cursorforandroid.data.repo.NewChatDrafts
import com.cursorforandroid.domain.DraftImage
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.CursorRoot
import com.cursorforandroid.ui.components.FlyingAttachmentTag
import com.cursorforandroid.ui.components.SendFlight
import com.cursorforandroid.ui.components.SendMotion
import com.cursorforandroid.ui.components.SendMotionHost
import com.cursorforandroid.ui.home.NewChatHomeCopy
import com.cursorforandroid.ui.home.NewChatHomeTags
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.RetryOnLeakedExceptions
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The send, through the whole app on the demo, on a phone and on a wide window with the sidebar beside the pane: a
 * follow-up's text is lifted off the composer at the tap — the composer empty from that moment — flies into the
 * bubble the transcript shows for it and lands there, never fading for want of one; a prompt sent from New Chat
 * flies into the first bubble of the chat it starts, the chat fading in over the pane rather than sliding in. Pictures
 * attached to either lift off the composer's row with the text and fly into their thumbnails in the bubble.
 *
 * With `SEND_DEMO_DIR` set, every frame of each flight is written there as a PNG (the demo video's frames).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class SendAnimationFlowTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RetryOnLeakedExceptions()).around(compose)

    private lateinit var graph: AppGraph
    private val motion = SendMotion(animatorsEnabled = { true })
    private val demoDir: File? = System.getenv("SEND_DEMO_DIR")?.let(::File)

    @Before
    fun setUp() {
        graph = AppGraph(ApplicationProvider.getApplicationContext())
        runBlocking {
            graph.session.enterDemo()
            graph.drafts.clear()
        }
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                SendMotionHost(motion) {
                    CursorRoot(graph = graph, deepLinkAgentId = null, onDeepLinkConsumed = {})
                }
            }
        }
        waitForText(NewChatHomeCopy.PLACEHOLDER, 30_000)
        compose.waitUntil(30_000) { graph.agents.state.value.let { it.hasLoaded && !it.isRefreshing } }
    }

    @After
    fun tearDown() {
        compose.mainClock.autoAdvance = true
        runBlocking { graph.drafts.clear() }
    }

    private fun onScreen(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(text: String, timeoutMillis: Long = 20_000) = compose.waitUntil(timeoutMillis) { onScreen(text) }

    private fun openIdleChat() {
        compose.waitUntil(30_000) {
            runCatching { compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(IDLE_CHAT, substring = true)) }.isSuccess
        }
        compose.onAllNodesWithText(IDLE_CHAT).onFirst().performClick()
        waitForText(CHAT_PROMPT, 30_000)
        compose.waitForIdle()
    }

    /**
     * What a flight went through, frame by frame, with the frames written out for the demo when asked; [copies] is the
     * most attachment copies seen in the air at once.
     */
    private class Flown(val phases: List<SendFlight.Phase>, val flight: SendFlight, val midway: Boolean, val copies: Int)

    /**
     * Types [text] into the composer tagged [composerTag] and taps its Send with the clock held, then steps the clock a
     * frame at a time until the flight is over. Real time is given between frames for the work the send does off the
     * clock (the bubble staged, the transcript presented off the main thread).
     */
    private fun sendAndFly(composerTag: String, text: String, demoName: String): Flown {
        val inComposer = hasAnyAncestor(hasTestTag(composerTag))
        compose.onNode(hasSetTextAction() and inComposer).performTextInput(text)
        val send = hasContentDescription("Send") and inComposer
        compose.waitUntil(20_000) { compose.onAllNodes(send and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        var frame = 0
        fun capture() {
            val dir = demoDir ?: return
            dir.mkdirs()
            // Drawn here rather than through captureToImage, which waits for a redraw the held clock never lets happen.
            val root = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            compose.runOnUiThread { root.draw(Canvas(bitmap)) }
            File(dir, "%s_%03d.png".format(demoName, frame)).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // A few frames at rest with the text in the composer, for the demo's lead-in.
        repeat(if (demoDir != null) 12 else 0) { capture(); frame++; compose.mainClock.advanceTimeByFrame() }
        compose.onNode(send).performClick()
        val flight = checkNotNull(motion.flight) { "The tap lifted nothing off the composer." }
        val phases = ArrayList<SendFlight.Phase>()
        var midway = false
        var copies = 0
        var steps = 0
        while (motion.flight === flight && steps < MAX_FRAMES) {
            // A frame, drawn as it leaves the screen — composed first, as every frame on a device is — and then the
            // main thread's queue run as a real frame would leave it (the bubble staged there).
            compose.mainClock.advanceTimeByFrame()
            capture()
            frame++
            phases += flight.phase
            if (flight.phase == SendFlight.Phase.Flying && flight.progress.value in 0.2f..0.7f) midway = true
            copies = maxOf(copies, compose.onAllNodes(hasTestTag(FlyingAttachmentTag), useUnmergedTree = true).fetchSemanticsNodes().size)
            compose.waitForIdle()
            if (flight.phase == SendFlight.Phase.Holding) Thread.sleep(HOLD_REAL_MILLIS)
            steps++
        }
        // The landing, and a moment after it.
        repeat(if (demoDir != null) 24 else 1) { compose.mainClock.advanceTimeByFrame(); capture(); frame++ }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        return Flown(phases.distinct(), flight, midway, copies)
    }

    private fun assertLanded(flown: Flown, label: String) {
        assertWithMessage("$label: the flight is over").that(motion.flight).isNull()
        assertWithMessage("$label: phases").that(flown.phases).contains(SendFlight.Phase.Flying)
        assertWithMessage("$label: it never faded for want of a bubble").that(flown.phases).doesNotContain(SendFlight.Phase.Fading)
        assertWithMessage("$label: seen midway").that(flown.midway).isTrue()
        assertWithMessage("$label: it reached the bubble").that(flown.flight.progress.value).isEqualTo(1f)
    }

    private fun followUpLands(label: String) {
        openIdleChat()
        val flown = sendAndFly(FOLLOW_UP_COMPOSER, FOLLOW_UP, "${label}_follow_up")
        assertLanded(flown, label)
        assertThat(flown.flight.text).isEqualTo(FOLLOW_UP)
        waitForText(FOLLOW_UP)
        // The composer is empty and says so again; the message is the transcript's.
        compose.waitUntil(10_000) { onScreen("Follow up") }
        val field = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(FOLLOW_UP_COMPOSER))).fetchSemanticsNode()
        assertThat(field.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()).isEmpty()
    }

    private fun newChatLands(label: String) {
        val flown = sendAndFly(NewChatHomeTags.COMPOSER, NEW_CHAT, "${label}_new_chat")
        assertLanded(flown, label)
        val agentId = checkNotNull(flown.flight.agentId) { "$label: the flight was never bound to the chat it started" }
        val first = graph.conversations.state(agentId).value.items.filterIsInstance<UserMessage>().first()
        assertThat(first.text).isEqualTo(NEW_CHAT)
        compose.waitUntil(10_000) { onScreen("Follow up") }
        assertThat(onScreen(NewChatHomeCopy.PLACEHOLDER)).isFalse()
    }

    /** Two pictures, the bytes of PNGs: an orange landscape and a blue portrait. */
    private fun pictures(): List<PromptImage> = listOf(160 to 120 to AndroidColor.rgb(214, 108, 52), 120 to 200 to AndroidColor.rgb(52, 120, 246)).map { (size, color) ->
        val bitmap = Bitmap.createBitmap(size.first, size.second, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        PromptImage(ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray(), "image/png")
    }

    private fun assertPicturesFlew(flown: Flown, label: String, bubble: UserMessage) {
        assertLanded(flown, label)
        assertWithMessage("$label: both pictures lifted off").that(flown.flight.takeoff.attachments.map { it.look.ordinal }).containsExactly(0, 1).inOrder()
        assertWithMessage("$label: both copies in the air").that(flown.copies).isEqualTo(2)
        assertWithMessage("$label: the copies are gone with the flight").that(compose.onAllNodes(hasTestTag(FlyingAttachmentTag), useUnmergedTree = true).fetchSemanticsNodes()).isEmpty()
        assertWithMessage("$label: the bubble carries them").that(bubble.attachments).hasSize(2)
    }

    private fun followUpPicturesLand(label: String) {
        graph.followUps.setDraftImages(IDLE_AGENT, pictures().mapIndexed { index, image -> DraftImage("img-$index", image) })
        openIdleChat()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("media-tile")).fetchSemanticsNodes().size == 2 }
        val flown = sendAndFly(FOLLOW_UP_COMPOSER, FOLLOW_UP, "${label}_follow_up_pictures")
        waitForText(FOLLOW_UP)
        val bubble = graph.conversations.state(IDLE_AGENT).value.items.filterIsInstance<UserMessage>().last()
        assertThat(bubble.text).isEqualTo(FOLLOW_UP)
        assertPicturesFlew(flown, label, bubble)
    }

    private fun newChatPicturesLand(label: String) {
        runBlocking {
            val images = pictures().mapNotNull { graph.drafts.writeImage(PICTURES_DRAFT, it) }
            graph.newChatDrafts.save(DraftStore.Record(id = PICTURES_DRAFT, createdAtMillis = 1L, updatedAtMillis = 1L, images = images, nonce = "n-pictures"))
        }
        compose.waitUntil(30_000) { graph.newChatDrafts.record(PICTURES_DRAFT) != null }
        graph.newChatDrafts.request(NewChatDrafts.Request.Open(PICTURES_DRAFT))
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("media-tile")).fetchSemanticsNodes().size == 2 }
        val flown = sendAndFly(NewChatHomeTags.COMPOSER, NEW_CHAT, "${label}_new_chat_pictures")
        val agentId = checkNotNull(flown.flight.agentId) { "$label: the flight was never bound to the chat it started" }
        val first = graph.conversations.state(agentId).value.items.filterIsInstance<UserMessage>().first()
        assertThat(first.text).isEqualTo(NEW_CHAT)
        assertPicturesFlew(flown, label, first)
    }

    @Test
    fun `phone - a follow-up flies from the composer into its bubble`() = followUpLands("phone")

    @Test
    fun `phone - a follow-up's pictures fly with its text into their thumbnails in the bubble`() = followUpPicturesLand("phone")

    @Test
    fun `phone - a New Chat prompt's pictures fly into the first bubble of the chat it starts`() = newChatPicturesLand("phone")

    @Test
    @Config(sdk = [35], qualifiers = TABLET)
    fun `wide - a follow-up's pictures fly with its text beside the sidebar`() = followUpPicturesLand("wide")

    @Test
    fun `phone - a New Chat prompt flies into the first bubble of the chat it starts`() = newChatLands("phone")

    @Test
    @Config(sdk = [35], qualifiers = TABLET)
    fun `wide - a follow-up flies from the composer into its bubble beside the sidebar`() = followUpLands("wide")

    @Test
    @Config(sdk = [35], qualifiers = TABLET)
    fun `wide - a New Chat prompt flies into the first bubble of the chat it starts`() = newChatLands("wide")

    private companion object {
        const val IDLE_CHAT = "Cli exploration"
        const val IDLE_AGENT = "bc-demo-0004"
        const val PICTURES_DRAFT = "draft-pictures"
        const val CHAT_PROMPT = "Explore how the Cursor CLI resumes cloud agents"
        const val FOLLOW_UP_COMPOSER = "follow-up-composer"
        const val FOLLOW_UP = "Now make resume survive a dropped connection"
        const val NEW_CHAT = "Add a dark mode toggle to the settings page"
        const val MAX_FRAMES = 240
        const val HOLD_REAL_MILLIS = 15L
    }
}

private const val PHONE = "w411dp-h914dp-night-420dpi"
private const val TABLET = "w1000dp-h720dp-night-320dpi"
