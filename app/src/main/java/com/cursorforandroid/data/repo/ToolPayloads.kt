package com.cursorforandroid.data.repo

import com.cursorforandroid.domain.DiffStats
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolNames
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.ToolPayloadLimits
import com.cursorforandroid.domain.WorkerStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/**
 * Keeps the bytes of an image a tool produced somewhere they can be read back from, and says where: a `file://` URI.
 * Null when they could not be kept, in which case the payload falls back to carrying them inline if they are small.
 */
fun interface GeneratedImageSink {
    fun save(callId: String, bytes: ByteArray, mimeType: String?): String?
}

/** The store behind every agent's [GeneratedImageSink]: the same, filed by agent. */
fun interface GeneratedImageStore {
    fun save(agentId: String, callId: String, bytes: ByteArray, mimeType: String?): String?

    /** The sink for one agent's runs. */
    fun forAgent(agentId: String): GeneratedImageSink = GeneratedImageSink { callId, bytes, mimeType -> save(agentId, callId, bytes, mimeType) }
}

/**
 * Reads what a tool call produced ([ToolPayload]) off its arguments and result. The same reader serves the simplified
 * `tool_call` event — whose `result` carries the payload under a `success` / `value` wrapper when it is small enough —
 * and the `interaction_update` event, whose typed result always does; both are tool-specific and unstable, so every
 * field is looked for under the names the public stream, the desktop build and the SDK use, and a result that has
 * none of them yields nothing.
 */
object ToolPayloads {

    /**
     * The payload for a call of [name] (the public tool name or the SDK's type). [images] keeps a generated image's
     * bytes on the device; without one the image is kept inline when it is small enough to belong in a trace.
     */
    fun from(name: String, args: JsonElement?, result: JsonElement?, images: GeneratedImageSink? = null, callId: String = ""): ToolPayload? {
        val kind = ToolNames.kindOf(name)
        val arguments = args as? JsonObject
        val value = result.resultValue()
        return when (kind) {
            ToolKind.Edit -> diff(arguments, value)
            ToolKind.Create -> written(arguments, value)
            ToolKind.Read -> read(arguments, value)
            ToolKind.Image -> image(arguments, value, images, callId)
            ToolKind.Task -> subagent(arguments, value)
            ToolKind.Question -> question(arguments, value)
            ToolKind.Coordinator -> coordinator(name, arguments, value)
            ToolKind.Other -> if (isRecording(name)) recording(value) else null
            else -> null
        }
    }

