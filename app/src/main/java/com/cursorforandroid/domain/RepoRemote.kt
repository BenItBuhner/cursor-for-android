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

    private fun String.removeSuffixIgnoringCase(suffix: String): String =
        if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this

    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://")
    private val SCP = Regex("^([^@/\\s]+@)?([^:/\\s]+):(.+)$")
    private val HOST_PORT = Regex("^[^@/\\s:]+:\\d+(/|$)")
}
