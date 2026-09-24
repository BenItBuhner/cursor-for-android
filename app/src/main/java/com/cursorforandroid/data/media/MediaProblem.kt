package com.cursorforandroid.data.media

import android.os.Build
import com.cursorforandroid.domain.FileFormat
import java.io.IOException

/**
 * Why a picture, a recording or a sound could not be shown, in the words the row or the viewer page says it with —
 * never a decoder's own ("BitmapFactory returned a null bitmap … not encoded as a valid image format"), which is
 * what the viewer used to print. [title] is the row's first line, [detail] its second; [retryable] offers Retry,
 * and [openable] says the file itself can still be handed to the browser or another app. [asked] is the request a
 * refusal answered, as sent and as received (`POST /…/ReadBinaryFile → HTTP 404 not_found "…"`), so the next
 * screenshot says which call it was; [wakeable] offers to wake the agent's machine first.
 */
sealed interface MediaProblem {
    val title: String
    val detail: String?
    val retryable: Boolean get() = false
    val openable: Boolean get() = true
    val asked: String? get() = null
    val wakeable: Boolean get() = false

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
    data class Failed(
        override val title: String,
        override val detail: String? = null,
        override val retryable: Boolean = true,
        override val asked: String? = null,
    ) : MediaProblem

    /** No answer within the load's deadline; [stage] is where the read was when it was given up on (see [MediaStage]). */
    data class TimedOut(val stage: MediaStage, val seconds: Long) : MediaProblem {
        override val title: String get() = "This image took too long to load"
        override val detail: String get() = "No answer after ${seconds}s — stuck ${stage.words.replaceFirstChar { it.lowercase() }}."
        override val retryable: Boolean get() = true
        override val openable: Boolean get() = false
    }

    /** The agent's machine is not running: waking it (`WakeBackgroundComposer`) and reading again is the way on. */
    data class MachineAsleep(override val asked: String? = null) : MediaProblem {
        override val title: String get() = "The agent's machine is asleep"
        override val detail: String get() = "Wake it and the file is read again."
        override val retryable: Boolean get() = true
        override val openable: Boolean get() = false
        override val wakeable: Boolean get() = true
    }

    /** The chat is over and its machine with it: nothing but what the transcript carried is left to show. */
    data class MachineGone(override val asked: String? = null) : MediaProblem {
        override val title: String get() = "The agent's machine is gone"
        override val detail: String get() = "The chat expired or was archived, and its VM went with it; only what the transcript carried can be shown."
        override val openable: Boolean get() = false
    }

    /**
     * The file is on the agent's machine outside its workspace, and no source this chat carries has it: the picture
     * can only be had if the agent copies it under the workspace. [copyable] offers to draft that ask.
     */
    data class OutsideWorkspace(override val asked: String? = null) : MediaProblem {
        override val title: String get() = "This picture is outside the agent's workspace"
        override val detail: String get() = "It was saved outside the agent's workspace, and Cursor only lets apps read files inside it; this chat didn't include a copy."
        override val openable: Boolean get() = false
        /** Whether to offer "Ask the agent to copy it into the workspace", which drafts a follow-up. */
        val copyable: Boolean get() = true
    }

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
