package com.cursorforandroid.fixtures

import com.cursorforandroid.data.api.CursorJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * `fixtures/machines/machine_start_shapes.json`: the fleet listing for the user's machines (Bennett's shape among
 * them: registered as `bennett/codex-poly-bot`, no `repoUrl`), the desktop's start for a machine picked with its
 * repository, the account's answer, and its refusal of a machine that is not connected.
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

    /** The account's answer to a start, for the chat [bcId]. */
    fun response(bcId: String): String = shapes["response"]!!.toString().replace("BC_ID", bcId)

    val offline: JsonObject get() = shapes["refusals"]!!.jsonObject["offline"]!!.jsonObject

    const val BENNETT_WORKER = "5f1c9d2a-3b4e-4f60-8a71-92b3c4d5e6f7"
    const val STUDIO_WORKER = "c2b1a0f9-8e7d-4c6b-a5f4-e3d2c1b0a9f8"
    const val OWNER = 42L
}
