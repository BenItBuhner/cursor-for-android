package com.cursorforandroid.ui.components

/** Placeholder for the baseline measurement: every parse from scratch. */
object MarkdownCache {
    fun parse(markdown: String): List<MdBlock> = MarkdownParser.parse(markdown)
    fun clear() = Unit
}
