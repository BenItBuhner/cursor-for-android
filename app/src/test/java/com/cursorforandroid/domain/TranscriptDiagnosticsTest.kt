package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The transcript diagnostics: every item and call accounted for by kind and shape, nothing of their text in it. */
class TranscriptDiagnosticsTest {

    private val secret = "Bennett has the explanation plus the note that his recording was the reference app"
    private val items = listOf(
        UserMessage("u1", "Start this Project, and keep the API key hunter2 out of it."),
        SystemNotification("n1", SystemNotification.Kind.Subagent, "Subagent completed", "Land merge train and prep release", "The merge train landed.", NoticeTone.Success, raw = "<system_notification>…</system_notification>", agentId = "bc-24e35e9f-1a2d-5218-8e2e-7464ea74f671"),
        AssistantMessage("a1", "The release worker shipped v0.3.3."),
        ActivityGroup(
            "g1",
            listOf(
                ThinkingBlock("Route the release and tell Bennett."),
                ToolCall("c1", "sendToAgent", ToolKind.Coordinator, "completed", "bc-24e35e9f", payload = ToolPayload.WorkerAction(ToolPayload.WorkerAction.Kind.Messaged, listOf(WorkerStatus(agentId = "bc-24e35e9f-1a2d-5218-8e2e-7464ea74f671")), text = "Land it."), argKeys = listOf("toolCallId", "agentId", "message", "delivery", "title"), linkedAgentIds = listOf("bc-24e35e9f-1a2d-5218-8e2e-7464ea74f671")),
                ToolCall("c2", "SendMessage", ToolKind.Other, "completed", ""),
                ToolCall("c3", "sendMessage", ToolKind.Coordinator, "completed", secret.take(45) + "...", detail = secret, payload = ToolPayload.CoordinatorMessage(secret), argKeys = listOf("text")),
                ToolCall("c4", "sendMessage", ToolKind.Coordinator, "completed", "", payload = ToolPayload.CoordinatorMessage("", missing = true), truncated = ToolTruncation(args = true)),
                ToolCall("c5", "edit", ToolKind.Edit, "completed", "notes.md", detail = "notes.md", linesAdded = 2, linesRemoved = 1, argKeys = listOf("path")),
            ),
        ),
        RunFooter("f1", "run-1", RunStatus.FINISHED, 81_000, emptyList()),
    )

    private fun render(agent: Agent? = null, recordProjectMode: Boolean = false) = TranscriptDiagnostics.render(
        TranscriptDiagnostics.Input(
            appVersion = "0.3.7",
            nowIso = "2026-09-14T04:00:00Z",
            extendedMode = true,
            agentId = "bc-bae107cb-2562-40b2-b814-4f8eca874668",
            agent = agent,
            state = TranscriptDiagnostics.State(items, runStatus = RunStatus.FINISHED, hasOlder = true, recordProjectMode = recordProjectMode),
        ),
    )

