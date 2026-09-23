package com.cursorforandroid.domain

/**
 * How a Project coordinator's chat reads, decided from what the transcript itself carries rather than from what the
 * account calls the chat.
 *
 * Cursor's own client reads a Project's conversation under its "sendMessage" model: the coordinator's `SendMessage`
 * tool calls (`agent.v1.SendMessageToolCall`, `SendMessageArgs {text {content}}`) are the messages, the prose it
 * writes between tool calls is demoted to its working notes, and the turns Cursor injects for the model (a
 * subagent's report, a subscribed pull request's change) are rows, not prompts. Whether a chat is a Project's is a
 * fact the account's list carries, and on a large account that word arrives late or not at all; but the tools are in
 * the transcript for anyone to see, so a chat that carries them is read the coordinator's way from the first frame.
 *
 * The same reading is applied to what is already on disk. A trace written by a build that did not know the
 * coordinator's tools filed them under [ToolKind.Other] with nothing read off their arguments; the name is still
 * there, so those calls are re-read on their way to the screen ([reinterpret]), and a message call whose body that
 * build dropped is marked as one without its body ([needsRefresh] says the turn is worth asking for again).
 */
object CoordinatorTranscript {

    /**
     * True when [call] is one of a Project coordinator's tools: by its name under any spelling the streams use
     * (`SendMessage`, `sendMessage`, `send_to_user`, `sendToAgent`, `create_agent`, …), by the kind this build files
     * them under, or by the payload an earlier build read off one.
     */
    fun isCoordinatorCall(call: ToolCall): Boolean =
        call.kind == ToolKind.Coordinator || ToolNames.coordinatorTool(call.name) != null ||
            call.payload is ToolPayload.CoordinatorMessage || call.payload is ToolPayload.WorkerAction

    /** True when [call] is the coordinator's word to the user: the `SendMessage` tool, or the older `send_to_user`. */
    fun isUserMessageCall(call: ToolCall): Boolean =
        call.payload is ToolPayload.CoordinatorMessage || ToolNames.coordinatorTool(call.name) == ToolNames.USER_MESSAGE_TOOL

    /** The chat is a coordinator's by its content: one of the coordinator's tools among its calls, in any turn shown. */
    fun hasCoordinatorContent(items: List<TimelineItem>): Boolean = calls(items).any(::isCoordinatorCall)

    /** True when [items] hold the coordinator's word to the user with its body — a `SendMessage` call, however an earlier build filed it, whose text this copy has. */
    fun hasUserMessage(items: List<TimelineItem>): Boolean = calls(items).any { call ->
        val read = reinterpret(call)
        isUserMessageCall(read) && (read.payload as? ToolPayload.CoordinatorMessage)?.missing != true
    }

    /**
     * True when [items] hold a coordinator's message whose body was read leniently out of arguments that did not
     * parse (see `MessageRecovery`): shown, but worth replacing with the run's own copy while its log lasts.
     */
    fun hasRecoveredMessage(items: List<TimelineItem>): Boolean = calls(items).any { (it.payload as? ToolPayload.CoordinatorMessage)?.recovered == true }

    /**
     * What [items] hold of the coordinator's word to the user, for the diagnostics: `body` (a message with its
     * text), `recovered` (its text read leniently out of a record that did not give it whole), `missing` (the call
     * without its text), `none` (no message call among them); and, after it, whether the call's result was recorded
     * (`result=yes` once the call is completed, `result=no` while it runs or was interrupted).
     */
    fun messageStage(items: List<TimelineItem>): String {
        val calls = calls(items).map(::reinterpret).filter(::isUserMessageCall).toList()
        if (calls.isEmpty()) return "none"
        val payloads = calls.mapNotNull { it.payload as? ToolPayload.CoordinatorMessage }
        val stage = when {
            payloads.any { !it.missing && it.message.isNotBlank() && !it.recovered } -> "body"
            payloads.any { it.recovered && it.message.isNotBlank() } -> "recovered"
            else -> "missing"
        }
        val result = if (calls.any { it.status == ToolCall.STATUS_COMPLETED || it.isError }) "yes" else "no"
        return "$stage result=$result"
    }

