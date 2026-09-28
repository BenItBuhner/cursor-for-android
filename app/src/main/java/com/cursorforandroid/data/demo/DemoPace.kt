package com.cursorforandroid.data.demo

import java.io.File

/**
 * How fast the demo's scripted runs play. [Brisk] is the default: quick enough for tests and a first look. [Realistic]
 * plays at a real model's speed — replies and thinking under 100 tokens a second, tool calls and subagents taking
 * seconds — for recordings; a debug build uses it when the marker file [MARKER] exists in the app's cache directory
 * (`adb shell run-as com.cursorforandroid.debug touch cache/demo-realistic-pace`).
 */
data class DemoPace(
    /** Characters per reply chunk, and the pause after each. */
    val typeChunk: Int,
    val typeDelayMs: Long,
    /** Words per thinking chunk, and the pause after each. */
    val thinkWords: Int,
    val thinkDelayMs: Long,
    /** Multiplies the scripts' pauses between steps: how long a tool call or a subagent takes. */
    val stepScale: Double,
) {
    fun step(ms: Long): Long = (ms * stepScale).toLong()

    companion object {
        const val MARKER = "demo-realistic-pace"

        val Brisk = DemoPace(typeChunk = 18, typeDelayMs = 22, thinkWords = 6, thinkDelayMs = 60, stepScale = 1.0)

        /** 12 characters (about 3 tokens) per 34 ms is some 88 tokens a second; 6 words (about 8 tokens) per 90 ms, 89. */
        val Realistic = DemoPace(typeChunk = 12, typeDelayMs = 34, thinkWords = 6, thinkDelayMs = 90, stepScale = 10.0)

        fun enabled(cacheDir: File?): Boolean = cacheDir != null && File(cacheDir, MARKER).exists()
    }
}