    @Test
    fun `the report carries kinds, names, statuses, payload shapes and argument keys, and none of the text`() {
        val report = render()
        assertThat(report).contains("Cursor for Android 0.3.7 · transcript diagnostics · 2026-09-14T04:00:00Z")
        // The engine beside the mode: Beta unless Stable was chosen, whatever the mode (see TranscriptEngine).
        assertThat(report).contains("mode=extended engine=beta")
        assertThat(TranscriptDiagnostics.render(TranscriptDiagnostics.Input("0.3.7", "2026-09-14T04:00:00Z", extendedMode = true, engine = TranscriptEngine.STABLE, agentId = null, agent = null, state = null))).contains("mode=extended engine=stable")
        assertThat(TranscriptDiagnostics.render(TranscriptDiagnostics.Input("0.3.7", "2026-09-14T04:00:00Z", extendedMode = false, engine = TranscriptEngine.BETA, agentId = null, agent = null, state = null))).contains("mode=default engine=beta")
        assertThat(report).contains("chat=…874668 row=not in list")
        assertThat(report).contains("classification: COORDINATOR listProject=false recordProjectMode=false content=true evidence=sendToAgent,SendMessage,sendMessage")
        assertThat(report).contains("presented: items=5 folded=0 staleMessages=1 rows=7 stretches=2 messages=3")
        assertThat(report).contains("  user chars=59 attachments=0")
        assertThat(report).contains("  notification kind=Subagent title=\"Subagent completed\" tone=Success summaryChars=33 bodyChars=23 agent=…74f671")
        assertThat(report).contains("  assistant chars=34")
        assertThat(report).contains("  activity steps=6 coordination=true grouped=false")
        assertThat(report).contains("    thinking chars=35")
        assertThat(report).contains("    tool sendToAgent · Coordinator · completed · worker_action(messaged,workers=1,reported=false) · args=[toolCallId,agentId,message,delivery,title] · linked=1 · truncated=- · id=c1")
        assertThat(report).contains("    tool SendMessage · Other→Coordinator · completed · coordinator_message(missing) · args=[] · linked=0 · truncated=- · id=c2")
        assertThat(report).contains("    tool sendMessage · Coordinator · completed · coordinator_message(${secret.length} chars) · args=[text] · linked=0 · truncated=- · id=c3")
        assertThat(report).contains("    tool sendMessage · Coordinator · completed · coordinator_message(missing) · args=[] · linked=0 · truncated=args · id=c4")
        assertThat(report).contains("    tool edit · Edit · completed · - · args=[path] · linked=0 · truncated=- · id=c5")
        // A body read leniently out of a record says so; a long id is cut to its tail.
        val recovered = ToolCall("toolu_01SendPieces", "SendMessage", ToolKind.Coordinator, "completed", "", payload = ToolPayload.CoordinatorMessage("The phone worker has the Fold8", recovered = true), argKeys = listOf("text"))
        assertThat(TranscriptDiagnostics.describe(recovered)).isEqualTo("SendMessage · Coordinator · completed · coordinator_message(30 chars,recovered) · args=[text] · linked=0 · truncated=- · id=…Pieces")
        assertThat(report).contains("  footer status=FINISHED duration=81000 branches=0")
        // Nothing the user or the coordinator wrote, and no whole id.
        assertThat(report).doesNotContain("hunter2")
        assertThat(report).doesNotContain("Bennett")
        assertThat(report).doesNotContain("Land merge train")
        assertThat(report).doesNotContain("notes.md")
        assertThat(report).doesNotContain("bc-24e35e9f-1a2d")
        assertThat(report).doesNotContain("bc-bae107cb-2562")
    }

