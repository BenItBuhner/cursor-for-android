package com.cursorforandroid.ui.components

import com.cursorforandroid.domain.PromptImage
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * The size limit on a picked image, enforced against the stream rather than against an array that has already been
 * allocated. Nothing here needs Android: the picker hands over a declared length (or none) and a way to open the
 * stream, which is all the bound is decided from.
 */
class AttachmentBytesTest {

    /** A provider stream of a given length that allocates nothing, and counts what was actually taken from it. */
    private class Stream(private val length: Long) : InputStream() {
        var consumed = 0L
            private set

        override fun read(): Int {
            if (consumed >= length) return -1
            consumed++
            return 0
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (consumed >= length) return -1
            val n = minOf(len.toLong(), length - consumed).toInt()
            consumed += n
            return n
        }
    }

    @Test
    fun `a declared size over the limit is refused without opening the stream`() {
        var opened = 0
        val failure = assertThrows(IllegalStateException::class.java) {
            readBoundedBytes(PromptImage.MAX_BYTES + 1) { opened++; Stream(PromptImage.MAX_BYTES + 1) }
        }
        assertThat(failure).hasMessageThat().isEqualTo("Images must be 15 MB or smaller.")
        assertThat(opened).isEqualTo(0)
    }

    @Test
    fun `a stream that declares no length is refused one byte past the limit, not after all of it`() {
        // What a document provider handing over a 100 MB original with no OpenableColumns.SIZE looks like.
        val stream = Stream(100L * 1024 * 1024)
        val failure = assertThrows(IllegalStateException::class.java) { readBoundedBytes(-1L) { stream } }
        assertThat(failure).hasMessageThat().isEqualTo("Images must be 15 MB or smaller.")
        assertThat(stream.consumed).isEqualTo(PromptImage.MAX_BYTES + 1)
    }

    @Test
    fun `a declared size that understates the stream does not raise the limit`() {
        val stream = Stream(40L * 1024 * 1024)
        assertThrows(IllegalStateException::class.java) { readBoundedBytes(1024L) { stream } }
        assertThat(stream.consumed).isEqualTo(PromptImage.MAX_BYTES + 1)
    }

    @Test
    fun `an image inside the limit is read whole, declared or not`() {
        val bytes = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
        assertThat(readBoundedBytes(bytes.size.toLong()) { ByteArrayInputStream(bytes) }).isEqualTo(bytes)
        assertThat(readBoundedBytes(-1L) { ByteArrayInputStream(bytes) }).isEqualTo(bytes)

        // Exactly at the cap is allowed; the extra byte the copy reads for is simply never there.
        val atCap = Stream(PromptImage.MAX_BYTES)
        assertThat(readBoundedBytes(-1L) { atCap }).hasLength(PromptImage.MAX_BYTES.toInt())
    }

    @Test
    fun `a provider that cannot open the selection says so`() {
        val failure = assertThrows(IllegalStateException::class.java) { readBoundedBytes(1024L) { null } }
        assertThat(failure).hasMessageThat().contains("Couldn't read")
    }
}
