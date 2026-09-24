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
    /** One `key="value"` (or single-quoted) attribute of the notification's opening tag. */
    private val attribute = Regex("""\b([a-z_][a-z0-9_-]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
    /** An HTML entity as Cursor escapes the titles it writes into the markup: `&amp;`, `&lt;`, `&#39;`, `&#x2F;`. */
    private val entity = Regex("""&(#x[0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z][a-zA-Z0-9]{1,10});""")
    private val namedEntities = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0", "hellip" to "\u2026", "mdash" to "\u2014", "ndash" to "\u2013", "lsquo" to "\u2018", "rsquo" to "\u2019", "ldquo" to "\u201C", "rdquo" to "\u201D", "copy" to "\u00A9", "trade" to "\u2122")
    /** `key: value` at the start of a `<task>` block; `detail:` runs to the end of the block. */
    private val taskField = Regex("""^([a-z][a-z0-9_]*):[ \t]*(.*)$""")
    /** Any remaining tag of the injected markup, once the blocks with content of their own have been read. */
    private val anyTag = Regex("""</?[a-z_][a-z0-9_-]*\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val whitespace = Regex("""\s+""")
    /** The line a subagent report opens with, and the resume hint it closes with: Cursor's framing, not the report. */
    private val detailPreamble = Regex("""^This is the (?:last|final) output of the [^\n]*:\s*""", RegexOption.IGNORE_CASE)
    private val detailResumeHint = Regex("""\s*Agent ID:\s*\S+(?:\s*\([^)]*\))?\s*$""", RegexOption.IGNORE_CASE)
    /** The cloud agent a report names, anywhere in the block: `Agent ID: bc-…`, `agent_id: bc-…`, `bc_id: bc-…`. */
    private val agentIdMention = Regex("""\b(?:agent[ _]?id|bc[ _]?id|worker[ _]?id)\s*[:=]\s*["'`]?(bc-[A-Za-z0-9-]+)""", RegexOption.IGNORE_CASE)

    /**
     * The `<user_query>` Cursor appends after a notification is its instruction to the model on how to react, not
     * something the user typed. Two templates are in use (both read off a coordinator's real transcript):
     *
     *  - "The beginning of the above subagent result is already visible to the user. Perform any follow-up actions
     *    (if needed). DO NOT regurgitate or reiterate its result unless asked. … end your response with a brief
     *    third-person confirmation … Don't repeat the same confirmation every time."
     *  - "Perform any necessary follow-up actions in response to the subagent completion above. If no follow-up work
     *    is needed, no further action is required. If you mention an agent or subagent in your response, link it with
     *    the `[Name](id)` … Don't repeat the same confirmation every time."
     *
     * Each is a sentence of the instruction, as a pattern that survives the wording moving around a little ("any
     * follow-up actions" / "any necessary follow-up actions", "subagent" / "task" / "worker"). A query is the
     * instruction when it sits in a turn that carries a notification — the structure — and matches one of these; a
     * query in such a turn that matches none is the user's own words and stays a prompt.
     */
    private val instructionPatterns: List<Pair<Regex, Int>> = listOf(
        // Sentences only Cursor writes to a model: one is enough.
        Regex("""\balready visible to the user\b""", RegexOption.IGNORE_CASE) to 2,
        Regex("""\bdo not regurgitate\b""", RegexOption.IGNORE_CASE) to 2,
        Regex("""\bdo not restate prior responses\b""", RegexOption.IGNORE_CASE) to 2,
        Regex("""\bend your response with a brief third-person confirmation\b""", RegexOption.IGNORE_CASE) to 2,
        Regex("""\bdon'?t repeat the same confirmation\b""", RegexOption.IGNORE_CASE) to 2,
        Regex("""\bif you were already aware, ignore this notification\b""", RegexOption.IGNORE_CASE) to 2,
        // Sentences a user could conceivably write: two of them together make the instruction.
        Regex("""\bperform any (?:\w+ )?follow-?up actions?\b""", RegexOption.IGNORE_CASE) to 1,
        Regex("""\bin response to the (?:\w+ )?(?:completion|result|notification|report|update) above\b""", RegexOption.IGNORE_CASE) to 1,
        Regex("""\bif no follow-?up (?:work|actions?) (?:is|are) needed\b""", RegexOption.IGNORE_CASE) to 1,
        Regex("""\bno further action is required\b""", RegexOption.IGNORE_CASE) to 1,
        Regex("""\blink it with the `?\[(?:name|label)\]\(id\)`?""", RegexOption.IGNORE_CASE) to 1,
        Regex("""\bdon'?t use generic labels? such as\b""", RegexOption.IGNORE_CASE) to 1,
    )

    /** The row's title for a turn Cursor started to keep working toward the goal (`source="goal"`, "Continue working toward…"). */
    const val GOAL_CONTINUED = "Goal continued"

    /** Whether [text] is (or contains) an injected notification rather than a prompt the user wrote. */
    fun isInjected(text: String): Boolean = notificationBlock.containsMatchIn(text)

    /**
     * Whether [query] — the text of a `<user_query>` in a turn that carries a notification — is Cursor's instruction
     * to the model rather than the user's words: a sentence only Cursor writes to a model, or two of the ones a user
     * might. No real prompt reads like that; a query in such a turn that matches none is the user's.
     */
    fun isInstruction(query: String): Boolean = instructionPatterns.sumOf { (pattern, weight) -> if (pattern.containsMatchIn(query)) weight else 0 } >= 2

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
        if (prompt.isNotEmpty()) items += UserMessage("$id-prompt", unescape(prompt), timestampMillis)
        return Injected(items)
    }

    private fun notification(id: String, raw: String, attributes: String, content: String, timestampMillis: Long?): SystemNotification {
        val attrs = attributes(attributes)
        val source = attrs["source"]?.lowercase()?.ifEmpty { null }
        val task = taskBlock.find(content)?.let { task(it.groupValues[1]) }
        val agentId = agentIdMention.find(content)?.groupValues?.get(1)
        return when {
            task != null -> task.toNotification(id, raw, timestampMillis, source, agentId)
            source == "goal" -> goal(id, raw, content, timestampMillis)
            source == "github" -> github(id, raw, attrs, content, timestampMillis)
            else -> other(id, raw, source, content, timestampMillis, agentId)
        }
    }

    /** The tag's `key="value"` attributes, keys lowercased, values with their entities decoded. */
    private fun attributes(attributes: String): Map<String, String> =
        attribute.findAll(attributes).associate { m -> m.groupValues[1].lowercase() to unescape(m.groupValues[2].ifEmpty { m.groupValues[3] }) }

    /**
     * A subscribed pull request's change (`source="github" pr="…/pull/59" action="synchronize" sender="cursor[bot]"`):
     * the body is one boilerplate sentence, so the row is made from the attributes — "#59 synchronize · cursor[bot]" —
     * and opens onto the sentence and the pull request's URL.
     */
    private fun github(id: String, raw: String, attrs: Map<String, String>, content: String, timestampMillis: Long?): SystemNotification {
        val pr = attrs["pr"]?.trim()?.takeIf { it.isNotEmpty() }
        val number = pr?.substringAfterLast('/', "")?.takeIf { it.all(Char::isDigit) && it.isNotEmpty() }
        val action = attrs["action"]?.trim()?.replace('_', ' ')?.takeIf { it.isNotEmpty() }
        val sender = attrs["sender"]?.trim()?.takeIf { it.isNotEmpty() }
        val plain = plainText(content).ifEmpty { null }
        val summary = listOfNotNull(number?.let { "#$it" }, action, sender).joinToString(" \u00B7 ").ifEmpty { null } ?: plain?.let(::firstLine)
        val body = listOfNotNull(plain, pr).joinToString("\n\n").ifEmpty { null }
        return SystemNotification(
            id = id,
            kind = SystemNotification.Kind.Other,
            title = "GitHub notification",
            summary = summary,
            body = body,
            raw = raw,
            timestampMillis = timestampMillis,
        )
    }

    /**
     * A goal continuation: "Continue working toward the active thread goal." followed by the objective and several
     * paragraphs on how the model should go about it. The objective is the one part the user wrote.
     */
    private fun goal(id: String, raw: String, content: String, timestampMillis: Long?): SystemNotification {
        val objective = objectiveBlock.find(content)?.groupValues?.get(1)?.trim()?.ifEmpty { null }?.let(::unescape)
        val plain = plainText(content)
        val continued = plain.contains("continue working toward", ignoreCase = true)
        val body = objective ?: plain.ifEmpty { null }
        return SystemNotification(
            id = id,
            kind = SystemNotification.Kind.Goal,
            title = if (continued) GOAL_CONTINUED else "Goal",
            summary = body?.let(::firstLine),
            body = body,
            raw = raw,
            timestampMillis = timestampMillis,
        )
    }

    /**
     * A notification of a shape not known here: its `source` names it, its first line stands for it. One that names a
     * cloud agent — a Project's worker writing to its coordinator outside a `<task>` report — is that worker's notice.
     */
    private fun other(id: String, raw: String, source: String?, content: String, timestampMillis: Long?, agentId: String?): SystemNotification {
        val plain = plainText(content).ifEmpty { null }
        val worker = agentId != null || source in WORKER_SOURCES
        return SystemNotification(
            id = id,
            kind = if (worker) SystemNotification.Kind.Worker else SystemNotification.Kind.Other,
            title = if (worker) "Worker update" else source?.let { "${it.replace('_', ' ').replaceFirstChar(Char::uppercase)} notification" } ?: "System notification",
            summary = plain?.let(::firstLine),
            body = plain,
            raw = raw,
            timestampMillis = timestampMillis,
            agentId = agentId,
        )
    }

    /** `source` attributes under which a Project's workers report to their coordinator. */
    private val WORKER_SOURCES = setOf("worker", "agent", "cloud_agent", "project_worker", "worker_agent", "child_agent")

    /** The `key: value` header of a `<task>` report plus its `detail`, which is the finished task's last output. */
    private class Task(val fields: Map<String, String>) {
        val kind: String? get() = fields["kind"]?.lowercase()
        val status: String? get() = fields["status"]?.lowercase()
        val title: String? get() = fields["title"]?.ifBlank { null }?.let(::unescape)
        val detail: String? get() = fields["detail"]?.ifBlank { null }?.let(::unescape)

        fun toNotification(id: String, raw: String, timestampMillis: Long?, source: String?, mentionedAgentId: String?): SystemNotification {
            // The cloud agent the report names — `agent_id:` in the header, `Agent ID: bc-…` in the resume hint — is
            // the one the row opens. A `subagent` stays a subagent (a cloud one has a chat of its own all the same);
            // an `agent` or a `worker` with an id is a Project's primary reporting to its coordinator.
            val agentId = fields.entries.firstOrNull { (key, _) -> key in AGENT_ID_FIELDS }?.value?.trim()?.takeIf { it.startsWith("bc-") } ?: mentionedAgentId
            val itemKind = when {
                kind in WORKER_KINDS || source in WORKER_SOURCES -> SystemNotification.Kind.Worker
                kind == "agent" && agentId != null -> SystemNotification.Kind.Worker
                kind == "subagent" || kind == "agent" -> SystemNotification.Kind.Subagent
                else -> SystemNotification.Kind.Task
            }
            val noun = when (itemKind) {
                SystemNotification.Kind.Worker -> "Worker"
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
                summary = title ?: fields["name"]?.ifBlank { null }?.let(::unescape) ?: report?.let(::firstLine),
                body = report,
                tone = tone,
                raw = raw,
                timestampMillis = timestampMillis,
                agentId = agentId,
                callId = fields["tool_call_id"]?.trim()?.takeIf { it.isNotEmpty() },
            )
        }

        /** The task's output without the sentence Cursor opens it with and the resume hint it closes it with. */
        private fun report(detail: String): String? {
            var text = detailPreamble.replace(detail.trim(), "")
            text = detailResumeHint.replace(text, "")
            return text.trim().ifEmpty { null }
        }
    }

    /** `kind:` values under which a Project's worker reports; a bare `agent` is a subagent unless an id says otherwise. */
    private val WORKER_KINDS = setOf("worker", "cloud_agent", "project_worker", "worker_agent", "primary", "background_agent")
    /** Header fields that carry the reporting agent's id. */
    private val AGENT_ID_FIELDS = setOf("agent_id", "agentid", "bc_id", "bcid", "worker_id", "workerid", "id")

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

    /** [content] without any of its tags and with its entities decoded, so a generic notification reads as prose. */
    private fun plainText(content: String): String = unescape(anyTag.replace(content, "").trim())

    /** The first non-blank line, as one run of words: what a row can show of a longer text. */
    private fun firstLine(text: String): String =
        (text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: text.trim()).replace(whitespace, " ")

    /**
     * [text] with its HTML entities decoded. Cursor escapes what it writes into the markup — a task titled
     * "Hand & Arm Renders" arrives as `Hand &amp; Arm Renders` — and the rows show words, not markup. An entity this
     * does not know is left as it is.
     */
    fun unescape(text: String): String {
        if (!text.contains('&')) return text
        return entity.replace(text) { m ->
            val ref = m.groupValues[1]
            when {
                ref.startsWith("#x", ignoreCase = true) -> ref.substring(2).toIntOrNull(16)?.let(::codePoint) ?: m.value
                ref.startsWith("#") -> ref.substring(1).toIntOrNull()?.let(::codePoint) ?: m.value
                else -> namedEntities[ref] ?: namedEntities[ref.lowercase()] ?: m.value
            }
        }
    }

    private fun codePoint(value: Int): String? = if (value in 1..0x10FFFF && value !in 0xD800..0xDFFF) String(Character.toChars(value)) else null
}
