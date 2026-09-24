package com.cursorforandroid.ui.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SlashTokensTest {

    private fun at(text: String, cursor: Int) = SlashTokens.at(TextFieldValue(text, TextRange(cursor)))

    @Test
    fun `a slash under the cursor opens a token, at the start of the text or after whitespace`() {
        assertThat(at("/", 1)).isEqualTo(SlashToken(0, 1, ""))
        assertThat(at("/go", 3)).isEqualTo(SlashToken(0, 3, "go"))
        assertThat(at("fix the tests /rev", 18)).isEqualTo(SlashToken(14, 18, "rev"))
        assertThat(at("a\n/Loop", 7)).isEqualTo(SlashToken(2, 7, "loop"))
        // The cursor inside the word still names the whole word.
        assertThat(at("/review-bugbot now", 4)).isEqualTo(SlashToken(0, 14, "review-bugbot"))
    }

    @Test
    fun `no token before the slash, after the word, in a path, or for a word a name cannot be`() {
        assertThat(at("/goal", 0)).isNull()
        // Right after the completed command's space (the position a pick leaves the cursor in), and between spaces.
        assertThat(at("/goal ", 6)).isNull()
        assertThat(at("/goal  fix", 6)).isNull()
        assertThat(at("/goal fix", 6)).isNull()
        assertThat(at("/goal fix", 9)).isNull()
        assertThat(at("see src/main", 12)).isNull()
        assertThat(at("/2x!", 4)).isNull()
        assertThat(at("", 0)).isNull()
        // A selection is not a cursor.
        assertThat(SlashTokens.at(TextFieldValue("/go", TextRange(1, 3)))).isNull()
    }

    private fun phrase(text: String, cursor: Int = text.length) = SlashTokens.phraseAt(text, TextRange(cursor))

    @Test
    fun `a phrase a model's name is typed as runs from its slash to the end of the cursor's word`() {
        assertThat(phrase("/Opus 5")).isEqualTo(SlashToken(0, 7, "opus 5"))
        assertThat(phrase("/gpt-5.6")).isEqualTo(SlashToken(0, 8, "gpt-5.6"))
        assertThat(phrase("/opus 5.5 max ")).isEqualTo(SlashToken(0, 14, "opus 5.5 max "))
        assertThat(phrase("fix it /Grok 4.7", 13)).isEqualTo(SlashToken(7, 16, "grok 4.7"))
        assertThat(phrase("first line\n/sonnet 5")).isEqualTo(SlashToken(11, 20, "sonnet 5"))
    }

    @Test
    fun `no phrase across a line, after a path, for other characters, past a name's length, or for a selection`() {
        assertThat(phrase("/opus\n5")).isNull()
        assertThat(phrase("see src/main 5")).isNull()
        assertThat(phrase("/goal fix the (login)")).isNull()
        assertThat(phrase("/" + "a".repeat(49))).isNull()
        assertThat(phrase("/")).isNull()
        assertThat(phrase("/ opus")).isNull()
        assertThat(SlashTokens.phraseAt("/opus 5", TextRange(2, 7))).isNull()
    }

    @Test
    fun `completing replaces the token with the command and one space, and puts the cursor after it`() {
        val typed = TextFieldValue("/go", TextRange(3))
        assertThat(SlashTokens.complete(typed, SlashTokens.at(typed)!!, "goal")).isEqualTo(TextFieldValue("/goal ", TextRange(6)))

        val mid = TextFieldValue("/rev fix the tests", TextRange(4))
        assertThat(SlashTokens.complete(mid, SlashTokens.at(mid)!!, "review")).isEqualTo(TextFieldValue("/review fix the tests", TextRange(8)))

        val later = TextFieldValue("fix the tests /au", TextRange(17))
        assertThat(SlashTokens.complete(later, SlashTokens.at(later)!!, "autopilot")).isEqualTo(TextFieldValue("fix the tests /autopilot ", TextRange(25)))
    }
}
