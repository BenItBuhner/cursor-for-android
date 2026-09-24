package com.cursorforandroid.ui.media

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.domain.FileFormat
import com.cursorforandroid.domain.MediaRef
import java.io.File
import java.io.IOException

/**
 * What the viewer's chrome does with the page on screen: share it, or hand a recording to whatever app plays it
 * (saving to the gallery is [MediaSaves]'s). Each starts by materialising the media as a file of the app's cache
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
        /** The type another app is told: the attachment's own when it was kept, else what the file's extension says. */
        fun mimeType(entry: MediaEntry): String {
            entry.mimeType?.takeIf { it.isNotBlank() }?.let { return it }
            val extension = entry.fileName.substringAfterLast('.', "").lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: FileFormat.ofName(entry.fileName)?.mimeType
                ?: when (entry.kind) {
                    MediaEntry.Kind.Video -> "video/*"
                    MediaEntry.Kind.Audio -> "audio/*"
                    MediaEntry.Kind.Image -> "image/*"
                }
        }
    }
}
