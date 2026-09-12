package com.cursorforandroid.domain

/**
 * Slash commands the way the web composer attaches them: `/multitask` and `/skill-name` apply to the message they
 * lead. The prompt itself is plain text, so a command is a standalone `/name` token kept at the front of it; the
 * composer paints the tokens in the Cursor orange and wears `/multitask` as a pill (see `ModePills`), but what
 * leaves the device is the text with the token in it.
 */
object SlashCommands {
    /** `/multitask`: run async subagents in parallel instead of queueing (the "Multitask" row of the "+" menu). */
    const val MULTITASK = "multitask"
    /**
     * `/plan`: the composer's shorthand for plan mode. Never part of the prompt — typing or picking it turns the plan
     * pill on, and the run is asked for `mode: "plan"` the same way the model picker's toggle asks for it.
     */
    const val PLAN = "plan"
    /**
     * `/ask` and `/debug`: the composer's shorthand for Ask and Debug modes (`agent.v1.AgentMode` ASK and DEBUG), which
     * only the account's follow-up RPC can carry. Pills like `/plan` where Extended mode allows the mode; text otherwise.
     */
    const val ASK = "ask"
    const val DEBUG = "debug"

    private val NAME = Regex("^[a-z0-9][a-z0-9-]*$")
    private val TOKEN = Regex("(?<=^|\\s)/([a-z0-9][a-z0-9-]*)(?=\\s|$)")
    /** The run of commands that opens the text, e.g. `/multitask /review ` in `/multitask /review fix it`. */
    private val LEADING = Regex("^(?:\\s*/[a-z0-9][a-z0-9-]*(?=\\s|$))+\\s*")

    /** Skill and command names: lowercase letters, digits and hyphens, as Cursor requires for `SKILL.md`. */
    fun isValidName(name: String): Boolean = NAME.matches(name)

    /** Every `/command` token in [text], in order. */
    fun commands(text: String): List<String> = TOKEN.findAll(text).map { it.groupValues[1] }.toList()

    /** The span of every `/command` token in [text], in order — what the composer paints as a command. */
    fun tokenRanges(text: String): List<IntRange> = TOKEN.findAll(text).map { it.range }.toList()

    /** Whether [text] carries `/name` as a standalone token. */
    fun has(text: String, name: String): Boolean = name in commands(text)

    /** Adds `/name` when absent, removes it when present. */
    fun toggle(text: String, name: String): String = if (has(text, name)) remove(text, name) else add(text, name)

    /** Inserts `/name` after any commands already leading the text, so commands stay grouped at the front. */
    fun add(text: String, name: String): String {
        require(isValidName(name)) { "Not a slash command name: $name" }
        if (has(text, name)) return text
        val lead = LEADING.find(text)?.value ?: ""
        val head = lead.trim()
        val rest = text.substring(lead.length).trimStart()
        val prefix = if (head.isEmpty()) "/$name" else "$head /$name"
        return "$prefix $rest"
    }

    /** The text without any of its commands: what the request is about, e.g. for a derived title. */
    fun strip(text: String): String = commands(text).fold(text) { acc, name -> remove(acc, name) }

    /** Removes the first `/name` token and the whitespace it owned. */
    fun remove(text: String, name: String): String {
        val match = TOKEN.findAll(text).firstOrNull { it.groupValues[1] == name } ?: return text
        val before = text.substring(0, match.range.first)
        val after = text.substring(match.range.last + 1)
        return when {
            before.isBlank() -> after.trimStart()
            after.isBlank() -> before.trimEnd()
            else -> before.trimEnd() + " " + after.trimStart()
        }
    }
}
