package com.cursorforandroid.ui.media

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.cursorforandroid.data.media.MediaProblem
import com.cursorforandroid.data.media.MediaProblemException
import com.cursorforandroid.data.media.head
import com.cursorforandroid.domain.FileBytes
import com.cursorforandroid.domain.FileFormat
import com.cursorforandroid.domain.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Copies a whole file of this device into the gallery, under Pictures, Movies or Music in a folder of the app's name,
 * typed by what its bytes are rather than what its name says. Nothing half-written is ever seen there: through the
 * MediaStore (Android 10 on) the entry is pending until its last byte is in and is deleted if anything fails; before
 * that, the bytes go to a hidden file beside the final one, renamed into place whole and only then scanned.
 */
class GallerySaver(private val context: Context, private val sdk: Int = Build.VERSION.SDK_INT) {

    /** Where a saved file went: the folder the reader knows it by, for "Saved to Movies". */
    enum class Folder(val label: String) { Pictures("Pictures"), Movies("Movies"), Music("Music"), Downloads("Downloads") }

    /** What the gallery is told a file is: its name with the extension its format goes by, the format's type, and where it goes. */
    data class Target(val displayName: String, val mimeType: String, val folder: Folder)

    /** Before Android 10 the shared folders are written as files, which takes the storage permission; from 10 on the MediaStore needs none. */
    val needsPermission: Boolean
        get() = !scoped && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
    private val scoped: Boolean = sdk >= Build.VERSION_CODES.Q

