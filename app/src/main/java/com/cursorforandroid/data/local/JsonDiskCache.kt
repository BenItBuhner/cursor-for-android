package com.cursorforandroid.data.local

import com.cursorforandroid.data.api.CursorJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * JSON-on-disk cache with one file per key. Writes go to a sibling temp file that is then moved into place
 * atomically, so a crash mid-write never leaves a truncated entry behind; anything that fails to parse (or was
 * written by an older schema [version]) reads as a miss and is deleted. Pure JVM, so it is unit-tested without
 * Robolectric. Readers of one key never see a writer of the same key half-way through.
 */
class JsonDiskCache(
    private val directory: File,
    private val json: Json = CursorJson,
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Shared with the children of this cache, so one wipe invalidates the writes of all of them. */
    private val epoch: CacheEpoch = CacheEpoch(),
) {
    @Serializable
    private class Envelope<T>(val version: Int, val savedAtMillis: Long, val value: T)

    /** A cache hit: the stored value and when it was written (epoch millis). */
    class Entry<T>(val value: T, val savedAtMillis: Long)

    private val locks = ConcurrentHashMap<String, Mutex>()

    /** A cache rooted at a sub-directory; each domain (agents, conversations, catalog) gets its own. */
    fun child(name: String): JsonDiskCache = JsonDiskCache(File(directory, name), json, nowProvider, dispatcher, epoch)

    suspend fun <T> read(key: String, serializer: KSerializer<T>, version: Int): Entry<T>? = withContext(dispatcher) {
        lockFor(key).withLock {
            val file = fileFor(key)
            if (!file.isFile) return@withLock null
            runCatching {
                val envelope = json.decodeFromString(Envelope.serializer(serializer), file.readText())
                if (envelope.version == version) Entry(envelope.value, envelope.savedAtMillis) else null
            }.getOrNull().also { if (it == null) file.delete() }
        }
    }

    /**
     * The generation the cache is on. Work that will write later takes one of these when it starts and hands it to
     * [write]: sampling it inside the write instead would accept an entry from an account that was signed out while
     * the work was suspended, since by then the wipe has finished and the new generation looks current.
     */
    fun token(): Int = epoch.current()

    suspend fun <T> write(key: String, serializer: KSerializer<T>, version: Int, value: T, token: Int = epoch.current()): Boolean = withContext(dispatcher) {
        if (epoch.isStale(token)) return@withContext false
        lockFor(key).withLock {
            val file = fileFor(key)
            val tmp = File(directory, file.name + ".tmp")
            runCatching {
                directory.mkdirs()
                tmp.writeText(json.encodeToString(Envelope.serializer(serializer), Envelope(version, nowProvider(), value)))
                // Blocking file IO is not a cancellation point, so a writer cancelled by a sign-out can still get
                // this far. Checking here is what keeps it from putting the signed-out account back on disk.
                if (epoch.isStale(token)) throw IOException("The cache was wiped while this entry was being written")
                moveIntoPlace(tmp, file)
            }.onFailure { tmp.delete() }.isSuccess
        }
    }

    suspend fun remove(key: String) = withContext(dispatcher) {
        lockFor(key).withLock { fileFor(key).delete() }
    }

    /**
     * Refuses every write from here until [clear] runs, and invalidates the ones already under way. Called at the
     * start of a sign-out so the work being cancelled cannot land after the wipe.
     */
    fun invalidate() = epoch.beginWipe()

    /** Deletes every entry of this cache (and of its children), and lets writes through again afterwards. */
    suspend fun clear() = withContext(dispatcher) {
        epoch.beginWipe()
        directory.deleteRecursively()
        epoch.endWipe()
    }

    /** Keys currently stored, most recently written first. */
    suspend fun keys(): List<String> = withContext(dispatcher) {
        entryFiles().sortedByDescending { it.lastModified() }.map { it.name.removeSuffix(SUFFIX) }
    }

    /** Keeps the cache bounded: deletes the least recently written entries beyond [maxEntries]. */
    suspend fun prune(maxEntries: Int) = withContext(dispatcher) {
        entryFiles().sortedByDescending { it.lastModified() }.drop(maxEntries).forEach { it.delete() }
        // A temp file a process kill left behind is named after no key, so nothing else ever deletes it. Compared
        // against the wall clock because that is what the file's own timestamp comes from.
        val staleBefore = System.currentTimeMillis() - STALE_TMP_MS
        tempFiles().forEach { if (it.lastModified() < staleBefore) it.delete() }
    }

    /** rename(2) replaces the target atomically on Linux; the fallback covers file systems where it does not. */
    private fun moveIntoPlace(tmp: File, file: File) {
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: IOException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun entryFiles(): List<File> = directory.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }?.toList() ?: emptyList()

    private fun tempFiles(): List<File> = directory.listFiles { f -> f.isFile && f.name.endsWith(TMP_SUFFIX) }?.toList() ?: emptyList()

    private fun lockFor(key: String): Mutex = locks.getOrPut(key) { Mutex() }

    private fun fileFor(key: String): File = File(directory, sanitize(key) + SUFFIX)

    private companion object {
        const val SUFFIX = ".json"
        const val TMP_SUFFIX = ".tmp"
        /** Long enough that no live write is ever mistaken for an abandoned one. */
        const val STALE_TMP_MS = 60_000L
        val UNSAFE = Regex("[^A-Za-z0-9._-]")
        fun sanitize(key: String): String = key.replace(UNSAFE, "_").take(120).ifEmpty { "_" }
    }
}

/**
 * The generation of one cache tree, shared by a root cache and its children. A wipe bumps it and holds writes off
 * until it is done, so work that a sign-out cancelled — blocking file IO cannot be interrupted mid-write — cannot
 * put the signed-out account's data back on disk behind the wipe.
 */
class CacheEpoch {
    private val generation = AtomicInteger(0)

    @Volatile
    private var wiping = false

    fun current(): Int = generation.get()

    fun isStale(generation: Int): Boolean = wiping || generation != this.generation.get()

    fun beginWipe() {
        generation.incrementAndGet()
        wiping = true
    }

    fun endWipe() {
        wiping = false
    }
}
