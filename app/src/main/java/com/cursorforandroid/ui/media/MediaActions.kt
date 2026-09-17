package com.cursorforandroid.ui.media

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.domain.MediaRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * What the viewer's chrome does with the page on screen: share it, save it to the device's gallery, or hand a
 * recording to whatever app plays it. Each starts by materialising the media as a file of the app's cache
 * ([MediaLoader.file]) — the artifact is behind a presigned URL, the store file behind an account read, the
 * generated image already on this device — and hands that out through the `FileProvider`.
 */
class MediaActions(private val context: Context, private val loader: MediaLoader) {

    /** The system share sheet with the file; a failure names the reason, for the viewer's notice. */
    suspend fun share(ref: MediaRef, entry: MediaEntry): Result<Unit> = runCatching {
        val file = loader.file(ref, entry.fileName)
        val uri = uriFor(file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType(entry)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri(entry.title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        start(Intent.createChooser(send, entry.title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Nothing on this device can share this.")
    }

    /** Opens the file with the system's handler for its type: the way to play a recording outside the viewer. */
    suspend fun openWith(ref: MediaRef, entry: MediaEntry): Result<Unit> = runCatching {
        val file = loader.file(ref, entry.fileName)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(file), mimeType(entry))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(Intent.createChooser(view, entry.title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Nothing on this device can open this.")
    }

    /**
     * A copy in the device's gallery, under Pictures or Movies in a folder of the app's name. Through the MediaStore,
     * which needs no permission from Android 10 on ([canSave]); earlier releases have the share sheet instead.
     */
    suspend fun save(ref: MediaRef, entry: MediaEntry): Result<String> = runCatching {
        check(canSave) { "Saving needs Android 10 or newer." }
        val file = loader.file(ref, entry.fileName)
        withContext(Dispatchers.IO) { insertIntoGallery(file, entry) }
        if (entry.isVideo) "Saved to Movies" else "Saved to Pictures"
    }

    private fun insertIntoGallery(file: File, entry: MediaEntry) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) throw IOException("Saving needs Android 10 or newer.")
        val resolver = context.contentResolver
        val collection = if (entry.isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val folder = if (entry.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, entry.fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType(entry))
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/$GalleryFolder")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("The gallery refused the file.")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: throw IOException("Couldn't write to the gallery.")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    private fun uriFor(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    private fun start(intent: Intent, failure: String) {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            throw IOException(failure)
        }
    }

    companion object {
        const val GalleryFolder = "Cursor for Android"

        /** The MediaStore takes a file without a storage permission from Android 10 on. */
        val canSave: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        /** The type another app is told: the attachment's own when it was kept, else what the file's extension says. */
        fun mimeType(entry: MediaEntry): String {
            entry.mimeType?.takeIf { it.isNotBlank() }?.let { return it }
            val extension = entry.fileName.substringAfterLast('.', "").lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: if (entry.isVideo) "video/*" else "image/*"
        }
    }
}
