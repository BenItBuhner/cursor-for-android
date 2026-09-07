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
import com.cursorforandroid.domain.PromptImage
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Prepares a picked image for the API. Attachments are base64-inlined into the create / follow-up request body, so a
 * 12 MP photo or a full-resolution phone screenshot turns into a multi-megabyte JSON upload that can take minutes on
 * a mobile link — for detail no vision model uses, since they all downsample to roughly [MAX_EDGE_PX] on the long
 * edge anyway. Anything above that size, or heavier than [PASSTHROUGH_MAX_BYTES], is decoded, oriented, scaled to fit
 * and re-encoded as JPEG. Small images and GIFs (animation) pass through untouched.
 */
object AttachmentImages {
    /** Long-edge ceiling in pixels; matches what vision models consume, so nothing the model would see is lost. */
    const val MAX_EDGE_PX = 1568
    /** Images at or under this many bytes that already fit [MAX_EDGE_PX] are sent as picked. */
    const val PASSTHROUGH_MAX_BYTES = 768 * 1024
    const val JPEG_QUALITY = 88

    fun prepare(bytes: ByteArray, mimeType: String): PromptImage {
        val mime = mimeType.lowercase()
        if (mime == "image/gif") return PromptImage(bytes, mime)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longEdge = max(bounds.outWidth, bounds.outHeight)
        if (longEdge <= 0) return PromptImage(bytes, mime)
        if (longEdge <= MAX_EDGE_PX && bytes.size <= PASSTHROUGH_MAX_BYTES) return PromptImage(bytes, mime)

        // Decode at the largest power-of-two reduction that still leaves at least MAX_EDGE_PX, then scale exactly.
        var sample = 1
        while (longEdge / (sample * 2) >= MAX_EDGE_PX) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return PromptImage(bytes, mime)
        val oriented = decoded.oriented(exifOrientation(bytes, mime))
        val fitted = oriented.fitWithin(MAX_EDGE_PX)
        val flattened = if (fitted.hasAlpha()) fitted.flattenOnWhite() else fitted
        val out = ByteArrayOutputStream()
        flattened.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        val jpeg = out.toByteArray()
        // Never make it worse: an already-tight file that only exceeded the edge cap stays as it was.
        return if (jpeg.isNotEmpty() && jpeg.size < bytes.size) PromptImage(jpeg, "image/jpeg") else PromptImage(bytes, mime)
    }

    private fun exifOrientation(bytes: ByteArray, mime: String): Int {
        if (mime != "image/jpeg") return ExifInterface.ORIENTATION_NORMAL
        return runCatching { ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
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

    /** JPEG has no alpha; transparent regions would otherwise come out black. */
    private fun Bitmap.flattenOnWhite(): Bitmap {
        val flat = createBitmap(width, height)
        val canvas = Canvas(flat)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(this, 0f, 0f, null)
        return flat
    }
}
