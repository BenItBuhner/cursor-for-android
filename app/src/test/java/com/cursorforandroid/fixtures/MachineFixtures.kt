package com.cursorforandroid.fixtures

import com.cursorforandroid.data.api.CursorJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * `fixtures/machines/machine_start_shapes.json`: the fleet listing for the user's machines (Bennett's shape among
 * them: registered as `bennett/codex-poly-bot`, no `repoUrl`), the account's environments and branch list, the
 * desktop's start for a machine picked with its repository — with and without an account environment for it — what
 * the app sent before it resolved the repository the desktop's way, the account's answer, and its refusals.
 */
object MachineFixtures {

    val shapes: JsonObject by lazy {
        val text = requireNotNull(MachineFixtures::class.java.getResourceAsStream("/fixtures/machines/machine_start_shapes.json")) { "missing machine fixtures" }
            .bufferedReader().use { it.readText() }
        CursorJson.parseToJsonElement(text).jsonObject
    }

    /** `GET /v0/private-workers?scope=personal` as [state] (`online`, `studioOffline`) has it. */
    fun workers(state: String): String = shapes["workers"]!!.jsonObject[state]!!.toString()

    /** The fields the desktop's start carries for [machine] that a cloud start does not, or that differ from one. */
    fun start(machine: String): JsonObject = shapes["start"]!!.jsonObject[machine]!!.jsonObject

    /** The account's answer to a start, for the chat [bcId] — on [repoUrl] when the start named another repository. */
    fun response(bcId: String, repoUrl: String = CODEX_GUESS): String =
        shapes["response"]!!.toString().replace("BC_ID", bcId).replace("\"repoUrl\":\"$CODEX_GUESS\"", "\"repoUrl\":\"$repoUrl\"")

    /** `ListEnvironments` as [state] (`withCodexPolyBot`, `none`) has it. */
    fun environments(state: String): String = shapes["environments"]!!.jsonObject[state]!!.toString()

    /** `GetRepositoryBranches` for [repo] (`codexPolyBot`). */
    fun branches(repo: String): String = shapes["branches"]!!.jsonObject[repo]!!.toString()

    /** The start the app sent for [machine] before it named the account's own repository (PR #301). */
    fun sentByPr301(machine: String): JsonObject = shapes["sentByPr301"]!!.jsonObject[machine]!!.jsonObject

    val offline: JsonObject get() = shapes["refusals"]!!.jsonObject["offline"]!!.jsonObject
    val noAccess: JsonObject get() = shapes["refusals"]!!.jsonObject["noAccess"]!!.jsonObject

    const val CODEX_ORIGIN = "https://origin.cursor.com/bennett/codex-poly-bot"
    const val CODEX_GUESS = "https://github.com/bennett/codex-poly-bot"

    const val BENNETT_WORKER = "5f1c9d2a-3b4e-4f60-8a71-92b3c4d5e6f7"
    const val STUDIO_WORKER = "c2b1a0f9-8e7d-4c6b-a5f4-e3d2c1b0a9f8"
    const val OWNER = 42L
}
