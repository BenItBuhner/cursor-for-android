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
) {
    @Serializable
    private class Envelope<T>(val version: Int, val savedAtMillis: Long, val value: T)

    /** A cache hit: the stored value and when it was written (epoch millis). */
    class Entry<T>(val value: T, val savedAtMillis: Long)

    private val locks = ConcurrentHashMap<String, Mutex>()

    /** A cache rooted at a sub-directory; each domain (agents, conversations, catalog) gets its own. */
    fun child(name: String): JsonDiskCache = JsonDiskCache(File(directory, name), json, nowProvider, dispatcher)

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

    suspend fun <T> write(key: String, serializer: KSerializer<T>, version: Int, value: T): Boolean = withContext(dispatcher) {
        lockFor(key).withLock {
            val file = fileFor(key)
            val tmp = File(directory, file.name + ".tmp")
            runCatching {
                directory.mkdirs()
                tmp.writeText(json.encodeToString(Envelope.serializer(serializer), Envelope(version, nowProvider(), value)))
                moveIntoPlace(tmp, file)
            }.onFailure { tmp.delete() }.isSuccess
        }
    }

    suspend fun remove(key: String) = withContext(dispatcher) {
        lockFor(key).withLock { fileFor(key).delete() }
    }

    /** Deletes every entry of this cache (and of its children). */
    suspend fun clear() = withContext(dispatcher) {
        directory.deleteRecursively()
        Unit
    }

    /** Keys currently stored, most recently written first. */
    suspend fun keys(): List<String> = withContext(dispatcher) {
        entryFiles().sortedByDescending { it.lastModified() }.map { it.name.removeSuffix(SUFFIX) }
    }

    /** Keeps the cache bounded: deletes the least recently written entries beyond [maxEntries]. */
    suspend fun prune(maxEntries: Int) = withContext(dispatcher) {
        entryFiles().sortedByDescending { it.lastModified() }.drop(maxEntries).forEach { it.delete() }
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

    private fun lockFor(key: String): Mutex = locks.getOrPut(key) { Mutex() }

    private fun fileFor(key: String): File = File(directory, sanitize(key) + SUFFIX)

    private companion object {
        const val SUFFIX = ".json"
        val UNSAFE = Regex("[^A-Za-z0-9._-]")
        fun sanitize(key: String): String = key.replace(UNSAFE, "_").take(120).ifEmpty { "_" }
    }
}
