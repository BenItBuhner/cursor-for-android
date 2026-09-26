package com.cursorforandroid.data.local

import com.cursorforandroid.util.toHex
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * Long texts a transcript carries but seldom shows — the file a tool call read, tens of thousands of characters —
 * kept on disk rather than on the heap for as long as the chat holding them is open (see `SpillText`). A chat
 * scrolled far back holds hundreds of turns; their files are read back only when a row is opened onto one.
 *
 * Files are named by their text's digest, so the same text is written once and a file never changes. Nothing is
 * evicted while the process lives: a text on disk is still referenced by some payload, and dropping it would empty
 * that row. Each process writes under a directory of its own and clears the ones earlier processes left; the
 * sign-out wipe of the caches' root takes the lot, and a write that outlives the wipe is refused.
 *
 * Nothing is spilled until [install] names a place; texts then stay inline, as they do past [MAX_BYTES] on disk
 * or when a write fails.
 */
object TextSpill {
    /** Shorter texts stay on the heap: a file costs more than they do. */
    const val MIN_CHARS = 4_096
    const val MAX_BYTES = 64L shl 20
    /** The texts read back most recently, kept for a row recomposed or reopened. */
    private const val RECENT_CHARS = 256 * 1024

    private class Place(val cache: JsonDiskCache, val dir: File)

    @Volatile private var place: Place? = null
    private val bytes = AtomicLong()
    private val recent = LinkedHashMap<String, String>(16, 0.75f, true)
    private var recentChars = 0

    /** Spills under [cache] from here on; null stops spilling (texts already on disk stay readable until the wipe). */
    fun install(cache: JsonDiskCache?) {
        if (cache == null) {
            place = null
            return
        }
        val session = "p${System.currentTimeMillis()}-${(Math.random() * Int.MAX_VALUE).toInt()}"
        val dir = File(cache.root, session)
        place = Place(cache, dir)
        bytes.set(0)
        synchronized(recent) { recent.clear(); recentChars = 0 }
        Thread({ cache.root.listFiles { f -> f.isDirectory && f.name != session }?.forEach { DiskSweep.deleteTree(it) } }, "text-spill-sweep")
            .apply { isDaemon = true }
            .start()
    }

    /** Keeps [text] on disk; the file it reads back from (named by its digest), or null when it stays on the heap. */
    fun put(text: String): File? {
        val at = place ?: return null
        if (text.length < MIN_CHARS) return null
        val encoded = text.toByteArray(Charsets.UTF_8)
        if (bytes.get() + encoded.size > MAX_BYTES) return null
        val key = MessageDigest.getInstance("SHA-256").digest(encoded).toHex(KEY_BYTES)
        val file = File(at.dir, key)
        if (file.isFile) return file
        val token = at.cache.token()
        val tmp = File(at.dir, "$key.tmp")
        return runCatching {
            at.dir.mkdirs()
            tmp.writeBytes(encoded)
            if (at.cache.isStale(token) || !tmp.renameTo(file)) throw java.io.IOException("not kept")
            bytes.addAndGet(encoded.size.toLong())
            file
        }.onFailure { tmp.delete() }.getOrNull()
    }

    /** The text [put] kept in [file]; null when it is gone (the caches were wiped since). */
    fun read(file: File): String? {
        val key = file.path
        synchronized(recent) { recent[key]?.let { return it } }
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        synchronized(recent) {
            recent.put(key, text)?.let { recentChars -= it.length }
            recentChars += text.length
            val oldest = recent.entries.iterator()
            while (recentChars > RECENT_CHARS && recent.size > 1 && oldest.hasNext()) {
                recentChars -= oldest.next().value.length
                oldest.remove()
            }
        }
        return text
    }

    /** 128 bits: a collision would show one file's text in another's row. */
    private const val KEY_BYTES = 16
}
