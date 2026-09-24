package com.cursorforandroid.domain

import com.cursorforandroid.fixtures.LiveModelCatalog
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The desktop's rules for a subagent's row (`SubagentTaskCard`, Cursor 3.21.18) as this app reads them: which calls
 * are rows, the title, the model's label, the placement glyph, the line under the title and the indicator, for a
 * task's subagent and for a Project's worker created, steered, queued, messaged or stopped.
 */
class SubagentRowsTest {

    private val models = LiveModelCatalog.models

    private fun task(
        description: String? = "CursorBench chart hover highlight",
        status: String = ToolCall.STATUS_RUNNING,
        agentId: String? = null,
        model: String? = null,
        type: String? = null,
        environment: String? = null,
    ) = ToolCall(
        "t1", "task", ToolKind.Task, status, description.orEmpty(),
        payload = ToolPayload.Subagent(description, agentId = agentId, subagentType = type, model = model, environment = environment),
    )

    private fun created(name: String? = "Build Projects under Extended mode", prompt: String = "Fix the Projects list.", agentId: String? = "bc-w1", status: String = ToolCall.STATUS_COMPLETED, model: String? = null, workerId: String? = null) = ToolCall(
        "c1", "create_agent", ToolKind.Coordinator, status, name.orEmpty(),
        payload = ToolPayload.WorkerAction(ToolPayload.WorkerAction.Kind.Created, listOfNotNull(agentId?.let { WorkerStatus(it, name) }), text = prompt, title = name, model = model, workerId = workerId),
    )

    private fun sent(delivery: ToolPayload.WorkerAction.Delivery?, title: String? = "Rebase", status: String = ToolCall.STATUS_COMPLETED, callId: String = "s1") = ToolCall(
        callId, "send_to_agent", ToolKind.Coordinator, status, "bc-w1",
        payload = ToolPayload.WorkerAction(ToolPayload.WorkerAction.Kind.Messaged, listOf(WorkerStatus("bc-w1")), text = "Rebase onto main.", title = title, delivery = delivery),
    )

    private fun stopped(status: String = ToolCall.STATUS_COMPLETED, isError: Boolean = false) = ToolCall(
        "x1", "stop_agent", ToolKind.Coordinator, status, "bc-w1", isError = isError,
        payload = ToolPayload.WorkerAction(ToolPayload.WorkerAction.Kind.Stopped, listOf(WorkerStatus("bc-w1"))),
    )

    private fun agent(status: RunStatus?, env: EnvType = EnvType.CLOUD, pending: Boolean = false, modelId: String? = null, name: String = "Usage events aggregation") = Agent(
        id = "bc-w1", name = name, lifecycle = AgentLifecycle.ACTIVE, runStatus = status, envType = env, envName = null,
        url = "https://cursor.com/agents/bc-w1", createdAtMillis = 1L, updatedAtMillis = 2L, latestRunId = "run-1", repoUrl = null, startingRef = null,
        hasPendingInteraction = pending, modelId = modelId,
    )

    private fun look(call: ToolCall, child: SubagentChild? = null, latest: Boolean = true) = SubagentRows.look(call, SubagentCall.of(call)!!, child, latest)

    // -- which calls are rows, and what they say ------------------------------------------------------------------

    @Test
    fun `every kind of subagent call is a row, and nothing else is`() {
        assertThat(SubagentCall.of(task())!!.source).isEqualTo(SubagentCall.Source.Task)
        assertThat(SubagentCall.of(created())!!.source).isEqualTo(SubagentCall.Source.Created)
        assertThat(SubagentCall.of(sent(ToolPayload.WorkerAction.Delivery.Followup))!!.source).isEqualTo(SubagentCall.Source.Steered)
        assertThat(SubagentCall.of(sent(ToolPayload.WorkerAction.Delivery.Queue))!!.source).isEqualTo(SubagentCall.Source.Queued)
        assertThat(SubagentCall.of(sent(null))!!.source).isEqualTo(SubagentCall.Source.Messaged)
        assertThat(SubagentCall.of(stopped())!!.source).isEqualTo(SubagentCall.Source.Stopped)
        // A status check and a transcript read are the coordinator's own reading, not a row.
        val status = ToolCall("g1", "get_agent_status", ToolKind.Coordinator, ToolCall.STATUS_COMPLETED, "2 agents", payload = ToolPayload.WorkerAction(ToolPayload.WorkerAction.Kind.Status))
        val read = ToolCall("r1", "read_agent_transcript", ToolKind.Coordinator, ToolCall.STATUS_COMPLETED, "bc-w1", payload = ToolPayload.WorkerAction(ToolPayload.WorkerAction.Kind.ReadTranscript))
        assertThat(SubagentCall.of(status)).isNull()
        assertThat(SubagentCall.of(read)).isNull()
        assertThat(SubagentCall.of(ToolCall("e1", "edit", ToolKind.Edit, ToolCall.STATUS_COMPLETED, "Main.kt"))).isNull()
    }