    /**
     * A coordinator's tools, by the shapes of `agent/v1/coordinator_tools` and the two message tools (field names in
     * the proto's spelling and the SDK's): `CreateAgentArgs {prompt, name}` → `{agent_id, message}`; `SendToAgentArgs
     * {agent_id, message, delivery, title}` → `{worker_bc_id, delivered_as, message}`; `GetAgentStatusArgs {agent_ids}`
     * → `{workers[{bc_id, name, lifecycle, turn_in_flight, last_terminal_turn_status, pr_url, last_activity_at_ms}],
     * message}`; `StopAgentArgs {agent_id}` → `{worker_bc_id, message}`; `ReadAgentTranscriptArgs {agent_id, mode}` →
     * `{transcript, truncated}`; `SendMessageArgs {text {content} | attachment {url, alt}}` and `SendToUserArgs
     * {message}` (see [coordinatorMessage]).
     */
    private fun coordinator(name: String, args: JsonObject?, value: JsonObject?): ToolPayload? {
        val tool = ToolNames.coordinatorTool(name) ?: return null
        if (tool == ToolNames.USER_MESSAGE_TOOL) {
            val message = coordinatorMessage(args) ?: value?.deepString("message", "text") ?: return null
            return ToolPayload.CoordinatorMessage(ToolPayloadLimits.clip(message).first)
        }
        val note = value?.deepString("message")
        val argAgent = args.string(AGENT_ID_KEYS)
        val resultAgent = value?.deepString("agent_id", "agentId", "worker_bc_id", "workerBcId")
        return when (tool) {
            "create_agent" -> ToolPayload.WorkerAction(
                kind = ToolPayload.WorkerAction.Kind.Created,
                workers = listOf(WorkerStatus(agentId = resultAgent, name = args.string(listOf("name")))),
                text = args.string(listOf("prompt"))?.let { ToolPayloadLimits.clip(it, PROMPT_CHARS).first },
                title = args.string(listOf("name")),
                note = note,
                reported = resultAgent != null,
            )
            "send_to_agent" -> ToolPayload.WorkerAction(
                kind = ToolPayload.WorkerAction.Kind.Messaged,
                workers = listOf(WorkerStatus(agentId = resultAgent ?: argAgent)),
                text = args.string(listOf("message"))?.let { ToolPayloadLimits.clip(it, PROMPT_CHARS).first },
                title = args.string(listOf("title")),
                note = value?.deepString("delivered_as", "deliveredAs")?.let { "Delivered as ${it.lowercase().replace('_', ' ')}" } ?: note,
            )
            "get_agent_status" -> {
                val reported = (value?.deep("workers") as? JsonArray)?.mapNotNull { element ->
                    val w = element as? JsonObject ?: return@mapNotNull null
                    WorkerStatus(
                        agentId = w.string(listOf("bc_id", "bcId", "agent_id", "agentId")),
                        name = w.string(listOf("name")),
                        lifecycle = w.string(listOf("lifecycle")),
                        turnInFlight = w.bool("turn_in_flight", "turnInFlight"),
                        lastTurnStatus = w.string(listOf("last_terminal_turn_status", "lastTerminalTurnStatus")),
                        prUrl = w.string(listOf("pr_url", "prUrl")),
                        lastActivityAtMillis = w.deepLong("last_activity_at_ms", "lastActivityAtMs"),
                    )
                }.orEmpty()
                val asked = (args?.get("agent_ids") as? JsonArray ?: args?.get("agentIds") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                val workers = reported.ifEmpty { asked.map { WorkerStatus(agentId = it) } }
                ToolPayload.WorkerAction(kind = ToolPayload.WorkerAction.Kind.Status, workers = workers, note = note, reported = reported.isNotEmpty())
            }
            "stop_agent" -> ToolPayload.WorkerAction(
                kind = ToolPayload.WorkerAction.Kind.Stopped,
                workers = listOf(WorkerStatus(agentId = resultAgent ?: argAgent)),
                note = note,
            )
            "read_agent_transcript" -> {
                val transcript = value?.deepString("transcript")
                val (excerpt, cut) = transcript?.let { ToolPayloadLimits.clip(it, PROMPT_CHARS) } ?: (null to false)
                ToolPayload.WorkerAction(
                    kind = ToolPayload.WorkerAction.Kind.ReadTranscript,
                    workers = listOf(WorkerStatus(agentId = argAgent)),
                    text = excerpt,
                    title = args.string(listOf("mode")),
                    truncated = cut || value?.deepBool("truncated") == true,
                )
            }
            else -> null
        }
    }

    /**
     * The text of a coordinator's message to the user, under every shape the clients write the two tools' arguments:
     *
     *  - `SendMessageArgs` (`agent.v1`, the `SendMessage` tool) is a oneof — `text {content}` or `attachment {url,
     *    alt}` — so its proto3 JSON is `{"text": {"content": "…"}}`, which is what Cursor's TypeScript writes for it
     *    whether through `toJson()` or `JSON.stringify` (the desktop's own `rawArgs`); the message is `text.content`,
     *    two levels down, never a top-level string. An object spread instead of serialised keeps the oneof as
     *    `{"message": {"case": "text", "value": {"content": "…"}}}`, read here too.
     *  - `SendToUserArgs {message}` (the older `send_to_user`) is the flat `{"message": "…"}`.
     *  - A hand-written stream or an older build's fixture may carry `text`, `content`, `body` or `markdown` flat.
     *
     * An attachment sent instead of text is rendered as the markdown for its link. Null when the arguments carry
     * none of these — nothing was said, or the stream left the arguments out.
     */
    internal fun coordinatorMessage(args: JsonObject?): String? {
        if (args == null) return null
        args.string(MESSAGE_KEYS)?.let { return it }
        // SendMessageArgs as proto3 JSON: the oneof case is the key, the text one level under it.
        (args["text"] as? JsonObject)?.string(listOf("content", "text"))?.let { return it }
        (args["attachment"] as? JsonObject)?.let { attachmentMarkdown(it) }?.let { return it }
        // The oneof kept as {case, value}, or a wrapper object carrying the text under a familiar key.
        (args["message"] as? JsonObject)?.let { message ->
            message.string(listOf("content", "text"))?.let { return it }
            (message["text"] as? JsonObject)?.string(listOf("content", "text"))?.let { return it }
            val case = message.string(listOf("case"))
            val value = message["value"] as? JsonObject
            if (value != null) {
                if (case == "attachment") attachmentMarkdown(value)?.let { return it }
                value.string(listOf("content", "text", "message"))?.let { return it }
                (value["text"] as? JsonObject)?.string(listOf("content"))?.let { return it }
            }
            (message["attachment"] as? JsonObject)?.let { attachmentMarkdown(it) }?.let { return it }
        }
        return null
    }

    /** `SendMessageAttachment {url, alt}` as the markdown that shows it: an image when the URL looks like one, a link otherwise. */
    private fun attachmentMarkdown(attachment: JsonObject): String? {
        val url = attachment.string(listOf("url", "uri", "href")) ?: return null
        val alt = attachment.string(listOf("alt", "title", "name")) ?: "Attachment"
        val image = IMAGE_URL.containsMatchIn(url.substringBefore('?'))
        return if (image) "![$alt]($url)" else "[$alt]($url)"
    }

    private fun diff(args: JsonObject?, value: JsonObject?): ToolPayload? {
        val text = value?.deepString("diffString", "diff", "unifiedDiff", "unified_diff", "patch") ?: return null
        val path = args.string(PATH_KEYS) ?: value.string(PATH_KEYS) ?: return null
        val (clipped, truncated) = ToolPayloadLimits.clip(text)
        val stats = DiffStats.fromToolResult(value)
        return ToolPayload.FileDiff(path, clipped, stats?.additions, stats?.deletions, truncated)
    }

    private fun written(args: JsonObject?, value: JsonObject?): ToolPayload? {
        val path = args.string(PATH_KEYS) ?: value.string(PATH_KEYS) ?: return null
        // What the file holds after the write when the result says, else what the agent asked to be written.
        val text = value?.deepString("fileContentAfterWrite", "file_content_after_write", "content")
            ?: args.string(listOf("fileText", "file_text", "contents", "content", "code_edit"))
            ?: return null
        val (clipped, truncated) = ToolPayloadLimits.clip(text)
        return ToolPayload.FileContent(
            path = path,
            content = clipped,
            kind = ToolPayload.FileContent.Kind.Written,
            totalLines = value?.deepInt("linesCreated", "lines_created", "totalLines", "total_lines"),
            fileSize = value?.deepLong("fileSize", "file_size"),
            truncated = truncated,
        )
    }

    private fun read(args: JsonObject?, value: JsonObject?): ToolPayload? {
        val text = value?.deepString("content", "text", "contents") ?: return null
        val path = args.string(PATH_KEYS) ?: value.string(PATH_KEYS) ?: return null
        val (clipped, truncated) = ToolPayloadLimits.clip(text)
        return ToolPayload.FileContent(
            path = path,
            content = clipped,
            kind = ToolPayload.FileContent.Kind.Read,
            totalLines = value.deepInt("totalLines", "total_lines"),
            fileSize = value.deepLong("fileSize", "file_size"),
            truncated = truncated,
        )
    }

    private fun image(args: JsonObject?, value: JsonObject?, images: GeneratedImageSink?, callId: String): ToolPayload? {
        val path = value?.deepString("filePath", "file_path", "path") ?: args.string(listOf("filePath", "file_path", "path"))
        val description = args.string(listOf("description", "prompt"))
        val data = value?.deepString("imageData", "image_data")
        if (data == null) return if (path == null) null else ToolPayload.GeneratedImage(path, description, src = null)
        return ToolPayload.GeneratedImage(path, description, src = imageSource(data, images, callId))
    }

    /**
     * Where the image's bytes can be read from: the file a sink wrote them to, else the `data:` URI itself when it is
     * small enough to live in a trace, else nothing (the row still says an image was made; the panel cannot show it).
     */
    private fun imageSource(data: String, images: GeneratedImageSink?, callId: String): String? {
        val (mimeType, base64) = splitDataUri(data)
        if (images != null) {
            val bytes = runCatching { Base64.getMimeDecoder().decode(base64.trim()) }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) {
                images.save(callId, bytes, mimeType ?: sniffMimeType(bytes))?.let { return it }
            }
        }
        if (base64.length > ToolPayloadLimits.MAX_INLINE_IMAGE_CHARS) return null
        return if (data.startsWith("data:", ignoreCase = true)) data else "data:${mimeType ?: "image/png"};base64,${base64.trim()}"
    }

