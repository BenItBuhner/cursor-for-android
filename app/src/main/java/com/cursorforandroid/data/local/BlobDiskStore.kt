package com.cursorforandroid.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * The account records' blobs on disk (Extended mode, the Beta transcript engine): one file per blob under a directory
 * per chat, named by the blob's own id. Blobs are content-addressed — the same id is the same bytes, forever — so a
 * file is never rewritten or invalidated, only evicted: least recently used first once the store passes [maxBytes].
 * A reopen in a new process reads the turns it already had from here and asks the network only for blobs it has
 * never seen. Lives under the caches' root, so a sign-out's wipe takes it and a write that outlives the wipe is
 * refused (see [JsonDiskCache.token]).
 */
class BlobDiskStore(private val cache: JsonDiskCache, private val maxBytes: Long = MAX_BYTES) {
    private val dir: File get() = cache.root
    /** Bytes on disk, counted once on first use and kept as files come and go; -1 until counted. */
    private val bytes = AtomicLong(-1L)

    suspend fun read(agentId: String, blobId: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = file(agentId, blobId)
        if (!file.isFile) return@withContext null
        runCatching { file.readBytes() }.getOrNull()?.also { file.setLastModified(System.currentTimeMillis()) }
    }

    fun has(agentId: String, blobId: String): Boolean = file(agentId, blobId).isFile

    /** Every blob, gone: what the account service produced does not outlive Extended mode. */
    suspend fun clear() {
        cache.drop()
        bytes.set(0L)
    }

    /** Writes [value] as [blobId]'s blob unless it is on disk already, or the store was wiped since [token] was taken. */
    suspend fun write(agentId: String, blobId: String, value: ByteArray, token: Int = cache.token()) = withContext(Dispatchers.IO) {
        if (cache.isStale(token)) return@withContext
        val file = file(agentId, blobId)
        if (file.isFile) return@withContext
        val tmp = File(file.parentFile, file.name + ".tmp")
        val written = runCatching {
            file.parentFile?.mkdirs()
            tmp.writeBytes(value)
            if (cache.isStale(token)) throw java.io.IOException("The cache was wiped while this blob was being written")
            if (!tmp.renameTo(file)) throw java.io.IOException("rename failed")
        }.onFailure { tmp.delete() }.isSuccess
        if (written) {
            val total = counted() + value.size
            bytes.set(total)
            if (total > maxBytes) trim()
        }
    }

    /**
     * The ids of [agentId]'s blobs most recently read or written, newest first, at most [max]: what a state read
     * tells the server it need not prefetch again — the newest turns' blobs, which are the ones it prefetches.
     */
    suspend fun recentIds(agentId: String, max: Int): List<String> = withContext(Dispatchers.IO) {
        val files = chatDir(agentId).listFiles { f -> f.isFile && !f.name.endsWith(".tmp") } ?: return@withContext emptyList()
        files.sortedByDescending { it.lastModified() }.asSequence().mapNotNull { idOf(it.name) }.take(max).toList()
    }

    private fun counted(): Long {
        val known = bytes.get()
        if (known >= 0) return known
        val total = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        bytes.compareAndSet(-1L, total)
        return bytes.get()
    }

    /** Evicts the least recently used blobs, whichever chat they belong to, until the store is at three quarters of [maxBytes]. */
    private fun trim() {
        val files = dir.walkTopDown().filter { it.isFile }.sortedBy { it.lastModified() }.toList()
        var total = files.sumOf { it.length() }
        val target = maxBytes * 3 / 4
        for (file in files) {
            if (total <= target) break
            val size = file.length()
            if (file.delete()) total -= size
        }
        bytes.set(total)
    }

    private fun chatDir(agentId: String): File = File(dir, sha1(agentId).take(24))

    private fun file(agentId: String, blobId: String): File = File(chatDir(agentId), nameOf(blobId))

    companion object {
        /** Blobs on disk across every chat, all told. */
        const val MAX_BYTES = 96L * 1024 * 1024

        /** A file name for a blob id: its characters in hex, reversible whatever the id's alphabet; a hash for an id too long for a name. */
        internal fun nameOf(blobId: String): String {
            val bytes = blobId.toByteArray()
            return if (bytes.size <= 100) "x" + bytes.joinToString("") { "%02x".format(it) } else "h${sha1(blobId)}"
        }

        /** The id a file was named after, or null for one named by a hash. */
        internal fun idOf(name: String): String? {
            if (!name.startsWith("x") || name.length % 2 != 1) return null
            val hex = name.substring(1)
            return runCatching { String(ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }) }.getOrNull()
        }

        private fun sha1(text: String): String = MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