    @Test
    fun `a failed call carries no payload, and is still its row off its name, summary and link`() {
        val create = SubagentCall.of(ToolCall("c", "createAgent", ToolKind.Coordinator, ToolCall.STATUS_COMPLETED, "Webhooks", isError = true, linkedAgentIds = listOf("bc-w2")))!!
        assertThat(create.source).isEqualTo(SubagentCall.Source.Created)
        assertThat(create.title).isEqualTo("Webhooks")
        assertThat(create.agentId).isEqualTo("bc-w2")
        val send = SubagentCall.of(ToolCall("s", "sendToAgent", ToolKind.Coordinator, ToolCall.STATUS_INTERRUPTED, "bc-w1"))!!
        assertThat(send.source).isEqualTo(SubagentCall.Source.Messaged)
        assertThat(send.title).isEqualTo(SubagentCall.FOLLOW_UP)
        assertThat(send.agentId).isEqualTo("bc-w1")
        assertThat(SubagentCall.of(ToolCall("x", "stop_agent", ToolKind.Coordinator, ToolCall.STATUS_COMPLETED, "bc-w1"))!!.source).isEqualTo(SubagentCall.Source.Stopped)
        val bareTask = SubagentCall.of(ToolCall("t", "task", ToolKind.Task, ToolCall.STATUS_RUNNING, "subagent"))!!
        assertThat(bareTask.title).isNull()
    }

    @Test
    fun `the title is the desktop's, per source`() {
        fun title(call: ToolCall, workerName: String? = null, childName: String? = null, child: SubagentChild? = null): String {
            val subagent = SubagentCall.of(call)!!
            return SubagentRows.title(subagent, SubagentRows.look(call, subagent, child), workerName, childName)
        }
        // A task: its description; none, the child's own name; neither, the desktop's word.
        assertThat(title(task())).isEqualTo("CursorBench chart hover highlight")
        assertThat(title(task(description = null), childName = "Explore the repo")).isEqualTo("Explore the repo")
        assertThat(title(task(description = null))).isEqualTo(SubagentCall.NEW_SUBAGENT)
        // A worker created: its name, else its prompt's first line, cut to 79 characters and an ellipsis.
        assertThat(title(created())).isEqualTo("Build Projects under Extended mode")
        assertThat(title(created(name = null, prompt = "Fix the Projects list: workers leak into Today.\nThen cut a release."))).isEqualTo("Fix the Projects list: workers leak into Today.")
        val long = "x".repeat(120)
        val cut = title(created(name = null, prompt = long))
        assertThat(cut).hasLength(SubagentCall.TITLE_MAX)
        assertThat(cut).endsWith("\u2026")
        // A worker cancelled before it started is the desktop's "New Agent", whatever it was to be called.
        assertThat(title(created(agentId = null, status = ToolCall.STATUS_INTERRUPTED))).isEqualTo(SubagentCall.NEW_AGENT)
        // A message: its title, else "Agent follow-up", steered or queued alike.
        assertThat(title(sent(ToolPayload.WorkerAction.Delivery.Followup))).isEqualTo("Rebase")
        assertThat(title(sent(ToolPayload.WorkerAction.Delivery.Queue, title = null))).isEqualTo(SubagentCall.FOLLOW_UP)
        // A stop: the worker's name as the call that created it gave it, else the list's, else "Agent".
        assertThat(title(stopped(), workerName = "Build Projects under Extended mode")).isEqualTo("Build Projects under Extended mode")
        assertThat(title(stopped(), childName = "Usage events aggregation")).isEqualTo("Usage events aggregation")
        assertThat(title(stopped())).isEqualTo(SubagentCall.AGENT)
    }

