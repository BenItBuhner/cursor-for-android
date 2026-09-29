package com.cursorforandroid.ui.conversation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.util.AppClock
import com.cursorforandroid.util.RecomposeCounter
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * A streaming reply's publications recompose the transcript's list and the reply, and nothing around them: not the
 * screen's body, the header, the composer, the goal strip or the load notice over it, or the queue's deliveries. Until
 * the list read the presented transcript in a scope of its own, every delta ran the body, and with it the composer
 * and the deliveries.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ScreenPublicationRecompositionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = Instant.parse("2026-09-16T12:00:00Z").toEpochMilli()

    @Before
    fun setUp() {
        AppClock.nowMillis = { now }
    }

    @After
    fun tearDown() {
        AppClock.nowMillis = System::currentTimeMillis
        RecomposeCounter.uninstall()
    }

    @Test
    fun `a streamed reply's publications leave the header, the composer and the strips alone`() {
        val chat = ScreenPublications(turns = 50)
        chat.seed(now)
        chat.compose(compose)
        val viewModel = chat.viewModel(compose)
        chat.publish()
        chat.settle(compose, viewModel)
        assertThat(chat.shows(compose, "goal-strip")).isTrue()
        assertThat(chat.shows(compose, "load-notice")).isTrue()

        RecomposeCounter.install()
        repeat(10) {
            chat.publish()
            chat.settle(compose, viewModel)
        }

        assertThat(RecomposeCounter.count("ReplyMessage")).isAtLeast(10)
        for (name in listOf("ConversationScreen", "ChatHeader", "ComposerBox", "GoalStrip", "LoadNoticeRow", "QueueDeliveries")) {
            assertWithMessage("$name recompositions").that(RecomposeCounter.count(name)).isEqualTo(0)
        }
    }
}
