package com.cursorforandroid.ui.media

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/**
 * Hands a file of the app's cache to another app through the `FileProvider`: the system's handler for its type
 * (Open with…) or the share sheet. For the files this app shows no page for — a PDF, an archive, a format no
 * decoder here draws — so that none of them is a dead end.
 */
object FileHandoff {

    fun open(context: Context, file: File, name: String, mimeType: String): Result<Unit> = runCatching {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(context, file), mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(context, Intent.createChooser(view, name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Nothing on this device can open this.")
    }

    fun share(context: Context, file: File, name: String, mimeType: String): Result<Unit> = runCatching {
        val uri = uriFor(context, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        start(context, Intent.createChooser(send, name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Nothing on this device can share this.")
    }

    private fun uriFor(context: Context, file: File) = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    private fun start(context: Context, intent: Intent, failure: String) {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            throw IOException(failure)
        }
    }
}