    /** The distinct names of the calls that make [hasCoordinatorContent] true, in order of first appearance: the evidence. */
    fun evidence(items: List<TimelineItem>): List<String> = calls(items).filter(::isCoordinatorCall).map { it.name }.distinct().toList()

    /**
     * [call] as this build reads it. A call filed under another kind by an earlier build — the name is a
     * coordinator's tool's, the kind is not — is set right; a message call without a payload (that build did not
     * read the tool) is given one: its body when the build kept the message as the row's detail, else a marker
     * that the body is missing from this copy. Every other call is returned as it is.
     */
    fun reinterpret(call: ToolCall): ToolCall {
        val tool = ToolNames.coordinatorTool(call.name) ?: return call
        var read = call
        if (read.kind != ToolKind.Coordinator) read = read.copy(kind = ToolKind.Coordinator)
        if (tool == ToolNames.USER_MESSAGE_TOOL && read.payload == null && !read.isError) {
            val body = read.detail?.trim()?.takeIf { it.isNotEmpty() }
            read = read.copy(payload = ToolPayload.CoordinatorMessage(body ?: "", missing = body == null))
        }
        return read
    }

    /**
     * True when [items] hold a coordinator's message whose body this copy lacks and a replay could bring: one an
     * earlier build dropped on its way to disk. A message the stream itself left out for size (`truncated.args`)
     * is not one — asking again would bring the same frame.
     */
    fun needsRefresh(items: List<TimelineItem>): Boolean = calls(items).any { call ->
        if (call.truncated?.args == true) return@any false
        val payload = reinterpret(call).payload
        payload is ToolPayload.CoordinatorMessage && payload.missing
    }

    /**
     * True when [items] hold a call of the coordinator's message tool at all — with its body, without it, or in
     * pieces — as against a turn that never called it (see `ConversationRepository.recordItems`: a turn the
     * account's record shows without the call sent nothing, whatever a run's log says).
     */
    fun hasMessageCall(items: List<TimelineItem>): Boolean = calls(items).any { isUserMessageCall(reinterpret(it)) }

    /** The key a message call is drawn under (see `TranscriptRow.Message.key`): its group's id and its own. */
    fun messageKey(group: ActivityGroup, call: ToolCall): String = "${group.id}:${call.callId}"

    /**
     * The message calls of [items] that say again what an earlier one among them said, each by its [messageKey],
     * mapped to the id of the call it repeats. Two calls carry the same message when they read the same and are
     * the same call — the same id — or the same delivered message — the same `SendMessageResult.success.messageId`,
     * when both have one. The first of them is the message; the rest are copies.
     *
     * Where copies come from: a run's log is the run's own events, and a Project coordinator's log can carry the
     * last message the coordinator sent ahead of the run's own events although the run sent nothing — the same
     * `SendMessage`, id and result included (Bennett's 2026-09-19 frame, the seven-run fixture). Drawn as it came,
     * that was one reply per run under it. The text is always part of the identity and never the whole of it: the
     * stream's call ids are positional (`turn-N:step:M:tool`) and a fixture or a server may hand out one message
     * id twice, so two different messages must never be taken for one because a name came round; and a coordinator
     * is free to say the same words twice in two calls of its own. Nothing is read from anywhere else and no text
     * is made up: a copy is left out, that is all.
     */
    fun repeatedMessages(items: List<TimelineItem>): Map<String, String> {
        var repeats: MutableMap<String, String>? = null
        var seen: MutableMap<String, String>? = null
        for (item in items) {
            if (item !is ActivityGroup) continue
            for (step in item.steps) {
                if (step !is ToolCall) continue
                val call = reinterpret(step)
                val payload = call.payload as? ToolPayload.CoordinatorMessage ?: continue
                if (payload.missing && payload.message.isBlank()) continue
                val text = normalize(payload.message)
                val names = listOfNotNull(
                    call.callId.takeIf { it.isNotBlank() }?.let { "call\u0000$it\u0000$text" },
                    payload.messageId?.takeIf { it.isNotBlank() }?.let { "message\u0000$it\u0000$text" },
                )
                if (names.isEmpty()) continue
                val original = names.firstNotNullOfOrNull { seen?.get(it) }
                if (original != null) {
                    (repeats ?: LinkedHashMap<String, String>().also { repeats = it })[messageKey(item, call)] = original
                    continue
                }
                val known = seen ?: HashMap<String, String>().also { seen = it }
                names.forEach { known[it] = call.callId }
            }
        }
        return repeats ?: emptyMap()
    }

