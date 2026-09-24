package com.cursorforandroid.domain

/**
 * A git remote in the form `repos[].url` takes it: `https://host/owner/name`, which is how Cursor's desktop spells a
 * machine's repository before it sends one (`J5` in `workbench.glass.main.js`, 3.21.18): ssh, git and scp-style
 * (`git@host:owner/name`) remotes become https, and credentials, a port on a non-web remote, the `.git` suffix and
 * trailing slashes are dropped. Null for a remote that does not name an owner and a repository.
 */
object RepoRemote {

    fun canonical(remote: String?): String? {
        val raw = remote?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val scheme = SCHEME.find(raw)
        val scp = if (scheme == null && !HOST_PORT.containsMatchIn(raw)) SCP.matchEntire(raw) else null
        val protocol = when {
            scheme != null -> scheme.groupValues[1].lowercase()
            scp != null -> "ssh"
            else -> "https"
        }
        val rest = when {
            scheme != null -> raw.substring(scheme.range.last + 1)
            scp != null -> scp.groupValues[2] + "/" + scp.groupValues[3]
            else -> raw
        }
        val web = protocol == "http" || protocol == "https"
        val authorityEnd = rest.indexOf('/').let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authorityEnd).substringAfterLast('@')
        val host = (if (web) authority else authority.substringBefore(':')).lowercase()
        if (host.isEmpty()) return null
        val path = rest.substring(authorityEnd).substringBefore('?').substringBefore('#').trimEnd('/')
        val segments = path.removeSuffixIgnoringCase(".git").split('/').filter { it.isNotEmpty() }
        if (segments.size < 2) return null
        return "${if (protocol == "http") "http" else "https"}://$host/${segments.joinToString("/")}"
    }

    /**
     * The `repo=` label a worker registers for a checkout with this remote, and the one the desktop puts on a request
     * for it (`repo-label.js` `af` in the agent CLI, `T1t` in the desktop): `owner/name…` on the well-known hosts,
     * `owner/name` on Cursor's Origin (`/git/owner/name` too), `host/path` anywhere else. Null for a remote that names
     * no repository.
     */
    fun label(remote: String?): String? {
        val url = canonical(remote) ?: return null
        val rest = url.substringAfter("://")
        val authority = rest.substringBefore('/')
        val host = authority.substringBefore(':')
        val segments = rest.substringAfter('/', "").split('/').filter { it.isNotEmpty() }
        return when {
            ORIGIN.matches(host) -> when {
                segments.size == 3 && segments[0].equals("git", ignoreCase = true) -> "${segments[1]}/${segments[2]}"
                segments.size == 2 && !segments[0].equals("git", ignoreCase = true) -> "${segments[0]}/${segments[1]}"
                else -> null
            }
            KNOWN_HOSTS.any { host == it || host.endsWith(".$it") } -> segments.joinToString("/")
            else -> "$authority/${segments.joinToString("/")}"
        }
    }

    private fun String.removeSuffixIgnoringCase(suffix: String): String =
        if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this

    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://")
    private val SCP = Regex("^([^@/\\s]+@)?([^:/\\s]+):(.+)$")
    private val HOST_PORT = Regex("^[^@/\\s:]+:\\d+(/|$)")
    private val ORIGIN = Regex("^origin(?:-[a-z0-9]+)?\\.cursor\\.com$", RegexOption.IGNORE_CASE)
    private val KNOWN_HOSTS = setOf("github.com", "gitlab.com", "bitbucket.org", "bitbucket.com", "codeberg.org", "gitea.com", "sr.ht")
}
