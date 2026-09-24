package com.cursorforandroid.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.cursorforandroid.fixtures.CoordinatorFixtures
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * A coordinator's links to its workers (see [CoordinatorFixtures], `agent_links_message.json`): `[label](bc-<uuid>)`,
 * the same with `#desktop`, and a cursor.com/agents URL in prose, each a link whose target is what was written — the
 * screen decides where it goes. The other scheme-less targets coordinators write are as they were: a store path is a
 * link, a relative path is text.
 */
class AgentLinksMarkdownTest {

    private val fixture = CoordinatorFixtures.json("agent_links_message.json")
    private val markdown = fixture.getValue("markdown").jsonPrimitive.content
    private val workers = fixture.getValue("workers").jsonObject.mapValues { it.value.jsonPrimitive.content }
    private val backStack = workers.getValue("backStack")
    private val agentLinks = workers.getValue("agentLinks")
    private val release = workers.getValue("release")
    private val store = "/cursor/stores/bc-bae107cb-2562-40b2-b814-4f8eca874668/docs/project-context.md"

    private fun render(text: String, onLinkClick: ((String) -> Unit)? = {}): AnnotatedString =
        InlineMarkdown.render(text, TextStyle(fontSize = 15.sp), Color.White, Color.DarkGray, Color.Blue, Color.White, onLinkClick)

    private fun links(text: String): List<String> {
        val rendered = render(text)
        return rendered.getLinkAnnotations(0, rendered.length).mapNotNull { (it.item as? LinkAnnotation.Url)?.url }
    }

    private fun paragraphs() = markdown.split("\n\n")

    @Test
    fun `an agent id, with or without a fragment, and the agent's page are link targets`() {
        assertThat(InlineMarkdown.linkTarget(backStack)).isEqualTo(backStack)
        assertThat(InlineMarkdown.linkTarget(" $backStack ")).isEqualTo(backStack)
        assertThat(InlineMarkdown.linkTarget("$agentLinks#desktop")).isEqualTo("$agentLinks#desktop")
        assertThat(InlineMarkdown.linkTarget("$agentLinks#turn-2")).isEqualTo("$agentLinks#turn-2")
        assertThat(InlineMarkdown.linkTarget("https://cursor.com/agents/$release")).isEqualTo("https://cursor.com/agents/$release")
    }

    @Test
    fun `the other scheme-less targets coordinators write are as they were`() {
        // A store path is a link onto itself.
        assertThat(InlineMarkdown.linkTarget(store)).isEqualTo(store)
        // A relative path, including one beside a store document or named like an agent, is text; so is the VM's artifacts folder.
        listOf(
            "docs/spec.md",
            "../media/agent-links/coordinator.png",
            "notes.md",
            "./diff.patch",
            "bc-notes.md",
            "$backStack.md",
            "/opt/cursor/artifacts/demo.png",
            "javascript:alert(1)",
            "file:///sdcard/secret.txt",
        ).forEach { target -> assertWithMessage(target).that(InlineMarkdown.linkTarget(target)).isNull() }
    }

    @Test
    fun `the coordinator's message links every agent it cites, in order, with the labels as its words`() {
        val (first, second, third) = paragraphs()
        assertThat(links(first)).containsExactly(backStack, agentLinks).inOrder()
        assertThat(render(first).text).isEqualTo(
            "Two workers are on Bennett's navigation report. Make back navigation follow history is building the history-based back stack, and Make agent links tappable is teaching the renderer these very links.",
        )
        // The desktop link and a bare page URL in prose.
        assertThat(links(second)).containsExactly("$agentLinks#desktop", "https://cursor.com/agents/$release").inOrder()
        // The store path in code is still a link; the relative spec link is still its label alone.
        assertThat(links(third)).containsExactly(store)
        assertThat(render(third).text).endsWith("and its spec section is relative to it.")
    }

    @Test
    fun `a tap hands the listener the target as written`() {
        val tapped = mutableListOf<String>()
        val rendered = render(paragraphs()[0], onLinkClick = { tapped += it })
        rendered.getLinkAnnotations(0, rendered.length).forEach { range ->
            val link = range.item as LinkAnnotation.Url
            link.linkInteractionListener!!.onClick(link)
        }
        assertThat(tapped).containsExactly(backStack, agentLinks).inOrder()
    }

    @Test
    fun `an agent link is found under its label and nowhere else`() {
        val text = "See [the worker]($backStack) or [the docs](https://example.com/docs)."
        val rendered = render(text)
        val label = rendered.text.indexOf("the worker")
        assertThat(InlineMarkdown.agentLinkAt(rendered, label + 2)).isEqualTo(backStack)
        assertThat(InlineMarkdown.agentLinkAt(rendered, 1)).isNull()
        // An ordinary link is a link, but not an agent's.
        val docs = rendered.text.indexOf("the docs")
        assertThat(InlineMarkdown.isLinkAt(rendered, docs + 2)).isTrue()
        assertThat(InlineMarkdown.agentLinkAt(rendered, docs + 2)).isNull()
    }
}
