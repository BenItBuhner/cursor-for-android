package com.cursorforandroid.data.api.proto

import com.cursorforandroid.data.api.proto.ProtoWire.Field
import com.cursorforandroid.data.api.proto.ProtoWire.Kind
import com.cursorforandroid.data.api.proto.ProtoWire.Schema

/**
 * The `agent.v1` messages the account's blob-backed record is made of, as the descriptors of Cursor 3.21.16's
 * desktop bundle give them (`workbench.desktop.main.js`, `T.makeMessageType("agent.v1.…", …)`), in the corner this
 * app reads: a turn's structure, its steps, the user's message, and each tool call with the arguments and results
 * the payload mappers read. Field numbers are the wire's; names are the proto3 JSON names (lowerCamelCase), which
 * is the spelling the SDK-shape stream events carry and `ToolPayloads` / `ToolCallMapper` already read.
 *
 * A message named here with no fields is one this build does not read into: its fields come back as
 * `_unknownFields` (see [ProtoWire.UNKNOWN_FIELDS]), so the transcript diagnostics can say what it held. The tool
 * call's oneof ([TOOL_CALL]) lists every variant the desktop knows by number and name; a variant without a schema of
 * its own decodes to its name alone, and shows as a call of that name with no payload.
 */
object AgentSchemas {

    private fun msg(name: String, vararg fields: Field) = Schema(name, fields.toList())
    private fun str(no: Int, name: String, repeated: Boolean = false) = Field(no, name, Kind.STRING, repeated)
    private fun bytes(no: Int, name: String, repeated: Boolean = false) = Field(no, name, Kind.BYTES, repeated)
    private fun bool(no: Int, name: String) = Field(no, name, Kind.BOOL)
    private fun i32(no: Int, name: String) = Field(no, name, Kind.INT32)
    private fun u32(no: Int, name: String, repeated: Boolean = false) = Field(no, name, Kind.UINT32, repeated)
    private fun i64(no: Int, name: String) = Field(no, name, Kind.INT64)
    private fun u64(no: Int, name: String) = Field(no, name, Kind.UINT64)
    private fun enum(no: Int, name: String) = Field(no, name, Kind.ENUM)
    private fun sub(no: Int, name: String, repeated: Boolean = false, schema: () -> Schema) = Field(no, name, Kind.MESSAGE, repeated, schema)
    /** A `map<string, …>` field: [value] is the entry's value field (number 2), a scalar or a message. */
    private fun map(no: Int, name: String, value: Field) = Field(no, name, Kind.MAP, false) { msg("entry", str(1, "key"), value) }
    private fun opaque(name: String) = msg(name)

    // -- the record's structure ---------------------------------------------------------------------------------

    /**
     * `agent.v1.ConversationStateStructure`, in the fields the record reads: the turns (blob ids, oldest first), their
     * timings, the pending calls, whether the chat is a Project's root. For a state that comes as a blob rather than
     * inline on `StreamConversation`'s `initial_state` (see `HeadlessConversationApi.state`).
     */
    val CONVERSATION_STATE: Schema = msg(
        "agent.v1.ConversationStateStructure",
        str(4, "pendingToolCalls", repeated = true),
        bytes(8, "turns", repeated = true),
        enum(10, "mode"),
        sub(14, "turnTimings", repeated = true) { msg("agent.v1.StepTiming", u64(1, "durationMs"), u64(2, "timestampMs")) },
        u64(26, "conversationStartedTimestampMs"),
        map(29, "communicateUpdateStatesByParentToolCallId", sub(2, "value") {
            msg(
                "agent.v1.CommunicateUpdateTurnState",
                sub(1, "history", repeated = true) { msg("agent.v1.CommunicateUpdateHistoryEntry", str(1, "step"), u32(3, "messageIndex")) },
                str(2, "finalSummary"),
                str(3, "completedSubtitle"),
            )
        }),
        map(30, "subagentRunsByParentToolCallId", sub(2, "value") {
            msg(
                "agent.v1.SubagentRunState",
                str(1, "parentToolCallId"), str(2, "subagentId"), enum(3, "environment"), enum(4, "status"), str(5, "title"),
                str(6, "detail"), str(7, "transcriptPath"), str(8, "outputPath"), u64(9, "completedTimestampMs"), str(10, "completionReason"),
            )
        }),
        bool(33, "isRootProjectConversation"),
    )

