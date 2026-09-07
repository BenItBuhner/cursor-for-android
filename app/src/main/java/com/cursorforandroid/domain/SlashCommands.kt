package com.cursorforandroid.domain

/**
 * Slash commands the way the web composer attaches them: `/multitask` and `/skill-name` apply to the message they
 * lead. The composer here is plain text, so a command is a standalone `/name` token kept at the front of the prompt
 * rather than a pill.
 */
object SlashCommands {
    /** `/multitask`: run async subagents in parallel instead of queueing (the "Multitask" row of the "+" menu). */
    const val MULTITASK = "multitask"

    private val NAME = Regex("^[a-z0-9][a-z0-9-]*$")
    private val TOKEN = Regex("(?<=^|\\s)/([a-z0-9][a-z0-9-]*)(?=\\s|$)")
    /** The run of commands that opens the text, e.g. `/multitask /review ` in `/multitask /review fix it`. */
    private val LEADING = Regex("^(?:\\s*/[a-z0-9][a-z0-9-]*(?=\\s|$))+\\s*")

    /** Skill and command names: lowercase letters, digits and hyphens, as Cursor requires for `SKILL.md`. */
    fun isValidName(name: String): Boolean = NAME.matches(name)

    /** Every `/command` token in [text], in order. */
    fun commands(text: String): List<String> = TOKEN.findAll(text).map { it.groupValues[1] }.toList()

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