    /** Everything [items] leave out as said again: the message copies ([repeatedMessages]) and the replayed activity ([replayedActivity]), by key. */
    fun leftOut(items: List<TimelineItem>): Set<String> {
        val messages = repeatedMessages(items)
        val replays = replayedActivity(items)
        return when {
            replays.isEmpty() -> messages.keys
            messages.isEmpty() -> replays.keys
            else -> messages.keys + replays.keys
        }
    }

    /** The key a whole item is left out under (see [replayedActivity]): `item:` and its id. */
    fun itemKey(item: TimelineItem): String = "item:${item.id}"

    /**
     * Every call and item of [items] that is an earlier run's activity said again, by the key it is left out under
     * ([messageKey] for a call, [itemKey] for a whole item), mapped to the run the original was drawn in.
     *
     * Bennett's 2026-09-20 export settled it: a silent turn's log replays the previous turn's *whole* activity, not
     * its message alone — eleven tool calls with the very ids the turn before had drawn, then the same assistant text,
     * under a footer of its own. [repeatedMessages] caught the message and left the other ten calls and the text
     * to be drawn twice. Here every tool call whose id (and tool) an earlier run's group already drew is a replay;
     * a group whose calls are all replays goes whole, its thoughts with it (the thoughts came with the calls); and
     * an assistant text that reads as one an earlier run drew goes when the run it sits in has no call of its own —
     * a silent run whose log carried the last turn's words and nothing else (the export's third `assistant
     * chars=239` under a 4 s footer). A run with new calls keeps its text: a coordinator may say the same words
     * twice. Footers are never a replay: the silent run did run, and its footer and stretch are its own. Runs are
     * cut at their footers; the items after the last footer are the run under way.
     */
    /**
     * The identity a call is drawn once under: its id and tool, and the arguments as the row reads them. A replayed
     * event carries all four again; a call that merely shares an id with an earlier one (an id scheme counting from
     * one per run) does not, and is drawn.
     */
    private fun callKey(call: ToolCall): String = "${call.callId}\u0000${call.name.lowercase()}\u0000${call.summary}\u0000${call.detail ?: ""}"

    fun replayedActivity(items: List<TimelineItem>): Map<String, String> {
        var out: MutableMap<String, String>? = null
        // Where each call id was first drawn: by group, and the run's footer id once known (the original's run).
        val seenCalls = HashMap<String, String>()
        val seenTexts = HashMap<String, String>()
        var start = 0
        var runNo = 0
        while (start < items.size) {
            var end = start
            while (end < items.size && items[end] !is RunFooter) end++
            val run = items.subList(start, minOf(end + 1, items.size))
            val runName = (run.lastOrNull() as? RunFooter)?.let { "run:${it.runId}" } ?: "run#$runNo"
            // First pass: which calls of this run were drawn before, and whether any call is the run's own.
            var own = false
            val replayed = HashSet<String>()
            for (item in run) {
                if (item !is ActivityGroup) continue
                for (step in item.steps) {
                    if (step !is ToolCall || step.callId.isBlank()) continue
                    val key = callKey(step)
                    if (seenCalls.containsKey(key)) replayed += messageKey(item, step) else own = true
                }
            }
            // Second pass: the calls and items left out, and what this run adds to what has been drawn.
            for (item in run) {
                when (item) {
                    is ActivityGroup -> {
                        val calls = item.steps.filterIsInstance<ToolCall>()
                        val gone = calls.filter { messageKey(item, it) in replayed }
                        if (gone.isEmpty()) {
                            calls.forEach { seenCalls.putIfAbsent(callKey(it), runName) }
                            continue
                        }
                        val map = out ?: LinkedHashMap<String, String>().also { out = it }
                        if (gone.size == calls.size) {
                            map[itemKey(item)] = seenCalls.getValue(callKey(gone.first()))
                        } else {
                            gone.forEach { map[messageKey(item, it)] = seenCalls.getValue(callKey(it)) }
                            calls.filter { messageKey(item, it) !in replayed }.forEach { seenCalls.putIfAbsent(callKey(it), runName) }
                        }
                    }
                    is AssistantMessage -> {
                        val text = normalize(item.markdown)
                        if (text.isEmpty()) continue
                        val earlier = seenTexts[text]
                        if (earlier != null && !own && earlier != runName) {
                            (out ?: LinkedHashMap<String, String>().also { out = it })[itemKey(item)] = earlier
                        } else {
                            seenTexts.putIfAbsent(text, runName)
                        }
                    }
                    else -> Unit
                }
            }
            start = end + 1
            runNo++
        }
        return out ?: emptyMap()
    }

