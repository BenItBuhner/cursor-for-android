package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** What a picked file reads as, and the words and numbers its chip and its guard use. */
class PromptFileTest {

    @Test
    fun `the cap is 15 MB, five files a prompt, and the guard's words say so`() {
        assertThat(PromptFile.MAX_BYTES).isEqualTo(15L * 1024 * 1024)
        assertThat(PromptFile.MAX_COUNT).isEqualTo(5)
        assertThat(PromptFile.TOO_LARGE_MESSAGE).isEqualTo("Files must be 15 MB or smaller.")
    }

    @Test
    fun `a file's kind follows its type, then its extension, the way the desktop's buckets do`() {
        assertThat(PromptFileKind.of("photo.PNG", "image/png")).isEqualTo(PromptFileKind.Image)
        assertThat(PromptFileKind.of("clip.mov", "video/quicktime")).isEqualTo(PromptFileKind.Video)
        assertThat(PromptFileKind.of("voice.m4a", "audio/mp4")).isEqualTo(PromptFileKind.Audio)
        assertThat(PromptFileKind.of("spec.pdf", "application/octet-stream")).isEqualTo(PromptFileKind.Pdf)
        assertThat(PromptFileKind.of("spec", "application/pdf")).isEqualTo(PromptFileKind.Pdf)
        assertThat(PromptFileKind.of("notes.md", "text/markdown")).isEqualTo(PromptFileKind.Text)
        assertThat(PromptFileKind.of("app.log", "application/octet-stream")).isEqualTo(PromptFileKind.Text)
        assertThat(PromptFileKind.of("config.yaml", "application/x-yaml")).isEqualTo(PromptFileKind.Code)
        assertThat(PromptFileKind.of("Main.kt", "application/octet-stream")).isEqualTo(PromptFileKind.Code)
        assertThat(PromptFileKind.of("trace.har", "application/json")).isEqualTo(PromptFileKind.Code)
        assertThat(PromptFileKind.of("bundle.zip", "application/zip")).isEqualTo(PromptFileKind.Archive)
        assertThat(PromptFileKind.of("build.apk", "application/vnd.android.package-archive")).isEqualTo(PromptFileKind.Archive)
        assertThat(PromptFileKind.of("mystery.xyz", "application/octet-stream")).isEqualTo(PromptFileKind.Other)
        assertThat(PromptFileKind.Other.label).isEqualTo("File")
    }

    @Test
    fun `the type is the provider's when it names one, the extension's otherwise, octet-stream failing both`() {
        assertThat(PromptFile.resolveMimeType("application/pdf; charset=binary", "x.bin")).isEqualTo("application/pdf")
        assertThat(PromptFile.resolveMimeType("APPLICATION/ZIP", "x.zip")).isEqualTo("application/zip")
        assertThat(PromptFile.resolveMimeType(null, "report.PDF")).isEqualTo("application/pdf")
        assertThat(PromptFile.resolveMimeType("application/octet-stream", "notes.md")).isEqualTo("text/markdown")
        assertThat(PromptFile.resolveMimeType("*/*", "song.mp3")).isEqualTo("audio/mpeg")
        assertThat(PromptFile.resolveMimeType("video/*", "clip.mkv")).isEqualTo("video/x-matroska")
        assertThat(PromptFile.resolveMimeType(null, "README")).isEqualTo("application/octet-stream")
        assertThat(PromptFile.resolveMimeType("", "archive.tar.gz")).isEqualTo("application/gzip")
    }

    @Test
    fun `sizes read as a chip shows them`() {
        assertThat(PromptFile.formatSize(0)).isEqualTo("0 B")
        assertThat(PromptFile.formatSize(900)).isEqualTo("900 B")
        assertThat(PromptFile.formatSize(12 * 1024L)).isEqualTo("12 KB")
        assertThat(PromptFile.formatSize(1_500_000L)).isEqualTo("1.4 MB")
        assertThat(PromptFile.formatSize(15L * 1024 * 1024)).isEqualTo("15 MB")
    }

    @Test
    fun `a name is made safe for one path segment the way the desktop's IWa makes it`() {
        assertThat(PromptFile.safeFileName("Q3 report (final).pdf")).isEqualTo("Q3_report__final_.pdf")
        assertThat(PromptFile.safeFileName("../../etc/passwd")).isEqualTo(".._.._etc_passwd")
        assertThat(PromptFile.safeFileName("..")).isEqualTo("attachment")
        assertThat(PromptFile.safeFileName("   ")).isEqualTo("attachment")
        assertThat(PromptFile.extensionOf("archive.tar.gz")).isEqualTo("gz")
        assertThat(PromptFile.extensionOf(".env")).isEqualTo("")
        assertThat(PromptFile.extensionOf("README")).isEqualTo("")
    }

    @Test
    fun `an attachment-only prompt says what it carries`() {
        assertThat(attachmentOnlyText(images = 1, files = 0)).isEqualTo("See the attached image.")
        assertThat(attachmentOnlyText(images = 0, files = 1)).isEqualTo("See the attached file.")
        assertThat(attachmentOnlyText(images = 0, files = 3)).isEqualTo("See the attached files.")
        assertThat(attachmentOnlyText(images = 2, files = 1)).isEqualTo("See the attached files and images.")
        val queued = QueuedFollowUp("q", "", files = listOf(DraftFile("f", PromptFile(ByteArray(1), "a.pdf", "application/pdf"))), queuedAtMillis = 1L)
        assertThat(queued.previewText).isEqualTo("See the attached file.")
        assertThat(FollowUpDraft(files = queued.files).isEmpty).isFalse()
    }

    @Test
    fun `a transcript attachment is a file when it has a name, an image otherwise`() {
        val file = MessageAttachment.file("/data/f0-spec.pdf", "spec.pdf", "application/pdf", 2048L)
        assertThat(file.isFile).isTrue()
        assertThat(file.kind).isEqualTo(PromptFileKind.Pdf)
        assertThat(file.aspectRatio).isEqualTo(1f)
        val image = MessageAttachment("/data/0.png", 400, 300)
        assertThat(image.isFile).isFalse()
        assertThat(image.aspectRatio).isWithin(0.001f).of(4f / 3f)
    }
}
