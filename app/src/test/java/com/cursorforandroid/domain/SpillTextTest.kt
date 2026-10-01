package com.cursorforandroid.domain

import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.TextSpill
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The files a transcript's tool calls read, kept on disk while the chat is open (see [TextSpill]): what a row reads
 * back is what was read, the trace caches get the text itself, two payloads of the same file are the same payload,
 * and a wipe leaves an empty row rather than a crash.
 */
class SpillTextTest {

    @get:Rule val folder = TemporaryFolder()
    private lateinit var cache: JsonDiskCache

    @Before
    fun setUp() {
        cache = JsonDiskCache(folder.newFolder("spill"))
        TextSpill.install(cache)
    }

    @After
    fun tearDown() {
        TextSpill.maxBytes = TextSpill.MAX_BYTES
        TextSpill.install(null)
    }

    /** Collects until [done]: a text nothing holds is given back once the collector has found it so. */
    private fun collectUntil(done: () -> Boolean) {
        repeat(100) {
            if (done()) return
            System.gc()
            Thread.sleep(20)
        }
        assertThat(done()).isTrue()
    }

    /** A payload made and dropped here, so nothing on the caller's frame keeps it. */
    private fun readAndDrop(text: String): Int = read(text).contentLength

    private fun report(seed: Int) = (1..400).joinToString("\n") { "Line $it of report $seed — the checkout flow, compared." }

    private fun read(text: String) = ToolPayload.FileContent(path = "docs/report.md", content = text, kind = ToolPayload.FileContent.Kind.Read)

    private fun spilled() = cache.root.walkTopDown().filter { it.isFile }.toList()

    @Test
    fun `a long file is kept on disk and read back whole`() {
        val text = report(1)
        val payload = read(text)
        assertThat(spilled()).hasSize(1)
        assertThat(payload.content).isEqualTo(text)
        assertThat(payload.contentLength).isEqualTo(text.length)
        assertThat(payload.toString()).doesNotContain("Line 400")
    }

    @Test
    fun `a short one stays on the heap`() {
        val payload = read("fun main() = println(\"hi\")\n")
        assertThat(spilled()).isEmpty()
        assertThat(payload.content).isEqualTo("fun main() = println(\"hi\")\n")
    }

    @Test
    fun `the same text is one file and one payload, another text another`() {
        val first = read(report(1))
        val again = read(report(1))
        val other = read(report(2))
        assertThat(spilled()).hasSize(2)
        assertThat(again).isEqualTo(first)
        assertThat(again.hashCode()).isEqualTo(first.hashCode())
        assertThat(other).isNotEqualTo(first)
        TextSpill.install(null)
        assertThat(read(report(1))).isEqualTo(first)
    }

    @Test
    fun `the trace caches get the text itself, and read it back into a spilled payload`() {
        val payload: ToolPayload = read(report(3))
        val json = CursorJson.encodeToString(ToolPayload.serializer(), payload)
        assertThat(CursorJson.parseToJsonElement(json).jsonObject["content"]!!.jsonPrimitive.content).isEqualTo(report(3))
        val back = CursorJson.decodeFromString(ToolPayload.serializer(), json) as ToolPayload.FileContent
        assertThat(back).isEqualTo(payload)
        assertThat(back.content).isEqualTo(report(3))
        assertThat(spilled()).hasSize(1)
    }

    @Test
    fun `a text nothing holds any more gives its file and its bytes back`() {
        assertThat(readAndDrop(report(6))).isEqualTo(report(6).length)
        assertThat(spilled()).hasSize(1)
        collectUntil { TextSpill.heldBytes() == 0L }
        assertThat(spilled()).isEmpty()
    }

    @Test
    fun `a text still held keeps its file however much else is let go`() {
        val kept = read(report(7))
        repeat(5) { readAndDrop(report(100 + it)) }
        collectUntil { TextSpill.heldBytes() == report(7).toByteArray().size.toLong() }
        assertThat(spilled()).hasSize(1)
        assertThat(kept.content).isEqualTo(report(7))
        // The same text again is the same file, held twice: letting one go keeps it for the other.
        assertThat(readAndDrop(report(7))).isEqualTo(report(7).length)
        collectUntil { TextSpill.heldBytes() == report(7).toByteArray().size.toLong() }
        assertThat(kept.content).isEqualTo(report(7))
    }

    @Test
    fun `a session past the budget keeps spilling once what it spilled before is let go`() {
        // Room for two texts at a time; the session reads twenty, each dropped before the next, as turns come and go.
        TextSpill.maxBytes = report(299).toByteArray().size * 2L + 1_000
        repeat(20) { n ->
            val payload = read(report(200 + n))
            assertThat(payload.toString()).doesNotContain("Line 400")
            assertThat(payload.content).isEqualTo(report(200 + n))
            collectUntil { TextSpill.heldBytes() == report(200 + n).toByteArray().size.toLong() }
            assertThat(payload.content).isEqualTo(report(200 + n))
        }
        collectUntil { TextSpill.heldBytes() == 0L }
    }

    @Test
    fun `a sign-out's wipe leaves the row empty and refuses writes that outlive it`() = runBlocking {
        val payload = read(report(4))
        cache.clear()
        assertThat(payload.content).isEmpty()
        cache.invalidate()
        assertThat(read(report(5)).content).isEqualTo(report(5))
        assertThat(spilled()).isEmpty()
    }
}
