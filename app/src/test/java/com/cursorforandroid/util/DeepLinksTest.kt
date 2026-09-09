package com.cursorforandroid.util

import android.content.Intent
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeepLinksTest {

    private fun agentIdOf(url: String): String? = DeepLinks.agentId(url.toUri())

    @Test
    fun `an agents link opens that chat, on either host`() {
        assertThat(agentIdOf("https://cursor.com/agents/bc-demo-0001")).isEqualTo("bc-demo-0001")
        assertThat(agentIdOf("https://www.cursor.com/agents/bc-demo-0001")).isEqualTo("bc-demo-0001")
        assertThat(agentIdOf("https://cursor.com/agents/bc-demo-0001/logs")).isEqualTo("bc-demo-0001")
        assertThat(agentIdOf("https://CURSOR.COM/agents/bc-demo-0001")).isEqualTo("bc-demo-0001")
        assertThat(agentIdOf("https://cursor.com/agents?id=bc-demo-0002")).isEqualTo("bc-demo-0002")
    }

    @Test
    fun `a link without an agents segment is not a chat link`() {
        // indexOf returned -1 and the first path segment was read as the id, so any bc-prefixed path navigated.
        assertThat(agentIdOf("https://cursor.com/bc-anything/whatever")).isNull()
        assertThat(agentIdOf("https://cursor.com/")).isNull()
        assertThat(agentIdOf("https://cursor.com/pricing")).isNull()
    }

    @Test
    fun `other hosts and junk ids are refused`() {
        assertThat(agentIdOf("https://evil.test/agents/bc-demo-0001")).isNull()
        assertThat(agentIdOf("https://cursor.com/agents/bcx")).isNull()
        assertThat(agentIdOf("https://cursor.com/agents/../etc")).isNull()
        assertThat(DeepLinks.agentId(null as android.net.Uri?)).isNull()
    }

    @Test
    fun `a query id is still read when the path segment is not an id`() {
        assertThat(agentIdOf("https://cursor.com/agents/new?id=bc-demo-0003")).isEqualTo("bc-demo-0003")
    }

    @Test
    fun `a consumed link is not read again when the intent is re-read after a recreation`() {
        val intent = Intent(Intent.ACTION_VIEW, "https://cursor.com/agents/bc-demo-0001".toUri())
        assertThat(DeepLinks.agentId(intent)).isEqualTo("bc-demo-0001")
        DeepLinks.clearAgentLink(intent)
        assertThat(DeepLinks.agentId(intent)).isNull()
    }

    @Test
    fun `a consumed action leaves an ordinary launch intent`() {
        val intent = Intent("com.cursorforandroid.action.NEW_CHAT")
        DeepLinks.clearAction(intent, "com.cursorforandroid.action.NEW_CHAT")
        assertThat(intent.action).isEqualTo(Intent.ACTION_MAIN)
        DeepLinks.clearAction(intent, "some.other.action")
        assertThat(intent.action).isEqualTo(Intent.ACTION_MAIN)
    }
}
