package com.cursorforandroid.ui.shortcuts

import com.cursorforandroid.domain.PaletteEntry
import com.cursorforandroid.domain.PaletteResult
import com.cursorforandroid.domain.TranscriptHit
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PaletteSelectionTest {

    private fun row(id: String, hit: TranscriptHit? = null) = PaletteResult(PaletteEntry(id, "Chat $id", null, isProject = false, updatedAtMillis = 0L), hit = hit)

    private val abc = listOf(row("a"), row("b"), row("c"))

    @Test
    fun `rows found again for the same query keep the highlight on its chat, wherever it went`() {
        val on = PaletteSelection("atlas", abc, index = 1)
        val next = on.next("atlas", listOf(row("n"), row("a"), row("c"), row("b")))
        assertThat(next.index).isEqualTo(3)
        assertThat(next.current?.entry?.agentId).isEqualTo("b")
    }

    @Test
    fun `the highlighted chat's newer transcript hit is the one Enter opens`() {
        val on = PaletteSelection("atlas", listOf(row("a"), row("b", TranscriptHit("i1", "atlas", 1))), index = 1)
        val newer = TranscriptHit("i2", "atlas", 2)
        assertThat(on.next("atlas", listOf(row("a"), row("x"), row("b", newer))).current?.hit).isEqualTo(newer)
    }

    @Test
    fun `a chat no longer found leaves the highlight in place, on the last row at most`() {
        assertThat(PaletteSelection("atlas", abc, index = 1).next("atlas", listOf(row("a"), row("c"))).index).isEqualTo(1)
        assertThat(PaletteSelection("atlas", abc, index = 2).next("atlas", listOf(row("a"), row("b"))).index).isEqualTo(1)
        assertThat(PaletteSelection("atlas", abc, index = 2).next("atlas", emptyList()).index).isEqualTo(0)
    }

    @Test
    fun `a new query starts from the top`() {
        assertThat(PaletteSelection("atlas", abc, index = 2).next("atlas b", abc).index).isEqualTo(0)
    }

    @Test
    fun `moving stops at either end`() {
        val on = PaletteSelection("atlas", abc)
        assertThat(on.moved(-1).index).isEqualTo(0)
        assertThat(on.moved(1).moved(1).moved(1).index).isEqualTo(2)
        assertThat(PaletteSelection("atlas", emptyList()).moved(1).index).isEqualTo(0)
    }
}
