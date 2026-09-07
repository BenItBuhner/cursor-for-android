package com.cursorforandroid.domain

/** A skill the composer can attach to one message by leading it with `/name`. */
data class Skill(val name: String, val description: String) {
    val command: String get() = "/$name"
}

/**
 * The built-in Cursor skills that apply on a cloud VM (cursor.com/docs/skills, "Built-in Cursor skills", plus the
 * cloud-only `/subscribe`). The IDE- and CLI-only ones (`/canvas`, `/statusline`, `/update-cli-config`,
 * `/update-cursor-settings`) are left out. Project skills (`.cursor/skills`) and synced personal skills are real on
 * the VM too, but the Cloud Agents API has no call to list them, so those are entered by name.
 */
object BuiltInSkills {
    val cloud: List<Skill> = listOf(
        Skill("autopilot", "Monitor a pull request and address feedback, conflicts and failing checks"),
        Skill("review", "Select and run the appropriate code-review agent"),
        Skill("review-bugbot", "Review the changes for likely bugs and regressions with Bugbot"),
        Skill("review-security", "Review the changes for security vulnerabilities"),
        Skill("split-to-prs", "Split large changes into smaller pull requests"),
        Skill("subscribe", "Wait for GitHub, Slack, Linear or timer events and keep working"),
        Skill("loop", "Run a prompt or skill repeatedly at an interval"),
        Skill("automate", "Create a Cursor Automation from schedules, Slack, GitHub or other triggers"),
        Skill("create-skill", "Create an Agent Skill with its SKILL.md"),
        Skill("create-rule", "Create a Cursor rule with the right scope"),
        Skill("create-subagent", "Create a custom subagent with a focused role"),
        Skill("create-hook", "Create Cursor hooks in hooks.json"),
        Skill("cursor-blame", "Investigate AI-authored changes and the prompts behind them"),
        Skill("migrate-to-skills", "Convert dynamic rules and slash commands into skills"),
        Skill("sdk", "Build applications and integrations with the Cursor SDK"),
        Skill("shell", "Run the message as a literal shell command"),
    )

    fun byName(name: String): Skill? = cloud.firstOrNull { it.name == name }

    /**
     * Skills matching [query] against the name and description, custom (typed or recent) names first. An unknown but
     * valid name is offered as-is so project and synced skills stay one tap away.
     */
    fun search(query: String, recent: List<String>): List<Skill> {
        val q = query.trim().removePrefix("/").lowercase()
        val custom = recent.filterNot { byName(it) != null }.map { Skill(it, CUSTOM_DESCRIPTION) }
        val typed = if (q.isNotEmpty() && SlashCommands.isValidName(q) && byName(q) == null && q !in recent) listOf(Skill(q, CUSTOM_DESCRIPTION)) else emptyList()
        return (typed + custom + cloud).filter { q.isEmpty() || it.name.contains(q) || it.description.lowercase().contains(q) }
    }

    const val CUSTOM_DESCRIPTION = "Project or synced skill"
}
