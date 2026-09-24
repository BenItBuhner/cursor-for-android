package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The targets a message cites an agent by: its bare id (as a Project's coordinator links its workers), the id with a
 * fragment (`#desktop`, the agent's VM desktop), and the agent's page on cursor.com. Nothing else is one: a relative
 * file that happens to start with `bc-`, a store path under an agent's id, another host.
 */
class AgentLinkTest {

    private val id = "bc-0cbcf799-5a79-59fd-b8b3-b855a5bba8bf"

    @Test
    fun `a bare agent id is a link to that agent`() {
        val link = AgentLink.parse(id)!!
        assertThat(link).isEqualTo(AgentLink(id))
        assertThat(link.isDesktop).isFalse()
        assertThat(link.webUrl).isEqualTo("https://cursor.com/agents/$id")
        // As written between the parentheses, spaces and all.
        assertThat(AgentLink.parse("  $id ")).isEqualTo(AgentLink(id))
        // Upper-case hex is still the id.
        val upper = "bc-" + id.removePrefix("bc-").uppercase()
        assertThat(AgentLink.parse(upper)).isEqualTo(AgentLink(upper))
    }

    @Test
    fun `a fragment is kept, and #desktop is the agent's desktop`() {
        val desktop = AgentLink.parse("$id#desktop")!!
        assertThat(desktop).isEqualTo(AgentLink(id, "desktop"))
        assertThat(desktop.isDesktop).isTrue()
        assertThat(desktop.webUrl).isEqualTo("https://cursor.com/agents/$id#desktop")
        assertThat(AgentLink.parse("$id#Desktop")!!.isDesktop).isTrue()

        val other = AgentLink.parse("$id#turn-3")!!
        assertThat(other).isEqualTo(AgentLink(id, "turn-3"))
        assertThat(other.isDesktop).isFalse()
        assertThat(other.webUrl).isEqualTo("https://cursor.com/agents/$id#turn-3")
        // An empty fragment is no fragment.
        assertThat(AgentLink.parse("$id#")).isEqualTo(AgentLink(id))
    }

    @Test
    fun `the agent's page on cursor com is a link to it, in every spelling the deep links accept`() {
        assertThat(AgentLink.parse("https://cursor.com/agents/$id")).isEqualTo(AgentLink(id))
        assertThat(AgentLink.parse("https://cursor.com/agents/$id/")).isEqualTo(AgentLink(id))
        assertThat(AgentLink.parse("http://www.cursor.com/agents/$id")).isEqualTo(AgentLink(id))
        assertThat(AgentLink.parse("HTTPS://Cursor.com/agents/$id")).isEqualTo(AgentLink(id))
        assertThat(AgentLink.parse("https://cursor.com/agents?id=$id")).isEqualTo(AgentLink(id))
        assertThat(AgentLink.parse("https://cursor.com/agents?tab=all&id=$id")).isEqualTo(AgentLink(id))
        assertThat(AgentLink.parse("https://cursor.com/agents/$id#desktop")).isEqualTo(AgentLink(id, "desktop"))
        // The page takes the looser ids the app's deep links do.
        assertThat(AgentLink.parse("https://cursor.com/agents/bc-demo-1")).isEqualTo(AgentLink("bc-demo-1"))
    }

    @Test
    fun `a relative file, a store path, another host or a page without an agent is not a link to one`() {
        listOf(
            "bc-notes.md",
            "bc-0cbcf799",
            "$id.md",
            "$id/docs/spec.md",
            "bc-",
            "/cursor/stores/$id/docs/spec.md",
            "docs/$id",
            "javascript:$id",
            "https://cursor.com/agents",
            "https://cursor.com/agents/",
            "https://cursor.com/agents/not-an-agent",
            "https://cursor.com/agents/$id/extra",
            "https://cursor.com/agentsX/$id",
            "https://cursor.com.evil.example/agents/$id",
            "https://evil.example/agents/$id",
            "https://cursor.com/dashboard",
            "",
        ).forEach { target -> assertWithMessage(target).that(AgentLink.parse(target)).isNull() }
    }
}