    // -- the state ------------------------------------------------------------------------------------------------

    @Test
    fun `a running subagent's line is its step, else its action, else planning, shimmering beside the dot grid`() {
        val stepped = look(task(), SubagentChild(SubagentChild.Status.Running, step = "Wiring the hover state", action = "Editing Chart.kt"))
        assertThat(stepped.indicator).isEqualTo(SubagentLook.Indicator.Running)
        assertThat(stepped.status).isEqualTo("Wiring the hover state")
        assertThat(stepped.active).isTrue()
        assertThat(look(task(), SubagentChild(SubagentChild.Status.Running, action = "Editing Chart.kt")).status).isEqualTo("Editing Chart.kt")
        assertThat(look(task(), SubagentChild(SubagentChild.Status.Running)).status).isEqualTo(SubagentRows.PLANNING)
        // The call alone, still running: planning.
        assertThat(look(task()).status).isEqualTo(SubagentRows.PLANNING)
    }

    @Test
    fun `finished, errored, stopped and waiting children read as the desktop's states`() {
        val done = look(task(status = ToolCall.STATUS_COMPLETED), SubagentChild(SubagentChild.Status.Succeeded))
        assertThat(done).isEqualTo(SubagentLook(SubagentLook.Indicator.Finished, SubagentRows.COMPLETED))
        assertThat(look(task(status = ToolCall.STATUS_COMPLETED))).isEqualTo(SubagentLook(SubagentLook.Indicator.Finished, SubagentRows.COMPLETED))
        val failed = look(created(), SubagentChild(SubagentChild.Status.Failed))
        assertThat(failed).isEqualTo(SubagentLook(SubagentLook.Indicator.Error, SubagentRows.STOPPED_WITH_ERROR))
        assertThat(look(task(status = ToolCall.STATUS_COMPLETED).copy(isError = true))).isEqualTo(SubagentLook(SubagentLook.Indicator.Error, SubagentRows.STOPPED_WITH_ERROR))
        val aborted = look(created(), SubagentChild(SubagentChild.Status.Aborted))
        assertThat(aborted).isEqualTo(SubagentLook(SubagentLook.Indicator.Finished, SubagentRows.STOPPED, dimmed = true))
        assertThat(look(task(status = ToolCall.STATUS_INTERRUPTED)).dimmed).isTrue()
        val waiting = look(created(), SubagentChild(SubagentChild.Status.Running, waiting = true))
        assertThat(waiting).isEqualTo(SubagentLook(SubagentLook.Indicator.Attention, SubagentRows.WAITING, attention = true))
    }

    @Test
    fun `a worker's row starts with its call, fails to start with it, and settles once a later row addresses the worker`() {
        assertThat(look(created(agentId = null, status = ToolCall.STATUS_RUNNING))).isEqualTo(SubagentLook(SubagentLook.Indicator.Running, SubagentRows.STARTING, active = true))
        assertThat(look(sent(ToolPayload.WorkerAction.Delivery.Queue, status = ToolCall.STATUS_RUNNING))).isEqualTo(SubagentLook(SubagentLook.Indicator.Running, SubagentRows.STARTING, active = true))
        assertThat(look(created().copy(isError = true))).isEqualTo(SubagentLook(SubagentLook.Indicator.Finished, SubagentRows.COULD_NOT_START, dimmed = true))
        val cancelled = look(created(agentId = null, status = ToolCall.STATUS_INTERRUPTED))
        assertThat(cancelled.indicator).isEqualTo(SubagentLook.Indicator.Error)
        assertThat(cancelled.status).isEqualTo(SubagentRows.CANCELLED)
        // Running by the list, but a later row speaks for the worker now: this one is over.
        assertThat(look(created(), SubagentChild(SubagentChild.Status.Running), latest = false)).isEqualTo(SubagentLook(SubagentLook.Indicator.Finished, SubagentRows.COMPLETED))
        // A task is never superseded.
        assertThat(look(task(), SubagentChild(SubagentChild.Status.Running), latest = false).indicator).isEqualTo(SubagentLook.Indicator.Running)
    }

