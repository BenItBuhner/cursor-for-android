package com.cursorforandroid.domain

/**
 * Which engine renders a chat's transcript in Extended mode — the setting Bennett asked for on 2026-09-21 after two
 * of Cursor's private record methods were removed in two days ("revert back to a version that was at least
 * functioning… and have a beta toggle"): no version before the record read existed still works, every one of them
 * reading the record over `FetchBackgroundComposer`, so the revert is a switch.
 *
 *  - [BETA], the default since 2026-09-24 (Bennett: "the Beta transcript engine is the default from now on"): the
 *    account's record read over `StreamConversation`'s prewarm and its blobs, the notice row with the request and the
 *    server's answer when it is refused, and the documented path behind it whenever the record cannot be read.
 *  - [STABLE]: transcripts from Cursor's documented API alone — the `/v1` runs and their logs, the `/v0`
 *    conversation — with every documented-path fix kept. No private record read (no `StreamConversation`, no
 *    `GetBlobForAgentKV`), and so never the "Account transcript unavailable" notice. The Goal strip goes by what the
 *    chat's own transcript says of the goal, as it does with Extended mode off.
 *
 * Settings no longer offers the choice (Bennett, 2026-10-01: "Full transcript history" is always on): every install
 * renders with [DEFAULT], and the `transcript_engine` preference the switch once wrote is retired, so a stored
 * `stable` is forgotten. [STABLE] stays as the documented path's own mode, which the tests drive directly.
 *
 * Only meaningful while Extended mode is on (the record is a private surface): with the mode off the documented path
 * renders whatever this says (see [Capabilities.of]). Read at the start of a chat's load, so a change takes effect on
 * the next chat open.
 */
enum class TranscriptEngine {
    STABLE,
    BETA,
    ;

    /** How the diagnostics export and the preference spell it. */
    val key: String get() = name.lowercase()

    companion object {
        val DEFAULT = BETA

        fun parse(raw: String?): TranscriptEngine = entries.firstOrNull { it.key == raw || it.name == raw } ?: DEFAULT
    }
}
