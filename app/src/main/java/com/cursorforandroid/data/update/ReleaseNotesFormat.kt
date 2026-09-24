package com.cursorforandroid.data.update

import com.cursorforandroid.data.repo.parseIsoMillis
import com.cursorforandroid.domain.AppVersion
import com.cursorforandroid.domain.ReleaseNotes

/**
 * Reads the human-readable section out of a release's notes as the release workflow and the release runbook shape
 * them. Pure functions.
 *
 * A published release's body is, in order: the header table the workflow writes (versionName, versionCode, commit,
 * the signing certificate, crash reports) and its `**Install:**` line; the curated notes — a lead line, then headed
 * sections of bullets; the v0.1.0 reinstall notice under its own heading; and, after the HTML comment GitHub leaves,
 * the generated "What's Changed" commit list with its "Full Changelog" link. Only the curated notes are for a reader
 * of the installed version: the header and the install line describe files, the reinstall notice addresses a build
 * that cannot be reading this, and the commit list is what the notes were curated from. Every cut is by shape, so a
 * body with any of the parts missing — a hand-written release, one whose notes are still to be pasted in — reads
 * as whatever it does carry, and a body that carries nothing curated reads as no notes at all.
 */
object ReleaseNotesFormat {
    /** `## Heading`, with any closing hashes; the level is the group's length. */
    private val HEADING = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val FENCE = Regex("""^(`{3,}|~{3,}).*$""")
    private val GENERATED_COMMENT = Regex("""^<!--\s*Release notes generated""", RegexOption.IGNORE_CASE)
    private val WHATS_CHANGED = Regex("""^#{1,6}\s+What['’]?s Changed\b""", RegexOption.IGNORE_CASE)
    private val FULL_CHANGELOG = Regex("""^\*\*Full Changelog\*\*""", RegexOption.IGNORE_CASE)
    private val REINSTALL_NOTICE = Regex("""^Upgrading from v0\.1\.0\b""", RegexOption.IGNORE_CASE)
    private const val INSTALL_LINE = "**Install:**"

    /** `- `, `* `, `+ `, `1. `, `1) `: what a list item starts with, so its text can be read without the marker. */
    private val LIST_MARKER = Regex("""^(?:[-*+]|\d{1,3}[.)])\s+""")
    private val BOLD_RUN = Regex("""^\*\*(.+?)\*\*""")
    private val LINK = Regex("""\[([^\]]*)]\([^)]*\)""")
    private val INLINE_MARKS = Regex("""\*\*|__|`""")
    private val SPACES = Regex("""\s+""")

    /** The release as the What's new page shows it; null for a draft, a tag that is not a version, or a body with nothing curated in it. */
    fun from(dto: GitHubReleaseDto): ReleaseNotes? {
        if (dto.draft) return null
        val version = AppVersion.parse(dto.tagName) ?: return null
        val markdown = curated(dto.body) ?: return null
        return ReleaseNotes(
            tagName = dto.tagName,
            versionName = version.toString(),
            publishedAtMs = parseIsoMillis(dto.publishedAt),
            htmlUrl = dto.htmlUrl ?: "",
            markdown = markdown,
            lead = lead(markdown),
        )
    }

    /**
     * The curated notes of [body] as markdown: the header table and the install line taken off the front, the
     * generated commit list off the back, and the reinstall notice's section out of the middle. Null when nothing
     * is left, which is what a release whose notes have not been written yet looks like.
     */
    fun curated(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val lines = body.lines()
        var index = skipBlank(lines, 0)
        // The header: every leading table row, then the install paragraph if it follows.
        while (index < lines.size && lines[index].trimStart().startsWith('|')) index++
        val afterTable = skipBlank(lines, index)
        if (afterTable < lines.size && lines[afterTable].trimStart().startsWith(INSTALL_LINE)) {
            index = afterTable
            while (index < lines.size && lines[index].isNotBlank()) index++
        }
        val kept = ArrayList<String>(lines.size - index)
        var fence: String? = null
        // The level of the heading whose section is being dropped; 0 while nothing is.
        var dropping = 0
        while (index < lines.size) {
            val line = lines[index++]
            val trimmed = line.trim()
            // Inside a fenced block nothing is a heading or a marker, whatever it looks like.
            if (fence != null) {
                if (trimmed.startsWith(fence)) fence = null
                if (dropping == 0) kept += line
                continue
            }
            val opening = FENCE.matchEntire(trimmed)
            if (opening != null) {
                fence = opening.groupValues[1]
                if (dropping == 0) kept += line
                continue
            }
            if (GENERATED_COMMENT.containsMatchIn(trimmed) || WHATS_CHANGED.containsMatchIn(trimmed) || FULL_CHANGELOG.containsMatchIn(trimmed)) break
            val heading = HEADING.matchEntire(trimmed)
            if (heading != null) {
                val level = heading.groupValues[1].length
                // A heading at the dropped section's level or above ends it; one below is part of it.
                if (dropping > 0 && level <= dropping) dropping = 0
                if (dropping == 0 && REINSTALL_NOTICE.containsMatchIn(heading.groupValues[2])) {
                    dropping = level
                    continue
                }
            }
            if (dropping == 0) kept += line
        }
        return kept.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }.joinToString("\n").ifBlank { null }
    }

    /**
     * The lead line of curated [markdown]: the first paragraph — or, in notes that go straight to bullets, the first
     * item — as plain text. The notes open their lead with the sentence that matters in bold; when they do, that
     * sentence alone is the lead, and what follows it stays in the body. Null for notes with no text before a table
     * or a code block.
     */
    fun lead(markdown: String): String? {
        val lines = markdown.lines()
        var index = 0
        while (index < lines.size) {
            val trimmed = lines[index].trim()
            when {
                trimmed.isEmpty() || HEADING.matches(trimmed) || trimmed.startsWith("<!--") -> index++
                trimmed.startsWith('|') || FENCE.matches(trimmed) || trimmed.startsWith('>') -> return null
                else -> {
                    val item = LIST_MARKER.find(trimmed)
                    val text = if (item != null) {
                        trimmed.substring(item.range.last + 1)
                    } else {
                        // A paragraph runs to the next blank line; a list, table or heading also ends it.
                        val paragraph = ArrayList<String>()
                        while (index < lines.size) {
                            val next = lines[index].trim()
                            if (next.isEmpty() || (paragraph.isNotEmpty() && (HEADING.matches(next) || LIST_MARKER.containsMatchIn(next) || next.startsWith('|') || FENCE.matches(next)))) break
                            paragraph += next
                            index++
                        }
                        paragraph.joinToString(" ")
                    }
                    return plain(BOLD_RUN.find(text)?.groupValues?.get(1) ?: text).ifEmpty { null }
                }
            }
        }
        return null
    }

    /** Inline markdown taken off: links to their label, emphasis and code marks dropped, whitespace collapsed. */
    private fun plain(text: String): String =
        text.replace(LINK) { it.groupValues[1] }.replace(INLINE_MARKS, "").replace(SPACES, " ").trim()

    private fun skipBlank(lines: List<String>, from: Int): Int {
        var index = from
        while (index < lines.size && lines[index].isBlank()) index++
        return index
    }
}