    private fun splitDataUri(data: String): Pair<String?, String> {
        val match = DATA_URI.find(data.trim()) ?: return null to data
        return match.groupValues[1].ifBlank { null } to match.groupValues[2]
    }

    /** The image format by its magic bytes, for a payload that names none. */
    fun sniffMimeType(bytes: ByteArray): String? = when {
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
        bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() -> "image/gif"
        bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() -> "image/webp"
        else -> null
    }

    private fun recording(value: JsonObject?): ToolPayload? {
        val path = value?.deepString("path", "filePath", "file_path") ?: return null
        return ToolPayload.Recording(path, value.deepLong("recordingDurationMs", "recording_duration_ms", "durationMs", "duration_ms"))
    }

    private fun subagent(args: JsonObject?, value: JsonObject?): ToolPayload? {
        val description = args.string(listOf("description", "name"))
        val agentId = value?.deepString("agentId", "agent_id") ?: args.string(listOf("agentId", "agent_id"))
        val transcript = value?.deepString("transcriptPath", "transcript_path")
        val subagentType = (args?.get("subagentType") as? JsonObject)?.string(listOf("name", "kind"))
            ?: args.string(listOf("subagentType", "subagent_type"))
        if (description == null && agentId == null && transcript == null) return null
        return ToolPayload.Subagent(
            description = description,
            agentId = agentId,
            transcriptPath = transcript,
            durationMs = value?.deepLong("durationMs", "duration_ms"),
            isBackground = value?.deepBool("isBackground", "is_background") == true,
            subagentType = subagentType,
        )
    }