    /**
     * The load's lines: the window over the chat's turns, the run list and the order it came in, each run of the window
     * with the state of its trace, the live follow, the failures — the account of a chat that opened with its text
     * and no tool calls, or with its running turn read as over.
     */
    @Test
    fun `the load line says where the window sits, what came of each run's trace, and what the live stream said`() {
        val load = TranscriptLoadDiagnostics(
            attached = 1, paused = false, fetched = true, fetchedAtIso = "2026-09-14T03:59:58Z", messages = 319, prompts = 160,
            runsLoaded = 160, runsComplete = true, hasOlderCursor = false, runOrder = RunOrder.OLDEST_FIRST, latestFetchedById = true,
            window = 10, windowStart = 150, chatTurns = 160,
            runs = listOf(
                TranscriptLoadDiagnostics.RunLine("…run151", "FINISHED", "expired", 0),
                TranscriptLoadDiagnostics.RunLine("…run152", "FINISHED", "failed", 0),
                TranscriptLoadDiagnostics.RunLine("…run159", "FINISHED", "shown", 4),
                TranscriptLoadDiagnostics.RunLine("…run160", "RUNNING", "live", 0),
                // A coordinator's turn from the record: what the record had of the message, and what reached the screen from where.
                TranscriptLoadDiagnostics.RunLine("turn@8301/…run161", "FINISHED", "shown(log)", 5, message = "record=none rendered=yes via=log"),
                TranscriptLoadDiagnostics.RunLine("turn@8310", "-", "shown(unpaired)", 2, message = "record=missing result=yes rendered=missing via=record"),
            ),
            traceQueue = 0, traceInFlight = 1, traceWorkerRunning = true, expiredBeforeIso = "2026-09-07T10:00:00Z", expiredRuns = 7, failedTraces = 1,
            liveRunId = "run-160", following = true,
            liveStream = TranscriptLoadDiagnostics.LiveStreamLine(events = 3, status = "RUNNING", reconnecting = false, expired = false, finished = false, items = 2),
            lastError = null, transcriptError = "Cursor took too long to respond. https://api.cursor.com/v0/agents/bc-1234/conversation", transcriptUnavailable = false,
            source = "record", record = TranscriptLoadDiagnostics.RecordLine(total = 8_320, firstStep = 8_060, turnsLoaded = 10, turnCount = 320, stateRead = true, empty = false, error = null),
            status = TranscriptLoadDiagnostics.StatusLine(
                shown = "RUNNING", latestRun = "CANCELLED", streaming = false, rowRunning = false, accountRunning = true, rowNewerThanRecordMs = 600_000L,
                // Why the newest failed run reads as failed: the run, the server's word it came from, the reason (redacted), and whether the chat moved past it.
                failure = TranscriptLoadDiagnostics.FailureLine("…run158", "run-record+account-record", "Tool result not found for toolu_01CRGAspS8qhi8Wfr74zYnJA (agent bc-24e35e9f-1a2d-5218-8e2e-7464ea74f671)", current = false),
            ),
            shapes = listOf(
                TurnShape(
                    stepIndex = 8_301, prompt = "user", projectMode = true,
                    steps = listOf(
                        StepShape(8_301, "human_message", "{humanMessage:{text:str(17),agentMode:AGENT_MODE_PROJECT}}"),
                        StepShape(8_302, "tool_call[id=toolCallId name=name args=piece]", "{toolCall:{toolCallId:str(18),name:SendMessage,rawArgs:str(50),isStreaming:true}}"),
                    ),
                    calls = listOf(CallShape("…Pieces", "SendMessage", steps = 3, args = "recovered(repaired,3)", result = true)),
                ),
            ),
        )
        val report = TranscriptDiagnostics.render(
            TranscriptDiagnostics.Input("0.3.15", "2026-09-14T04:00:00Z", extendedMode = false, agentId = "bc-bae107cb-2562-40b2-b814-4f8eca874668", agent = null, state = TranscriptDiagnostics.State(items), load = load),
        )
        assertThat(report).contains("load: source=record attached=1 paused=false fetched=true fetchedAt=2026-09-14T03:59:58Z messages=319 prompts=160 runs=160 complete=true olderCursor=false order=OLDEST_FIRST latestById=true window=10 turns=[150,160)")
        assertThat(report).contains("record: read=steps total=8320 firstStep=8060 turnsLoaded=10 turnCount=320 state=read empty=false")
        assertThat(report).contains("status: shown=RUNNING latestRun=CANCELLED streaming=false rowRunning=false accountRunning=true rowNewerThanRecordMs=600000 failed: run=…run158 source=run-record+account-record current=false reason=\"Tool result not found for toolu_01CRGAspS8qhi8Wfr74zYnJA (agent bc-…)\"")
        assertThat(report).doesNotContain("bc-24e35e9f")
        assertThat(report).contains("traces: shown=3 of 5 queue=0 inFlight=1 worker=true expiredRuns=7 expiredBefore=2026-09-07T10:00:00Z failed=1")
        assertThat(report).contains("  run …run151 FINISHED trace=expired items=0")
        assertThat(report).contains("  run …run152 FINISHED trace=failed items=0")
        assertThat(report).contains("  run …run160 RUNNING trace=live items=0")
        assertThat(report).contains("  run turn@8301/…run161 FINISHED trace=shown(log) items=5 sendMessage: record=none rendered=yes via=log")
        assertThat(report).contains("  run turn@8310 - trace=shown(unpaired) items=2 sendMessage: record=missing result=yes rendered=missing via=record")
        assertThat(report).contains("live: run=…un-160 following=true stream=events:3,status:RUNNING,reconnecting:false,expired:false,finished:false,items:2")
        assertThat(report).contains("errors: last=- transcript=\"Cursor took too long to respond. <url>\" transcriptUnavailable=false")
        assertThat(report).doesNotContain("bc-1234")
        // The shape dump: the newest turns step by step, then their calls.
        assertThat(report).contains("shapes: newest 1 turns of the record, oldest first (step: index · branch · keys:types; call: id · name · steps · args · result):")
        assertThat(report).contains("turn@8301 steps=2 prompt=user project=true calls=1 sendMessage=recovered result=yes")
        assertThat(report).contains("  8301 human_message {humanMessage:{text:str(17),agentMode:AGENT_MODE_PROJECT}}")
        assertThat(report).contains("  8302 tool_call[id=toolCallId name=name args=piece] {toolCall:{toolCallId:str(18),name:SendMessage,rawArgs:str(50),isStreaming:true}}")
        assertThat(report).contains("  call …Pieces SendMessage steps=3 args=recovered(repaired,3) result=true")
    }