    /**
     * [items] without the message calls keyed in [repeats] (see [repeatedMessages]) and without the calls and items
     * keyed as replays (see [replayedActivity]): a group loses the call, a group left with nothing goes, an item
     * keyed whole goes. The same list when nothing is left out.
     */
    fun withoutRepeats(items: List<TimelineItem>, repeats: Set<String>): List<TimelineItem> {
        if (repeats.isEmpty()) return items
        var changed = false
        val out = ArrayList<TimelineItem>(items.size)
        for (item in items) {
            if (itemKey(item) in repeats) { changed = true; continue }
            if (item !is ActivityGroup || item.steps.none { it is ToolCall && messageKey(item, it) in repeats }) {
                out += item
                continue
            }
            changed = true
            val steps = item.steps.filterNot { it is ToolCall && messageKey(item, it) in repeats }
            if (steps.isNotEmpty()) out += item.copy(steps = steps)
        }
        return if (changed) out else items
    }

    /** The message texts of [items] as they read once whitespace is normalised, for telling a copy from its message. */
    fun messageTexts(items: List<TimelineItem>): Set<String> {
        var out: MutableSet<String>? = null
        for (call in calls(items)) {
            val payload = (reinterpret(call).payload as? ToolPayload.CoordinatorMessage) ?: continue
            if (payload.missing && payload.message.isBlank()) continue
            (out ?: HashSet<String>().also { out = it }) += normalize(payload.message)
        }
        return out ?: emptySet()
    }

    /** The message calls of [items] whose text, normalised, is among [texts]: the copies of messages already shown. */
    fun messageCallsReading(items: List<TimelineItem>, texts: Set<String>): Set<String> =
        if (texts.isEmpty()) emptySet() else messageCallsReading(items) { it in texts }

    /** The message calls of [items] whose text, normalised, [reads] says is another's, by [messageKey]. */
    fun messageCallsReading(items: List<TimelineItem>, reads: (String) -> Boolean): Set<String> {
        var out: MutableSet<String>? = null
        for (item in items) {
            if (item !is ActivityGroup) continue
            for (step in item.steps) {
                if (step !is ToolCall) continue
                val payload = (reinterpret(step).payload as? ToolPayload.CoordinatorMessage) ?: continue
                if (payload.missing && payload.message.isBlank()) continue
                if (reads(normalize(payload.message))) (out ?: HashSet<String>().also { out = it }) += messageKey(item, step)
            }
        }
        return out ?: emptySet()
    }

