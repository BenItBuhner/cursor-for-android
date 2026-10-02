// The framework ExifInterface reads the one tag needed here on every supported API level (26+); the AndroidX copy
// is not worth a dependency for that.
@file:Suppress("ExifInterface")

package com.cursorforandroid.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.cursorforandroid.domain.FileFormat
import com.cursorforandroid.domain.PromptImage
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Bytes that cannot go out as an image, with the reason worded for the composer. */
class UnreadableImageException(message: String) : IllegalArgumentException(message)

/**
 * Prepares a picked image for the API, so what an agent receives is an image it can view as it arrives: the bytes are
 * one of [SENDABLE]'s formats, the MIME type is the one those bytes actually are, the file ends where its format says
 * it does, and the size is at most [MAX_SEND_BYTES].
 *
 * The agent's VM gets each image written to its workspace and read by a strict sniffer: a PNG must end on its `IEND`
 * chunk and a JPEG on its EOI, or the image falls to a decoder (pngjs) that refuses any byte after `IEND`. Phones
 * append their own records there — a Samsung screenshot's `SEFT` trailer, a motion photo's video, a gain map — and
 * such an image reached the agent as one it had to convert before it could look at it. So an image is cut, losslessly,
 * at its container's real end ([trimmed]) and otherwise sent byte for byte, whatever its size or resolution: Cursor
 * scales what the model sees itself. It is only decoded and re-encoded where it has to be — a file whose structure
 * cannot be walked, a JPEG whose EXIF says to turn it, a long edge over [MAX_PASSTHROUGH_EDGE_PX], or more than the
 * byte ceiling — and then fitted to [MAX_EDGE_PX]. HEIC, AVIF, BMP and ICO are converted. Anything that will not
 * decode is refused with an [UnreadableImageException] rather than sent as it is.
 */
object AttachmentImages {
    /** Long-edge ceiling in pixels for an image that has to be re-encoded; matches what vision models consume. */
    const val MAX_EDGE_PX = 1568
    /** The longest edge sent as picked: vision models refuse an image over 8000 px on a side. */
    const val MAX_PASSTHROUGH_EDGE_PX = 8000
    /** The most an image may weigh as sent: the Cloud Agents API's 15 MB per image. */
    const val MAX_SEND_BYTES = PromptImage.MAX_BYTES.toInt()
    const val JPEG_QUALITY = 88

    /** The formats the API takes as an image (`image/png`, `image/jpeg`, `image/gif`, `image/webp`). */
    private val SENDABLE = setOf(FileFormat.PNG, FileFormat.JPEG, FileFormat.GIF, FileFormat.WEBP)

    /** Image formats the API does not take, decoded here (HEIC from API 28, AVIF where the platform decodes it) and sent as JPEG or PNG. */
    private val CONVERTED = setOf(FileFormat.HEIC, FileFormat.AVIF, FileFormat.BMP, FileFormat.ICO)

    /** Lower JPEG qualities tried, in turn, when an encode at [JPEG_QUALITY] is still over the byte ceiling. */
    private val FALLBACK_QUALITIES = intArrayOf(80, 70, 60)

    /** Whether [bytes] are a picture [prepare] turns into a prompt image, by what they are rather than what they are called. */
    fun isPicture(bytes: ByteArray): Boolean = FileFormat.sniff(bytes).let { it in SENDABLE || it in CONVERTED }

    /** Whether [bytes] are a format the API takes as it is (as opposed to one [prepare] converts). */
    fun isSendableFormat(bytes: ByteArray): Boolean = FileFormat.sniff(bytes) in SENDABLE

