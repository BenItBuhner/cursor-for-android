package com.cursorforandroid.domain

import kotlinx.serialization.Serializable

/**
 * One entry of the composer's `/` menu: a command such as `/goal` or `/multitask`, or a skill — built into Cursor,
 * from the repository's `.cursor/skills`, from a plugin, synced from the account, or a `.cursor/commands` file on the
 * agent's machine. Attached to one message by leading it with `/name` (see [SlashCommands]).
 */
@Serializable
data class SlashCommand(
    val name: String,
    val description: String = "",
    val kind: Kind = Kind.Skill,
    val origin: Origin = Origin.Unknown,
    /** Where the definition lives, as the account service reports it (`.cursor/skills/deploy/SKILL.md`, a plugin path). */
    val sourcePath: String? = null,
    /** What the command expects after its name, e.g. `<objective>` for `/goal`. */
    val argumentHint: String? = null,
) {
    val command: String get() = "/$name"

    /** The second line of a row: the description, or where the entry comes from when it has none. */
    val summary: String get() = description.ifBlank { origin.label(kind) }

    enum class Kind { Command, Skill }

    enum class Origin {
        /** Ships with Cursor and applies on every cloud VM. */
        BuiltIn,
        /** `.cursor/skills` or `.cursor/commands` in the repository. */
        Project,
        /** Installed from a plugin (a marketplace one, or the project's). */
        Plugin,
        /** The account's own synced skills. */
        Personal,
        /** Defined for the team. */
        Team,
        /** A name typed or picked here before; the catalog never listed it. */
        Recent,
        Unknown;

        fun label(kind: Kind): String {
            val noun = if (kind == Kind.Command) "command" else "skill"
            return when (this) {
                BuiltIn -> "Built-in $noun"
                Project -> "Project $noun"
                Plugin -> "Plugin $noun"
                Personal -> "Personal $noun"
                Team -> "Team $noun"
                Recent -> "Project or synced $noun"
                Unknown -> if (kind == Kind.Command) "Command" else "Skill"
            }
        }
    }

    companion object {
        /**
         * The origin an account-service `source_path` / `source_url` implies. Best effort, for the label only: the
         * server does not say, and the paths are those of the VM the definition was found on.
         */
        fun originOf(sourcePath: String?, sourceUrl: String? = null): Origin {
            val path = sourcePath?.replace('\\', '/')?.lowercase().orEmpty()
            val url = sourceUrl?.lowercase().orEmpty()
            return when {
                path.contains("skills-cursor") -> Origin.BuiltIn
                path.contains("/plugins/") || path.startsWith("plugins/") || url.contains("plugin") -> Origin.Plugin
                path.startsWith("~") || path.startsWith("/home/") || path.startsWith("/root/") || path.startsWith("/users/") || path.contains("/cursor/stores/user") -> Origin.Personal
                path.isNotEmpty() -> Origin.Project
                else -> Origin.Unknown
            }
        }
    }
}

/**
 * The `/` commands one composer can offer: what the account service lists for the chat's repository or agent (see
 * `SlashCommandRepository`), merged over the built-ins so the menu is never empty — offline, in demo mode, or under a
 * key the account service refuses. [pending] is the follow-up call's `machineInventoryPending`: the agent's machine
 * has not reported its `.cursor/commands` and project skills yet, so the list may still grow.
 */
@Serializable
data class SlashCatalog(
    val entries: List<SlashCommand> = emptyList(),
    val pending: Boolean = false,
) {
    val commands: List<SlashCommand> get() = entries.filter { it.kind == SlashCommand.Kind.Command }
    val skills: List<SlashCommand> get() = entries.filter { it.kind == SlashCommand.Kind.Skill }

    fun byName(name: String): SlashCommand? = entries.firstOrNull { it.name == name }

    /**
     * This catalog with [other]'s entries taken over its own where names collide (a server description over a
     * built-in one), and appended where they are new. Entries whose server description is blank keep the built-in text.
     */
    fun mergedWith(other: SlashCatalog): SlashCatalog {
        val merged = LinkedHashMap<String, SlashCommand>()
        entries.forEach { merged[it.name] = it }
        other.entries.forEach { incoming ->
            val existing = merged[incoming.name]
            merged[incoming.name] = if (existing == null) incoming else incoming.copy(
                description = incoming.description.ifBlank { existing.description },
                origin = if (incoming.origin == SlashCommand.Origin.Unknown) existing.origin else incoming.origin,
                argumentHint = incoming.argumentHint ?: existing.argumentHint,
            )
        }
        return SlashCatalog(merged.values.toList(), pending = pending || other.pending)
    }

    /**
     * The entries matching [query] — what follows the `/` being typed, with or without the slash — for the popover
     * and the Skills page: names that start with it first, then names that contain it, then descriptions that do,
     * each group in catalog order (commands ahead of skills). An empty query lists everything, [recent] names first.
     * A valid name the catalog does not know is offered last as a project or synced skill, unless a listed name
     * already starts with it: project and synced skills are real on the VM even when nothing reported them.
     */
    fun search(query: String, recent: List<String> = emptyList()): List<SlashCommand> {
        val q = query.trim().removePrefix("/").lowercase()
        val recentEntries = recent.filter { byName(it) == null && SlashCommands.isValidName(it) }
            .map { SlashCommand(it, kind = SlashCommand.Kind.Skill, origin = SlashCommand.Origin.Recent) }
        val all = recentEntries + entries
        if (q.isEmpty()) return all
        val starts = all.filter { it.name.startsWith(q) }
        val contains = all.filter { it.name.contains(q) && it !in starts }
        val described = all.filter { it !in starts && it !in contains && it.description.lowercase().contains(q) }
        val typed = if (starts.isEmpty() && SlashCommands.isValidName(q) && byName(q) == null && q !in recent) {
            listOf(SlashCommand(q, kind = SlashCommand.Kind.Skill, origin = SlashCommand.Origin.Recent))
        } else {
            emptyList()
        }
        return starts + contains + described + typed
    }

    companion object {
        /** What every cloud composer can offer before the account service has said anything. */
        val BUILT_IN: SlashCatalog get() = SlashCatalog(BuiltInSlashCommands.commands + BuiltInSlashCommands.skills)
    }
}

