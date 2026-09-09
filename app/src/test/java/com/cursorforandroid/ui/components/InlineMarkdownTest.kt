package com.cursorforandroid.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Covers the inline pass: what becomes a link, what stays text, and the delimiter edge cases agents write. */
class InlineMarkdownTest {

    private fun render(text: String, onLinkClick: ((String) -> Unit)? = null): AnnotatedString =
        InlineMarkdown.render(
            text = text,
            base = TextStyle(fontSize = 15.sp),
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
    fun `a bracket-heavy paragraph is scanned once, not once per bracket`() {
        // A pasted build log: thousands of "[" that are not links. `find` searched to the end of the text for each
        // of them and threw the result away, which is quadratic; `matchAt` looks only where the bracket is.
        val log = (1..12_000).joinToString("\n") { "[INFO] building module $it of 12000, nothing to do here" }
        val started = System.nanoTime()
        val rendered = render(log)
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertThat(rendered.text).isEqualTo(log)
        assertThat(rendered.getLinkAnnotations(0, log.length)).isEmpty()
        assertThat(elapsedMillis).isLessThan(2_000)
    }

    @Test
    fun `a double-backtick span shows the backtick inside it`() {
        // The single-backtick search closed on the opener's own second tick, emitting an empty chip and then the
        // content as prose with a stray delimiter behind it.
        assertThat(render("``a ` b``").text).isEqualTo(" a ` b ")
        assertThat(render("use ``code`` here").text).isEqualTo("use  code  here")
    }

    @Test
    fun `a single-backtick span is unchanged`() {
        assertThat(render("call `render()` twice").text).isEqualTo("call  render()  twice")
        assertThat(render("an unclosed ` tick").text).isEqualTo("an unclosed ` tick")
    }

    @Test
    fun `multiplication and globs are not italics`() {
        assertThat(render("2 * 3 * 4").text).isEqualTo("2 * 3 * 4")
        assertThat(render("2 * 3 * 4")).isEqualTo(AnnotatedString("2 * 3 * 4"))
        assertThat(render("run a * b then c").text).isEqualTo("run a * b then c")
    }

    @Test
    fun `emphasis that hugs its text still renders`() {
        val em = render("this is *important* today")
        assertThat(em.text).isEqualTo("this is important today")
        assertThat(em.spanStyles.single().let { it.start to it.end }).isEqualTo(8 to 17)
        assertThat(render("an _underscored_ word").text).isEqualTo("an underscored word")
    }

    @Test
    fun `an identifier with underscores is left alone`() {
        assertThat(render("snake_case_name and __init__")).isEqualTo(AnnotatedString("snake_case_name and __init__"))
    }

    @Test
    fun `an unclosed delimiter is scanned once, not once per delimiter`() {
        // Every "*x" is a candidate opener with no closer anywhere; searching to the end for each is quadratic.
        val stars = (1..12_000).joinToString(" ") { "*item$it" }
        val started = System.nanoTime()
        val rendered = render(stars)
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertThat(rendered.text).isEqualTo(stars)
        assertThat(elapsedMillis).isLessThan(2_000)
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
