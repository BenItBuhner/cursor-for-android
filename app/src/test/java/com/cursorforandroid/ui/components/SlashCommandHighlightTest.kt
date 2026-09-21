package com.cursorforandroid.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import com.cursorforandroid.domain.SlashCommands
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The `/command` highlight on a plain line of the reader's text: the queued cards and the account queue's editor. */
class SlashCommandHighlightTest {

    private val tint = Color(0xFFF1B467)

    @Test
    fun `the tokens are the composer's and only they are tinted`() {
        val text = "/review Ship the notes, then /subscribe to the checks"
        val annotated = highlightSlashCommands(text, tint)
        assertThat(annotated.text).isEqualTo(text)
        assertThat(annotated.spanStyles.map { text.substring(it.start, it.end) to it.item.color }).containsExactly("/review" to tint, "/subscribe" to tint).inOrder()
        assertThat(annotated.spanStyles.map { it.start..it.end - 1 }).isEqualTo(SlashCommands.tokenRanges(text))
    }

    @Test
    fun `text without a command is left alone`() {
        for (text in listOf("a/b testing at path/to/file", "ship it", "", "/Goal is not lowercase", "/goal, with a comma")) {
            val annotated = highlightSlashCommands(text, tint)
            assertThat(annotated.text).isEqualTo(text)
            assertThat(annotated.spanStyles).isEmpty()
        }
    }

    @Test
    fun `the field transformation colours the tokens and moves no character`() {
        val transformed = SlashCommandVisualTransformation(tint).filter(AnnotatedString("fix it /review now"))
        assertThat(transformed.text.text).isEqualTo("fix it /review now")
        assertThat(transformed.offsetMapping).isSameInstanceAs(OffsetMapping.Identity)
        assertThat(transformed.text.spanStyles.single().let { transformed.text.text.substring(it.start, it.end) }).isEqualTo("/review")
        // The same tint makes the same transformation, so a field keeps it across recompositions.
        assertThat(SlashCommandVisualTransformation(tint)).isEqualTo(SlashCommandVisualTransformation(tint))
    }
}
