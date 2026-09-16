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
     * The items as the transcript shows them. Every call is [reinterpret]ed. In a coordinator's chat
     * ([coordinatorMode]) a turn Cursor injected — a subagent's report, a subscribed pull request's change, a timer
     * — that the coordinator answered with a brief remark and nothing to the user has that remark folded under the
     * turn's row ([SystemNotification.narration]) rather than standing as a "Background" line of its own; a turn
     * that spoke to the user, or wrote more than a remark, keeps its rows.
     */
    fun present(items: List<TimelineItem>, coordinatorMode: Boolean): List<TimelineItem> {
        val read = items.map { item -> if (item is ActivityGroup) reinterpret(item) else item }
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
