package com.cursorforandroid.domain

/**
 * Whose files an Agent Store holds (`aiserver.v1.AgentStore.kind`): a cloud agent's — a Project's Context is its
 * coordinator's — a local agent's, an automation's, the user's own, the team's, or a named agent's. [UNKNOWN] is a
 * kind this build has not heard of.
 */
enum class AgentStoreKind {
    CLOUD,
    LOCAL,
    AUTOMATION,
    USER,
    TEAM,
    NAMED_AGENT,
    UNKNOWN,
    ;

    companion object {
        private const val WIRE_PREFIX = "AGENT_STORE_KIND_"

        /** The proto name, the bare name or the number (CLOUD 1 … NAMED_AGENT 6), as the account service may spell it. */
        fun parse(raw: String?): AgentStoreKind {
            val token = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return UNKNOWN
            token.toIntOrNull()?.let { number -> return entries.getOrNull(number - 1)?.takeIf { it != UNKNOWN } ?: UNKNOWN }
            val name = token.uppercase().removePrefix(WIRE_PREFIX)
            return entries.firstOrNull { it.name == name && it != UNKNOWN } ?: UNKNOWN
        }
    }
}

/** One Agent Store as `ListAgentStores` names it: its id, whose it is, the chat or automation it belongs to, when it last changed. */
data class AgentStoreRef(
    val storeId: String,
    val kind: AgentStoreKind = AgentStoreKind.UNKNOWN,
    /** The chat (a Project's coordinator) or automation the store belongs to; null for the user's and the team's. */
    val sourceId: String? = null,
    val lastFileWriteAtMillis: Long? = null,
)

/**
 * The stores a chat's panel browses, as the web's "All Files" tab roots them: the Project's Context (the store the
 * coordinator owns — a chat of its own has its own store, when the account lists one) under the Project's name, and
 * the user's store under "User". Either may be missing.
 */
data class ContextStores(
    val project: AgentStoreRef? = null,
    val user: AgentStoreRef? = null,
) {
    val isEmpty: Boolean get() = project == null && user == null
    val all: List<AgentStoreRef> get() = listOfNotNull(project, user)
}

/** A file of a store opened in a tab of the panel: which store, where in it, and its text. */
data class ContextDocument(
    val store: AgentStoreRef,
    val path: String,
    val text: String,
) {
    val name: String get() = path.trimEnd('/').substringAfterLast('/')
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
    /** Markdown renders as a preview by default; anything else opens on its source. */
    val isMarkdown: Boolean get() = extension == "md" || extension == "markdown"
}

/** A file listed under "Recents": one of the newest files across the stores, with a thumbnail when it is a picture the account will serve. */
data class RecentContextFile(
    val store: AgentStoreRef,
    val entry: ContextEntry,
    /** A URL the picture can be fetched from (presigned, or the demo's asset); null for a file that is not a picture, or one the account would not presign. */
    val thumbnailUrl: String? = null,
) {
    val isImage: Boolean get() = entry.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

    companion object {
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
    }
}