    @Test
    fun `a stop reads as stopping, stopped or failing to stop`() {
        assertThat(look(stopped(status = ToolCall.STATUS_RUNNING))).isEqualTo(SubagentLook(SubagentLook.Indicator.Running, SubagentRows.STOPPING, active = true))
        assertThat(look(stopped())).isEqualTo(SubagentLook(SubagentLook.Indicator.Finished, SubagentRows.STOPPED, dimmed = true))
        assertThat(look(stopped(isError = true))).isEqualTo(SubagentLook(SubagentLook.Indicator.Error, SubagentRows.COULD_NOT_STOP))
    }

    @Test
    fun `the group a row sits in counts it as working while it runs, starts or waits, not while it stops`() {
        fun working(call: ToolCall, child: SubagentChild? = null, latest: Boolean = true, live: Boolean = true) =
            SubagentRows.isWorking(SubagentCall.of(call)!!, look(call, child, latest), child, live)
        assertThat(working(task(), SubagentChild(SubagentChild.Status.Running, step = "Wiring the hover state"))).isTrue()
        assertThat(working(task())).isTrue()
        assertThat(working(created(agentId = null, status = ToolCall.STATUS_RUNNING))).isTrue()
        assertThat(working(created(), SubagentChild(SubagentChild.Status.Running, waiting = true))).isTrue()
        assertThat(working(stopped(status = ToolCall.STATUS_RUNNING))).isFalse()
        assertThat(working(task(status = ToolCall.STATUS_COMPLETED), SubagentChild(SubagentChild.Status.Succeeded))).isFalse()
        assertThat(working(created(), SubagentChild(SubagentChild.Status.Failed))).isFalse()
        // A later row speaks for the worker: this one no longer holds the group open.
        assertThat(working(created(), SubagentChild(SubagentChild.Status.Running), latest = false)).isFalse()
    }

    @Test
    fun `once its run has ended, only a child reported at work holds the group open, not a call left running`() {
        fun working(call: ToolCall, child: SubagentChild? = null) = SubagentRows.isWorking(SubagentCall.of(call)!!, look(call, child), child, live = false)
        // The run ended with the call never closed and nothing known of the child: the row still says where the call
        // stood, but the group settles.
        assertThat(look(task()).indicator).isEqualTo(SubagentLook.Indicator.Running)
        assertThat(working(task())).isFalse()
        assertThat(working(created(agentId = null, status = ToolCall.STATUS_RUNNING))).isFalse()
        assertThat(working(task(), SubagentChild(name = "Explorer"))).isFalse()
        // A child the list or its stream reports at work, or waiting on the reader, still does.
        assertThat(working(task(), SubagentChild(SubagentChild.Status.Running, step = "Wiring the hover state"))).isTrue()
        assertThat(working(created(), SubagentChild(SubagentChild.Status.Running))).isTrue()
        assertThat(working(created(), SubagentChild(waiting = true))).isTrue()
    }

    // -- the action line ------------------------------------------------------------------------------------------

