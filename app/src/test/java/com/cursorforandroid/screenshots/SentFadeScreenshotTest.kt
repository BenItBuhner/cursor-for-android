package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.MessageAttachment
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.conversation.LocalSentFades
import com.cursorforandroid.ui.conversation.LocalTranscriptControls
import com.cursorforandroid.ui.conversation.OutgoingStatus
import com.cursorforandroid.ui.conversation.TimelineItemView
import com.cursorforandroid.ui.conversation.TranscriptControls
import com.cursorforandroid.ui.conversation.TranscriptItemSpacing
import com.cursorforandroid.ui.conversation.rememberSentFades
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A sent message's bubble between its sending look and its sent one, on a stopped clock: sending, with its file card;
 * the server's copy of it (a new row in the list) partway up its fade; and a failed send's status line leaving as the
 * send is retried, fading out as the bubble eases to its new height. Same device qualifiers as [AppScreenshotTest];
 * written to `screenshots/`, which CI compares pixel for pixel.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SentFadeScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    private var items by mutableStateOf<List<TimelineItem>>(emptyList())
    private var outgoing by mutableStateOf<Map<String, OutgoingStatus>>(emptyMap())

    private val prompt = "Match the header to the spec and ship it behind the flag."
    private val earlier = UserMessage("u-0", "Start with the settings screen.")
    private val reply = AssistantMessage("a-0", "Settings is done: the header now uses the new spacing tokens.")

    private fun spec(): List<MessageAttachment> {
        val copy = File(compose.activity.cacheDir, "Q3-header-spec.pdf").apply { writeBytes(ByteArray(16)) }
        return listOf(MessageAttachment(copy.path, 0, 0, name = copy.name, mimeType = "application/pdf", sizeBytes = 2_400L * 1024))
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(initial: List<TimelineItem>) {
        items = initial
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val fades = rememberSentFades("chat", animatorsEnabled = { true })
            fades.look(remember(items) { items.filterIsInstance<UserMessage>() })
            CursorTheme(mode = ThemeMode.Dark) {
                val controls = TranscriptControls(outgoing = outgoing, onRetryOutgoing = {}, onEditOutgoing = {})
                CompositionLocalProvider(LocalRippleConfiguration provides null, LocalTranscriptControls provides controls, LocalSentFades provides fades) {
                    Box(Modifier.testTag(FRAME).fillMaxWidth().height(380.dp).background(CursorTheme.colors.canvas)) {
                        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(TranscriptItemSpacing)) {
                            items(items, key = { it.id }) { TimelineItemView(it) }
                        }
                    }
                }
            }
        }
        frames(64)
    }

    private fun frames(millis: Long) = repeat((millis / 16).toInt()) {
        compose.mainClock.advanceTimeBy(16)
        compose.waitForIdle()
    }

    private fun capture(name: String) = compose.onNodeWithTag(FRAME).captureRoboImage(File(outDir, "$name.png").path, RoborazziOptions())

    @Test
    fun sending() {
        show(listOf(earlier, reply, UserMessage("local-1", prompt, attachments = spec(), isPending = true)))
        capture("710_sent_bubble_sending")
    }

    @Test
    fun serverCopyMidFade() {
        val attachments = spec()
        show(listOf(earlier, reply, UserMessage("local-1", prompt, attachments = attachments, isPending = true)))
        compose.runOnUiThread {
            items = listOf(earlier, reply, UserMessage("run-1-msg", prompt, attachments = attachments))
            Snapshot.sendApplyNotifications()
        }
        frames(112)
        capture("711_sent_bubble_server_copy_mid_fade")
    }

    @Test
    fun statusLineLeaving() {
        outgoing = mapOf("local-1" to OutgoingStatus.Failed("Couldn't reach Cursor. Check your connection."))
        show(listOf(earlier, reply, UserMessage("local-1", prompt, isPending = true)))
        compose.runOnUiThread {
            outgoing = mapOf("local-1" to OutgoingStatus.Sending)
            Snapshot.sendApplyNotifications()
        }
        frames(80)
        capture("712_sent_bubble_status_line_leaving")
    }

    private companion object {
        const val FRAME = "sent_fade_frame"
    }
}