    /** Saves [file], shown as [fileName] with the declared [mimeType] (either may be wrong); [onProgress] hears the share of it copied. */
    suspend fun save(file: File, fileName: String, mimeType: String?, expected: MediaEntry.Kind, onProgress: (Float) -> Unit = {}): Folder = withContext(Dispatchers.IO) {
        val target = targetFor(file.head(), fileName, mimeType, expected)
        if (scoped) intoMediaStore(file, target, onProgress) else intoSharedFolder(file, target, onProgress)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun intoMediaStore(file: File, target: Target, onProgress: (Float) -> Unit): Folder {
        val resolver = context.contentResolver
        val (uri, folder) = insertPending(target)
        var published = false
        try {
            val out = resolver.openOutputStream(uri, "w") ?: throw IOException("Couldn't write to the gallery.")
            out.use { stream -> file.inputStream().use { it.copyReporting(stream, file.length(), onProgress) } }
            val done = resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            if (done < 1) throw IOException("The gallery didn't take the file.")
            published = true
        } finally {
            if (!published) withContext(NonCancellable) { runCatching { resolver.delete(uri, null, null) } }
        }
        return folder
    }

    /**
     * A pending entry for [target] in its collection; a type the collection will not take (the MediaStore is strict
     * about which types go under Pictures, Movies and Music) goes under Downloads instead.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun insertPending(target: Target): Pair<Uri, Folder> {
        val resolver = context.contentResolver
        fun values(folder: Folder) = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, target.displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, target.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${directoryOf(folder)}/$GalleryFolder")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        if (target.folder != Folder.Downloads) {
            val inserted = try {
                resolver.insert(collectionOf(target.folder), values(target.folder))
            } catch (_: IllegalArgumentException) {
                null
            }
            if (inserted != null) return inserted to target.folder
        }
        val download = resolver.insert(collectionOf(Folder.Downloads), values(Folder.Downloads)) ?: throw IOException("The gallery refused the file.")
        return download to Folder.Downloads
    }

    @Suppress("DEPRECATION")
    private suspend fun intoSharedFolder(file: File, target: Target, onProgress: (Float) -> Unit): Folder {
        if (needsPermission) throw MediaProblemException(MediaProblem.NotReadable(PERMISSION_NEEDED, null))
        val dir = File(Environment.getExternalStoragePublicDirectory(directoryOf(target.folder)), GalleryFolder)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Couldn't reach the gallery.")
        val final = freeName(dir, target.displayName)
        // A dot-name is never scanned, so the gallery cannot list the file before it is whole.
        val partial = File(dir, ".${final.name}.pending")
        try {
            partial.outputStream().use { out -> file.inputStream().use { it.copyReporting(out, file.length(), onProgress) } }
            if (!partial.renameTo(final)) throw IOException("Couldn't write to the gallery.")
        } finally {
            withContext(NonCancellable) { partial.delete() }
        }
        MediaScannerConnection.scanFile(context, arrayOf(final.absolutePath), arrayOf(target.mimeType), null)
        return target.folder
    }

    private fun freeName(dir: File, name: String): File {
        val base = name.substringBeforeLast('.')
        val extension = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var candidate = File(dir, name)
        var n = 1
        while (candidate.exists()) candidate = File(dir, "$base (${n++})$extension")
        return candidate
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun collectionOf(folder: Folder): Uri = when (folder) {
        Folder.Pictures -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        Folder.Movies -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        Folder.Music -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        Folder.Downloads -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }

    private fun directoryOf(folder: Folder): String = when (folder) {
        Folder.Pictures -> Environment.DIRECTORY_PICTURES
        Folder.Movies -> Environment.DIRECTORY_MOVIES
        Folder.Music -> Environment.DIRECTORY_MUSIC
        Folder.Downloads -> Environment.DIRECTORY_DOWNLOADS
    }

    private fun InputStream.copyReporting(out: OutputStream, total: Long, onProgress: (Float) -> Unit) {
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        var reported = -1
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            copied += n
            if (total > 0L) {
                val percent = (copied * 100 / total).toInt()
                if (percent != reported) {
                    reported = percent
                    onProgress(percent / 100f)
                }
            }
        }
    }

    companion object {
        const val GalleryFolder = "Cursor for Android"
        const val PERMISSION_NEEDED = "Saving needs access to photos and media"

        /**
         * What the gallery is told [head]'s file is. Its bytes decide: a recording named `.mov` that is an MP4 is
         * saved as `.mp4`, a name without an extension gets the one its format goes by. Bytes that are no picture,
         * recording or sound — a web page or JSON behind the link, a Git LFS pointer — are refused rather than put in
         * the gallery as junk. Bytes this app cannot tell fall back on the declared type, then on the name's.
         */
        fun targetFor(head: ByteArray, fileName: String, mimeType: String?, expected: MediaEntry.Kind): Target {
            val expectedWords = when (expected) {
                MediaEntry.Kind.Image -> "an image"
                MediaEntry.Kind.Video -> "a video"
                MediaEntry.Kind.Audio -> "a sound"
            }
            val sniffed = FileFormat.sniff(head)
            val format = when {
                sniffed != null && sniffed.isMedia -> sniffed
                sniffed != null -> throw MediaProblemException(MediaProblem.NotMedia(expectedWords, sniffed))
                FileBytes.looksLikeText(head) -> {
                    if (FileBytes.of(head, fileName) is FileBytes.LfsPointer) throw MediaProblemException(MediaProblem.LfsPointer)
                    throw MediaProblemException(MediaProblem.NotMedia(expectedWords, null))
                }
                else -> null
            }
            val name = fileName.substringAfterLast('/').replace(Regex("""[\\:*?"<>|\u0000-\u001f]"""), "_").trim().ifBlank { "media" }
            if (format != null) return Target(withExtension(name, format), format.mimeType, folderOf(format.kind))
            val declared = mimeType?.trim()?.lowercase()?.takeIf { it.matches(CONCRETE_MEDIA_TYPE) }
            if (declared != null) return Target(name, declared, folderOf(kindOfType(declared)))
            val named = FileFormat.ofName(name)?.takeIf { it.isMedia }
            if (named != null) return Target(name, named.mimeType, folderOf(named.kind))
            return Target(name, "application/octet-stream", Folder.Downloads)
        }

        private fun withExtension(name: String, format: FileFormat): String {
            if (FileFormat.ofName(name) == format) return name
            val base = if (FileFormat.ofName(name) != null) name.substringBeforeLast('.') else name
            return "${base.ifBlank { "media" }}.${format.extension}"
        }

        private fun folderOf(kind: MediaKind): Folder = when (kind) {
            MediaKind.Image -> Folder.Pictures
            MediaKind.Video -> Folder.Movies
            MediaKind.Audio -> Folder.Music
            else -> Folder.Downloads
        }

        private fun kindOfType(type: String): MediaKind = when (type.substringBefore('/')) {
            "image" -> MediaKind.Image
            "video" -> MediaKind.Video
            "audio" -> MediaKind.Audio
            else -> MediaKind.Other
        }

        private val CONCRETE_MEDIA_TYPE = Regex("""(image|video|audio)/[a-z0-9][a-z0-9.+-]*""")
    }
}
