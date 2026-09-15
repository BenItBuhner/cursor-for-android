package com.cursorforandroid.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.cursorforandroid.fixtures.CoordinatorFixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * Store paths in a reply's prose are links, under every shape the coordinator writes them (see
 * [CoordinatorFixtures], `store_paths_message.json`): `[label](path)`, a code span that is a path, a bare path in a
 * sentence — each opening onto the path itself, which the screen resolves against the chat's store.
 */
class StoreLinksMarkdownTest {

    private val fixture = CoordinatorFixtures.json("store_paths_message.json")
    private val store = fixture.getValue("storeId").jsonPrimitive.content
    private val markdown = fixture.getValue("markdown").jsonPrimitive.content

    private fun render(text: String, onLinkClick: ((String) -> Unit)? = {}): AnnotatedString =
        InlineMarkdown.render(text, TextStyle(fontSize = 15.sp), Color.White, Color.DarkGray, Color.Blue, Color.White, onLinkClick)

    private fun links(text: String): List<String> {
        val rendered = render(text)
        return rendered.getLinkAnnotations(0, rendered.length).mapNotNull { (it.item as? LinkAnnotation.Url)?.url }
    }

    @Test
    fun `a store path is a link target, a repository path is not`() {
        assertThat(InlineMarkdown.linkTarget("/cursor/stores/$store/docs/execution-plan.md")).isEqualTo("/cursor/stores/$store/docs/execution-plan.md")
        assertThat(InlineMarkdown.linkTarget("file:///cursor/stores/self/docs/notes.md")).isEqualTo("/cursor/stores/self/docs/notes.md")
        assertThat(InlineMarkdown.linkTarget("/cursor/stores/$store")).isNull()
        assertThat(InlineMarkdown.linkTarget("app/src/main/Markdown.kt")).isNull()
        assertThat(InlineMarkdown.linkTarget("/opt/cursor/artifacts/demo.png")).isNull()
    }

    @Test
    fun `the coordinator's message links every store reference in its prose, once each, in order`() {
        // The prose paragraphs of the fixture, as the block parser hands them to the inline pass.
        val prose = markdown.lines().filter { it.isNotBlank() && !it.startsWith("![") && !it.startsWith("<video") }
        val found = prose.flatMap { links(it) }
        assertThat(found).containsExactly(
            // [`path`](path): the label is the code span, the link the path.
            "/cursor/stores/$store/docs/execution-plan.md",
            // A code span that is a path.
            "/cursor/stores/$store/docs/readiness-triage.md",
            // A bare path, its sentence mark left behind.
            "/cursor/stores/$store/docs/project-ui-parity-spec.md",
        ).inOrder()
        val sentence = prose[1]
        val rendered = render(sentence)
        assertThat(rendered.text).endsWith("The full path, for the record: /cursor/stores/$store/docs/project-ui-parity-spec.md.")
        // The label of the first link is drawn as code, not doubled with the path.
        assertThat(rendered.text).contains("The spec it follows is  /cursor/stores/$store/docs/execution-plan.md , and the readiness notes")
    }

    @Test
    fun `a store code span reads as code and opens as a link only when there is a hand to open it`() {
        val span = "see `/cursor/stores/$store/docs/readiness-triage.md` first"
        assertThat(links(span)).containsExactly("/cursor/stores/$store/docs/readiness-triage.md")
        val plain = render(span, onLinkClick = null)
        assertThat(plain.getLinkAnnotations(0, plain.length)).isEmpty()
        assertThat(plain.text).isEqualTo("see  /cursor/stores/$store/docs/readiness-triage.md  first")
        // A code span that is not a path stays plain code.
        assertThat(links("run `./gradlew test` now")).isEmpty()
    }

    @Test
    fun `a bare store path in prose is a link like a bare URL, and stops where the sentence does`() {
        assertThat(links("Read /cursor/stores/self/docs/notes.md, then /cursor/stores/$store/media/a.png.")).containsExactly(
            "/cursor/stores/self/docs/notes.md",
            "/cursor/stores/$store/media/a.png",
        ).inOrder()
        // Inside a link's label nothing is re-linked; a mount alone is prose.
        assertThat(links("[go](https://cursor.com/agents/bc-1) /cursor/stores/nothing")).containsExactly("https://cursor.com/agents/bc-1")
    }
}