    /** `agent.v1.ConversationTurnStructure`: one turn blob (`ConversationStateStructure.turns[i]` names it). */
    val CONVERSATION_TURN: Schema = msg(
        "agent.v1.ConversationTurnStructure",
        sub(1, "agentConversationTurn") { AGENT_TURN },
        sub(2, "shellConversationTurn") { opaque("agent.v1.ShellConversationTurnStructure") },
    )

    /** `agent.v1.AgentConversationTurnStructure`: the user's message and the steps, each a blob id; the message steps by index. */
    val AGENT_TURN: Schema = msg(
        "agent.v1.AgentConversationTurnStructure",
        bytes(1, "userMessage"),
        bytes(2, "steps", repeated = true),
        str(3, "requestId"),
        str(4, "encryptedModel"),
        u32(5, "dynamicToolCount"),
        u32(6, "sendMessageStepIndices", repeated = true),
        str(7, "routedModelDisplayName"),
        sub(8, "subagentDispatchSteps", repeated = true) { msg("agent.v1.SubagentDispatchStep", u32(1, "stepIndex"), str(2, "toolCallId"), enum(3, "tool"), bool(4, "backgrounded")) },
    )

    /** `agent.v1.ConversationStep`: one step blob — the model's text, a tool call, or a thought. */
    val CONVERSATION_STEP: Schema = msg(
        "agent.v1.ConversationStep",
        sub(1, "assistantMessage") { msg("agent.v1.AssistantMessage", str(1, "text"), u64(2, "startedAtMs"), u64(3, "completedAtMs")) },
        sub(2, "toolCall") { TOOL_CALL },
        sub(3, "thinkingMessage") { msg("agent.v1.ThinkingMessage", str(1, "text"), u32(2, "durationMs"), u64(3, "startedAtMs"), u64(4, "completedAtMs")) },
    )

    /** `agent.v1.UserMessage`: the prompt blob. `mode` is `agent.v1.AgentMode` (PROJECT = 6); `turnSteer` marks a message delivered into a turn under way. */
    val USER_MESSAGE: Schema = msg(
        "agent.v1.UserMessage",
        str(1, "text"),
        str(2, "messageId"),
        sub(3, "selectedContext") { opaque("agent.v1.SelectedContext") },
        enum(4, "mode"),
        bool(5, "isSimulatedMsg"),
        str(8, "richText"),
        enum(9, "simulatedMsgReason"),
        bytes(10, "conversationStateBlobId"),
        sub(15, "simulatedMessageMetadata") { msg("agent.v1.UserMessage.SimulatedMessageMetadata", str(1, "title"), str(2, "taskId"), str(3, "fsdFindingAction"), str(4, "url"), enum(5, "subscriptionSource")) },
        str(16, "promptReferenceId"),
        str(17, "threadId"),
        bytes(18, "textBlobId"),
        bytes(19, "richTextBlobId"),
        bool(24, "turnSteer"),
        u64(25, "startedAtMs"),
        u64(26, "completedAtMs"),
        str(27, "sentByAgentId"),
    )

    // -- tool calls ------------------------------------------------------------------------------------------------

    /**
     * `agent.v1.ToolCall`: the oneof of every tool, by field number, under the name [toolName] gives it (the member
     * minus `_tool_call`, which `ToolNames.kindOf` reads), plus the call's id and times. A variant's message is
     * `{args, result}` (and for a few a third field); [call] builds one from the schemas of its args and result.
     */
    val TOOL_CALL: Schema by lazy {
        msg(
            "agent.v1.ToolCall",
            *TOOL_VARIANTS.map { (no, name) -> sub(no, "${lowerCamel(name)}ToolCall") { TOOL_SCHEMAS[name] ?: opaque("agent.v1.${upperCamel(name)}ToolCall") } }.toTypedArray(),
            sub(54, "hookAdditionalContexts", repeated = true) { opaque("agent.v1.HookAdditionalContext") },
            str(57, "toolCallId"),
            u64(59, "startedAtMs"),
            u64(60, "completedAtMs"),
        )
    }