    @Test
    fun `the child's action is read newest first, the way the desktop reads its bubbles`() {
        val edit = ToolCall("e", "edit", ToolKind.Edit, ToolCall.STATUS_RUNNING, "Chart.kt")
        val shell = ToolCall("sh", "shell", ToolKind.Shell, ToolCall.STATUS_RUNNING, "./gradlew test")
        val web = ToolCall("w", "web_search", ToolKind.WebSearch, ToolCall.STATUS_COMPLETED, "compose shimmer")
        val todo = ToolCall("td", "todo_write", ToolKind.Todo, ToolCall.STATUS_COMPLETED, "", labels = ToolLabels("Updating to-dos", "Completed 2 of 5", "Update to-dos"))
        val update = ToolCall("u", "update_current_step", ToolKind.Other, ToolCall.STATUS_COMPLETED, "Wiring the hover state")
        assertThat(SubagentRows.actionOf(listOf(ActivityGroup("g", listOf(edit))))).isEqualTo("Editing Chart.kt")
        assertThat(SubagentRows.actionOf(listOf(ActivityGroup("g", listOf(web))))).isEqualTo("Searching web compose shimmer")
        // A command has no subject on the desktop's line: the step before it speaks.
        assertThat(SubagentRows.actionOf(listOf(ActivityGroup("g", listOf(edit, shell))))).isEqualTo("Editing Chart.kt")
        assertThat(SubagentRows.actionOf(listOf(ActivityGroup("g", listOf(edit, ThinkingBlock("Hmm.", isStreaming = true)))))).isEqualTo(SubagentRows.THINKING)
        assertThat(SubagentRows.actionOf(listOf(ActivityGroup("g", listOf(todo))))).isEqualTo("Completed 2 of 5")
        assertThat(SubagentRows.actionOf(listOf(ActivityGroup("g", listOf(edit, update))))).isEqualTo("Editing Chart.kt")
        // A reply: its first fifty characters, as plain text.
        val reply = AssistantMessage("a", "**Done.** The hover highlight now follows the [pointer](https://x.y) across every series in the chart.")
        assertThat(SubagentRows.actionOf(listOf(ActivityGroup("g", listOf(edit)), reply))).isEqualTo("Done. The hover highlight now follows the pointer ")
        // The prompt that started the turn ends the reading.
        assertThat(SubagentRows.actionOf(listOf(ActivityGroup("g", listOf(edit)), UserMessage("u", "Go on.")))).isNull()
        assertThat(SubagentRows.actionOf(emptyList())).isNull()
        // The step it announced.
        assertThat(SubagentRows.stepOf(listOf(ActivityGroup("g", listOf(update, edit))))).isEqualTo("Wiring the hover state")
        assertThat(SubagentRows.stepOf(listOf(ActivityGroup("g", listOf(edit))))).isNull()
        assertThat(SubagentRows.isStepUpdate("communicate_update")).isTrue()
        assertThat(SubagentRows.isStepUpdate("updateCurrentStepToolCall")).isTrue()
    }

    @Test
    fun `a run's stream moves the line on, and its end says how the child finished`() {
        val edit = ActivityGroup("g", listOf(ToolCall("e", "edit", ToolKind.Edit, ToolCall.STATUS_RUNNING, "Chart.kt")))
        val going = SubagentRows.withRun(SubagentChild(), listOf(edit), finished = false, status = RunStatus.RUNNING)
        assertThat(going.status).isEqualTo(SubagentChild.Status.Running)
        assertThat(going.action).isEqualTo("Editing Chart.kt")
        val asking = ActivityGroup("q", listOf(ToolCall("q", "ask_question", ToolKind.Question, ToolCall.STATUS_RUNNING, "", payload = ToolPayload.Question(title = null, questions = listOf(ToolPayload.Question.Item("1", "Which one?", emptyList()))))))
        assertThat(SubagentRows.withRun(SubagentChild(), listOf(edit, asking), finished = false, status = RunStatus.RUNNING).waiting).isTrue()
        val ended = SubagentRows.withRun(going, listOf(edit), finished = true, status = RunStatus.ERROR)
        assertThat(ended.status).isEqualTo(SubagentChild.Status.Failed)
        assertThat(ended.waiting).isFalse()
    }

    @Test
    fun `the list's row says how a cloud child stands, whether it waits, its name and model`() {
        assertThat(SubagentRows.statusOf(RunStatus.CREATING)).isEqualTo(SubagentChild.Status.Running)
        assertThat(SubagentRows.statusOf(RunStatus.FINISHED)).isEqualTo(SubagentChild.Status.Succeeded)
        assertThat(SubagentRows.statusOf(RunStatus.EXPIRED)).isEqualTo(SubagentChild.Status.Failed)
        assertThat(SubagentRows.statusOf(RunStatus.CANCELLED)).isEqualTo(SubagentChild.Status.Aborted)
        assertThat(SubagentRows.statusOf(null)).isNull()
        val child = SubagentRows.childOf(agent(RunStatus.RUNNING, pending = true, modelId = "composer-2.5"), models)
        assertThat(child.status).isEqualTo(SubagentChild.Status.Running)
        assertThat(child.waiting).isTrue()
        assertThat(child.name).isEqualTo("Usage events aggregation")
        assertThat(child.model?.label).isEqualTo("Composer 2.5")
        // A finished child waits on nothing.
        assertThat(SubagentRows.childOf(agent(RunStatus.FINISHED, pending = true), models).waiting).isFalse()
    }

