package com.cursorforandroid.ui.media

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * The MediaStore as far as a save uses it: inserted rows with their values, the bytes written to each, updates and
 * deletes. Robolectric hands `openOutputStream` its own streams rather than a provider's, so each insert registers
 * one that writes into the row. [failWriteAfter] cuts a write off after that many bytes; [refuseUpdates] makes the
 * finalizing update touch nothing; [refuseCollection] makes inserts under that collection throw, as the real
 * provider does for a type it does not take there.
 */
class FakeMediaStore : ContentProvider() {

    class Row(val uri: Uri, val collection: Uri, val values: ContentValues) {
        val bytes = ByteArrayOutputStream()
        val pending: Boolean get() = values.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 1
        val displayName: String? get() = values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)
        val mimeType: String? get() = values.getAsString(MediaStore.MediaColumns.MIME_TYPE)
        val relativePath: String? get() = values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH)
    }

    val rows = LinkedHashMap<Uri, Row>()
    var failWriteAfter: Long? = null
    var refuseUpdates = false
    var refuseCollection: Uri? = null
    private var nextId = 1L

    override fun onCreate(): Boolean = true

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        if (uri == refuseCollection) throw IllegalArgumentException("MIME type not supported under $uri")
        val row = Row(ContentUris.withAppendedId(uri, nextId++), uri, ContentValues(values))
        synchronized(rows) { rows[row.uri] = row }
        val limit = failWriteAfter
        shadowOf(context!!.contentResolver).registerOutputStreamSupplier(row.uri) {
            object : OutputStream() {
                override fun write(b: Int) {
                    if (limit != null && row.bytes.size() >= limit) throw IOException("No space left on device")
                    row.bytes.write(b)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    for (i in off until off + len) write(b[i].toInt())
                }
            }
        }
        return row.uri
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        if (refuseUpdates) return 0
        val row = synchronized(rows) { rows[uri] } ?: return 0
        values?.let { row.values.putAll(it) }
        return 1
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = if (synchronized(rows) { rows.remove(uri) } != null) 1 else 0

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = synchronized(rows) { rows[uri] }?.mimeType

    fun published(): List<Row> = synchronized(rows) { rows.values.filter { !it.pending } }

    companion object {
        fun install(): FakeMediaStore = Robolectric.setupContentProvider(FakeMediaStore::class.java, MediaStore.AUTHORITY)
    }
}
