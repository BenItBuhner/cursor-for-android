package com.cursorforandroid.domain

/**
 * A link to a cloud agent, in the shapes agents write one: the id alone, `[worker](bc-<uuid>)`, which is how a
 * Project's coordinator cites the agents it runs; the id with a fragment naming a part of the agent's page, above all
 * `bc-<uuid>#desktop`, the agent's cloud VM desktop, which only cursor.com shows; and the page itself,
 * `https://cursor.com/agents/bc-…`, or the `?id=` form of it, the two the app's deep links accept.
 *
 * A bare id is taken only in its full shape, `bc-` and a UUID, so that a relative file such as `bc-notes.md` stays
 * a file; the page's address takes the looser id the deep links do.
 */
data class AgentLink(val agentId: String, val fragment: String? = null) {

    /** The agent's cloud VM desktop, which is cursor.com's to show. */
    val isDesktop: Boolean get() = fragment.equals(DESKTOP_FRAGMENT, ignoreCase = true)

    /** The agent's page on cursor.com, fragment kept: what a copy of the link carries, and where it opens outside a chat. */
    val webUrl: String get() = WEB_ROOT + agentId + fragment?.let { "#$it" }.orEmpty()

    companion object {
        const val DESKTOP_FRAGMENT = "desktop"
        private const val WEB_ROOT = "https://cursor.com/agents/"

        private val bareId = Regex("bc-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val webId = Regex("bc-[A-Za-z0-9._-]+")
        /** `http(s)://[www.]cursor.com/agents`, then the id as a path segment or in the query, then the fragment. */
        private val webPage = Regex("""https?://(?:www\.)?cursor\.com/agents(/[^?#]*)?(\?[^#]*)?(?:#(.*))?""", RegexOption.IGNORE_CASE)

        /** The agent [raw] links to, or null when it is not a link to one. Surrounding whitespace is ignored; an empty fragment is none. */
        fun parse(raw: String): AgentLink? {
            val text = raw.trim()
            if (text.startsWith("bc-")) {
                val id = text.substringBefore('#')
                if (!bareId.matches(id)) return null
                return AgentLink(id, text.substringAfter('#', "").ifEmpty { null })
            }
            val page = webPage.matchEntire(text) ?: return null
            val (path, query, fragment) = page.destructured
            // `agents/<id>` and nothing under it: a deeper path is some other page of cursor.com's.
            val fromPath = path.removePrefix("/").removeSuffix("/")
            val id = if (fromPath.isNotEmpty()) fromPath.takeIf(webId::matches) else idInQuery(query)
            return id?.let { AgentLink(it, fragment.ifEmpty { null }) }
        }

        private fun idInQuery(query: String): String? = query.removePrefix("?").split('&')
            .firstNotNullOfOrNull { pair -> pair.takeIf { it.startsWith("id=") }?.removePrefix("id=")?.takeIf(webId::matches) }
    }
}
