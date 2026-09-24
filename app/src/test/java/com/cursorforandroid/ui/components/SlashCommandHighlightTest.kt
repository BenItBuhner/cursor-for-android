package com.cursorforandroid.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import com.cursorforandroid.domain.SlashCommands
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The `/command` highlight on a plain line of the reader's text: the queued cards and the account queue's editor. */
class SlashCommandHighlightTest {

    private val tints = CommandTints.forTheme(dark = true)
    /** The desktop's command chip, `--cursor-text-yellow-primary` in the dark glass theme. */
    private val yellow = Color(0xFFF1B467)

    @Test
    fun `the tokens are the composer's and only they are tinted`() {
        val text = "/review Ship the notes, then /subscribe to the checks"
        val annotated = highlightSlashCommands(text, tints)
        assertThat(annotated.text).isEqualTo(text)
        assertThat(annotated.spanStyles.map { text.substring(it.start, it.end) to it.item.color }).containsExactly("/review" to yellow, "/subscribe" to yellow).inOrder()
        assertThat(annotated.spanStyles.map { it.start..it.end - 1 }).isEqualTo(SlashCommands.tokenRanges(text))
    }

    @Test
    fun `a mode's own token takes its pill's tint and every other command the command yellow, in both themes`() {
        val text = "/multitask /plan /debug /ask then /review it"
        fun painted(dark: Boolean) = highlightSlashCommands(text, CommandTints.forTheme(dark)).let { a -> a.spanStyles.map { text.substring(it.start, it.end) to it.item.color } }
        assertThat(painted(dark = true)).containsExactly(
            "/multitask" to Color(0xFF9386F2),
            "/plan" to Color(0xFFF1B467),
            "/debug" to Color(0xFFFC6B83),
            "/ask" to Color(0xFF3FA266),
            "/review" to Color(0xFFF1B467),
        ).inOrder()
        assertThat(painted(dark = false)).containsExactly(
            "/multitask" to Color(0xFF7565CC),
            "/plan" to Color(0xFFA46701),
            "/debug" to Color(0xFFBE1744),
            "/ask" to Color(0xFF007041),
            "/review" to Color(0xFFA46701),
        ).inOrder()
        // Each is its pill's tint, whatever the palette ever says.
        for (pill in ModePills.Pill.entries) {
            assertThat(CommandTints.forTheme(dark = true).forToken(pill.command)).isEqualTo(pillTint(pill, dark = true))
            assertThat(CommandTints.forTheme(dark = false).forToken(pill.command)).isEqualTo(pillTint(pill, dark = false))
        }
    }

    @Test
    fun `faded tints keep their colours at the text's strength`() {
        val faded = tints.faded(0.5f)
        assertThat(faded.forToken("review")).isEqualTo(yellow.copy(alpha = 0.5f))
        assertThat(faded.forToken("multitask")).isEqualTo(Color(0xFF9386F2).copy(alpha = 0.5f))
        assertThat(tints.faded(1f)).isSameInstanceAs(tints)
    }

    @Test
    fun `text without a command is left alone`() {
        for (text in listOf("a/b testing at path/to/file", "ship it", "", "/Goal is not lowercase", "/goal, with a comma")) {
            val annotated = highlightSlashCommands(text, tints)
            assertThat(annotated.text).isEqualTo(text)
            assertThat(annotated.spanStyles).isEmpty()
        }
    }

    @Test
    fun `the field transformation colours the tokens and moves no character`() {
        val transformed = SlashCommandVisualTransformation(tints).filter(AnnotatedString("fix it /review now"))
        assertThat(transformed.text.text).isEqualTo("fix it /review now")
        assertThat(transformed.offsetMapping).isSameInstanceAs(OffsetMapping.Identity)
        assertThat(transformed.text.spanStyles.single().let { transformed.text.text.substring(it.start, it.end) }).isEqualTo("/review")
        // The same tints make the same transformation, so a field keeps it across recompositions.
        assertThat(SlashCommandVisualTransformation(tints)).isEqualTo(SlashCommandVisualTransformation(CommandTints.forTheme(dark = true)))
    }
}