    /** The ids of the message calls of [items] keyed in [keys] (see [messageKey]), in order, for the diagnostics. */
    fun messageCallIds(items: List<TimelineItem>, keys: Set<String>): List<String> {
        if (keys.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        for (item in items) {
            if (item !is ActivityGroup) continue
            for (step in item.steps) if (step is ToolCall && messageKey(item, step) in keys) out += step.callId
        }
        return out
    }

    /** [text] as compared: trimmed, every run of whitespace one space. */
    fun normalize(text: String): String = text.trim().replace(WHITESPACE, " ")

    private val WHITESPACE = Regex("\\s+")

    /**
     * The items as the transcript shows them. Every call is [reinterpret]ed; the message calls keyed in [repeats]
     * — copies of a message drawn earlier (see [repeatedMessages]; the copies within [items] alone unless the
     * caller, seeing the whole transcript, says which) — are left out. In a coordinator's chat ([coordinatorMode])
     * a turn Cursor injected — a subagent's report, a subscribed pull request's change, a timer — that the
     * coordinator answered with a brief remark and nothing to the user has that remark folded under the turn's row
     * ([SystemNotification.narration]) rather than standing as a "Background" line of its own; a turn that spoke
     * to the user, or wrote more than a remark, keeps its rows.
     */
    fun present(items: List<TimelineItem>, coordinatorMode: Boolean, repeats: Set<String> = leftOut(items)): List<TimelineItem> {
        val once = withoutRepeats(items, repeats)
        val read = once.map { item -> if (item is ActivityGroup) reinterpret(item) else item }
        return if (coordinatorMode) foldRemarks(read) else read
    }

    /** [group] with its calls re-read; the same instance when none of them changes. */
    private fun reinterpret(group: ActivityGroup): ActivityGroup {
        var changed = false
        val steps = group.steps.map { step ->
            if (step !is ToolCall) return@map step
            val read = reinterpret(step)
            if (read !== step) changed = true
            read
        }
        return if (changed) group.copy(steps = steps) else group
    }

    private fun foldRemarks(items: List<TimelineItem>): List<TimelineItem> {
        if (items.none { it is SystemNotification }) return items
        val out = ArrayList<TimelineItem>(items.size)
        var i = 0
        while (i < items.size) {
            if (items[i] !is SystemNotification) {
                out += items[i]
                i++
                continue
            }
            // An injected turn: its rows, then what the agent did until its run's footer, or the next prompt or
            // injected turn when the run has none.
            var j = i
            while (j < items.size && items[j] is SystemNotification) j++
            var k = j
            while (k < items.size && items[k] !is UserMessage && items[k] !is SystemNotification) {
                k++
                if (items[k - 1] is RunFooter) break
            }
            val head = items.subList(i, j)
            val body = items.subList(j, k)
            out += fold(head, body) ?: (head + body)
            i = k
        }
        return out
    }

    /** The turn with its remark folded under its last row, or null when the turn is not one to fold. */
    private fun fold(head: List<TimelineItem>, body: List<TimelineItem>): List<TimelineItem>? {
        if (body.any { it is ActivityGroup && it.calls.any(::isUserMessageCall) }) return null
        val replies = body.filterIsInstance<AssistantMessage>()
        if (replies.isEmpty() || replies.any { it.isStreaming }) return null
        val texts = replies.map { it.markdown.trim() }.filter { it.isNotEmpty() }
        if (!isRemark(texts)) return null
        val row = head.last() as SystemNotification
        val narration = texts.joinToString("\n\n").ifEmpty { null }
        return head.dropLast(1) + row.copy(narration = narration) + body.filterNot { it is AssistantMessage }
    }

    /** A brief remark: a confirmation, a note that nothing needs doing — not a reply the reader would miss. */
    private fun isRemark(texts: List<String>): Boolean {
        val total = texts.sumOf { it.length }
        val lines = texts.sumOf { text -> text.lineSequence().count { it.isNotBlank() } }
        return total <= REMARK_MAX_CHARS && lines <= REMARK_MAX_LINES
    }

    private fun calls(items: List<TimelineItem>): Sequence<ToolCall> =
        items.asSequence().filterIsInstance<ActivityGroup>().flatMap { it.calls.asSequence() }

    /** As long as a remark gets: two or three sentences, the "brief third-person confirmation" Cursor asks the model for. */
    const val REMARK_MAX_CHARS = 320
    const val REMARK_MAX_LINES = 4
}
