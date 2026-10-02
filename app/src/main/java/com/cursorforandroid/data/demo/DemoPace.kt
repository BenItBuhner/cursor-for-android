package com.cursorforandroid.data.demo

import java.io.File

/**
 * How fast the demo's scripted runs play. [Brisk] is the default: quick enough for tests and a first look. [Realistic]
 * plays at a real model's speed — replies and thinking under 100 tokens a second, tool calls and subagents taking
 * seconds — for recordings; a debug build uses it when the marker file [MARKER] exists in the app's cache directory
 * (`adb shell run-as com.cursorforandroid.debug touch cache/demo-realistic-pace`). Writing a larger step scale into
 * it (`echo 40 > cache/demo-realistic-pace`) stretches tool calls and subagents further; replies keep their speed.
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

        /** [Realistic] when the marker exists; a number in it replaces the step scale, for a device too slow to keep up. */
        fun fromMarker(cacheDir: File?): DemoPace? {
            val marker = cacheDir?.let { File(it, MARKER) }?.takeIf { it.exists() } ?: return null
            val scale = runCatching { marker.readText().trim().toDoubleOrNull() }.getOrNull()
            return if (scale != null && scale >= Realistic.stepScale) Realistic.copy(stepScale = scale) else Realistic
        }
    }
}
