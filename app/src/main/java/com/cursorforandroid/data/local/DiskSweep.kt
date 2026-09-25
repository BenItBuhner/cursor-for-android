package com.cursorforandroid.data.local

import java.io.File

/**
 * Bounds for directories of throwaway copies nothing else evicts. The unit is a file directly in the directory and
 * its modification time is its recency, so a writer that reuses a copy touches it. Pure JVM.
 */
object DiskSweep {
    /** The bytes of [file], or of every file under it when it is a directory. */
    fun bytesUnder(file: File): Long = if (file.isDirectory) file.listFiles()?.sumOf(::bytesUnder) ?: 0L else file.length()

    /** Deletes the files directly in [dir] last modified before [cutoffMillis], apart from those [skip] names; answers how many went. */
    fun deleteOlderThan(dir: File, cutoffMillis: Long, skip: (File) -> Boolean = { false }): Int {
        var deleted = 0
        dir.listFiles()?.forEach { if (it.isFile && !skip(it) && it.lastModified() < cutoffMillis && it.delete()) deleted++ }
        return deleted
    }

    /**
     * Deletes the least recently modified files directly in [dir] until the ones left fit [maxBytes]. Files named in
     * [keep] (the one just written, say), those modified at or after [keepAfterMillis] (still in use) and those [skip]
     * names are never deleted; [skip]'s are not counted either. Answers the bytes left.
     */
    fun trimToBytes(
        dir: File,
        maxBytes: Long,
        keep: Set<String> = emptySet(),
        keepAfterMillis: Long = Long.MAX_VALUE,
        skip: (File) -> Boolean = { false },
    ): Long {
        val files = dir.listFiles { f -> f.isFile && !skip(f) }?.map { Sized(it, it.length(), it.lastModified()) } ?: return 0L
        var total = files.sumOf { it.bytes }
        if (total <= maxBytes) return total
        for (file in files.filter { it.file.name !in keep && it.modifiedAt < keepAfterMillis }.sortedBy { it.modifiedAt }) {
            if (total <= maxBytes) break
            if (file.file.delete()) total -= file.bytes
        }
        return total
    }

    /**
     * [files] by modification time, oldest first ([newestFirst]: newest first), each file's time read once. A sort
     * that asked the file on every comparison saw a file touched, written or deleted mid-sort change its key, which
     * TimSort refuses ("Comparison method violates its general contract!") — every reader of a blob touches it.
     */
    fun byModified(files: Sequence<File>, newestFirst: Boolean = false): List<File> {
        val stamped = files.map { it to it.lastModified() }.toMutableList()
        if (newestFirst) stamped.sortByDescending { it.second } else stamped.sortBy { it.second }
        return stamped.map { it.first }
    }

    fun byModified(files: Array<File>, newestFirst: Boolean = false): List<File> = byModified(files.asSequence(), newestFirst)

    private class Sized(val file: File, val bytes: Long, val modifiedAt: Long)
}
