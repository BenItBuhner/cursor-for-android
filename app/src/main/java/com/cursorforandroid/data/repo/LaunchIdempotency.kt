package com.cursorforandroid.data.repo

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * Derives the client-supplied `agentId` for Create An Agent from the request itself.
 *
 * The API treats a repeated `agentId` as the same create (`409 agent_id_conflict`), so a launch that is retried after
 * a timeout, a dropped connection or a cancel adopts the agent the first attempt may already have created instead of
 * starting a duplicate. Hashing the request means any edit to the draft (text, images, repo, ref, model, options)
 * yields a fresh id, while an unchanged retry keeps the old one. [nonce] separates otherwise identical drafts — the
 * composer rotates it after every successful launch so that sending the same prompt twice on purpose still creates
 * two agents.
 */
object LaunchIdempotency {

    private val ID_PATTERN = Regex("^bc-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    fun agentId(request: LaunchRequest, nonce: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        // Every field is length-prefixed (null is length -1), so no arrangement of contents can hash like another.
        fun bytes(value: ByteArray?) {
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value?.size ?: -1).array())
            if (value != null) digest.update(value)
        }
        fun field(value: String?) = bytes(value?.toByteArray(StandardCharsets.UTF_8))
        field(nonce)
        field(request.prompt)
        field(request.repoUrl)
        field(request.ref)
        field(request.modelId)
        field(request.modelParams.size.toString())
        request.modelParams.forEach { field(it.id); field(it.value) }
        field(request.autoCreatePr.toString())
        field(request.planMode.toString())
        field(request.name)
        field(request.images.size.toString())
        request.images.forEach { image ->
            field(image.mimeType)
            bytes(image.bytes)
        }
        return "bc-" + UUID.nameUUIDFromBytes(digest.digest())
    }

    fun isValid(id: String): Boolean = ID_PATTERN.matches(id)

    fun newNonce(): String = UUID.randomUUID().toString()
}
