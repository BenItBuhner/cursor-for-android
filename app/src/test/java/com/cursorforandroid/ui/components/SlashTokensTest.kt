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
