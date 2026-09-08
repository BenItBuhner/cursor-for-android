package com.cursorforandroid.domain

/**
 * Reads the turns Cursor injects into a conversation on the user's behalf. When a goal continues across turns or a
 * background subagent finishes, Cursor starts the agent's next run with a prompt it wrote for the model — a
 * `<system_notification>` around an `<objective>` or a `<task>` report, a `<timestamp>`, and a `<user_query>`
 * saying how to respond — and `GET /v0/agents/{id}/conversation` hands that prompt back as a `user_message` like any
 * other. Shown verbatim it is a screenful of markup in a prompt bubble; read here, it becomes a [SystemNotification]
 * per block and, only when the turn also carried something the user actually wrote, a [UserMessage] for that.
 *
 * Pure string processing over the markup as Cursor writes it today. Anything unrecognised inside a notification
 * still gets a row — labelled by its `source` when it has one — so a new kind of injection degrades to a short line
 * rather than to the markup.
 */
object SystemNotifications {

    /** What one injected `user_message` stands for: its notifications, then the user's own words if there were any. */
    class Injected(val items: List<TimelineItem>) {
        val notifications: List<SystemNotification> get() = items.filterIsInstance<SystemNotification>()
        val prompt: UserMessage? get() = items.filterIsInstance<UserMessage>().singleOrNull()
    }

    private val dotAll = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    private val notificationBlock = Regex("""<system_notification\b([^>]*)>(.*?)</system_notification\s*>""", dotAll)
    private val timestampBlock = Regex("""<timestamp\b[^>]*>.*?</timestamp\s*>""", dotAll)
    private val reminderBlock = Regex("""<system_reminder\b[^>]*>.*?</system_reminder\s*>""", dotAll)
    private val userQueryBlock = Regex("""<user_query\b[^>]*>(.*?)</user_query\s*>""", dotAll)
    private val taskBlock = Regex("""<task\b[^>]*>(.*?)</task\s*>""", dotAll)
    private val objectiveBlock = Regex("""<objective\b[^>]*>(.*?)</objective\s*>""", dotAll)
    private val sourceAttribute = Regex("""\bsource\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
    /** `key: value` at the start of a `<task>` block; `detail:` runs to the end of the block. */
    private val taskField = Regex("""^([a-z][a-z0-9_]*):[ \t]*(.*)$""")
    /** Any remaining tag of the injected markup, once the blocks with content of their own have been read. */
    private val anyTag = Regex("""</?[a-z_][a-z0-9_-]*\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val whitespace = Regex("""\s+""")
    /** The line a subagent report opens with, and the resume hint it closes with: Cursor's framing, not the report. */
    private val detailPreamble = Regex("""^This is the (?:last|final) output of the [^\n]*:\s*""", RegexOption.IGNORE_CASE)
    private val detailResumeHint = Regex("""\s*Agent ID:\s*\S+(?:\s*\([^)]*\))?\s*$""", RegexOption.IGNORE_CASE)

    /**
     * The `<user_query>` Cursor appends to a notification tells the model how to react to it ("The beginning of the
     * above subagent result is already visible to the user. Perform any follow-up actions…"). One of these phrases
     * marks it; a query without them is something the user typed.
     */
    private val instructionMarkers = listOf(
        "already visible to the user",
        "perform any follow-up actions",
        "do not regurgitate",
        "do not restate prior responses",
        "end your response with a brief third-person confirmation",
    )

    /** Whether [text] is (or contains) an injected notification rather than a prompt the user wrote. */
    fun isInjected(text: String): Boolean = notificationBlock.containsMatchIn(text)

    /**
     * The items [text] stands for, or null when it carries no notification and is the user's prompt as written.
     * Notifications take [id] (suffixed by position when there are several); the user's own words, if any, take
     * `"$id-prompt"`. [timestampMillis] is the paired run's start, as for any prompt.
     */
    fun parse(id: String, text: String, timestampMillis: Long? = null): Injected? {
        val blocks = notificationBlock.findAll(text).toList()
        if (blocks.isEmpty()) return null
        val items = mutableListOf<TimelineItem>()
        blocks.forEachIndexed { index, block ->
            val itemId = if (blocks.size == 1) id else "$id-$index"
            items += notification(itemId, block.value, block.groupValues[1], block.groupValues[2], timestampMillis)
        }
        // Whatever the markup leaves behind is the user's, unless it is the instruction Cursor appends for the model.
        var rest = notificationBlock.replace(text, "")
        rest = timestampBlock.replace(rest, "")
        rest = reminderBlock.replace(rest, "")
        val queries = userQueryBlock.findAll(rest).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() && !isInstruction(it) }.toList()
        rest = userQueryBlock.replace(rest, "").trim()
        val prompt = (queries + listOfNotNull(rest.takeIf { it.isNotEmpty() })).joinToString("\n\n").trim()
        if (prompt.isNotEmpty()) items += UserMessage("$id-prompt", prompt, timestampMillis)
        return Injected(items)
    }

    private fun isInstruction(query: String): Boolean = instructionMarkers.any { query.contains(it, ignoreCase = true) }

