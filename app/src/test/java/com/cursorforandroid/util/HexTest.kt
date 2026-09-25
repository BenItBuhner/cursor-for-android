package com.cursorforandroid.util

import com.cursorforandroid.data.repo.parseIsoMillis
import com.cursorforandroid.domain.CoordinatorTranscript
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.security.MessageDigest
import kotlin.random.Random

/** The allocation-free rewrites of hot helpers answer exactly what the code they replaced did. */
class HexTest {

    @Test
    fun `hex is what formatting each byte gave`() {
        val random = Random(7)
        repeat(200) {
            val bytes = random.nextBytes(random.nextInt(0, 64))
            assertThat(bytes.toHex()).isEqualTo(bytes.joinToString("") { "%02x".format(it) })
            assertThat(bytes.toHex(8)).isEqualTo(bytes.take(8).joinToString("") { "%02x".format(it) })
        }
        val digest = MessageDigest.getInstance("SHA-1").digest("bc-held-1".toByteArray())
        assertThat(digest.toHex()).hasLength(40)
    }

    @Test
    fun `normalize is what trimming and collapsing whitespace with a regex gave`() {
        val regex = Regex("\\s+")
        val samples = listOf(
            "", " ", "plain", "  lead", "trail  ", " both ", "a  b", "a\tb", "a\n\n b", "a \u00A0 b", "\u00A0nbsp\u00A0",
            "tab\t", "\r\nwin\r\nlines\r\n", "one two three", "x\u000By\u000Cz", "a \u2003 b", " \u2003lead", "é  ü",
        )
        val random = Random(11)
        val alphabet = "ab \t\n\r\u00A0\u2003\u000B\u000C."
        val generated = List(500) { String(CharArray(random.nextInt(0, 24)) { alphabet[random.nextInt(alphabet.length)] }) }
        for (text in samples + generated) {
            assertThat(CoordinatorTranscript.normalize(text)).isEqualTo(text.trim().replace(regex, " "))
        }
        val clean = "Already normal text."
        assertThat(CoordinatorTranscript.normalize(clean)).isSameInstanceAs(clean)
    }

    @Test
    fun `a timestamp read again answers the same, and an unreadable one its fallback each time`() {
        val iso = "2026-09-25T21:38:18.845Z"
        val first = parseIsoMillis(iso)
        assertThat(first).isEqualTo(java.time.Instant.parse(iso).toEpochMilli())
        assertThat(parseIsoMillis(iso)).isEqualTo(first)
        assertThat(parseIsoMillis("2026-09-25T21:38:18+02:00")).isEqualTo(java.time.OffsetDateTime.parse("2026-09-25T21:38:18+02:00").toInstant().toEpochMilli())
        assertThat(parseIsoMillis("not a time", fallback = 5L)).isEqualTo(5L)
        assertThat(parseIsoMillis("not a time", fallback = 9L)).isEqualTo(9L)
        assertThat(parseIsoMillis(null, fallback = 3L)).isEqualTo(3L)
    }
}