/**
 * The commands and skills Cursor ships for cloud agents (cursor.com/docs/skills, "Built-in Cursor skills", plus the
 * cloud-only `/subscribe` and the commands the changelog introduced for cloud agents). The IDE- and CLI-only ones
 * (`/canvas`, `/statusline`, `/update-cli-config`, `/update-cursor-settings`) are left out. The account service's
 * lists take precedence over these; they are what the composer falls back to.
 */
object BuiltInSlashCommands {
    private fun command(name: String, description: String, argumentHint: String? = null) =
        SlashCommand(name, description, SlashCommand.Kind.Command, SlashCommand.Origin.BuiltIn, argumentHint = argumentHint)

    private fun skill(name: String, description: String) =
        SlashCommand(name, description, SlashCommand.Kind.Skill, SlashCommand.Origin.BuiltIn)

    val commands: List<SlashCommand> = listOf(
        command("goal", "Set a goal that Cursor will pursue to completion", argumentHint = "<objective>"),
        command(SlashCommands.MULTITASK, "Orchestrate multiple subagents in parallel"),
    )

    val skills: List<SlashCommand> = listOf(
        skill("autopilot", "Monitor a pull request and address feedback, conflicts and failing checks"),
        skill("review", "Select and run the appropriate code-review agent"),
        skill("review-bugbot", "Review the changes for likely bugs and regressions with Bugbot"),
        skill("review-security", "Review the changes for security vulnerabilities"),
        skill("split-to-prs", "Split large changes into smaller pull requests"),
        skill("subscribe", "Wait for GitHub, Slack, Linear or timer events and keep working"),
        skill("loop", "Run a prompt or skill repeatedly at an interval"),
        skill("automate", "Create a Cursor Automation from schedules, Slack, GitHub or other triggers"),
        skill("create-skill", "Create an Agent Skill with its SKILL.md"),
        skill("create-rule", "Create a Cursor rule with the right scope"),
        skill("create-subagent", "Create a custom subagent with a focused role"),
        skill("create-hook", "Create Cursor hooks in hooks.json"),
        skill("cursor-blame", "Investigate AI-authored changes and the prompts behind them"),
        skill("migrate-to-skills", "Convert dynamic rules and slash commands into skills"),
        skill("sdk", "Build applications and integrations with the Cursor SDK"),
        skill("shell", "Run the message as a literal shell command"),
    )

    fun byName(name: String): SlashCommand? = (commands + skills).firstOrNull { it.name == name }
}

/**
 * The project skills and commands a repository defines for its agents, read off its file tree: `.cursor/skills/<name>/SKILL.md`
 * is a skill named `<name>`, `.cursor/commands/<name>.md` a command named `<name>` (cursor.com/docs/skills,
 * cursor.com/docs/commands). This is what a cloud agent loads from the repository, so it is what a documented-API-only
 * composer can offer without asking the account service: names and paths, since the descriptions live inside the files.
 */
object RepositorySlashCommands {
    private val SKILL = Regex("""^\.cursor/skills/([^/]+)/SKILL\.md$""")
    private val COMMAND = Regex("""^\.cursor/commands/([^/]+)\.md$""")

    fun fromPaths(paths: Iterable<String>): SlashCatalog {
        val entries = LinkedHashMap<String, SlashCommand>()
        fun put(command: SlashCommand) {
            if (SlashCommands.isValidName(command.name) && command.name !in entries) entries[command.name] = command
        }
        val normalized = paths.map { it.trim().trimStart('/').replace('\\', '/') }.sorted()
        normalized.forEach { path ->
            COMMAND.matchEntire(path)?.let { put(SlashCommand(it.groupValues[1], kind = SlashCommand.Kind.Command, origin = SlashCommand.Origin.Project, sourcePath = path)) }
        }
        normalized.forEach { path ->
            SKILL.matchEntire(path)?.let { put(SlashCommand(it.groupValues[1], kind = SlashCommand.Kind.Skill, origin = SlashCommand.Origin.Project, sourcePath = path)) }
        }
        return SlashCatalog(entries.values.toList())
    }
}