    /**
     * `ask_question`: the questions come with the call (`agent.v1.AskQuestionArgs`: `title`, `questions[{id, prompt,
     * options[{id, label}], allowMultiple}]`), the answers with its result (`success.answers[{questionId,
     * selectedOptionIds, freeformText}]`). Names vary by client, so each is read under the spellings seen.
     */
    private fun question(args: JsonObject?, value: JsonObject?): ToolPayload? {
        val list = args?.get("questions") as? JsonArray ?: return null
        val items = list.mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: return@mapIndexedNotNull null
            val prompt = obj.string(listOf("prompt", "question", "text", "title", "header")) ?: return@mapIndexedNotNull null
            val options = (obj["options"] as? JsonArray)?.mapIndexedNotNull { i, option ->
                when (option) {
                    is JsonObject -> option.string(listOf("label", "text", "title", "value"))?.let { ToolPayload.Question.Option(option.string(listOf("id")) ?: i.toString(), it) }
                    is JsonPrimitive -> option.contentOrNull?.let { ToolPayload.Question.Option(i.toString(), it) }
                    else -> null
                }
            }.orEmpty()
            ToolPayload.Question.Item(
                id = obj.string(listOf("id")) ?: index.toString(),
                prompt = prompt,
                options = options,
                allowMultiple = obj.bool("allowMultiple", "allow_multiple", "multiSelect", "multi_select") == true,
            )
        }
        if (items.isEmpty()) return null
        val answers = (value?.deep("answers") as? JsonArray)?.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            ToolPayload.Question.Answer(
                questionId = obj.string(listOf("questionId", "question_id", "id")) ?: return@mapNotNull null,
                selectedOptionIds = (obj["selectedOptionIds"] as? JsonArray ?: obj["selected_option_ids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                freeformText = obj.string(listOf("freeformText", "freeform_text", "text")),
            )
        }.orEmpty()
        return ToolPayload.Question(args.string(listOf("title")), items, answers)
    }

