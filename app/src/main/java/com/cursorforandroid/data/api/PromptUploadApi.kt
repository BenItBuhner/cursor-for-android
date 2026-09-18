package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.UploadRef
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.util.UUID

/**
 * A file the prompt refers to, staged with the account or carried inline, the way the desktop Agents Window builds one
 * (Cursor 3.20.21 `cloudAgentPromptUpload.js`): an image (`HKy`) becomes `agent.v1.SelectedImage {uuid, mime_type,
 * data_or_blob_id}` in `selected_images[]`, anything else (`WKy`) an `agent.v1.SelectedDocument {uuid, filename,
 * mime_type, data_or_blob_id}` in `selected_documents[]`. After an upload the oneof is `prompt_upload_ref {upload_id}`;
 * with the upload path off, or for an empty file, it is the bytes themselves (`data`).
 */
class UploadedFile(
    val filename: String,
    val mimeType: String,
    /** The `PresignPromptUpload` id the bytes went up under; null when [data] is carried inline. */
    val uploadId: String?,
    val data: ByteArray? = null,
    val uuid: String = UUID.randomUUID().toString(),
    /** The S3 multipart id `CompletePromptUpload` settled, which `AbortPromptUpload` takes to drop the upload again; null for an inline file. */
    val s3UploadId: String? = null,
) {
    init {
        require(uploadId != null || data != null) { "An UploadedFile carries an upload id or its bytes." }
    }

    /** The reference a draft keeps of a staged upload, so a restart sends what is already up; null for an inline file. */
    val ref: UploadRef? get() = uploadId?.let { UploadRef(it, s3UploadId.orEmpty(), uuid) }

    companion object {
        /** [file] as the prompt names it once its upload has been completed under [ref]. */
        fun of(file: PromptFile, ref: UploadRef): UploadedFile = UploadedFile(file.name, file.mimeType.ifBlank { PromptFile.OCTET_STREAM }, ref.uploadId, uuid = ref.uuid, s3UploadId = ref.s3UploadId)
    }

    /**
     * Whether the prompt names it as an image (`selected_images[]`) rather than a document: one of the types the
     * documented API takes as an image. The desktop's cloud picker treats HEIC and SVG as documents too (`Dkt`
     * excludes its conversion images), so they, and every other image type outside that set, travel as documents here as well.
     */
    val isImage: Boolean get() = PromptImage.isSupported(mimeType)
}

/** One presigned part of a multipart prompt upload (`aiserver.v1.PromptUploadPart`). */
data class PromptUploadPart(val partNumber: Int, val url: String, val offsetBytes: Long, val sizeBytes: Long)

/** What `PresignPromptUpload` answered: where each part of the file goes, and the ids `CompletePromptUpload` takes back. */
data class PresignedPromptUpload(
    val uploadId: String,
    val s3UploadId: String,
    val parts: List<PromptUploadPart>,
    val partSizeBytes: Long,
    val urlsExpireAtMs: Long?,
)

/** `aiserver.v1.PromptUploadCompletionStatus`, read by name or by number. */
enum class PromptUploadCompletion(val wireName: String, val number: Int) {
    UNSPECIFIED("PROMPT_UPLOAD_COMPLETION_STATUS_UNSPECIFIED", 0),
    COMPLETED("PROMPT_UPLOAD_COMPLETION_STATUS_COMPLETED", 1),
    NOT_FOUND("PROMPT_UPLOAD_COMPLETION_STATUS_NOT_FOUND", 2),
    NO_PARTS("PROMPT_UPLOAD_COMPLETION_STATUS_NO_PARTS", 3),
    SIZE_MISMATCH("PROMPT_UPLOAD_COMPLETION_STATUS_SIZE_MISMATCH", 4),
    INVALID_PARTS("PROMPT_UPLOAD_COMPLETION_STATUS_INVALID_PARTS", 5);

    companion object {
        fun parse(value: JsonPrimitive?): PromptUploadCompletion {
            val text = value?.contentOrNull ?: return UNSPECIFIED
            return entries.firstOrNull { it.wireName == text } ?: text.toIntOrNull()?.let { n -> entries.firstOrNull { it.number == n } } ?: UNSPECIFIED
        }
    }
}

/**
 * The account's staging area for prompt attachments (`aiserver.v1.BackgroundComposerService`): a file is presigned
 * into S3 parts, each `PUT` to its URL, and the upload completed — the id then stands for the bytes in the prompt.
 * An interface so the uploader can be tested against a fake.
 */
interface PromptUploadApi {
    /** `PresignPromptUpload {filename, mime_type, content_length_bytes, team_id?}`: the parts to `PUT`. */
    suspend fun presign(filename: String, mimeType: String, contentLengthBytes: Long, teamId: Int? = null): PresignedPromptUpload

    /** `CompletePromptUpload {upload_id, s3_upload_id}`: the server's verdict once every part is in. */
    suspend fun complete(uploadId: String, s3UploadId: String): PromptUploadCompletion

    /** `AbortPromptUpload {upload_id, s3_upload_id}`: drops a staged upload that will not be referenced. */
    suspend fun abort(uploadId: String, s3UploadId: String)
}

