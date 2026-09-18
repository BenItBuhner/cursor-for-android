package com.cursorforandroid.data.demo

import com.cursorforandroid.domain.AgentSource
import java.io.File

/**
 * Two long chats for measuring the transcript on a device, in the shape Bennett's account has them, added to the
 * demo's seeds only in a debug build when the marker file [MARKER] exists in the app's cache directory (`adb shell
 * run-as com.cursorforandroid.debug touch cache/perf-seed`). Never part of the demo anyone else sees: the seeds are
 * generated on demand and cost the dataset nothing until asked for.
 *
 *  - The coordinator's chat: 420 turns of which 35 are the user's prompts and the rest turns Cursor injected
 *    (subagent completions under both instruction templates, pull request changes), with the coordinator's
 *    `SendMessage` updates (some 200), `createAgent` / `sendToAgent` / `getAgentStatus` calls and reads with
 *    4 000-character payloads, the newest turn live.
 *  - The ordinary chat: 300 turns of six reads with 6 000-character payloads and a markdown reply each, the newest
 *    turn live.
 *
 * The payloads are smaller than the 40 000 characters the field carries: the demo keeps every turn's event log in
 * memory at once, where the real backend holds only the window's, and the device's heap is the measurement's
 * subject, not the demo's.
 */
internal object DemoPerfSeeds {
    const val MARKER = "perf-seed"

    const val COORDINATOR_ID = "bc-perf-coordinator"
    const val ORDINARY_ID = "bc-perf-ordinary"

    fun enabled(cacheDir: File?): Boolean = cacheDir != null && File(cacheDir, MARKER).exists()

    fun seeds(): List<DemoData.Seed> = listOf(coordinator(), ordinary())

    private const val COORDINATOR_TURNS = 420
    private const val ORDINARY_TURNS = 300
    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN

    private fun payload(seed: Int, chars: Int): String = buildString(chars) {
        var i = 0
        while (length < chars) append("line ").append(seed).append('-').append(i++).append(": val x = compute(y) // some code that the tool returned\n")
        setLength(chars)
    }