    // -- the model and the placement ------------------------------------------------------------------------------

    @Test
    fun `the model label is the type's name for a built-in type, nothing for a review, else the model with Fast`() {
        assertThat(SubagentRows.modelLabel(SubagentCall.of(task(type = "explore"))!!, models)).isEqualTo(SubagentModel("Explorer"))
        assertThat(SubagentRows.modelLabel(SubagentCall.of(task(type = "computerUse"))!!, models)).isEqualTo(SubagentModel("Computer Use"))
        assertThat(SubagentRows.modelLabel(SubagentCall.of(task(type = "bugbot", model = "composer-2.5"))!!, models)).isNull()
        assertThat(SubagentRows.modelLabel(SubagentCall.of(task(model = "composer-2.5"))!!, models)).isEqualTo(SubagentModel("Composer 2.5", fast = true))
        val opus = SubagentRows.modelLabel(SubagentCall.of(task(model = "claude-opus-5-5-high"))!!, models)!!
        assertThat(opus.label).startsWith("Claude Opus 5.5")
        assertThat(opus.fast).isFalse()
        // A message names no model: the one the worker was created with, from the call that created it.
        assertThat(SubagentRows.modelLabel(SubagentCall.of(sent(null))!!, models, workerModelId = "composer-2.5")).isEqualTo(SubagentModel("Composer 2.5", fast = true))
        // Else the model the child runs on, as the list says.
        assertThat(SubagentRows.modelLabel(SubagentCall.of(sent(null))!!, models, child = agent(RunStatus.RUNNING, modelId = "composer-2.5"))?.label).isEqualTo("Composer 2.5")
        assertThat(SubagentRows.modelLabel(SubagentCall.of(sent(null))!!, models)).isNull()
    }

    @Test
    fun `the placement glyph follows the desktop's rule for where the child runs`() {
        val cloud = SubagentPlacement.Cloud
        // A worker has a VM of its own: the cloud, or the self-hosted machine it was sent to.
        assertThat(SubagentRows.placement(SubagentCall.of(created())!!, cloud)).isEqualTo(SubagentPlacement.Cloud)
        assertThat(SubagentRows.placement(SubagentCall.of(created(workerId = "w-1"))!!, cloud)).isEqualTo(SubagentPlacement.Machine)
        assertThat(SubagentRows.placement(SubagentCall.of(created())!!, cloud, agent(RunStatus.RUNNING, env = EnvType.MACHINE))).isEqualTo(SubagentPlacement.Machine)
        // A task asked to run locally shares its parent's VM: no glyph. Asked for the cloud: the cloud.
        assertThat(SubagentRows.placement(SubagentCall.of(task(environment = "SUBAGENT_EXECUTION_ENVIRONMENT_LOCAL"))!!, cloud)).isNull()
        assertThat(SubagentRows.placement(SubagentCall.of(task(environment = "2"))!!, cloud)).isEqualTo(SubagentPlacement.Cloud)
        // Neither said: the glyph only when the child runs somewhere its parent does not.
        assertThat(SubagentRows.placement(SubagentCall.of(task(agentId = "bc-t1"))!!, cloud)).isNull()
        assertThat(SubagentRows.placement(SubagentCall.of(task(agentId = "bc-t1"))!!, SubagentPlacement.Machine)).isEqualTo(SubagentPlacement.Cloud)
        assertThat(SubagentRows.placement(SubagentCall.of(task())!!, cloud)).isNull()
        assertThat(SubagentPlacement.parse("1")).isEqualTo(SubagentPlacement.Local)
        assertThat(SubagentPlacement.parse("cloud")).isEqualTo(SubagentPlacement.Cloud)
        assertThat(SubagentPlacement.parse("unspecified")).isNull()
    }

    // -- across the transcript ------------------------------------------------------------------------------------

