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
    fun `files are ordered by the time each had when read, whatever a writer stamps meanwhile`() {
        val dir = folder.newFolder("stamped")
        val files = (0 until 3_000).map { i -> dir.file("f$i", 1, 1_000_000L + i * 1_000L) }
        val stamping = java.util.concurrent.atomic.AtomicBoolean(true)
        val writer = kotlin.concurrent.thread {
            val random = java.util.Random(7)
            while (stamping.get()) files[random.nextInt(files.size)].setLastModified(System.currentTimeMillis())
        }
        try {
            repeat(20) { assertThat(DiskSweep.byModified(dir.listFiles()!!, newestFirst = true)).hasSize(files.size) }
        } finally {
            stamping.set(false)
            writer.join()
        }
        files[5].setLastModified(1L)
        files[9].setLastModified(2L)
        assertThat(DiskSweep.byModified(dir.listFiles()!!).take(2)).containsExactly(files[5], files[9]).inOrder()
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

    /**
     * A tree whose sub-directories a writer deletes the instant a walk looks at one — the moment a follow-up save of an
     * empty draft removes its chat's directory while a clear walks the store (InheritedModelComposerTest's crash).
     */
    private fun vanishingTree(name: String): File {
        val root = folder.newFolder(name)
        for (chat in listOf("bc-1", "bc-2")) File(root, chat).apply { mkdirs() }.file("state.json", 10, 1_000L)
        return object : File(root.path) {
            override fun listFiles(): Array<File>? = super.listFiles()?.map { Vanishing(it.path) }?.toTypedArray()
        }
    }

    private class Vanishing(path: String) : File(path) {
        private fun vanish() = File(path).walkBottomUp().forEach { it.delete() }
        override fun isDirectory(): Boolean = super.isDirectory().also { if (it) vanish() }
        override fun listFiles(): Array<File>? = vanish().let { super.listFiles() }
    }

    @Test
    fun `a directory deleted while the tree is walked is gone, not a failure`() {
        val root = vanishingTree("walked")
        // What every store's clear called: the walk asserts a directory it has just seen is still one.
        val walked = runCatching { root.deleteRecursively() }
        assertThat(walked.exceptionOrNull()).isInstanceOf(AssertionError::class.java)
        assertThat(walked.exceptionOrNull()).hasMessageThat().contains("rootDir must be verified to be directory beforehand")

        val again = vanishingTree("swept")
        assertThat(DiskSweep.deleteTree(again)).isTrue()
        assertThat(again.exists()).isFalse()
    }

    @Test
    fun `files under a tree whose directories vanish mid-walk are listed without failing`() {
        val root = folder.newFolder("blobs")
        File(root, "kept").writeBytes(ByteArray(3))
        File(root, "chat").apply { mkdirs() }.file("blob", 5, 1_000L)
        val walked = object : File(root.path) {
            override fun listFiles(): Array<File>? = super.listFiles()?.map { if (it.isDirectory) Vanishing(it.path) else it }?.toTypedArray()
        }

        assertThat(runCatching { walked.walkTopDown().toList() }.exceptionOrNull()).isInstanceOf(AssertionError::class.java)
        assertThat(DiskSweep.filesUnder(walked).map { it.name }).containsExactly("kept")
    }

    @Test
    fun `deleteTree removes a whole tree and answers true for one already gone`() {
        val root = folder.newFolder("tree")
        File(root, "a/b").mkdirs()
        File(root, "a/b").file("c", 4, 1_000L)
        File(root, "d").writeBytes(ByteArray(2))

        assertThat(DiskSweep.deleteTree(root)).isTrue()
        assertThat(root.exists()).isFalse()
        assertThat(DiskSweep.deleteTree(root)).isTrue()
        assertThat(DiskSweep.filesUnder(root)).isEmpty()
    }
}
