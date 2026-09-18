package com.cursorforandroid.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

/** A finished message's blocks parsed once for as long as it stands, from a cache that never grows past its bounds. */
class MarkdownCacheTest {

    @Before
    fun clear() = MarkdownCache.clear()

    @Test
    fun `a message is parsed once and answered from the cache after, by its text`() {
        val text = "# Title\n\nA paragraph with `code` and **bold**.\n\n- one\n- two\n\n```kotlin\nval x = 1\n```"
        assertThat(MarkdownCache.cached(text)).isNull()
        val first = MarkdownCache.parse(text)
        assertThat(first).isEqualTo(MarkdownParser.parse(text))
        assertThat(MarkdownCache.cached(text)).isSameInstanceAs(first)
        // The same text in another instance — a rebuilt item — is the same entry.
        assertThat(MarkdownCache.parse(String(text.toCharArray()))).isSameInstanceAs(first)
        assertThat(MarkdownCache.size).isEqualTo(1)
    }

    @Test
    fun `priming parses ahead and a primed message costs nothing to draw`() {
        val text = "Primed **reply** with a [link](https://example.com)."
        MarkdownCache.prime(text)
        val cached = MarkdownCache.cached(text)
        assertThat(cached).isNotNull()
        assertThat(MarkdownCache.parse(text)).isSameInstanceAs(cached)
        // Blank text and oversized text are not held.
        MarkdownCache.prime("   ")
        MarkdownCache.prime("x".repeat(250_000))
        assertThat(MarkdownCache.size).isEqualTo(1)
    }

    @Test
    fun `the cache is bounded by entries and by characters, the least recently drawn let go first`() {
        repeat(2_500) { i -> MarkdownCache.parse("message $i with some **markdown** in it") }
        assertThat(MarkdownCache.size).isAtMost(2_048)
        assertThat(MarkdownCache.cached("message 0 with some **markdown** in it")).isNull()
        assertThat(MarkdownCache.cached("message 2499 with some **markdown** in it")).isNotNull()
        MarkdownCache.clear()
        // A hundred messages of 100 000 characters are more than the character budget: the oldest go.
        val big = (0 until 100).map { i -> "m$i " + "word ".repeat(20_000) }
        big.forEach { MarkdownCache.parse(it) }
        assertThat(MarkdownCache.cached(big.first())).isNull()
        assertThat(MarkdownCache.cached(big.last())).isNotNull()
        assertThat(MarkdownCache.size).isLessThan(100)
    }

    @Test
    fun `priming a message already held keeps it from being the next to go`() {
        val newest = "the newest **reply**"
        MarkdownCache.prime(newest)
        repeat(2_047) { i -> MarkdownCache.parse("older message $i") }
        // Touched again as the newest page's is on every presentation: it stays while the sweep evicts the rest.
        MarkdownCache.prime(newest)
        MarkdownCache.parse("one more")
        assertThat(MarkdownCache.cached(newest)).isNotNull()
        assertThat(MarkdownCache.cached("older message 0")).isNull()
    }
}
