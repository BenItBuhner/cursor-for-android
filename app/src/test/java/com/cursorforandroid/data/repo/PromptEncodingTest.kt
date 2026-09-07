package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.data.api.dto.CreateRunRequestDto
import com.cursorforandroid.data.api.dto.PromptDto
import com.cursorforandroid.domain.PromptImage
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class PromptEncodingTest {

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    @Test
    fun `text-only prompts omit the images field`() {
        val json = CursorJson.encodeToString(PromptDto.serializer(), PromptEncoding.toPromptDto("Fix the bug", emptyList()))
        assertThat(json).isEqualTo("""{"text":"Fix the bug"}""")
    }

    @Test
    fun `images are base64 encoded with their mime type in the API shape`() {
        val dto = PromptEncoding.toPromptDto("What's in this screenshot?", listOf(PromptImage(png, "image/PNG")))
        val json = CursorJson.encodeToString(CreateRunRequestDto.serializer(), CreateRunRequestDto(dto))
        assertThat(json).isEqualTo(
            """{"prompt":{"text":"What's in this screenshot?","images":[{"data":"${Base64.getEncoder().encodeToString(png)}","mimeType":"image/png"}]}}""",
        )
    }

    @Test
    fun `api limits are enforced before sending`() {
        val many = List(6) { PromptImage(png, "image/png") }
        assertThrows(IllegalArgumentException::class.java) { PromptEncoding.toPromptDto("x", many) }
        assertThrows(IllegalArgumentException::class.java) { PromptEncoding.toPromptDto("x", listOf(PromptImage(png, "image/bmp"))) }
        assertThat(PromptImage.isSupported("image/webp")).isTrue()
        assertThat(PromptImage.isSupported("image/svg+xml")).isFalse()
    }
}