    /**
     * [bytes] as the prompt carries them. [declaredMime] is only what the picker or clipboard said, used to name a
     * refusal: the bytes decide the format. [maxBytes] is the byte ceiling, [MAX_SEND_BYTES] outside tests.
     */
    fun prepare(bytes: ByteArray, declaredMime: String?, maxBytes: Int = MAX_SEND_BYTES): PromptImage {
        val format = FileFormat.sniff(bytes)?.takeIf { it in SENDABLE || it in CONVERTED }
            ?: throw UnreadableImageException("Unsupported image type (${declaredMime?.takeIf { it.isNotBlank() } ?: "unknown"}). Use PNG, JPEG, GIF or WebP.")
        val whole = trimmed(bytes, format)
        val source = whole ?: bytes
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        val longEdge = max(bounds.outWidth, bounds.outHeight)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw unreadable(format)
        val orientation = exifOrientation(source, format)
        if (whole != null && orientation == ExifInterface.ORIENTATION_NORMAL && longEdge <= MAX_PASSTHROUGH_EDGE_PX && whole.size <= maxBytes) {
            return PromptImage(whole, format.mimeType)
        }

        // Decode at the largest power-of-two reduction that still leaves at least MAX_EDGE_PX, then scale exactly.
        var sample = 1
        while (longEdge / (sample * 2) >= MAX_EDGE_PX) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(source, 0, source.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw unreadable(format)
        return encode(decoded.oriented(orientation).fitWithin(MAX_EDGE_PX), maxBytes)
    }

    private fun unreadable(format: FileFormat) =
        UnreadableImageException("Couldn't read this ${format.label}. Re-save it as PNG or JPEG and attach it again.")

    /** Whether the file ends where its format says it does, with nothing after its end marker. */
    internal fun isIntact(bytes: ByteArray, format: FileFormat): Boolean = trimmed(bytes, format)?.size == bytes.size

    /**
     * [bytes] cut at the end of the image its container describes, the same array when nothing follows it: a PNG after
     * its `IEND` chunk, a JPEG after the EOI that closes its first frame (an EXIF thumbnail's own EOI sits inside APP1
     * and is skipped with it), a WebP at its RIFF length. Nothing the image is made of is touched. Null when the
     * structure cannot be walked to its end (truncated, or not what it claims), or for a GIF that does not end on its
     * trailer — those have to be decoded instead.
     */
    internal fun trimmed(bytes: ByteArray, format: FileFormat): ByteArray? {
        val end = when (format) {
            FileFormat.PNG -> pngEnd(bytes)
            FileFormat.JPEG -> jpegEnd(bytes)
            FileFormat.GIF -> bytes.size.takeIf { it > 0 && bytes.last() == 0x3B.toByte() }
            FileFormat.WEBP -> if (bytes.size < 12) null else riffLength(bytes).takeIf { it in 12..bytes.size.toLong() }?.toInt()
            else -> null
        } ?: return null
        return if (end == bytes.size) bytes else bytes.copyOf(end)
    }

    /** The offset just past a PNG's `IEND` chunk, walking each `length · type · data · CRC` chunk from the signature. */
    private fun pngEnd(bytes: ByteArray): Int? {
        var offset = 8L
        while (offset + 12 <= bytes.size) {
            val at = offset.toInt()
            val next = offset + 12 + u32(bytes, at)
            if (next > bytes.size) return null
            if (bytes[at + 4] == 0x49.toByte() && bytes[at + 5] == 0x45.toByte() && bytes[at + 6] == 0x4E.toByte() && bytes[at + 7] == 0x44.toByte()) {
                return next.toInt().takeIf { PNG_IEND.indices.all { i -> bytes[at + i] == PNG_IEND[i] } }
            }
            offset = next
        }
        return null
    }

    /**
     * The offset just past a JPEG's EOI: marker segments are skipped by their lengths, and each scan's entropy-coded
     * data up to the next marker that is not a stuffed `FF 00` or a restart.
     */
    private fun jpegEnd(bytes: ByteArray): Int? {
        if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
        var i = 2
        while (i < bytes.size) {
            if (bytes[i] != 0xFF.toByte()) return null
            while (i < bytes.size && bytes[i] == 0xFF.toByte()) i++
            if (i >= bytes.size) return null
            val marker = bytes[i++].toInt() and 0xFF
            when {
                marker == 0xD9 -> return i
                marker == 0x01 || marker in 0xD0..0xD7 -> continue
                marker == 0x00 || marker == 0xD8 -> return null
            }
            if (i + 2 > bytes.size) return null
            val length = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
            if (length < 2 || i + length > bytes.size) return null
            i += length
            if (marker != 0xDA) continue
            while (true) {
                if (i + 1 >= bytes.size) return null
                if (bytes[i] != 0xFF.toByte()) { i++; continue }
                val next = bytes[i + 1].toInt() and 0xFF
                if (next == 0x00 || next in 0xD0..0xD7) i += 2 else if (next == 0xFF) i++ else break
            }
        }
        return null
    }

    private fun u32(bytes: ByteArray, at: Int): Long =
        (0..3).fold(0L) { acc, k -> (acc shl 8) or (bytes[at + k].toLong() and 0xFF) }

    /** The RIFF container's own length: its little-endian chunk size at 4, plus the 8 bytes of the header it does not count. */
    private fun riffLength(bytes: ByteArray): Long =
        (0..3).fold(0L) { acc, i -> acc or ((bytes[4 + i].toLong() and 0xFF) shl (8 * i)) } + 8

    /**
     * [bitmap] encoded within [maxBytes]: as PNG when it has transparent pixels and the PNG fits, so a logo on a clear
     * background reaches the model as drawn; otherwise flattened onto white and written as JPEG, stepping the quality
     * down and then the size until it fits.
     */
    private fun encode(bitmap: Bitmap, maxBytes: Int): PromptImage {
        var current = bitmap
        if (current.hasAlpha() && current.hasTransparentPixels()) {
            val png = current.compressed(Bitmap.CompressFormat.PNG, 100)
            if (png.isNotEmpty() && png.size <= maxBytes) return PromptImage(png, FileFormat.PNG.mimeType)
        }
        if (current.hasAlpha()) current = current.flattenOnWhite()
        while (true) {
            for (quality in intArrayOf(JPEG_QUALITY) + FALLBACK_QUALITIES) {
                val jpeg = current.compressed(Bitmap.CompressFormat.JPEG, quality)
                if (jpeg.isNotEmpty() && jpeg.size <= maxBytes) return PromptImage(jpeg, FileFormat.JPEG.mimeType)
            }
            if (max(current.width, current.height) <= MIN_EDGE_PX) throw UnreadableImageException("This image is too detailed to send. Crop it and try again.")
            current = current.fitWithin((max(current.width, current.height) * 3) / 4)
        }
    }

    private fun Bitmap.compressed(format: Bitmap.CompressFormat, quality: Int): ByteArray =
        ByteArrayOutputStream().also { compress(format, quality, it) }.toByteArray()

    private fun exifOrientation(bytes: ByteArray, format: FileFormat): Int {
        if (format != FileFormat.JPEG) return ExifInterface.ORIENTATION_NORMAL
        return runCatching { ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            .let { if (it == ExifInterface.ORIENTATION_UNDEFINED) ExifInterface.ORIENTATION_NORMAL else it }
    }

    /** Bakes the EXIF orientation into the pixels; a re-encoded JPEG carries no EXIF, so it must not rely on it. */
    private fun Bitmap.oriented(orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.preScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.preScale(-1f, 1f) }
            else -> return this
        }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    private fun Bitmap.fitWithin(maxEdge: Int): Bitmap {
        val edge = max(width, height)
        if (edge <= maxEdge) return this
        val factor = maxEdge.toFloat() / edge
        return scale((width * factor).roundToInt().coerceAtLeast(1), (height * factor).roundToInt().coerceAtLeast(1))
    }

    /** A band of rows at a time: one JNI round trip per row would be over a thousand of them for a full-size image. */
    private fun Bitmap.hasTransparentPixels(): Boolean {
        val rows = (PIXEL_BAND_PX / width).coerceIn(1, height)
        val band = IntArray(width * rows)
        var y = 0
        while (y < height) {
            val take = minOf(rows, height - y)
            getPixels(band, 0, width, 0, y, width, take)
            for (i in 0 until width * take) if (band[i] ushr 24 != 0xFF) return true
            y += take
        }
        return false
    }

    /** JPEG has no alpha; transparent regions would otherwise come out black. */
    private fun Bitmap.flattenOnWhite(): Bitmap {
        val flat = createBitmap(width, height)
        val canvas = Canvas(flat)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(this, 0f, 0f, null)
        return flat
    }

    /** A PNG's last chunk: `IEND`, empty, with its fixed CRC. */
    private val PNG_IEND = byteArrayOf(0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte())
    private const val PIXEL_BAND_PX = 64 * 1024
    /** The smallest long edge the size steps go down to before an image is refused as too heavy to send. */
    private const val MIN_EDGE_PX = 256
}