    @Test
    fun `the perf block says what the open cost, and what the presenter, the markdown cache, the disk and the network did`() {
        var nanos = 0L
        TranscriptPerf.useClock { nanos }
        try {
            val session = TranscriptPerf.opened("bc-perf")
            nanos = 120_000_000L
            session.publication(items = 12, whole = false, buildNanos = 4_000_000L)
            nanos = 340_000_000L
            session.publication(items = 40, whole = true, buildNanos = 9_000_000L)
            session.presenterRun(2_500_000L, built = 10, reused = 0)
            session.presenterRun(400_000L, built = 1, reused = 9)
            session.rowComposed(); session.rowComposed()
            session.markdownParsed(3_000_000L); session.markdownHit(); session.markdownHit()
            session.turnBuilt(20_000_000L); session.turnRendered(); session.turnRenderReused()
            session.conversationRead(); session.traceRead(files = 39, nanos = 87_000_000L)
            session.network("record"); session.network("record"); session.network("runs"); session.network("state")
            nanos = 2_000_000_000L
            val report = TranscriptDiagnostics.render(
                TranscriptDiagnostics.Input("0.3.32", "2026-09-17T10:00:00Z", extendedMode = true, agentId = "bc-perf", agent = null, state = TranscriptDiagnostics.State(items), perf = session.snapshot()),
            )
            assertThat(report).contains("perf: open=2000ms firstContent=120ms newestPageWhole=340ms publications=2 (2.0/min, lastMinute=2) itemsRebuilt=13.0ms (max 9.0ms)")
            assertThat(report).contains("  presenter: runs=2 total=2.9ms avg=1.5ms max=2.5ms rowsMaterialized=11 rowsReused=9 rowsComposed=2 turnsBuilt=1 (20.0ms) turnsReused=0 turnsRendered=1 turnRendersReused=1")
            assertThat(report).contains("  markdown: parses=1 cacheHits=2 parseTime=3.0ms")
            assertThat(report).contains("  disk: conversationReads=1 traceFileReads=39 (87.0ms)")
            assertThat(report).contains("  network: total=4 (4.0/min, lastMinute=4) record=2 runs=1 state=1")
            // A chat never opened this process has no block; nothing else of the report changes.
            assertThat(TranscriptDiagnostics.render(TranscriptDiagnostics.Input("0.3.32", "2026-09-17T10:00:00Z", extendedMode = true, agentId = "bc-perf", agent = null, state = TranscriptDiagnostics.State(items)))).doesNotContain("perf:")
        } finally {
            TranscriptPerf.useClock(System::nanoTime)
            TranscriptPerf.clearAll()
        }
    }