    /** The oneof's members: field number to the tool's name (`shell_tool_call` → `shell`). */
    val TOOL_VARIANTS: Map<Int, String> = linkedMapOf(
        1 to "shell", 3 to "delete", 4 to "glob", 5 to "grep", 8 to "read", 9 to "update_todos", 10 to "read_todos", 12 to "edit",
        13 to "ls", 14 to "read_lints", 15 to "mcp", 16 to "sem_search", 17 to "create_plan", 18 to "web_search", 19 to "task",
        20 to "list_mcp_resources", 21 to "read_mcp_resource", 22 to "apply_agent_diff", 23 to "ask_question", 24 to "fetch",
        25 to "switch_mode", 28 to "generate_image", 29 to "record_screen", 30 to "computer_use", 31 to "write_shell_stdin",
        32 to "reflect", 33 to "setup_vm_environment", 34 to "truncated", 35 to "start_grind_execution", 36 to "start_grind_planning",
        37 to "web_fetch", 38 to "report_bugfix_results", 39 to "ai_attribution", 40 to "pr_management", 41 to "mcp_auth", 42 to "await",
        43 to "blame_by_file_path", 44 to "get_mcp_tools", 45 to "report_bug", 46 to "set_active_branch", 48 to "communicate_update",
        49 to "send_final_summary", 50 to "update_pr_code_tour", 51 to "replace_env", 52 to "edit_pr_labels",
        53 to "record_ci_investigation_findings", 55 to "send_message", 56 to "fetch_cloud_agent_data", 58 to "send_to_user",
        61 to "pi_read", 62 to "pi_bash", 63 to "pi_edit", 64 to "pi_write", 65 to "pi_grep", 66 to "pi_find", 67 to "pi_ls",
        68 to "connect_scm", 69 to "search_conversations", 70 to "create_goal", 71 to "update_goal", 72 to "adopt",
        73 to "get_agent_status", 74 to "send_to_agent", 75 to "read_agent_transcript", 76 to "create_agent", 77 to "stop_agent",
        78 to "get_pr_code_tour", 79 to "write_canvas", 80 to "read_canvas",
    )

    /** The JSON key of the variant [name] in a decoded `agent.v1.ToolCall`: `send_message` → `sendMessageToolCall`. */
    fun variantKey(name: String): String = "${lowerCamel(name)}ToolCall"

    private fun call(name: String, args: Schema, result: Schema, vararg more: Field): Schema =
        msg("agent.v1.${upperCamel(name)}ToolCall", sub(1, "args") { args }, sub(2, "result") { result }, *more)

    /** `{success, error}` results whose success and error carry the fields the mappers read. */
    private fun outcome(name: String, success: Schema, vararg others: Pair<Int, Pair<String, Schema>>): Schema =
        msg("agent.v1.${name}Result", sub(1, "success") { success }, *others.map { (no, named) -> sub(no, named.first) { named.second } }.toTypedArray())

    private fun error(name: String) = msg("agent.v1.$name", str(1, "error"))