    private fun notification(id: String, raw: String, attributes: String, content: String, timestampMillis: Long?): SystemNotification {
        val source = sourceAttribute.find(attributes)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }?.trim()?.lowercase()?.ifEmpty { null }
        val task = taskBlock.find(content)?.let { task(it.groupValues[1]) }
        return when {
            task != null -> task.toNotification(id, raw, timestampMillis)
            source == "goal" -> goal(id, raw, content, timestampMillis)
            else -> other(id, raw, source, content, timestampMillis)
        }
    }

    /**
     * A goal continuation: "Continue working toward the active thread goal." followed by the objective and several
     * paragraphs on how the model should go about it. The objective is the one part the user wrote.
     */
    private fun goal(id: String, raw: String, content: String, timestampMillis: Long?): SystemNotification {
        val objective = objectiveBlock.find(content)?.groupValues?.get(1)?.trim()?.ifEmpty { null }
        val plain = plainText(content)
        val continued = plain.contains("continue working toward", ignoreCase = true)
        val body = objective ?: plain.ifEmpty { null }
        return SystemNotification(
            id = id,
            kind = SystemNotification.Kind.Goal,
            title = if (continued) "Goal continued" else "Goal",
            summary = body?.let(::firstLine),
            body = body,
            raw = raw,
            timestampMillis = timestampMillis,
        )
    }

    /** A notification of a shape not known here: its `source` names it, its first line stands for it. */
    private fun other(id: String, raw: String, source: String?, content: String, timestampMillis: Long?): SystemNotification {
        val plain = plainText(content).ifEmpty { null }
        return SystemNotification(
            id = id,
            kind = SystemNotification.Kind.Other,
            title = source?.let { "${it.replace('_', ' ').replaceFirstChar(Char::uppercase)} notification" } ?: "System notification",
            summary = plain?.let(::firstLine),
            body = plain,
            raw = raw,
            timestampMillis = timestampMillis,
        )
    }

    /** The `key: value` header of a `<task>` report plus its `detail`, which is the finished task's last output. */
    private class Task(val fields: Map<String, String>) {
        val kind: String? get() = fields["kind"]?.lowercase()
        val status: String? get() = fields["status"]?.lowercase()
        val title: String? get() = fields["title"]?.ifBlank { null }
        val detail: String? get() = fields["detail"]?.ifBlank { null }

        fun toNotification(id: String, raw: String, timestampMillis: Long?): SystemNotification {
            val itemKind = when (kind) {
                "subagent", "agent" -> SystemNotification.Kind.Subagent
                else -> SystemNotification.Kind.Task
            }
            val noun = when (itemKind) {
                SystemNotification.Kind.Subagent -> "Subagent"
                else -> kind?.takeIf { it.isNotBlank() }?.replace('_', ' ')?.replaceFirstChar(Char::uppercase) ?: "Task"
            }
            val (verb, tone) = when (status) {
                null, "success", "succeeded", "completed", "complete", "done", "finished", "ok" -> "completed" to NoticeTone.Success
                "error", "errored", "failed", "failure" -> "failed" to NoticeTone.Error
                "cancelled", "canceled", "stopped", "aborted", "interrupted" -> "cancelled" to NoticeTone.Warning
                "timeout", "timed_out", "expired" -> "timed out" to NoticeTone.Warning
                else -> "finished" to NoticeTone.Neutral
            }
            val report = detail?.let(::report)
            return SystemNotification(
                id = id,
                kind = itemKind,
                title = "$noun $verb",
                summary = title ?: report?.let(::firstLine),
                body = report,
                tone = tone,
                raw = raw,
                timestampMillis = timestampMillis,
            )
        }

        /** The task's output without the sentence Cursor opens it with and the resume hint it closes it with. */
        private fun report(detail: String): String? {
            var text = detailPreamble.replace(detail.trim(), "")
            text = detailResumeHint.replace(text, "")
            return text.trim().ifEmpty { null }
        }
    }

    private fun task(block: String): Task? {
        val lines = block.trim().lines()
        val fields = LinkedHashMap<String, String>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) { i++; continue }
            val m = taskField.matchEntire(line) ?: break
            val key = m.groupValues[1].lowercase()
            if (key == "detail") {
                // The report is free text, so it can only be the last field: everything to the end of the block is it.
                fields[key] = (listOf(m.groupValues[2]) + lines.drop(i + 1)).joinToString("\n").trim()
                i = lines.size
                break
            }
            fields[key] = m.groupValues[2].trim()
            i++
        }
        // Text after the header that no key introduced is the report all the same.
        if ("detail" !in fields && i < lines.size) fields["detail"] = lines.drop(i).joinToString("\n").trim()
        return Task(fields).takeIf { fields.isNotEmpty() }
    }

    /** [content] without any of its tags, so a generic notification reads as prose. */
    private fun plainText(content: String): String = anyTag.replace(content, "").trim()

    /** The first non-blank line, as one run of words: what a row can show of a longer text. */
    private fun firstLine(text: String): String =
        (text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: text.trim()).replace(whitespace, " ")
}