    private fun json(text: String): String = buildString {
        append('"')
        text.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                else -> append(c)
            }
        }
        append('"')
    }

    private fun read(t: Int, k: Int, chars: Int): DemoData.Step.Call {
        val path = json("app/src/render/Shard$t-$k.kt")
        val content = json(payload(t * 100 + k, chars))
        return DemoData.Step.Call("read_file", "{\"path\": $path}", "{\"success\": {\"content\": $content, \"path\": $path}}")
    }

    private fun update(t: Int): String = """
        |**Turn $t update.** The render shard for R6 landed and the slice cutter is on its second pass.
        |
        |- Kitchen v2: PR opened, CI green, awaiting the merge train.
        |- Backyard v2: rendering, 60% of frames, ETA an hour.
        |- Limb passes: the hand worker reports `arm_rig_v3` needs a re-weight; queued.
        |
        |```kotlin
        |val slices = scene.cut(everyFrames = 24).reorder(byMotion = true)
        |```
        |
        |Next: land the kitchen PR, then re-run the limb pass with the new weights.
    """.trimMargin()

    private fun sendMessage(t: Int) = DemoData.Step.Call("SendMessage", """{"text": {"content": ${json(update(t))}}}""", """{"success": {"timestamp": "1772559120000", "messageId": "msg_perf_$t"}}""")

    private fun worker(t: Int): String = "bc-%08d-1a2d-5218-8e2e-7464ea74f671".format(t)

    private fun injectedSubagent(t: Int): String {
        val query = if (t % 2 == 0) {
            "The beginning of the above subagent result is already visible to the user. Perform any follow-up actions (if needed). DO NOT regurgitate or reiterate its result unless asked. If you respond, end your response with a brief third-person confirmation of what was done and link it with the `[label](id)` syntax. Don't repeat the same confirmation every time."
        } else {
            "Perform any necessary follow-up actions in response to the subagent completion above. If no follow-up work is needed, no further action is required. If you mention an agent or subagent in your response, link it with the `[Name](id)` Don't use generic label such as `[agent]`, `[worker]`, or `[subagent]`. Don't repeat the same confirmation every time."
        }
        val detail = "The shard landed: #${100 + t} merged, frames 1-240 rendered at 24fps, the reorder table written to `renders/slices.json`.\n\nChecks:\n- CI green on the merge commit\n- Renders match the reference within 2% SSIM\n- No dropped frames in the motion pass"
        return "<timestamp>Saturday, Sep 12, 2026, 11:05 PM (UTC)</timestamp>\n<system_notification>\nThe following task has finished. If you were already aware, ignore this notification and do not restate prior responses.\n\n<task>\nkind: subagent\nstatus: success\ntask_id: ${worker(t)}\ntitle: Render shard R6-${t}of2 &amp; slice cut\ntool_call_id: toolu_01CR$t\nagent_id: ${worker(t)}\ndetail: This is the last output of the subagent:\n\n$detail\n\nAgent ID: ${worker(t)} (can be used with the `resume` parameter to send a follow-up)\n</task>\n</system_notification>\n<user_query>$query</user_query>"
    }

    private fun injectedGitHub(t: Int): String =
        "<system_notification source=\"github\" pr=\"https://github.com/BenItBuhner/revenue-scaling-pipeline/pull/${100 + t}\" action=\"${if (t % 14 == 0) "merged" else "synchronize"}\" sender=\"cursor[bot]\">A subscribed pull request changed. Use the linked PR for details if needed.</system_notification>"

    private fun coordinator(): DemoData.Seed {
        val earlier = (1 until COORDINATOR_TURNS).map { t ->
            when {
                t % 12 == 1 -> DemoData.Turn(
                    prompt = "Prompt $t: make the phone renders look like the reference shots, fast dynamic movement, and cut every scene into slices we can reorder. Keep the shard workers busy and report as each lands.",
                    replies = listOf("Bennett wants the render pipeline redone around reorderable slices; the shard workers can take that in parallel."),
                    durationMs = 4 * MIN,
                    trace = listOf(
                        DemoData.Step.Reply,
                        DemoData.Step.Call("createAgent", """{"title": ${json("Render kitchen v2 shard R6-${t}of2")}, "message": "Render the shard and open a PR.", "repo": "BenItBuhner/revenue-scaling-pipeline"}""", """{"agentId": ${json(worker(t))}, "toolCallId": "toolu_create_$t"}"""),
                        read(t, 1, 4_000), read(t, 2, 4_000), read(t, 3, 4_000), read(t, 4, 4_000), read(t, 5, 4_000), read(t, 6, 4_000), read(t, 7, 4_000), read(t, 8, 4_000),
                        DemoData.Step.Call("sendToAgent", """{"agentId": ${json(worker(t))}, "message": "Start with the kitchen shard.", "delivery": "queue"}""", """{"success": {"workerBcId": ${json(worker(t))}, "deliveredAs": "queue"}}"""),
                        sendMessage(t),
                    ),
                )
                t % 7 == 0 -> DemoData.Turn(
                    prompt = injectedGitHub(t),
                    replies = listOf("Noted; nothing to do for #${100 + t} until CI reports."),
                    durationMs = 20_000L,
                    trace = listOf(DemoData.Step.Reply),
                )
                else -> DemoData.Turn(
                    prompt = injectedSubagent(t),
                    replies = listOf("Logged; [Render shard](${worker(t)}) is on its next shard and the slice cutter has the reorder table."),
                    durationMs = 2 * MIN,
                    trace = buildList {
                        add(DemoData.Step.Call("getAgentStatus", "{}", """{"success": {"workers": [{"bcId": ${json(worker(t))}, "name": "Render shard", "lifecycle": "RUNNING"}, {"bcId": ${json(worker(t + 1))}, "name": "Slice cutter", "lifecycle": "FINISHED"}]}}"""))
                        add(read(t, 1, 4_000)); add(read(t, 2, 4_000)); add(read(t, 3, 4_000))
                        add(DemoData.Step.Reply)
                        if (t % 2 == 0) add(sendMessage(t))
                    },
                )
            }
        }
        return DemoData.Seed(
            id = COORDINATOR_ID, name = "Perf: Revenue Scaling Pipeline (coordinator)", repo = "https://github.com/BenItBuhner/revenue-scaling-pipeline", ageMillis = 3 * MIN,
            source = AgentSource.WEBSITE, runStatus = "RUNNING", lifecycle = "ACTIVE",
            prompt = "Prompt $COORDINATOR_TURNS: land the kitchen PR and re-run the limb pass with the new weights; report as each lands.",
            liveScript = "cesium",
            earlier = earlier,
        )
    }

    private fun ordinary(): DemoData.Seed {
        val earlier = (1 until ORDINARY_TURNS).map { t ->
            DemoData.Turn(
                prompt = "Prompt $t: please refactor the ${t}th module and explain the change in a short paragraph with a list of the files touched.",
                replies = listOf("Refactored module $t.\n\n- `File1.kt`: split the class\n- `File2.kt`: moved the helper\n\n```kotlin\nval x = $t\n```"),
                durationMs = 3 * MIN + (t % 7) * 10_000L,
                trace = listOf(
                    DemoData.Step.Thought("Turn $t: reading the module before changing it."),
                    read(t, 1, 6_000), read(t, 2, 6_000), read(t, 3, 6_000), read(t, 4, 6_000), read(t, 5, 6_000), read(t, 6, 6_000),
                    DemoData.Step.Reply,
                ),
            )
        }
        return DemoData.Seed(
            id = ORDINARY_ID, name = "Perf: long ordinary chat", repo = "https://github.com/BenItBuhner/cursor-for-android", ageMillis = 2 * MIN,
            runStatus = "RUNNING", lifecycle = "ACTIVE",
            prompt = "Prompt $ORDINARY_TURNS: refactor the last module and summarise every change made so far in this chat.",
            liveScript = "cesium",
            earlier = earlier,
        )
    }
}
