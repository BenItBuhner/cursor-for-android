package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/** A recorded clip turned into text: what the account heard, and how long it took to hear it (null when not said). */
data class Transcription(val text: String, val transcriptionTimeMs: Long?)

/** Dictation into the composer: one recorded clip in, its text out. An interface so the composer's voice input can be faked. */
fun interface TranscriptionApi {
    /** Transcribes [audio], a whole recording in the container [mimeType] names (`audio/webm`, `audio/mp4`); [language] null lets the service detect it. */
    suspend fun transcribe(audio: ByteArray, mimeType: String, language: String?): Transcription
}

/**
 * `aiserver.v1.AiService/TranscribeAudio`, the call Cursor's composer dictates through when it records a clip rather
 * than streaming (Cursor 3.22.7's voice service, `transcribeBlob`): `{audio: bytes, mime_type, language?}` →
 * `{text, transcription_time_ms}`. In Connect JSON the bytes travel as standard base64 and the int64 as a decimal
 * string. The MIME type goes without its parameters (`audio/webm`, not `audio/webm;codecs=opus`), as the desktop sends
 * it. The call carries the account session, which Extended mode alone hands out.
 */
class AccountTranscriptionApi(
    private val rpc: ConnectJsonClient,
    private val tokens: SessionTokenProvider,
) : TranscriptionApi {

    override suspend fun transcribe(audio: ByteArray, mimeType: String, language: String?): Transcription {
        val request = TranscribeAudioRequestDto(
            audio = Base64.getEncoder().encodeToString(audio),
            mimeType = mimeType.substringBefore(';').trim(),
            language = language?.takeIf { it.isNotBlank() },
        )
        // A refused clip is said at once rather than waited out: the reader is looking at the composer, not a queue.
        val response = rpc.unaryWithSession(SERVICE, "TranscribeAudio", tokens, request, TranscribeAudioRequestDto.serializer(), TranscribeAudioResponseDto.serializer(), retryRefusals = false)
        val time = response.transcriptionTimeMs
        return Transcription(response.text.orEmpty(), time?.longOrNull ?: time?.contentOrNull?.toLongOrNull())
    }

    companion object {
        const val SERVICE = "aiserver.v1.AiService"
    }
}

@Serializable
internal data class TranscribeAudioRequestDto(
    val audio: String,
    val mimeType: String,
    val language: String? = null,
)

@Serializable
internal data class TranscribeAudioResponseDto(
    val text: String? = null,
    val transcriptionTimeMs: JsonPrimitive? = null,
)
