package com.cursorforandroid.util

import android.content.Intent
import android.net.Uri

/**
 * What a launch intent asks the app to open, and how to mark that request done.
 *
 * `onCreate` reads `getIntent()` again after every recreation the manifest's `configChanges` does not absorb — a
 * font-scale or locale change, process death — so a request that has already been carried out is cleared from the
 * intent instead of being left there to be replayed.
 */
object DeepLinks {
    private val hosts = setOf("cursor.com", "www.cursor.com")

    /** The id the API mints for a cloud agent: `bc-` and a hyphenated token. */
    private val agentIdShape = Regex("bc-[A-Za-z0-9._-]+")

    /** The agent a `https://[www.]cursor.com/agents/<id>` link — or an `?id=` query on that host — points at. */
    fun agentId(uri: Uri?): String? {
        val link = uri ?: return null
        if (link.host?.lowercase() !in hosts) return null
        val segments = link.pathSegments
        val index = segments.indexOf("agents")
        // Without an `agents` segment there is no path form of the link; indexOf's -1 used to read segment 0.
        val fromPath = if (index >= 0) segments.getOrNull(index + 1) else null
        return fromPath?.takeIf(::isAgentId) ?: link.getQueryParameter("id")?.takeIf(::isAgentId)
    }

    fun agentId(intent: Intent?): String? = agentId(intent?.data)

    /** Forgets the link [intent] arrived with, once the app has navigated to it. */
    fun clearAgentLink(intent: Intent?) {
        intent?.data = null
    }

    /** Forgets [action] on [intent] once it has been acted on, leaving the intent an ordinary launch. */
    fun clearAction(intent: Intent?, action: String) {
        if (intent?.action == action) intent.action = Intent.ACTION_MAIN
    }

    private fun isAgentId(candidate: String): Boolean = agentIdShape.matches(candidate)
}