    private fun isRecording(name: String): Boolean {
        val n = name.trim().lowercase()
        return n == "record_screen" || n == "recordscreen"
    }

    /**
     * The typed part of a result. The SDK wraps a success in `{status, value}` and the public stream in `{success}`
     * (the desktop, sometimes, in `{result}`; a proto `result` oneof kept as an object, in `{result: {case, value}}`);
     * an error result has no value worth reading, and a result that is not an object says nothing.
     */
    private fun JsonElement?.resultValue(): JsonObject? {
        val obj = this as? JsonObject ?: return null
        if ((obj["status"] as? JsonPrimitive)?.contentOrNull?.equals("error", ignoreCase = true) == true) return null
        (obj["value"] as? JsonObject)?.let { return it }
        (obj["success"] as? JsonObject)?.let { return it }
        (obj["result"] as? JsonObject)?.let { result ->
            val case = (result["case"] as? JsonPrimitive)?.contentOrNull
            if (case != null) return if (case.equals("error", ignoreCase = true)) null else (result["value"] as? JsonObject ?: result)
        }
        return obj
    }

    private fun JsonObject?.string(keys: List<String>): String? {
        if (this == null) return null
        for (key in keys) {
            val value = (this[key] as? JsonPrimitive)?.contentOrNull
            if (!value.isNullOrEmpty()) return value
        }
        return null
    }

    private fun JsonObject?.bool(vararg keys: String): Boolean? {
        if (this == null) return null
        for (key in keys) (this[key] as? JsonPrimitive)?.booleanOrNull?.let { return it }
        return null
    }

    private fun JsonObject.deepString(vararg keys: String): String? {
        for (key in keys) (deep(key) as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotEmpty()) return it }
        return null
    }

    private fun JsonObject?.deepInt(vararg keys: String): Int? {
        if (this == null) return null
        for (key in keys) (deep(key) as? JsonPrimitive)?.intOrNull?.let { return it }
        return null
    }

    private fun JsonObject?.deepLong(vararg keys: String): Long? {
        if (this == null) return null
        for (key in keys) (deep(key) as? JsonPrimitive)?.longOrNull?.let { return it }
        return null
    }

    private fun JsonObject.deepBool(vararg keys: String): Boolean? {
        for (key in keys) (deep(key) as? JsonPrimitive)?.booleanOrNull?.let { return it }
        return null
    }

    /** The value under [key] within two levels: a result may wrap its fields once more (`internal`, `data`). */
    private fun JsonObject.deep(key: String, depth: Int = 0): JsonElement? {
        this[key]?.let { return it }
        if (depth >= 2) return null
        for (value in values) if (value is JsonObject) value.deep(key, depth + 1)?.let { return it }
        return null
    }

    private val PATH_KEYS = listOf("path", "target_file", "targetFile", "file_path", "filePath", "relative_workspace_path", "relativeWorkspacePath", "file", "notebook_path", "absolutePath")
    private val AGENT_ID_KEYS = listOf("agent_id", "agentId")
    /** Where a message tool's text sits when it is a flat string: `SendToUserArgs.message`, and the spellings of hand-written streams. */
    private val MESSAGE_KEYS = listOf("message", "text", "content", "body", "markdown")
    private val IMAGE_URL = Regex("""\.(png|jpe?g|gif|webp|bmp|svg)$""", RegexOption.IGNORE_CASE)
    private val DATA_URI = Regex("""^data:([^;,]*)(?:;[^,]*)?,(.*)$""", RegexOption.DOT_MATCHES_ALL)
    /** A coordinator's prompt to a worker, or a transcript excerpt, is kept to a card's worth; the worker's own chat has the whole. */
    private const val PROMPT_CHARS = 4_000
}