    private val TOOL_SCHEMAS: Map<String, Schema> by lazy {
        mapOf(
            "shell" to call(
                "shell",
                msg("agent.v1.ShellArgs", str(1, "command"), str(2, "workingDirectory"), i32(3, "timeout"), str(4, "toolCallId"), str(5, "simpleCommands", repeated = true), bool(6, "hasInputRedirect"), bool(7, "hasOutputRedirect"), bool(11, "isBackground"), enum(13, "timeoutBehavior"), i32(14, "hardTimeout"), str(15, "description"), bool(17, "closeStdin"), str(21, "conversationId"), str(23, "requestId")),
                msg(
                    "agent.v1.ShellResult",
                    sub(1, "success") { SHELL_SUCCESS }, sub(2, "failure") { SHELL_FAILURE }, sub(3, "timeout") { opaque("agent.v1.ShellTimeout") }, sub(4, "rejected") { opaque("agent.v1.ShellRejected") },
                    sub(5, "spawnError") { opaque("agent.v1.ShellSpawnError") }, sub(7, "permissionDenied") { opaque("agent.v1.ShellPermissionDenied") }, bool(102, "isBackground"), str(103, "terminalsFolder"), u32(104, "pid"),
                ),
                str(3, "description"),
            ),
            "read" to call(
                "read",
                msg("agent.v1.ReadToolArgs", str(1, "path"), i32(2, "offset"), i32(3, "limit"), bool(5, "includeLineNumbers")),
                outcome("ReadTool", msg("agent.v1.ReadToolSuccess", str(1, "content"), bytes(6, "data"), bytes(9, "dataBlobId"), bytes(10, "contentBlobId"), bool(2, "isEmpty"), bool(3, "exceededLimit"), u32(4, "totalLines"), u32(5, "fileSize"), str(7, "path"), bool(11, "includeLineNumbers")), 2 to ("error" to msg("agent.v1.ReadToolError", str(1, "errorMessage")))),
            ),
            "edit" to call(
                "edit",
                msg("agent.v1.EditArgs", str(1, "path"), str(6, "streamContent")),
                outcome(
                    "Edit",
                    msg("agent.v1.EditSuccess", str(1, "path"), i32(3, "linesAdded"), i32(4, "linesRemoved"), str(5, "diffString"), str(6, "beforeFullFileContent"), str(7, "afterFullFileContent"), str(8, "message")),
                    2 to ("fileNotFound" to opaque("agent.v1.EditFileNotFound")), 3 to ("readPermissionDenied" to opaque("agent.v1.EditReadPermissionDenied")), 4 to ("writePermissionDenied" to opaque("agent.v1.EditWritePermissionDenied")),
                    6 to ("rejected" to opaque("agent.v1.EditRejected")), 7 to ("error" to msg("agent.v1.EditError", str(1, "path"), str(2, "error"), str(5, "modelVisibleError"))),
                ),
            ),
            "grep" to call(
                "grep",
                msg("agent.v1.GrepArgs", str(1, "pattern"), str(2, "path"), str(3, "glob"), str(4, "outputMode"), i32(5, "contextBefore"), i32(6, "contextAfter"), i32(7, "context"), bool(8, "caseInsensitive"), str(9, "type"), i32(10, "headLimit"), bool(11, "multiline"), str(14, "toolCallId")),
                outcome("Grep", msg("agent.v1.GrepSuccess", str(1, "pattern"), str(2, "path"), str(3, "outputMode")), 2 to ("error" to error("GrepError"))),
            ),
            "glob" to call(
                "glob",
                msg("agent.v1.GlobToolArgs", str(1, "targetDirectory"), str(2, "globPattern")),
                outcome("GlobTool", msg("agent.v1.GlobToolSuccess", str(1, "pattern"), str(2, "path"), str(3, "files", repeated = true), i32(4, "totalFiles"), bool(5, "clientTruncated"), bool(6, "ripgrepTruncated")), 2 to ("error" to error("GlobToolError"))),
            ),
            "ls" to call(
                "ls",
                msg("agent.v1.LsArgs", str(1, "path"), str(2, "ignore", repeated = true), str(3, "toolCallId")),
                outcome("Ls", msg("agent.v1.LsSuccess", sub(1, "directoryTreeRoot") { opaque("agent.v1.LsDirectoryTreeNode") }), 2 to ("error" to error("LsError")), 3 to ("rejected" to opaque("agent.v1.LsRejected")), 4 to ("timeout" to opaque("agent.v1.LsTimeout"))),
            ),
            "delete" to call(
                "delete",
                msg("agent.v1.DeleteArgs", str(1, "path"), str(2, "toolCallId")),
                outcome("Delete", msg("agent.v1.DeleteSuccess", str(1, "path"), str(2, "deletedFile"), i64(3, "fileSize")), 7 to ("error" to error("DeleteError"))),
            ),
            "mcp" to call(
                "mcp",
                msg("agent.v1.McpArgs", str(1, "name"), map(2, "args", sub(2, "value") { opaque("google.protobuf.Value") }), str(3, "toolCallId"), str(4, "providerIdentifier"), str(5, "toolName"), str(9, "serverIdentifier")),
                outcome(
                    "McpTool",
                    msg("agent.v1.McpSuccess", sub(1, "content", repeated = true) { msg("agent.v1.McpToolResultContentItem", sub(1, "text") { msg("agent.v1.McpTextContent", str(1, "text")) }, sub(2, "image") { opaque("agent.v1.McpImageContent") }) }, bool(2, "isError")),
                    2 to ("error" to msg("agent.v1.McpToolError", str(1, "error"), str(2, "readToolDefReminder"))), 3 to ("rejected" to opaque("agent.v1.McpRejected")), 4 to ("permissionDenied" to opaque("agent.v1.McpPermissionDenied")),
                ),
                str(3, "description"),
            ),
            "get_mcp_tools" to call("get_mcp_tools", opaque("agent.v1.GetMcpToolsArgs"), opaque("agent.v1.GetMcpToolsAgentResult")),
            "task" to call(
                "task",
                msg("agent.v1.TaskArgs", str(1, "description"), str(2, "prompt"), sub(3, "subagentType") { opaque("agent.v1.SubagentType") }, str(4, "model"), str(5, "resume"), str(6, "agentId"), str(7, "attachments", repeated = true), enum(8, "mode"), enum(10, "environment")),
                outcome("Task", msg("agent.v1.TaskSuccess", str(2, "agentId"), bool(3, "isBackground"), u64(4, "durationMs"), str(5, "resultSuffix"), enum(6, "backgroundReason"), str(7, "transcriptPath")), 2 to ("error" to error("TaskError"))),
                str(3, "cloudAgentBcId"),
            ),
            "ask_question" to call(
                "ask_question",
                msg("agent.v1.AskQuestionArgs", str(1, "title"), sub(2, "questions", repeated = true) { msg("agent.v1.AskQuestionArgs.Question", str(1, "id"), str(2, "prompt"), sub(3, "options", repeated = true) { msg("agent.v1.AskQuestionArgs.Option", str(1, "id"), str(2, "label")) }, bool(4, "allowMultiple")) }, bool(5, "runAsync"), str(6, "asyncOriginalToolCallId")),
                outcome("AskQuestion", msg("agent.v1.AskQuestionSuccess", sub(1, "answers", repeated = true) { msg("agent.v1.AskQuestionSuccess.Answer", str(1, "questionId"), str(2, "selectedOptionIds", repeated = true), str(3, "freeformText")) }), 2 to ("error" to error("AskQuestionError")), 3 to ("rejected" to opaque("agent.v1.AskQuestionRejected")), 4 to ("async" to opaque("agent.v1.AskQuestionAsync"))),
            ),
            "update_todos" to call(
                "update_todos",
                msg("agent.v1.UpdateTodosArgs", sub(1, "todos", repeated = true) { TODO_ITEM }, bool(2, "merge")),
                outcome("UpdateTodos", msg("agent.v1.UpdateTodosSuccess", sub(1, "todos", repeated = true) { TODO_ITEM }, i32(2, "totalCount"), bool(3, "wasMerge")), 2 to ("error" to error("UpdateTodosError"))),
            ),
            "fetch" to call(
                "fetch",
                msg("agent.v1.FetchArgs", str(1, "url"), str(2, "toolCallId")),
                outcome("Fetch", msg("agent.v1.FetchSuccess", str(1, "url"), str(2, "content"), i32(3, "statusCode"), str(4, "contentType")), 2 to ("error" to error("FetchError"))),
            ),
            "web_search" to call(
                "web_search",
                msg("agent.v1.WebSearchArgs", str(1, "searchTerm"), str(2, "toolCallId")),
                outcome("WebSearch", msg("agent.v1.WebSearchSuccess", sub(1, "references", repeated = true) { opaque("agent.v1.WebSearchReference") }), 2 to ("error" to error("WebSearchError")), 3 to ("rejected" to opaque("agent.v1.WebSearchRejected"))),
            ),
            "sem_search" to call(
                "sem_search",
                msg("agent.v1.SemSearchToolArgs", str(1, "query"), str(2, "targetDirectories", repeated = true), str(3, "explanation")),
                outcome("SemSearchTool", msg("agent.v1.SemSearchToolSuccess", str(1, "results")), 2 to ("error" to error("SemSearchToolError"))),
            ),
            "generate_image" to call(
                "generate_image",
                msg("agent.v1.GenerateImageArgs", str(1, "description"), str(2, "filePath"), str(5, "referenceImagePaths", repeated = true), str(6, "aspectRatio")),
                outcome("GenerateImage", msg("agent.v1.GenerateImageSuccess", str(1, "filePath"), str(2, "imageData")), 2 to ("error" to error("GenerateImageError"))),
            ),
            "record_screen" to call(
                "record_screen",
                msg("agent.v1.RecordScreenArgs", enum(1, "mode"), str(2, "toolCallId"), str(3, "saveAsFilename")),
                msg("agent.v1.RecordScreenResult", sub(1, "startSuccess") { opaque("agent.v1.RecordScreenStartSuccess") }, sub(2, "saveSuccess") { opaque("agent.v1.RecordScreenSaveSuccess") }, sub(3, "discardSuccess") { opaque("agent.v1.RecordScreenDiscardSuccess") }, sub(4, "failure") { opaque("agent.v1.RecordScreenFailure") }),
            ),
            "switch_mode" to call("switch_mode", msg("agent.v1.SwitchModeArgs", str(1, "targetModeId"), str(2, "explanation"), str(3, "toolCallId")), opaque("agent.v1.SwitchModeResult")),
            "truncated" to msg("agent.v1.TruncatedToolCall", bytes(1, "originalStepBlobId"), sub(2, "args") { opaque("agent.v1.TruncatedToolCallArgs") }, sub(3, "result") { opaque("agent.v1.TruncatedToolCallResult") }),
            // A Project coordinator's tools (see `ToolPayloads`): the message to the user, the workers it creates, addresses, checks, reads and stops.
            "send_message" to call(
                "send_message",
                msg("agent.v1.SendMessageArgs", sub(1, "text") { msg("agent.v1.SendMessageText", str(1, "content")) }, sub(2, "attachment") { msg("agent.v1.SendMessageAttachment", str(1, "url"), str(2, "alt")) }),
                outcome("SendMessage", msg("agent.v1.SendMessageSuccess", u64(1, "timestamp"), str(2, "messageId")), 2 to ("error" to error("SendMessageError"))),
            ),
            "send_to_user" to call("send_to_user", msg("agent.v1.SendToUserArgs", str(1, "message")), outcome("SendToUser", opaque("agent.v1.SendToUserSuccess"), 2 to ("error" to error("SendToUserError")))),
            "send_to_agent" to call(
                "send_to_agent",
                msg("agent.v1.SendToAgentArgs", str(1, "toolCallId"), str(2, "agentId"), str(3, "message"), str(4, "delivery"), str(5, "title")),
                outcome("SendToAgent", msg("agent.v1.SendToAgentSuccess", str(1, "workerBcId"), str(2, "deliveredAs"), str(3, "message")), 2 to ("error" to error("SendToAgentError"))),
            ),
            "create_agent" to call(
                "create_agent",
                msg("agent.v1.CreateAgentArgs", str(1, "toolCallId"), str(2, "prompt"), str(3, "name"), str(4, "model"), str(5, "baseBranch"), str(6, "machineType"), str(7, "workerId"), str(8, "pool"), map(9, "labels", str(2, "value")), str(10, "environmentBuildId")),
                outcome("CreateAgent", msg("agent.v1.CreateAgentSuccess", str(1, "agentId"), str(2, "message")), 2 to ("error" to error("CreateAgentError"))),
            ),
            "get_agent_status" to call(
                "get_agent_status",
                msg("agent.v1.GetAgentStatusArgs", str(1, "toolCallId"), str(2, "agentIds", repeated = true)),
                outcome(
                    "GetAgentStatus",
                    msg("agent.v1.GetAgentStatusSuccess", sub(1, "workers", repeated = true) { msg("agent.v1.GetAgentStatusWorker", str(1, "bcId"), str(2, "name"), str(3, "lifecycle"), bool(4, "turnInFlight"), str(5, "lastTerminalTurnStatus"), str(6, "prUrl"), u64(7, "lastActivityAtMs")) }, str(2, "message")),
                    2 to ("error" to error("GetAgentStatusError")),
                ),
            ),
            "stop_agent" to call("stop_agent", msg("agent.v1.StopAgentArgs", str(1, "toolCallId"), str(2, "agentId")), outcome("StopAgent", msg("agent.v1.StopAgentSuccess", str(1, "agentId"), str(2, "message")), 2 to ("error" to error("StopAgentError")))),
            "read_agent_transcript" to call("read_agent_transcript", msg("agent.v1.ReadAgentTranscriptArgs", str(1, "toolCallId"), str(2, "agentId")), outcome("ReadAgentTranscript", msg("agent.v1.ReadAgentTranscriptSuccess", str(1, "agentId"), str(2, "transcript"), str(3, "message")), 2 to ("error" to error("ReadAgentTranscriptError")))),
            "communicate_update" to call("communicate_update", msg("agent.v1.CommunicateUpdateArgs", str(1, "currentStep")), opaque("agent.v1.CommunicateUpdateResult")),
            "create_goal" to call("create_goal", msg("agent.v1.CreateGoalArgs", str(1, "toolCallId"), str(2, "objective")), outcome("CreateGoal", msg("agent.v1.CreateGoalSuccess", str(1, "goalId"), str(2, "message")), 2 to ("error" to error("CreateGoalError")))),
            "update_goal" to call("update_goal", msg("agent.v1.UpdateGoalArgs", str(1, "toolCallId"), str(2, "goalId"), str(3, "objective"), str(4, "status")), outcome("UpdateGoal", msg("agent.v1.UpdateGoalSuccess", str(1, "goalId"), str(2, "message")), 2 to ("error" to error("UpdateGoalError")))),
        )
    }

