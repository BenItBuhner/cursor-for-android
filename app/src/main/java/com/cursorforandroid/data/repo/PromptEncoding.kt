package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.dto.ImageDto
import com.cursorforandroid.data.api.dto.PromptDto
import com.cursorforandroid.domain.PromptImage
import java.util.Base64

/** Builds the `prompt` body for Create An Agent / Create A Run, encoding attachments as base64 image inputs. */
object PromptEncoding {
    fun toPromptDto(text: String, images: List<PromptImage>): PromptDto {
        require(images.size <= PromptImage.MAX_COUNT) { "At most ${PromptImage.MAX_COUNT} images can be attached." }
        images.forEach {
            require(it.sizeBytes <= PromptImage.MAX_BYTES) { "Each image must be 15 MB or smaller." }
            require(PromptImage.isSupported(it.mimeType)) { "Unsupported image type ${it.mimeType}." }
        }
        return PromptDto(
            text = text,
            images = images.takeIf { it.isNotEmpty() }?.map {
                ImageDto(data = Base64.getEncoder().encodeToString(it.bytes), mimeType = it.mimeType.lowercase())
            },
        )
    }
}
