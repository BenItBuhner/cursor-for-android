package com.cursorforandroid.promo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/** One text of the scripted run, cut into pieces that each decode on their own and hold [PromoText.tokens] between them. */
class PromoText(val key: String, val pieces: List<Pair<String, Int>>) {
    val text: String get() = pieces.joinToString("") { it.first }
    val tokens: Int get() = pieces.sumOf { it.second }
}

/** One file's change: an edit of the run (with the [tokens] its added lines hold), or a file of the pull request. */
class PromoEdit(val path: String, val diff: String, val added: Int, val removed: Int, val tokens: Int = 0)

/**
 * promo/capture/generated/script.json, as scripts/prepare.py wrote it: the run's texts, its edits by key (`path`, or
 * `path#name` for a file's later edits) and the pull request's files, each file's whole change.
 */
class PromoScript(val texts: Map<String, PromoText>, val edits: Map<String, PromoEdit>, val pullRequest: List<PromoEdit>) {
    fun text(key: String): PromoText = checkNotNull(texts[key]) { "No text $key in script.json" }
    fun edit(key: String): PromoEdit = checkNotNull(edits[key]) { "No edit $key in script.json" }

    companion object {
        fun load(root: File = Promo.root): PromoScript {
            val json = Json.parseToJsonElement(File(root, "capture/generated/script.json").readText()).jsonObject
            val texts = json.getValue("texts").jsonObject.mapValues { (key, value) ->
                PromoText(key, value.jsonObject.getValue("pieces").jsonArray.map { piece ->
                    val pair = piece.jsonArray
                    pair[0].jsonPrimitive.content to pair[1].jsonPrimitive.int
                })
            }
            val edits = json.getValue("edits").jsonObject.mapValues { (_, value) ->
                val o = value.jsonObject
                PromoEdit(
                    o.getValue("path").jsonPrimitive.content, o.getValue("diff").jsonPrimitive.content,
                    o.getValue("added").jsonPrimitive.int, o.getValue("removed").jsonPrimitive.int, o.getValue("tokens").jsonPrimitive.int,
                )
            }
            val pullRequest = json.getValue("pr").jsonObject.map { (path, value) ->
                val o = value.jsonObject
                PromoEdit(path, o.getValue("diff").jsonPrimitive.content, o.getValue("added").jsonPrimitive.int, o.getValue("removed").jsonPrimitive.int)
            }
            return PromoScript(texts, edits, pullRequest)
        }
    }
}

/**
 * Every burst of model output the scripted backend emitted, at the virtual time it went out. The capture fails if
 * any one-second window holds more than [LIMIT] tokens, and the log is kept beside the video as the proof.
 */
object PacingLog {
    const val LIMIT = 100

    class Burst(val atMs: Long, val tokens: Int, val kind: String, val key: String)

    private val bursts = mutableListOf<Burst>()

    @Synchronized
    fun reset() = bursts.clear()

    @Synchronized
    fun record(atMs: Long, tokens: Int, kind: String, key: String) {
        bursts += Burst(atMs, tokens, kind, key)
    }

    /** The most tokens emitted in any window [t, t + 1000) that starts at a burst: the worst closed second. */
    @Synchronized
    fun peakPerSecond(): Int = bursts.maxOfOrNull { start -> bursts.filter { it.atMs >= start.atMs && it.atMs < start.atMs + 1000 }.sumOf { it.tokens } } ?: 0

    @Synchronized
    fun write(file: File) {
        val json = buildJsonObject {
            put("limitPerSecond", LIMIT)
            put("peakPerSecond", peakPerSecond())
            put("bursts", buildJsonArray {
                bursts.forEach { b -> add(buildJsonObject { put("t", b.atMs); put("tokens", b.tokens); put("kind", b.kind); put("key", b.key) }) }
            })
        }
        file.parentFile?.mkdirs()
        file.writeText(json.toString())
    }

    fun check() {
        val peak = peakPerSecond()
        check(peak <= LIMIT) { "Streamed $peak tokens in one second; the limit is $LIMIT" }
    }
}