    @Test
    fun `the index names each worker's newest row and the name and model it was created with`() {
        val create = created(model = "composer-2.5")
        val steer = sent(ToolPayload.WorkerAction.Delivery.Followup, callId = "s1")
        val queue = sent(ToolPayload.WorkerAction.Delivery.Queue, callId = "s2")
        // The rows sit among the other steps of the stretches they were made in.
        fun stretch(vararg calls: ToolCall) = TranscriptRow.Stretch(calls.map { TranscriptRow.Entry.Call(it, "g:${it.callId}") })
        val read = ToolCall("r1", "read_file", ToolKind.Read, ToolCall.STATUS_COMPLETED, "Main.kt")
        val rows = listOf(stretch(read, create, steer), stretch(queue, read.copy(callId = "r2"), task()))
        val index = SubagentRows.index(rows)
        assertThat(index.latest).containsExactly("bc-w1", "s2")
        assertThat(index.workers).containsExactly("bc-w1", SubagentRows.Index.Worker("Build Projects under Extended mode", "composer-2.5"))
        assertThat(index.isLatest(create, SubagentCall.of(create)!!)).isFalse()
        assertThat(index.isLatest(steer, SubagentCall.of(steer)!!)).isFalse()
        assertThat(index.isLatest(queue, SubagentCall.of(queue)!!)).isTrue()
        assertThat(index.isLatest(task(), SubagentCall.of(task())!!)).isTrue()
        assertThat(SubagentRows.index(emptyList())).isSameInstanceAs(SubagentRows.Index.EMPTY)
    }

    // -- a subagent's notice --------------------------------------------------------------------------------------

    private fun notice(
        id: String = "n1",
        title: String = "Subagent completed",
        summary: String? = "CursorBench chart hover highlight",
        kind: SystemNotification.Kind = SystemNotification.Kind.Subagent,
        agentId: String? = null,
        callId: String? = null,
    ) = SystemNotification(id, kind, title, summary, body = "Done.", raw = "<system_notification/>", agentId = agentId, callId = callId)

    private fun events(vararg notices: SystemNotification) = TranscriptRow.Entry.Events(TranscriptRow.Events(notices.map { TranscriptRow.Event(it) }))

    @Test
    fun `a notice says how its child ended in the row's own words`() {
        fun status(title: String) = SubagentRows.notice(notice(title = title), start = null)!!.look
        assertThat(status("Subagent completed")).isEqualTo(SubagentLook(SubagentLook.Indicator.Finished, SubagentRows.COMPLETED))
        assertThat(status("Subagent finished")).isEqualTo(SubagentLook(SubagentLook.Indicator.Finished, SubagentRows.COMPLETED))
        assertThat(status("Subagent failed")).isEqualTo(SubagentLook(SubagentLook.Indicator.Error, SubagentRows.STOPPED_WITH_ERROR))
        assertThat(status("Worker timed out")).isEqualTo(SubagentLook(SubagentLook.Indicator.Error, SubagentRows.STOPPED_WITH_ERROR))
        assertThat(status("Subagent cancelled")).isEqualTo(SubagentLook(SubagentLook.Indicator.Finished, SubagentRows.STOPPED, dimmed = true))
        // A worker's update is not an ending: its row says what it said, under the worker's name.
        val update = SubagentRows.notice(notice(title = "Worker update", summary = "Rebased onto main; CI is green.", kind = SystemNotification.Kind.Worker, agentId = "bc-w1"), start = null, workerName = "Land the merge train")!!
        assertThat(update.title).isEqualTo("Land the merge train")
        assertThat(update.look).isEqualTo(SubagentLook(SubagentLook.Indicator.Finished, "Rebased onto main; CI is green."))
        assertThat(update.subagent.agentId).isEqualTo("bc-w1")
        assertThat(update.subagent.isWorker).isTrue()
        // Anything else injected is not a subagent's row.
        assertThat(SubagentRows.notice(notice(kind = SystemNotification.Kind.Task, title = "Shell completed"), start = null)).isNull()
        assertThat(SubagentRows.notice(notice(kind = SystemNotification.Kind.Other, title = "GitHub notification"), start = null)).isNull()
    }

