package com.cursorforandroid.domain

import com.cursorforandroid.data.local.TextSpill
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.io.File

/**
 * A text a payload carries that may be kept on disk instead of the heap (see [TextSpill]): read through [value]
 * whenever it is wanted, which for a spilled one is a file read (or the recent texts' copy). Serialized as the plain
 * string, so what is written to the trace caches is what it always was.
 *
 * Two are equal when their texts are: spilled ones by their files, which are named by the text's digest, so a
 * transcript rebuilt from the same record compares equal without reading anything back.
 */
@Serializable(with = SpillText.Serializer::class)
class SpillText private constructor(private val inline: String?, private val file: File?, val length: Int) {

    /** The text; empty when it was spilled and the caches have been wiped since (the chat it belonged to is gone too). */
    val value: String get() = inline ?: file?.let(TextSpill::read).orEmpty()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SpillText || other.length != length) return false
        if (file != null && other.file != null) return file.name == other.file.name
        return value == other.value
    }

    override fun hashCode(): Int = length

    override fun toString(): String = if (file != null) "SpillText(length=$length, on disk)" else value

    companion object {
        fun of(text: String): SpillText = TextSpill.put(text)?.let { SpillText(null, it, text.length) } ?: SpillText(text, null, text.length)
    }

    object Serializer : KSerializer<SpillText> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.cursorforandroid.domain.SpillText", PrimitiveKind.STRING)
        override fun serialize(encoder: Encoder, value: SpillText) = encoder.encodeString(value.value)
        override fun deserialize(decoder: Decoder): SpillText = of(decoder.decodeString())
    }
}
