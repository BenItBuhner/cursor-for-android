package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.AgentStoreApi
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.MediaRef
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Reads the files an agent's reply points into an Agent Store for (`/cursor/stores/<mount>/…`, see
 * [com.cursorforandroid.domain.StorePath]) through the account's store reads, Extended mode only: the store a
 * mount names is found once (`ListAgentStores`, by the owner's id as the store's source) and kept on disk; a
 * picture's bytes come through the presigned URL `PresignAgentStoreReads` hands out for a while (the URL cached
 * until shortly before it expires and asked for again when a fetch finds it dead) and are kept on disk, bounded,
 * so a figure once drawn is drawn again without the network; a recording is played from its URL; a document's text
 * comes through `ReadAgentStoreFile`, the read the panel's Context browser makes, and is kept on disk too.
 *
 * Without the account (default mode, the demo) every read says so, and the transcript points at the Project on
 * cursor.com instead.
 */
class StoreFileRepository(
    private val api: () -> AgentStoreApi?,
    private val capabilities: suspend () -> Capabilities,
    /** Which store each owner has, and the documents read; null keeps everything in memory. */
    private val cache: JsonDiskCache? = null,
    /** Where a picture's bytes are kept once fetched; null keeps none. */
    private val blobs: File? = null,
    /** Anonymous: a presigned URL rejects a request that also carries credentials. */
    private val http: OkHttpClient = OkHttpClient(),
    private val now: () -> Long = AppClock::now,
    private val maxBlobBytes: Long = MAX_BLOB_BYTES,
) {
    /** The store id of each owner, by the owner's id; a miss is remembered for a while too, so a missing store is not asked for on every figure. */
    private val stores = HashMap<String, Resolved>()
    private class Resolved(val storeId: String?, val atMillis: Long)

    private class SignedUrl(val url: String, val expiresAtMillis: Long)
    private val urls = object : LinkedHashMap<String, SignedUrl>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SignedUrl>) = size > MAX_URLS
    }
    private val locks = List(STRIPES) { Mutex() }
    private val blobLock = Mutex()

    @Serializable
    private data class CachedStoreId(val storeId: String?)

    @Serializable
    private data class CachedText(val text: String)

    /** Whether the account's store reads are on: Extended mode with the Projects capability, and a wired API. */
    suspend fun available(): Boolean = api() != null && capabilities().projects

    /** The store [ownerId]'s files live in, or null when the account lists none for it. */
    suspend fun storeId(ownerId: String): String? {
        synchronized(stores) { stores[ownerId] }?.takeIf { now() - it.atMillis < STORE_TTL_MS }?.let { return it.storeId }
        val store = api() ?: throw IOException(NOT_AVAILABLE)
        val disk = cache?.child(STORES)
        disk?.read(ownerId, CachedStoreId.serializer(), VERSION)?.let { entry ->
            // A store once found is a fact of the account: kept across launches; a miss is asked about again later.
            if (entry.value.storeId != null || now() - entry.savedAtMillis < STORE_TTL_MS) {
                synchronized(stores) { stores[ownerId] = Resolved(entry.value.storeId, now()) }
                return entry.value.storeId
            }
        }
        return locks[stripe(ownerId)].withLock {
            synchronized(stores) { stores[ownerId] }?.takeIf { now() - it.atMillis < STORE_TTL_MS }?.let { return@withLock it.storeId }
            val storeId = store.storeFor(ownerId)
            synchronized(stores) { stores[ownerId] = Resolved(storeId, now()) }
            disk?.write(ownerId, CachedStoreId.serializer(), VERSION, CachedStoreId(storeId))
            storeId
        }
    }

    /** A URL the bytes of [ref] can be fetched from, good for at least [MIN_REMAINING_MS] more. */
    suspend fun downloadUrl(ref: MediaRef.Store): String {
        if (!available()) throw IOException(NOT_AVAILABLE)
        val key = ref.cacheKey
        cachedUrl(key)?.let { return it }
        return locks[stripe(key)].withLock {
            cachedUrl(key)?.let { return@withLock it }
            val storeId = storeId(ref.ownerId) ?: throw IOException(NO_STORE)
            val store = api() ?: throw IOException(NOT_AVAILABLE)
            val signed = store.presignRead(ref.requesterId, storeId, ref.relativePath) ?: throw IOException(NO_FILE)
            val expiresAt = signed.expiresAtMillis?.takeIf { it > now() } ?: (now() + DEFAULT_TTL_MS)
            synchronized(urls) { urls[key] = SignedUrl(signed.url, expiresAt) }
            signed.url
        }
    }

    /** Forgets the URL of [ref]: a fetch found it dead before its time. */
    fun invalidate(ref: MediaRef.Store) {
        synchronized(urls) { urls.remove(ref.cacheKey) }
    }

    /**
     * The bytes of the file at [ref]: from the disk when they were fetched before, else through the presigned URL —
     * asked for afresh once when the one in hand turns out dead — and kept, within [maxBlobBytes] across all files,
     * the oldest going first.
     */
    suspend fun readBytes(ref: MediaRef.Store): ByteArray {
        val file = blobs?.let { File(it, blobName(ref)) }
        if (file != null) withContext(Dispatchers.IO) { file.takeIf { it.isFile }?.let { it.setLastModified(now()); it.readBytes() } }?.let { return it }
        var bytes = fetch(downloadUrl(ref))
        if (bytes == null) {
            invalidate(ref)
            bytes = fetch(downloadUrl(ref)) ?: throw IOException(NO_FILE)
        }
        if (file != null) keep(file, bytes)
        return bytes
    }

    /**
     * The text of the document at [ref]: from the disk when it was read before and [refresh] is off, else through
     * the account, and kept for the next time. A document is small; a store's whole tree is not what is kept.
     */
    suspend fun readText(ref: MediaRef.Store, refresh: Boolean = false): String {
        val disk = cache?.child(TEXTS)
        val key = textKey(ref)
        if (!refresh) disk?.read(key, CachedText.serializer(), VERSION)?.let { return it.value.text }
        if (!available()) throw IOException(NOT_AVAILABLE)
        val storeId = storeId(ref.ownerId) ?: throw IOException(NO_STORE)
        val store = api() ?: throw IOException(NOT_AVAILABLE)
        val text = try {
            store.readFile(storeId, ref.relativePath)
        } catch (e: CancellationException) {
            throw e
        }
        disk?.write(key, CachedText.serializer(), VERSION, CachedText(text))
        return text
    }

    /** The text kept for [ref], if any, without asking the account. */
    suspend fun cachedText(ref: MediaRef.Store): String? = cache?.child(TEXTS)?.read(textKey(ref), CachedText.serializer(), VERSION)?.value?.text

    /** On sign-out or a backend switch: nothing resolved for the previous account counts for the next; the files kept go too. */
    suspend fun resetAll() {
        synchronized(stores) { stores.clear() }
        synchronized(urls) { urls.clear() }
        blobs?.let { dir -> withContext(Dispatchers.IO) { dir.listFiles()?.forEach { it.delete() } } }
    }

    /** The bytes behind [url], or null when the URL is dead (rejected, expired or gone) rather than the network. */
    private suspend fun fetch(url: String): ByteArray? = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            when {
                response.isSuccessful -> response.body?.bytes() ?: ByteArray(0)
                response.code in STALE_URL_CODES -> null
                else -> throw IOException("The store answered ${response.code} for this file.")
            }
        }
    }

    private suspend fun keep(file: File, bytes: ByteArray) = blobLock.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.path + ".tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
                file.setLastModified(now())
                trim(file.parentFile ?: return@runCatching)
            }
        }
    }

    /** Drops the least recently used files until the directory is within [maxBlobBytes]. */
    private fun trim(dir: File) {
        val files = dir.listFiles { f -> f.isFile && !f.name.endsWith(".tmp") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxBlobBytes) break
            total -= f.length()
            f.delete()
        }
    }

    private fun cachedUrl(key: String): String? = synchronized(urls) {
        val entry = urls[key] ?: return null
        if (entry.expiresAtMillis - now() > MIN_REMAINING_MS) return entry.url
        urls.remove(key)
        null
    }

    private fun textKey(ref: MediaRef.Store) = JsonDiskCache.sanitize("${ref.ownerId}-${ref.relativePath.hashCode()}-${ref.label}")

    private fun blobName(ref: MediaRef.Store) = JsonDiskCache.sanitize("${ref.ownerId}-${ref.relativePath.hashCode()}-${ref.label}")

    private fun stripe(key: String) = (key.hashCode() and Int.MAX_VALUE) % STRIPES

    companion object {
        const val NOT_AVAILABLE = "The Project's context can be read with Extended mode on."
        const val NO_STORE = "The account lists no context store for this Project."
        const val NO_FILE = "The store has no such file."
        private const val VERSION = 1
        private const val STORES = "stores"
        private const val TEXTS = "texts"
        /** How long an answer about which store an owner has stands, in memory and on disk for a miss. */
        private const val STORE_TTL_MS = 6 * 60 * 60_000L
        private const val DEFAULT_TTL_MS = 15 * 60_000L
        private const val MIN_REMAINING_MS = 60_000L
        private const val MAX_URLS = 256
        private const val STRIPES = 8
        /** A few dozen screenshots of a Project's context; the oldest go when more arrive. */
        const val MAX_BLOB_BYTES = 64L * 1024 * 1024
        private val STALE_URL_CODES = setOf(400, 401, 403, 404, 410)
    }
}