/**
 * The prompt-upload corner of `aiserver.v1.BackgroundComposerService` over Connect JSON, field for field the desktop's
 * `prompt-upload-client.ts` (Cursor 3.20.21): `presignPromptUpload(new LEa({filename, mimeType: mimeType ||
 * "application/octet-stream", contentLengthBytes: BigInt(size), teamId?}))`, the `multipart` case of the reply, then
 * `completePromptUpload(new OEa({uploadId, s3UploadId}))`, and `abortPromptUpload` on failure. Field names are the
 * proto's in lowerCamelCase; the `int64`s travel as decimal strings and are read either way.
 */
class ConnectPromptUploadApi(
    private val rpc: ConnectJsonClient,
    private val tokens: SessionTokenProvider,
) : PromptUploadApi {

    override suspend fun presign(filename: String, mimeType: String, contentLengthBytes: Long, teamId: Int?): PresignedPromptUpload {
        val request = PresignDto(
            filename = filename,
            mimeType = mimeType.ifBlank { OCTET_STREAM },
            contentLengthBytes = contentLengthBytes.toString(),
            teamId = teamId,
        )
        val response = call("PresignPromptUpload", request, PresignDto.serializer(), PresignResponseDto.serializer())
        val uploadId = response.uploadId?.takeIf { it.isNotBlank() } ?: throw ConnectRpcException(200, null, "Cursor answered the upload request without an upload id.")
        val multipart = response.multipart ?: throw ConnectRpcException(200, null, "Prompt upload presign returned no upload instructions.")
        val parts = multipart.parts.mapNotNull { part ->
            val url = part.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PromptUploadPart(
                partNumber = part.partNumber?.asLong()?.toInt() ?: 0,
                url = url,
                offsetBytes = part.offsetBytes?.asLong() ?: 0L,
                sizeBytes = part.sizeBytes?.asLong() ?: 0L,
            )
        }.sortedBy { it.partNumber }
        return PresignedPromptUpload(
            uploadId = uploadId,
            s3UploadId = multipart.s3UploadId.orEmpty(),
            parts = parts,
            partSizeBytes = multipart.partSizeBytes?.asLong() ?: 0L,
            urlsExpireAtMs = response.urlsExpireAtMs?.asLong(),
        )
    }

    override suspend fun complete(uploadId: String, s3UploadId: String): PromptUploadCompletion {
        val response = call("CompletePromptUpload", UploadIdsDto(uploadId, s3UploadId), UploadIdsDto.serializer(), CompleteResponseDto.serializer())
        return PromptUploadCompletion.parse(response.status)
    }

    override suspend fun abort(uploadId: String, s3UploadId: String) {
        call("AbortPromptUpload", UploadIdsDto(uploadId, s3UploadId), UploadIdsDto.serializer(), EmptyDto.serializer())
    }

    private suspend fun <I, O> call(method: String, body: I, requestSerializer: KSerializer<I>, responseSerializer: KSerializer<O>): O =
        rpc.unaryWithSession(BackgroundComposerApi.SERVICE, method, tokens, body, requestSerializer, responseSerializer)

    private fun JsonPrimitive.asLong(): Long? = longOrNull ?: contentOrNull?.toLongOrNull()

    /** `aiserver.v1.PresignPromptUploadRequest {1 filename, 2 mime_type, 3 content_length_bytes, 4 team_id?}`. */
    @Serializable
    private data class PresignDto(val filename: String, val mimeType: String, val contentLengthBytes: String, val teamId: Int? = null)

    /** `aiserver.v1.PresignPromptUploadResponse {1 upload_id, 3 urls_expire_at_ms, 5 multipart}`. */
    @Serializable
    private data class PresignResponseDto(val uploadId: String? = null, val urlsExpireAtMs: JsonPrimitive? = null, val multipart: MultipartDto? = null)

    /** `PresignPromptUploadResponse.Multipart {1 s3_upload_id, 2 parts[], 3 part_size_bytes}`. */
    @Serializable
    private data class MultipartDto(val s3UploadId: String? = null, val parts: List<PartDto> = emptyList(), val partSizeBytes: JsonPrimitive? = null)

    /** `aiserver.v1.PromptUploadPart {1 part_number, 2 url, 3 offset_bytes, 4 size_bytes}`. */
    @Serializable
    private data class PartDto(val partNumber: JsonPrimitive? = null, val url: String? = null, val offsetBytes: JsonPrimitive? = null, val sizeBytes: JsonPrimitive? = null)

    /** `CompletePromptUploadRequest` and `AbortPromptUploadRequest` share `{1 upload_id, 2 s3_upload_id}`. */
    @Serializable
    private data class UploadIdsDto(val uploadId: String, val s3UploadId: String)

    /** `aiserver.v1.CompletePromptUploadResponse {1 status, 2 size_bytes}`. */
    @Serializable
    private data class CompleteResponseDto(val status: JsonPrimitive? = null, val sizeBytes: JsonPrimitive? = null)

    @Serializable
    private class EmptyDto

    private companion object {
        const val OCTET_STREAM = "application/octet-stream"
    }
}
