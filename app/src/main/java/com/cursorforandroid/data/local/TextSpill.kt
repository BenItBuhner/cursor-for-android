package com.cursorforandroid.data.local

import androidx.annotation.VisibleForTesting
import com.cursorforandroid.util.toHex
import java.io.File
import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Long texts a transcript carries but seldom shows — the file a tool call read, the diff of an edit, tens of thousands
 * of characters — kept on disk rather than on the heap for as long as something holds them (see `SpillText`). A chat
 * scrolled far back holds hundreds of turns; their texts are read back only when a row is opened onto one.
 *
 * Files are named by their text's digest, so the same text is written once and a file never changes. A file stays for
 * as long as a holder of it is reachable, and goes once the last one is collected: the budget is what is held now,
 * not what was ever written. It used to be the latter, and nothing was given back — eight hours of thirty agents
 * working spent the 64 MB, and every text after that stayed on the heap until it was full (Bennett's 0.4.26 crash).
 * Each process writes under a directory of its own and clears the ones earlier processes left; the sign-out wipe of
 * the caches' root takes the lot, and a write that outlives the wipe is refused.
 *
 * Nothing is spilled until [install] names a place; texts then stay inline, as they do past [MAX_BYTES] held on disk,
 * past [FLOOR_BYTES] on a device short of room (see [MIN_FREE_BYTES]), or when a write fails.
 */
object TextSpill {
    /** Shorter texts stay on the heap: a file costs more than they do. */
    const val MIN_CHARS = 4_096
    /** Held on disk however little room it has left. */
    const val FLOOR_BYTES = 64L shl 20
    /**
     * The most held on disk at once: twice the largest heap the app is given, so the disk's budget is never what
     * sends texts back onto the heap before the heap itself would be full. The 64 MB it once was ran out first:
     * twenty held chats' ten-turn windows of files and diffs hold more than that between them.
     */
    const val MAX_BYTES = 1L shl 30
    /** Past [FLOOR_BYTES], the room the device is left for everything else. */
    const val MIN_FREE_BYTES = 512L shl 20
    /** The texts read back most recently, kept for a row recomposed or reopened. */
    private const val RECENT_CHARS = 256 * 1024

    private class Place(val cache: JsonDiskCache, val dir: File)

    /** A text on disk and how many holders of it are still reachable. */
    private class Held(val file: File, val size: Long) {
        var holders = 0
    }

    /** One holder of a spilled text, heard of once it can no longer be reached. */
    private class Holder(owner: Any, val key: String, val place: Place, queue: ReferenceQueue<Any>) : PhantomReference<Any>(owner, queue)

    @Volatile private var place: Place? = null
    /** Texts on disk by digest; guards [bytes] too. */
    private val held = HashMap<String, Held>()
    private var bytes = 0L
    private val released = ReferenceQueue<Any>()
    /** Keeps each [Holder] reachable itself until it is enqueued: a reference collected first is never heard of. */
    private val holders: MutableSet<Holder> = Collections.newSetFromMap(ConcurrentHashMap())
    private val recent = LinkedHashMap<String, String>(16, 0.75f, true)
    private var recentChars = 0

    /** What may be held on disk at once; [MAX_BYTES] but for tests, which spend it faster. */
    @VisibleForTesting
    @Volatile
    internal var maxBytes = MAX_BYTES

    /** Spills under [cache] from here on; null stops spilling (texts already on disk stay readable until the wipe). */
    fun install(cache: JsonDiskCache?) {
        if (cache == null) {
            place = null
            return
        }
        val session = "p${System.currentTimeMillis()}-${(Math.random() * Int.MAX_VALUE).toInt()}"
        val dir = File(cache.root, session)
        synchronized(held) {
            place = Place(cache, dir)
            held.clear()
            bytes = 0L
        }
        holders.clear()
        synchronized(recent) { recent.clear(); recentChars = 0 }
        Thread({ cache.root.listFiles { f -> f.isDirectory && f.name != session }?.forEach { DiskSweep.deleteTree(it) } }, "text-spill-sweep")
            .apply { isDaemon = true }
            .start()
    }

    /**
     * Keeps [text] on disk and returns what [holder] makes of the file it reads back from (named by its digest), or
     * null when the text stays on the heap. The file stays for as long as that holder, or another of the same text,
     * can be reached.
     */
    fun <T : Any> put(text: String, holder: (File) -> T): T? {
        val at = place ?: return null
        if (text.length < MIN_CHARS) return null
        reclaim()
        val encoded = text.toByteArray(Charsets.UTF_8)
        val key = MessageDigest.getInstance("SHA-256").digest(encoded).toHex(KEY_BYTES)
        synchronized(held) {
            if (place !== at) return null
            val existing = held[key]
            if (existing == null && !fits(at, bytes + encoded.size)) return null
            // A text held already is one file; one the wipe took is written again, or kept on the heap if refused.
            if (existing?.file?.isFile != true && write(at, key, encoded) == null) return null
            val entry = existing ?: Held(File(at.dir, key), encoded.size.toLong()).also {
                held[key] = it
                bytes += it.size
            }
            entry.holders++
            val owner = holder(entry.file)
            holders += Holder(owner, key, at, released)
            return owner
        }
    }

    /** Whether [after] bytes may be held on disk. Under the lock of [held]. */
    private fun fits(at: Place, after: Long): Boolean {
        if (after > maxBytes) return false
        if (after <= FLOOR_BYTES) return true
        return at.cache.root.usableSpace - (after - bytes) >= MIN_FREE_BYTES
    }

    private fun write(at: Place, key: String, encoded: ByteArray): File? {
        val file = File(at.dir, key)
        val token = at.cache.token()
        val tmp = File(at.dir, "$key.tmp")
        return runCatching {
            at.dir.mkdirs()
            tmp.writeBytes(encoded)
            if (at.cache.isStale(token) || !tmp.renameTo(file)) throw java.io.IOException("not kept")
            file
        }.onFailure { tmp.delete() }.getOrNull()
    }

    /** Deletes the texts whose last holder has been collected, giving their bytes back to the budget. */
    private fun reclaim() {
        while (true) {
            val gone = released.poll() as? Holder ?: return
            holders -= gone
            val file = release(gone) ?: continue
            file.delete()
            synchronized(recent) { recent.remove(file.path)?.let { recentChars -= it.length } }
        }
    }

    /** One holder of [gone]'s text less; its file when that was the last. An earlier [install]'s is swept with its directory. */
    private fun release(gone: Holder): File? = synchronized(held) {
        if (gone.place !== place) return null
        val entry = held[gone.key] ?: return null
        if (--entry.holders > 0) return null
        held.remove(gone.key)
        bytes -= entry.size
        entry.file
    }

    /** Bytes held on disk right now; for tests. */
    @VisibleForTesting
    internal fun heldBytes(): Long {
        reclaim()
        return synchronized(held) { bytes }
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
