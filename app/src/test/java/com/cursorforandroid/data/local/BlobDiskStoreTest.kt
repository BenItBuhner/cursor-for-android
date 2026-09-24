package com.cursorforandroid.data.local

import com.cursorforandroid.data.api.BlobCache
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random
import kotlin.concurrent.thread

/**
 * Bennett's 2026-09-24 frame: a Project of 1,444 turns whose every refresh ended "Couldn't refresh the transcript:
 * Comparison method violates its general contract!". The blob store sorted its files by asking each one its
 * modification time on every comparison, while the chat's own readers stamped the files they read — thousands of
 * blobs in the chat's directory, a few read every moment. Each time a stamp landed mid-sort TimSort threw, from the
 * state read's list of blobs held (so the refresh failed) and from the store's trim on a write (so the blob read that
 * wrote it failed). These run the store against a reader stamping its files the whole time, as the phone does.
 */
class BlobDiskStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val readers = ArrayList<Thread>()
    @Volatile private var reading = true

    @After
    fun tearDown() {
        reading = false
        readers.forEach { it.join() }
    }

    private fun store(maxBytes: Long = BlobDiskStore.MAX_BYTES) = folder.newFolder("blobs").let { root -> root to BlobDiskStore(JsonDiskCache(root, dispatcher = Dispatchers.IO), maxBytes) }

    /** Every blob file under [root], stamped as a reader stamps the one it read, one after another at random. */
    private fun readerStamping(root: File, seed: Long = 1L) {
        val files = root.walkTopDown().filter { it.isFile }.toList()
        readers += thread(isDaemon = true) {
            val random = Random(seed)
            while (reading) files[random.nextInt(files.size)].setLastModified(System.currentTimeMillis())
        }
    }

    private suspend fun BlobDiskStore.fill(agentId: String, count: Int, bytes: Int = 16) {
        repeat(count) { i -> write(agentId, "blob-%05d".format(i), ByteArray(bytes)) }
    }

    @Test
    fun `the blobs held are listed while a reader stamps them`() = runBlocking {
        val (root, store) = store()
        store.fill(CHAT, BLOBS)
        readerStamping(root)

        repeat(40) { assertThat(store.recentIds(CHAT, BlobCache.MAX_HELD_IDS)).hasSize(BlobCache.MAX_HELD_IDS) }
    }

    @Test
    fun `the blobs held are the most recently used, newest first`() = runBlocking {
        val (root, store) = store()
        store.fill(CHAT, 60)
        val files = root.walkTopDown().filter { it.isFile && it.name.startsWith("x") }.associateBy { BlobDiskStore.idOf(it.name)!! }
        (0 until 60).forEach { i -> files.getValue("blob-%05d".format(i)).setLastModified(1_000_000L + i * 1_000L) }
        files.getValue("blob-00007").setLastModified(9_000_000L)

        assertThat(store.recentIds(CHAT, 3)).containsExactly("blob-00007", "blob-00059", "blob-00058").inOrder()
    }

    @Test
    fun `writes past the budget trim the store while a reader stamps it, and none of them fails`() = runBlocking {
        val (root, store) = store(maxBytes = BLOBS * 256L)
        store.fill(OTHER, BLOBS, bytes = 256)
        readerStamping(root)

        // Eight reads in flight, as the blob lane allows, each writing what it fetched.
        (0 until 8).map { lane -> async(Dispatchers.IO) { repeat(BLOBS / 8) { i -> store.write(CHAT, "new-$lane-$i", ByteArray(256)) } } }.awaitAll()

        val onDisk = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertThat(onDisk).isAtMost(BLOBS * 256L)
    }

    @Test
    fun `a blob written through the cache is read back through it whatever the disk's trim meets`() = runBlocking {
        val (root, store) = store(maxBytes = BLOBS * 256L)
        store.fill(OTHER, BLOBS, bytes = 256)
        readerStamping(root)
        val cache = BlobCache(BlobCache.MEMORY_BLOBS_WITH_DISK, BlobCache.MEMORY_BYTES_WITH_DISK, disk = store)

        (0 until 8).map { lane -> async(Dispatchers.IO) { repeat(BLOBS / 8) { i -> cache.keep(CHAT, "kept-$lane-$i", ByteArray(256) { lane.toByte() }) } } }.awaitAll()

        assertThat(cache.read(CHAT, "kept-7-${BLOBS / 8 - 1}")!!.bytes.first()).isEqualTo(7.toByte())
    }

    private companion object {
        const val CHAT = "bc-coordinator-5cdc6a"
        const val OTHER = "bc-worker"
        /** About the prefetched blobs Bennett's Project had on the phone (`prefetched=4222`). */
        const val BLOBS = 4_200
    }
}
