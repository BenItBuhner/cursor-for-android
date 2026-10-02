package com.cursorforandroid.notifications

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.SpotlightView
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SpotlightRendererTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val startedAt = 1_700_000_000_000L

    @Before
    fun setUp() = LiveNotifications.ensureChannels(context)

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    private fun Notification.subText() = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
    private fun Notification.bigText() = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

    private val view = SpotlightView(
        agentId = "bc-1",
        title = "Map the codebase",
        step = "Delegating to 3 subagents",
        lines = listOf("\u2022 Survey cloud sync layer", "\u2022 Survey distribution"),
        tally = "7 tool calls",
        startedAtMillis = startedAt,
    )

    @Test
    fun `a Spotlight is a silent ongoing card on its own channel, asking to be promoted`() {
        val n = SpotlightRenderer.spotlight(context, view)
        assertThat(NotificationCompat.getChannelId(n)).isEqualTo(LiveNotifications.CHANNEL_SPOTLIGHT)
        assertThat(n.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
        assertThat(n.flags and Notification.FLAG_ONLY_ALERT_ONCE).isNotEqualTo(0)
        assertThat(NotificationCompat.isRequestPromotedOngoing(n)).isTrue()
        // Android 16 refuses to promote colorized cards, group summaries and custom views.
        assertThat(n.extras.getBoolean(Notification.EXTRA_COLORIZED)).isFalse()
        assertThat(n.flags and Notification.FLAG_GROUP_SUMMARY).isEqualTo(0)
        assertThat(n.contentView).isNull()
        assertThat(n.bigContentView).isNull()
    }

    @Test
    fun `collapsed it is the name and the step, expanded the work under it, with a chronometer from the run's start`() {
        val n = SpotlightRenderer.spotlight(context, view)
        assertThat(n.title()).isEqualTo("Map the codebase")
        assertThat(n.text()).isEqualTo("Delegating to 3 subagents")
        assertThat(n.subText()).isEqualTo("Spotlight")
        assertThat(n.bigText()).isEqualTo("Delegating to 3 subagents\n\u2022 Survey cloud sync layer\n\u2022 Survey distribution\n7 tool calls")
        assertThat(n.`when`).isEqualTo(startedAt)
        assertThat(n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)).isTrue()
        assertThat(n.extras.getString(Notification.EXTRA_TEMPLATE)).isEqualTo(Notification.BigTextStyle::class.java.name)
    }

    @Test
    fun `a step with nothing under it still expands to just the step`() {
        val n = SpotlightRenderer.spotlight(context, view.copy(lines = emptyList(), tally = null))
        assertThat(n.bigText()).isEqualTo("Delegating to 3 subagents")
    }

    @Test
    fun `Stop Spotlight and a swipe both stop the Spotlight, and a tap opens the chat`() {
        val n = SpotlightRenderer.spotlight(context, view)
        assertThat(n.actions.orEmpty().map { it.title.toString() }).containsExactly("Stop Spotlight")
        val stop = shadowOf(n.actions.single().actionIntent).savedIntent
        assertThat(stop.action).isEqualTo(SpotlightService.ACTION_STOP)
        assertThat(stop.component?.className).isEqualTo(SpotlightService::class.java.name)
        assertThat(shadowOf(n.deleteIntent).savedIntent.action).isEqualTo(SpotlightService.ACTION_STOP)
        assertThat(shadowOf(n.contentIntent).savedIntent.dataString).endsWith("/agents/bc-1")
    }
}
