package com.cursorforandroid.data.media

import android.os.Build
import com.cursorforandroid.domain.FileFormat
import java.io.IOException

/**
 * Why a picture, a recording or a sound could not be shown, in the words the row or the viewer page says it with —
 * never a decoder's own ("BitmapFactory returned a null bitmap … not encoded as a valid image format"), which is
 * what the viewer used to print. [title] is the row's first line, [detail] its second; [retryable] offers Retry,
 * and [openable] says the file itself can still be handed to the browser or another app.
 */
sealed interface MediaProblem {
    val title: String
    val detail: String?
    val retryable: Boolean get() = false
    val openable: Boolean get() = true

    /** A format this device's decoders do not draw: HEIC before Android 9, AVIF before 12, TIFF anywhere. */
    data class Unsupported(val format: FileFormat) : MediaProblem {
        override val title: String get() = "${format.label}s don't show on this device"
        override val detail: String get() = when (format) {
            FileFormat.HEIC -> "Android 9 or newer draws HEIC; open it in another app instead."
            FileFormat.AVIF -> "Android 12 or newer draws AVIF; open it in another app instead."
            else -> "Open it in another app instead."
        }
    }

    /** The bytes are not what the name said: a web page behind an image link, a text file, a PDF named `.png`. [expected] is "an image", "a video". */
    data class NotMedia(val expected: String, val actual: FileFormat?) : MediaProblem {
        override val title: String get() = when (actual) {
            FileFormat.HTML -> "This link is a web page, not $expected"
            null -> "This file is text, not $expected"
            else -> "This is a ${actual.label}, not $expected"
        }
        override val detail: String? get() = if (actual == FileFormat.HTML) "Open it in the browser to see it." else null
    }

    /** The bytes claim to be the format but no decoder could read them: cut short, or damaged. */
    data class Damaged(val format: FileFormat?) : MediaProblem {
        override val title: String get() = "This ${format?.label?.lowercase() ?: "file"} couldn't be read"
        override val detail: String get() = "It may be damaged or cut short."
    }

    /** A Git LFS pointer where the file was expected: the repository keeps the file elsewhere. */
    data object LfsPointer : MediaProblem {
        override val title: String get() = "Stored with Git LFS"
        override val detail: String get() = "The repository keeps this file outside git; open it on the repository's host."
    }

    /** Nothing here may read where the file is: the agent's workspace without Extended mode, say. */
    data class NotReadable(override val title: String, override val detail: String?) : MediaProblem

    /** The read was made and failed: the network, a refusal, a file gone. */
    data class Failed(override val title: String, override val detail: String? = null, override val retryable: Boolean = true) : MediaProblem

    companion object {
        /** What a format that sniffed as an image but did not decode means on this device. */
        fun undecodable(format: FileFormat?): MediaProblem = when {
            format == FileFormat.HEIC && Build.VERSION.SDK_INT < Build.VERSION_CODES.P -> Unsupported(format)
            format == FileFormat.AVIF && Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> Unsupported(format)
            format == FileFormat.TIFF -> Unsupported(format)
            format == FileFormat.HEIC || format == FileFormat.AVIF -> Unsupported(format)
            else -> Damaged(format)
        }
    }
}

/** A read of media that ended in a [MediaProblem]: what the surfaces catch and name, instead of a raw message. */
class MediaProblemException(val problem: MediaProblem, cause: Throwable? = null) : IOException(problem.title, cause)