    private val SHELL_SUCCESS: Schema by lazy {
        msg("agent.v1.ShellSuccess", str(1, "command"), str(2, "workingDirectory"), i32(3, "exitCode"), str(4, "signal"), str(5, "stdout"), str(6, "stderr"), i32(7, "executionTime"), u32(9, "shellId"), str(10, "interleavedOutput"), u32(11, "pid"), str(15, "outputHead"), str(16, "outputTail"), u32(17, "elidedChars"))
    }

    private val SHELL_FAILURE: Schema by lazy {
        msg("agent.v1.ShellFailure", str(1, "command"), str(2, "workingDirectory"), i32(3, "exitCode"), str(4, "signal"), str(5, "stdout"), str(6, "stderr"), i32(7, "executionTime"), str(9, "interleavedOutput"), enum(10, "abortReason"), bool(11, "aborted"), str(13, "outputHead"), str(14, "outputTail"), u32(15, "elidedChars"))
    }

    private val TODO_ITEM: Schema by lazy {
        msg("agent.v1.TodoItem", str(1, "id"), str(2, "content"), enum(3, "status"), i64(4, "createdAt"), i64(5, "updatedAt"), str(6, "dependencies", repeated = true))
    }

    /** `send_to_agent` → `sendToAgent`. */
    fun lowerCamel(snake: String): String = snake.split('_').mapIndexed { i, part -> if (i == 0) part else part.replaceFirstChar { it.uppercase() } }.joinToString("")

    private fun upperCamel(snake: String): String = lowerCamel(snake).replaceFirstChar { it.uppercase() }

    /** `agent.v1.AgentMode.AGENT_MODE_PROJECT`. */
    const val AGENT_MODE_PROJECT = 6
}