    @Test
    fun `a notice takes its title, model and agent from the call that started the child, else from itself`() {
        val start = SubagentCall.of(task(model = "composer-2.5", environment = "SUBAGENT_EXECUTION_ENVIRONMENT_CLOUD"))!!
        val bound = SubagentRows.notice(notice(summary = "Chart hover", agentId = "bc-c1"), start)!!
        assertThat(bound.title).isEqualTo("CursorBench chart hover highlight")
        assertThat(bound.subagent.modelId).isEqualTo("composer-2.5")
        assertThat(bound.subagent.agentId).isEqualTo("bc-c1")
        assertThat(SubagentRows.placement(bound.subagent, SubagentPlacement.Cloud)).isEqualTo(SubagentPlacement.Cloud)
        // Without one, its own title; a worker's is the worker's row, a cloud one openable.
        val alone = SubagentRows.notice(notice(title = "Worker completed", summary = "Backfill the fills ledger", kind = SystemNotification.Kind.Worker, agentId = "bc-f1"), start = null)!!
        assertThat(alone.title).isEqualTo("Backfill the fills ledger")
        assertThat(alone.subagent.source).isEqualTo(SubagentCall.Source.Created)
        assertThat(alone.subagent.isCloudAgent).isTrue()
        assertThat(SubagentRows.notice(notice(summary = null), start = null, childName = "Quote tracker")!!.title).isEqualTo("Quote tracker")
        assertThat(SubagentRows.notice(notice(summary = null), start = null)!!.title).isEqualTo(SubagentCall.NEW_SUBAGENT)
    }

    @Test
    fun `the index binds each notice to the call before it that started its child, and keeps how it ended`() {
        val cloud = task(description = "Track big-pool lone quoters daily", agentId = "bc-q1", status = ToolCall.STATUS_COMPLETED).copy(callId = "t1")
        val local = task(description = "Price the maker rebate tiers", status = ToolCall.STATUS_COMPLETED).copy(callId = "t2")
        val again = local.copy(callId = "t3")
        val worker = created(name = "Backfill the fills ledger", agentId = "bc-f1")
        val byAgent = notice("n1", summary = "Some other words", agentId = "bc-q1")
        val byCall = notice("n2", title = "Subagent failed", summary = "Renamed since", callId = "t2")
        val byTitle = notice("n3", title = "Subagent cancelled", summary = "Price the maker rebate tiers")
        val ofWorker = notice("n4", title = "Worker completed", summary = "Backfill the fills ledger", kind = SystemNotification.Kind.Worker, agentId = "bc-f1")
        val early = notice("n0", summary = "Price the maker rebate tiers")
        val rows = listOf(
            TranscriptRow.Stretch(listOf(TranscriptRow.Entry.Event(TranscriptRow.Event(early)))),
            TranscriptRow.Stretch(listOf(TranscriptRow.Entry.Call(cloud, "g:t1"), TranscriptRow.Entry.Call(local, "g:t2"), TranscriptRow.Entry.Call(worker, "g:c1"))),
            TranscriptRow.Stretch(listOf(events(byAgent, byCall, ofWorker))),
            TranscriptRow.Stretch(listOf(TranscriptRow.Entry.Call(again, "g:t3"), TranscriptRow.Entry.Event(TranscriptRow.Event(byTitle)))),
        )
        val index = SubagentRows.index(rows)
        assertThat(index.startOf(byAgent)).isEqualTo(SubagentCall.of(cloud))
        assertThat(index.startOf(byCall)).isEqualTo(SubagentCall.of(local))
        assertThat(index.startOf(ofWorker)).isEqualTo(SubagentCall.of(worker))
        // By title, the newest call with it before the notice; a notice before any call binds to none.
        assertThat(index.startOf(byTitle)).isEqualTo(SubagentCall.of(again))
        assertThat(index.startOf(early)).isNull()
        assertThat(index.endingOf(cloud)).isEqualTo(SubagentChild.Status.Succeeded)
        assertThat(index.endingOf(local)).isEqualTo(SubagentChild.Status.Failed)
        assertThat(index.endingOf(again)).isEqualTo(SubagentChild.Status.Aborted)
        assertThat(index.endingOf(worker)).isEqualTo(SubagentChild.Status.Succeeded)
        // A background task's call returned before its child: the notice's ending is what its row says.
        assertThat(look(local, SubagentChild(status = index.endingOf(local)))).isEqualTo(SubagentLook(SubagentLook.Indicator.Error, SubagentRows.STOPPED_WITH_ERROR))
    }
}
