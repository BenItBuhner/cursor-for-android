package com.cursorforandroid.data.local

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DiskSweepTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun File.file(name: String, bytes: Int, modifiedAt: Long): File =
        File(this, name).apply { writeBytes(ByteArray(bytes)); setLastModified(modifiedAt) }

    @Test
    fun `files older than the cutoff go, newer ones, skipped ones and directories stay`() {
        val dir = folder.newFolder("copies")
        dir.file("old", 10, 1_000L)
        dir.file("old.part", 10, 1_000L)
        dir.file("new", 10, 5_000L)
        File(dir, "sub").apply { mkdirs(); setLastModified(1_000L) }

        val deleted = DiskSweep.deleteOlderThan(dir, cutoffMillis = 2_000L, skip = { it.name.endsWith(".part") })

        assertThat(deleted).isEqualTo(1)
        assertThat(dir.list()!!.toList()).containsExactly("old.part", "new", "sub")
    }

    @Test
    fun `trimming deletes the least recently modified until the rest fit, never a kept one`() {
        val dir = folder.newFolder("trim")
        dir.file("a", 100, 1_000L)
        dir.file("b", 100, 2_000L)
        dir.file("c", 100, 3_000L)
        dir.file("d", 100, 4_000L)
        dir.file("x.part", 1_000, 500L)

        val left = DiskSweep.trimToBytes(dir, maxBytes = 200, keep = setOf("a"), skip = { it.name.endsWith(".part") })

        assertThat(left).isEqualTo(200L)
        assertThat(dir.list()!!.toList()).containsExactly("a", "d", "x.part")
    }

    @Test
    fun `a file still in use counts against the bound but is never what goes`() {
        val dir = folder.newFolder("in-use")
        dir.file("a", 100, 1_000L)
        dir.file("b", 100, 2_000L)
        dir.file("shown", 150, 9_000L)

        val left = DiskSweep.trimToBytes(dir, maxBytes = 200, keepAfterMillis = 5_000L)

        assertThat(left).isEqualTo(150L)
        assertThat(dir.list()!!.toList()).containsExactly("shown")
    }

    @Test
    fun `a directory within its bound is left alone, and one that is missing weighs nothing`() {
        val dir = folder.newFolder("fits")
        dir.file("a", 100, 1_000L)

        assertThat(DiskSweep.trimToBytes(dir, maxBytes = 1_000)).isEqualTo(100L)
        assertThat(dir.list()!!.toList()).containsExactly("a")
        assertThat(DiskSweep.trimToBytes(File(dir, "missing"), maxBytes = 0)).isEqualTo(0L)
        assertThat(DiskSweep.bytesUnder(dir)).isEqualTo(100L)
    }
}