    @Test
    fun `the send block says what was decided from which source, what waits, and what came of each attempt`() {
        val inputs = SendGate.Inputs(
            rowLoaded = true, rowRunning = false, rowUpdatedAtMillis = 1_000_000L,
            chatRunStatus = RunStatus.FINISHED, chatStreaming = false, chatReconnecting = false,
            accountScanned = true, accountRunning = false, accountAtMillis = 1_002_000L,
        )
        val send = SendDiagnostics(
            decision = SendGate.decide(inputs),
            decidedAtIso = "2026-09-17T10:00:05Z",
            queue = listOf(SendDiagnostics.QueueLine("…q-1", chars = 42, sending = false, steered = false, busyRefusals = 3, heldForMs = 83_000L, error = null, needsConfirmation = false)),
            attempts = listOf(
                SendDiagnostics.Attempt("2026-09-17T10:00:05Z", "…q-1", "runs", "busy", "Agent is busy."),
                SendDiagnostics.Attempt("2026-09-17T10:00:06Z", "…q-1", "runs", "busy", "Agent is busy."),
                SendDiagnostics.Attempt("2026-09-17T10:00:08Z", "…q-0", "runs", "accepted", "run-9"),
            ),
            accepted = listOf("…run-9"),
            launch = SendDiagnostics.LaunchLine("2026-09-17T09:59:00Z", via = "account", target = "no-repo(personal environment)", files = 1, images = 1, outcome = "refused by the account http=400 code=invalid_argument", detail = "At least one model details is required"),
        )
        val report = TranscriptDiagnostics.render(
            TranscriptDiagnostics.Input("0.3.34", "2026-09-17T10:00:00Z", extendedMode = true, agentId = "bc-send", agent = null, state = TranscriptDiagnostics.State(emptyList()), send = send),
        )
        // The account's word carries its instant and whether the gate let it speak, so a fresher `status:` line is read against it.
        assertThat(report).contains("send: decision=send by=account at=2026-09-17T10:00:05Z row=idle chat=FINISHED streaming=false reconnecting=false account=idle@1970-01-01T00:16:42Z accountAgeMs=-2000 accountUsed=true")
        // The launch that started the chat from here: which request, what it named as the place to run, what came of it.
        assertThat(report).contains("  launch: at=2026-09-17T09:59:00Z via=account target=no-repo(personal environment) files=1 images=1 outcome=refused by the account http=400 code=invalid_argument \"At least one model details is required\"")
        assertThat(report).contains("  queue: 1 […q-1 chars=42 busyRefusals=3 heldFor=83s]")
        assertThat(report).contains("  attempts: 3 accepted=[…run-9]")
        assertThat(report).contains("    2026-09-17T10:00:05Z …q-1 via=runs busy \"Agent is busy.\"")
        assertThat(report).contains("    2026-09-17T10:00:08Z …q-0 via=runs accepted \"run-9\"")
        // A chat nothing was sent or queued from has no block; one only launched from here has the block with the launch alone.
        assertThat(TranscriptDiagnostics.render(TranscriptDiagnostics.Input("0.3.34", "2026-09-17T10:00:00Z", extendedMode = true, agentId = "bc-send", agent = null, state = TranscriptDiagnostics.State(emptyList())))).doesNotContain("send:")
        val launchedOnly = send.copy(decision = null, decidedAtIso = null, queue = emptyList(), attempts = emptyList(), accepted = emptyList())
        val onlyLaunch = TranscriptDiagnostics.render(TranscriptDiagnostics.Input("0.3.34", "2026-09-17T10:00:00Z", extendedMode = true, agentId = "bc-send", agent = null, state = TranscriptDiagnostics.State(emptyList()), send = launchedOnly))
        assertThat(onlyLaunch).contains("send: decision=none\n  launch: at=2026-09-17T09:59:00Z via=account")
        assertThat(onlyLaunch).contains("  queue: 0\n  attempts: 0\n")
    }

    @Test
    fun `the decision names each of its words, and a chat never opened says so`() {
        assertThat(render(recordProjectMode = true)).contains("classification: COORDINATOR listProject=false recordProjectMode=true content=true")
        val none = TranscriptDiagnostics.render(TranscriptDiagnostics.Input("0.3.7", "2026-09-14T04:00:00Z", extendedMode = false, agentId = null, agent = null, state = null))
        assertThat(none).contains("chat: none opened this session")
        assertThat(none).contains("mode=default")
        val unloaded = TranscriptDiagnostics.render(TranscriptDiagnostics.Input("0.3.7", "2026-09-14T04:00:00Z", extendedMode = false, agentId = "bc-1234567890", agent = null, state = null))
        assertThat(unloaded).contains("state: none (the chat has not been loaded)")
    }

    @Test
    fun `the files block names each file opened, the path the machine was asked for, what came back and the end`() {
        val line = com.cursorforandroid.data.repo.FileReadAttempt(
            atMillis = 0L,
            agentId = "bc-bae107cb-2562-40b2-b814-4f8eca874668",
            path = "/tmp/reel_frames_s.jpg",
            listing = "loaded(212)",
            vm = "/tmp/reel_frames_s.jpg→NotFound [POST /aiserver.v1.BackgroundComposerService/ReadBinaryFile → HTTP 404 not_found \"File not found\"]",
            repository = "skipped (outside the workspace)",
            result = "NotFound \"The agent's machine has no such file (any more).\"",
        ).text
        val report = TranscriptDiagnostics.render(
            TranscriptDiagnostics.Input(
                appVersion = "0.3.7",
                nowIso = "2026-09-14T04:00:00Z",
                extendedMode = true,
                agentId = "bc-bae107cb-2562-40b2-b814-4f8eca874668",
                agent = null,
                state = TranscriptDiagnostics.State(items, runStatus = RunStatus.FINISHED),
                fileReads = listOf(line),
            ),
        )
        assertThat(report).contains("files: 1 opened")
        assertThat(report).contains("  open /tmp/reel_frames_s.jpg list=loaded(212) vm=/tmp/reel_frames_s.jpg→NotFound [POST /aiserver.v1.BackgroundComposerService/ReadBinaryFile → HTTP 404 not_found \"File not found\"] repo=skipped (outside the workspace) result=NotFound")
        assertThat(render()).doesNotContain("files:")
    }
}
