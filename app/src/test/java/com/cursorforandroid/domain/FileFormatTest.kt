package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Base64

/**
 * What a file is by its bytes, and what its bytes are once what they came wrapped in is taken off. Each wrapper here
 * is one that put a picture in front of a decoder as something else — the literal "…not encoded as a valid image
 * format" the viewer used to print — and each format is one a workspace, a repository or a store holds.
 */
class FileFormatTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)
    private fun ftyp(major: String, vararg compatible: String): ByteArray {
        val brands = listOf(major, "\u0000\u0000\u0000\u0000") + compatible
        val box = ascii("ftyp") + brands.joinToString("").let(::ascii)
        val size = box.size + 4
        return bytes(0, 0, 0, size) + box + ByteArray(32)
    }

    private val png = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13) + ascii("IHDR") + ByteArray(20)

    @Test
    fun `raster images by their magic numbers`() {
        assertThat(FileFormat.sniff(png)).isEqualTo(FileFormat.PNG)
        assertThat(FileFormat.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 16) + ascii("JFIF"))).isEqualTo(FileFormat.JPEG)
        assertThat(FileFormat.sniff(ascii("GIF89a") + ByteArray(8))).isEqualTo(FileFormat.GIF)
        assertThat(FileFormat.sniff(ascii("RIFF") + bytes(0, 0, 0, 0) + ascii("WEBPVP8 "))).isEqualTo(FileFormat.WEBP)
        val bmp = ascii("BM") + bytes(0x46, 0, 0, 0, 0, 0, 0, 0, 0x36, 0, 0, 0, 40, 0, 0, 0) + ByteArray(12)
        assertThat(FileFormat.sniff(bmp)).isEqualTo(FileFormat.BMP)
        assertThat(FileFormat.sniff(bytes(0, 0, 1, 0, 1, 0) + ByteArray(20))).isEqualTo(FileFormat.ICO)
        assertThat(FileFormat.sniff(bytes(0x49, 0x49, 0x2A, 0) + ByteArray(8))).isEqualTo(FileFormat.TIFF)
    }

    @Test
    fun `a text file that happens to begin with BM is not a bitmap`() {
        assertThat(FileFormat.sniff(ascii("BMW 320i service notes\nOil change due in 2000 km.\n"))).isNull()
    }

    @Test
    fun `ISO media files by their brands - HEIC, AVIF, MP4, QuickTime, M4A, 3GP`() {
        assertThat(FileFormat.sniff(ftyp("heic", "mif1", "heic"))).isEqualTo(FileFormat.HEIC)
        assertThat(FileFormat.sniff(ftyp("mif1", "heic"))).isEqualTo(FileFormat.HEIC)
        assertThat(FileFormat.sniff(ftyp("avif", "mif1", "miaf"))).isEqualTo(FileFormat.AVIF)
        assertThat(FileFormat.sniff(ftyp("isom", "iso2", "avc1", "mp41"))).isEqualTo(FileFormat.MP4)
        assertThat(FileFormat.sniff(ftyp("qt  ", "qt  "))).isEqualTo(FileFormat.MOV)
        assertThat(FileFormat.sniff(ftyp("M4A ", "M4A ", "mp42", "isom"))).isEqualTo(FileFormat.M4A)
        assertThat(FileFormat.sniff(ftyp("3gp4", "3gp4", "isom"))).isEqualTo(FileFormat.THREE_GP)
    }

    @Test
    fun `sounds - MP3 with a tag or a frame, WAV, Ogg, Opus, FLAC, AAC`() {
        assertThat(FileFormat.sniff(ascii("ID3") + bytes(4, 0, 0, 0, 0, 0, 0))).isEqualTo(FileFormat.MP3)
        assertThat(FileFormat.sniff(bytes(0xFF, 0xFB, 0x90, 0x64) + ByteArray(16))).isEqualTo(FileFormat.MP3)
        assertThat(FileFormat.sniff(ascii("RIFF") + bytes(0, 0, 0, 0) + ascii("WAVEfmt "))).isEqualTo(FileFormat.WAV)
        assertThat(FileFormat.sniff(ascii("OggS") + ByteArray(24) + ascii("\u0001vorbis"))).isEqualTo(FileFormat.OGG)
        assertThat(FileFormat.sniff(ascii("OggS") + ByteArray(24) + ascii("OpusHead"))).isEqualTo(FileFormat.OPUS)
        assertThat(FileFormat.sniff(ascii("fLaC") + ByteArray(8))).isEqualTo(FileFormat.FLAC)
        assertThat(FileFormat.sniff(bytes(0xFF, 0xF1, 0x50, 0x80) + ByteArray(8))).isEqualTo(FileFormat.AAC)
    }

    @Test
    fun `video containers and documents`() {
        val ebml = bytes(0x1A, 0x45, 0xDF, 0xA3, 0x9F, 0x42, 0x86, 0x81, 0x01, 0x42, 0x82, 0x84)
        assertThat(FileFormat.sniff(ebml + ascii("webm") + ByteArray(8))).isEqualTo(FileFormat.WEBM)
        assertThat(FileFormat.sniff(ebml + ascii("matroska") + ByteArray(8))).isEqualTo(FileFormat.MKV)
        assertThat(FileFormat.sniff(ascii("RIFF") + bytes(0, 0, 0, 0) + ascii("AVI LIST"))).isEqualTo(FileFormat.AVI)
        assertThat(FileFormat.sniff(ascii("%PDF-1.7\n"))).isEqualTo(FileFormat.PDF)
        assertThat(FileFormat.sniff(bytes(0x50, 0x4B, 0x03, 0x04, 20, 0))).isEqualTo(FileFormat.ZIP)
    }

    @Test
    fun `SVG and HTML are told apart by their opening, past a prolog, a comment and a byte-order mark`() {
        assertThat(FileFormat.sniff(ascii("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"/>"))).isEqualTo(FileFormat.SVG)
        assertThat(FileFormat.sniff("\uFEFF<?xml version=\"1.0\"?>\n<!-- Generator: Figma -->\n<svg viewBox=\"0 0 1 1\"></svg>".toByteArray())).isEqualTo(FileFormat.SVG)
        assertThat(FileFormat.sniff(ascii("<!DOCTYPE html><html><head><title>screenshot.png · GitHub</title>"))).isEqualTo(FileFormat.HTML)
        assertThat(FileFormat.sniff(ascii("fun main() = println(\"hi\")\n"))).isNull()
    }

    @Test
    fun `the name says the format when the bytes do not`() {
        assertThat(FileFormat.ofName("screenshots/45_panel.PNG")).isEqualTo(FileFormat.PNG)
        assertThat(FileFormat.ofName("/workspace/demo/walkthrough.mov?x=1")).isEqualTo(FileFormat.MOV)
        assertThat(FileFormat.ofName("notes/voice.m4a")).isEqualTo(FileFormat.M4A)
        assertThat(FileFormat.ofName("Makefile")).isNull()
    }

    // -- wrappers ------------------------------------------------------------------------------------------------

    @Test
    fun `a file is itself when its bytes are a format`() {
        assertThat(FileBytes.of(png, "a.png")).isEqualTo(FileBytes.Plain(png, FileFormat.PNG))
    }

    @Test
    fun `base64 text of a picture is the picture`() {
        val text = Base64.getMimeEncoder().encode(png)
        val read = FileBytes.of(text, "shot.png") as FileBytes.Plain
        assertThat(read.bytes).isEqualTo(png)
        assertThat(read.format).isEqualTo(FileFormat.PNG)
        assertThat(read.unwrapped).isEqualTo(FileBytes.Wrapper.Base64)
    }

    @Test
    fun `a data URI written to a file is the picture it carries`() {
        val text = "data:image/png;base64," + Base64.getEncoder().encodeToString(png)
        val read = FileBytes.of(text.toByteArray(), "shot.png") as FileBytes.Plain
        assertThat(read.bytes).isEqualTo(png)
        assertThat(read.unwrapped).isEqualTo(FileBytes.Wrapper.DataUri)
        val svg = "data:image/svg+xml;utf8,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%2F%3E"
        assertThat((FileBytes.of(svg.toByteArray(), "icon.svg") as FileBytes.Plain).format).isEqualTo(FileFormat.SVG)
    }

    @Test
    fun `a JSON object carrying the file under a familiar key is the file`() {
        val base64 = Base64.getEncoder().encodeToString(png)
        for (key in listOf("content", "data", "imageData", "blobData")) {
            val read = FileBytes.of("{\"$key\": \"$base64\", \"mimeType\": \"image/png\"}".toByteArray(), "x.png") as FileBytes.Plain
            assertThat(read.bytes).isEqualTo(png)
            assertThat(read.unwrapped).isEqualTo(FileBytes.Wrapper.Json)
        }
        val nested = FileBytes.of("{\"content\": \"data:image/png;base64,$base64\"}".toByteArray(), "x.png") as FileBytes.Plain
        assertThat(nested.bytes).isEqualTo(png)
    }

    @Test
    fun `a Git LFS pointer is named with its object`() {
        val pointer = "version https://git-lfs.github.com/spec/v1\noid sha256:4d7a214614ab2935c943f9e0ff69d22eadbb8f32b1258daaa5e2ca24d17e2393\nsize 1214832\n"
        assertThat(FileBytes.of(pointer.toByteArray(), "screenshots/45.png")).isEqualTo(
            FileBytes.LfsPointer("sha256:4d7a214614ab2935c943f9e0ff69d22eadbb8f32b1258daaa5e2ca24d17e2393", 1_214_832L),
        )
    }

    @Test
    fun `text that is valid base64 but not of a known format stays text`() {
        val source = "abcdefghijklmnopqrstuvwxyzABCDEFGH".toByteArray()
        val read = FileBytes.of(source, "notes.txt") as FileBytes.Plain
        assertThat(read.bytes).isEqualTo(source)
        assertThat(read.unwrapped).isNull()
        assertThat(read.format).isNull()
    }

    @Test
    fun `a text file named like a picture is text, not a PNG`() {
        val read = FileBytes.of("404: Not Found".toByteArray(), "shot.png") as FileBytes.Plain
        assertThat(read.format).isNull()
        assertThat(FileBytes.looksLikeText(read.bytes)).isTrue()
    }

    @Test
    fun `a binary file of no known format keeps what its name says`() {
        val noise = ByteArray(64) { (it * 37 + 3).toByte() }.also { it[0] = 0; it[1] = 7 }
        assertThat((FileBytes.of(noise, "clip.mp4") as FileBytes.Plain).format).isEqualTo(FileFormat.MP4)
    }
}
