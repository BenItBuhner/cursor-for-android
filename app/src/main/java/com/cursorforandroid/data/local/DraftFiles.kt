package com.cursorforandroid.data.local

import android.util.Log
import androidx.core.util.AtomicFile
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.util.AppClock
import java.io.File
import java.security.MessageDigest

/**
 * The disk discipline every unsent draft is kept under — the follow-up composers' ([FollowUpStore]) and the New Chat
 * composer's ([DraftStore]) — so that nothing typed is lost to anything short of sending it or clearing it:
 *
 *  - a record is replaced whole, and synced to the disk before it takes the old one's place ([write]): a kill in the
 *    middle of a write leaves the previous copy, and a power cut after it leaves the new one;
 *  - a record this build cannot read — damaged, or written by a build it does not understand — is set aside with
 *    everything filed beside it ([setAside]) instead of being written over by the next save;
 *  - signing out does not delete what an account left unsent: it is parked under the account ([park]) where no other
 *    account reads it, and handed back when the same account signs in again ([unpark]).
 */
internal object DraftFiles {

    private const val TAG = "DraftFiles"

    /** Where [setAside] files what could not be read, inside the store's own root so parking and clearing take it along. */
    const val UNREADABLE_DIR = ".unreadable"

    /** The parked drafts of every account that signed out, by [ownerKey]: `files/parked-drafts/<owner>/<store root>`. */
    const val PARKED_DIR = "parked-drafts"

    /** The owner of drafts that were written while no account could be named (a session restored offline with nothing cached). */
    const val UNATTRIBUTED = "unattributed"

    /** Replaces [target] with [text]: written beside it, synced, then renamed over it. */
    fun write(target: File, text: String) {
        target.parentFile?.mkdirs()
        val atomic = AtomicFile(target)
        val out = atomic.startWrite()
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
        } catch (t: Throwable) {
            atomic.failWrite(out)
            throw t
        }
        atomic.finishWrite(out)
    }

    /**
     * [target]'s text, or null when there is none. A write that never finished is not read: its previous copy is.
     * Read directly rather than through [AtomicFile.openRead], which deletes a write in progress beside the record.
     */
    fun read(target: File): String? {
        if (!target.isFile) return null
        return target.readText(Charsets.UTF_8)
    }

    /**
     * Moves [dir] — a record that could not be read and the files filed with it — into [root]'s [UNREADABLE_DIR], under
     * its own name and the time, and returns where it went. What cannot be moved is left where it is, and reported.
     */
    fun setAside(root: File, dir: File, reason: Throwable?): File? {
        val shelf = File(root, UNREADABLE_DIR)
        val destination = File(shelf, "${dir.name}-${AppClock.now()}")
        shelf.mkdirs()
        val moved = dir.renameTo(destination)
        Log.w(TAG, if (moved) "Unreadable draft kept at ${destination.path}" else "Unreadable draft at ${dir.path} could not be set aside", reason)
        return destination.takeIf { moved }
    }

    /**
     * The name an account's parked drafts are filed under: a digest of the account's id (or, failing one, its email),
     * so the directory names no one. Null when the account cannot be named.
     */
    fun ownerKey(user: CursorUser?): String? {
        val identity = user?.userId?.let { "user:$it" } ?: user?.email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { "email:$it" } ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    /**
     * A store's directory under `files/`, as parking moves it: [entryWise] when each entry in it is a record of its own
     * (a chat's follow-ups, one draft) and moves alone; otherwise the directory is one record and moves whole.
     */
    class Root(val name: String, val entryWise: Boolean)

    /**
     * Moves each of [roots] into [owner]'s parking place. Something already parked under the same name — left there by
     * an earlier hand-back that found its place taken — is kept beside what replaces it, under a superseded name.
     */
    fun park(filesDir: File, owner: String, roots: List<Root>) {
        val parked = File(File(filesDir, PARKED_DIR), owner)
        for (root in roots) move(from = File(filesDir, root.name), into = File(parked, root.name), root.entryWise, replace = true)
    }

    /**
     * Hands [owner]'s parked drafts back where they were. A record whose place the active store has taken meanwhile
     * stays parked rather than being written over.
     */
    fun unpark(filesDir: File, owner: String, roots: List<Root>) {
        val parked = File(File(filesDir, PARKED_DIR), owner)
        if (!parked.isDirectory) return
        for (root in roots) move(from = File(parked, root.name), into = File(filesDir, root.name), root.entryWise, replace = false)
        if (parked.list()?.isEmpty() == true) parked.delete()
    }

    private fun move(from: File, into: File, entryWise: Boolean, replace: Boolean) {
        if (!from.exists()) return
        if (!entryWise) {
            place(from, into, replace)
            return
        }
        val entries = from.listFiles() ?: return
        into.mkdirs()
        for (entry in entries) place(entry, File(into, entry.name), replace)
        if (from.list()?.isEmpty() == true) from.delete()
    }

    private fun place(from: File, to: File, replace: Boolean) {
        if (to.exists()) {
            if (!replace) return
            to.renameTo(File(to.parentFile, "${to.name}.superseded-${AppClock.now()}"))
        }
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) Log.w(TAG, "Draft ${from.path} could not be moved to ${to.path}")
    }
}
