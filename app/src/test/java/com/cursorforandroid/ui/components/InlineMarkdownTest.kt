package com.cursorforandroid.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextStyle
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Covers the inline pass: what becomes a link, what stays text, and the delimiter edge cases agents write. */
class InlineMarkdownTest {

    private fun render(text: String, onLinkClick: ((String) -> Unit)? = null): AnnotatedString =
        InlineMarkdown.render(
            text = text,
            base = TextStyle.Default,
            codeColor = Color.White,
            codeBackground = Color.DarkGray,
            linkColor = Color.Blue,
            boldColor = Color.White,
            onLinkClick = onLinkClick,
        )

    private fun links(text: String): List<String> =
        render(text).getLinkAnnotations(0, text.length).mapNotNull { (it.item as? LinkAnnotation.Url)?.url }

    @Test
    fun `an absolute link becomes a link annotation carrying its target`() {
        assertThat(links("see [the PR](https://github.com/o/r/pull/14) please"))
            .containsExactly("https://github.com/o/r/pull/14")
        assertThat(render("see [the PR](https://github.com/o/r/pull/14) please").text).isEqualTo("see the PR please")
    }

    @Test
    fun `a repository-relative or scheme-less link renders as plain text, not a link`() {
        // An agent writing `[see the diff](./diff.patch)` used to produce a link whose tap threw from AndroidUriHandler.
        assertThat(links("[see the diff](./diff.patch)")).isEmpty()
        assertThat(render("[see the diff](./diff.patch)").text).isEqualTo("see the diff")
        assertThat(links("[Markdown.kt](app/src/main/java/Markdown.kt)")).isEmpty()
        assertThat(links("[home](/agents)")).isEmpty()
    }

    @Test
    fun `only schemes a phone can be asked for survive`() {
        assertThat(InlineMarkdown.linkTarget("https://cursor.com")).isEqualTo("https://cursor.com")
        assertThat(InlineMarkdown.linkTarget("HTTP://cursor.com")).isEqualTo("HTTP://cursor.com")
        assertThat(InlineMarkdown.linkTarget("mailto:hi@cursor.com")).isEqualTo("mailto:hi@cursor.com")
        assertThat(InlineMarkdown.linkTarget("tel:+15551234")).isEqualTo("tel:+15551234")
        assertThat(InlineMarkdown.linkTarget("//cdn.test/logo.png")).isEqualTo("https://cdn.test/logo.png")
        assertThat(InlineMarkdown.linkTarget("javascript:alert(1)")).isNull()
        assertThat(InlineMarkdown.linkTarget("file:///etc/passwd")).isNull()
        assertThat(InlineMarkdown.linkTarget("https:")).isNull()
        assertThat(InlineMarkdown.linkTarget("")).isNull()
    }

    @Test
    fun `a uri handler that cannot open the address does not throw out of the click`() {
        val handler = object : UriHandler {
            override fun openUri(uri: String): Unit = throw IllegalArgumentException("Can't open $uri.")
        }
        InlineMarkdown.opener(handler)("mailto:hi@cursor.com")
    }

    @Test
    fun `a tapped link is handed to the caller instead of Compose's uri handler`() {
        var opened: String? = null
        val annotated = render("[docs](https://cursor.com/docs)") { opened = it }
        val link = annotated.getLinkAnnotations(0, annotated.length).single().item as LinkAnnotation.Url
        link.linkInteractionListener!!.onClick(link)
        assertThat(opened).isEqualTo("https://cursor.com/docs")
    }
}
