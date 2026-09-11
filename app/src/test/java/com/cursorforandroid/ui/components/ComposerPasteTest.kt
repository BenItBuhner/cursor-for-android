package com.cursorforandroid.ui.components

import android.content.ClipData
import android.content.ClipDescription
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.ClipMetadata
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.InputStream

/**
 * Pasting an image into the composer. A content URI can be a cloud provider's original: the bytes may arrive over
 * the network and there may be a hundred megabytes of them, so the read belongs off the main thread and behind the
 * same 15 MB bound the photo picker enforces.
 */
@OptIn(ExperimentalFoundationApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ComposerPasteTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val errors = mutableListOf<String>()
    private val added = mutableListOf<PendingAttachment>()

    private fun receiver(): ReceiveContentListener {
        lateinit var listener: ReceiveContentListener
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                listener = rememberImagePasteReceiver(
                    enabled = true,
                    currentCount = 0,
                    onAddAttachments = { added += it },
                    onAttachmentError = { errors += it },
                )!!
            }
        }
        return listener
    }

    /** What the text field hands a receiver for a clipboard paste; the constructor is internal to Compose. */
    private fun clipboardPaste(uri: Uri): TransferableContent {
        val clip = ClipData(ClipDescription("pasted", arrayOf("image/png")), ClipData.Item(uri))
        val companion = TransferableContent.Source.Companion
        val source = TransferableContent.Source.Companion::class.java
            .getDeclaredMethod("getClipboard-kB6V9T0")
            .invoke(companion) as Int
        val constructor = TransferableContent::class.java.declaredConstructors.single { it.parameterTypes.size == 5 }
        constructor.isAccessible = true
        return constructor.newInstance(ClipEntry(clip), ClipMetadata(clip.description), source, null, null) as TransferableContent
    }

    @Test
    fun `a pasted image too large for the API is refused, read off the main thread and never held whole`() {
        val uri = Uri.parse("content://com.cursorforandroid.test/original.png")
        val stream = CountingStream(100L * 1024 * 1024)
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver)
            .registerInputStream(uri, stream)
        val listener = receiver()

        compose.runOnUiThread { listener.onReceive(clipboardPaste(uri)) }
        compose.waitUntil(10_000) { errors.isNotEmpty() }

        assertThat(errors).containsExactly("Images must be 15 MB or smaller.")
        assertThat(added).isEmpty()
        assertThat(stream.consumed).isEqualTo(PromptImage.MAX_BYTES + 1)
        assertThat(stream.readThread).isNotNull()
        assertThat(stream.readThread).isNotEqualTo(Looper.getMainLooper().thread)
    }

    /** A stream of [length] zero bytes that allocates nothing, recording what was taken and by whom. */
    private class CountingStream(private val length: Long) : InputStream() {
        @Volatile
        var consumed = 0L
            private set

        @Volatile
        var readThread: Thread? = null
            private set

        override fun read(): Int {
            if (consumed >= length) return -1
            readThread = readThread ?: Thread.currentThread()
            consumed++
            return 0
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (consumed >= length) return -1
            readThread = readThread ?: Thread.currentThread()
            val n = minOf(len.toLong(), length - consumed).toInt()
            consumed += n
            return n
        }
    }
}
