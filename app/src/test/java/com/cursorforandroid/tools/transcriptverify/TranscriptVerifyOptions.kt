package com.cursorforandroid.tools.transcriptverify

import java.io.File

/**
 * What one run of the harness is asked to do, parsed from the command line by [TranscriptVerifyMain] and handed to
 * the Robolectric-hosted run ([TranscriptVerifyHarness]) through system properties — the two live in different class
 * loaders, and the properties are the one thing they share. The key itself never travels: only the path of the file
 * that holds it does, and the file is read inside the run, into the closures the app's clients read it from.
 */
data class TranscriptVerifyOptions(
    /** The chat to read (`bc-…`); null in replay mode. */
    val agentId: String? = null,
    /** The file whose whole content, trimmed, is the Cursor user API key; null in replay mode. */
    val keyFile: File? = null,
    /** Which of the app's two paths to run: the account's record (Extended mode) or the documented one. */
    val extended: Boolean = true,
    /** How many of the chat's newest turns to page in; null reads the record (or the run list) to its start. */
    val turns: Int? = null,
    /** How long to follow a turn that is still running before the report is written; 0 waits for nothing. */
    val followSeconds: Int = 120,
    /** A follow-up to send through the composer's own path — off unless given, and only to an agent the send gate reads as idle. */
    val send: String? = null,
    /** Print the first characters of prompts and messages beside their lengths (the report is lengths-only otherwise). */
    val showText: Boolean = false,
    /** Run the pipeline on the recorded fixtures rather than an account: no key, no network. */
    val replay: Boolean = false,
    /** Where a copy of the report goes, besides standard output. */
    val out: File? = null,
    val help: Boolean = false,
) {
    val live: Boolean get() = !replay

    /** The options as the properties the harness reads (see [fromProperties]); the key's path, never the key. */
    fun toProperties(): Map<String, String> = buildMap {
        agentId?.let { put(PROP_AGENT, it) }
        keyFile?.let { put(PROP_KEY_FILE, it.absolutePath) }
        put(PROP_MODE, if (extended) "extended" else "default")
        turns?.let { put(PROP_TURNS, it.toString()) }
        put(PROP_FOLLOW_SECONDS, followSeconds.toString())
        send?.let { put(PROP_SEND, it) }
        put(PROP_SHOW_TEXT, showText.toString())
        put(PROP_REPLAY, replay.toString())
        out?.let { put(PROP_OUT, it.absolutePath) }
    }

    companion object {
        const val PROP_PREFIX = "transcriptVerify."
        const val PROP_AGENT = "${PROP_PREFIX}agent"
        const val PROP_KEY_FILE = "${PROP_PREFIX}keyFile"
        const val PROP_MODE = "${PROP_PREFIX}mode"
        const val PROP_TURNS = "${PROP_PREFIX}turns"
        const val PROP_FOLLOW_SECONDS = "${PROP_PREFIX}followSeconds"
        const val PROP_SEND = "${PROP_PREFIX}send"
        const val PROP_SHOW_TEXT = "${PROP_PREFIX}showText"
        const val PROP_REPLAY = "${PROP_PREFIX}replay"
        const val PROP_OUT = "${PROP_PREFIX}out"
        /** Set by [TranscriptVerifyMain] alone: the harness class is a JUnit test too, and skips itself without it. */
        const val PROP_INVOKED = "${PROP_PREFIX}invoked"

        /** The text of `--help`: what the harness does, every call it makes, and what it never does. */
        val HELP: String = """
            |transcript-verify — runs Cursor for Android's own transcript pipeline on this machine, against one chat of a
            |real account or against the recorded fixtures, and prints what each stage read and decided. It exists to
            |find where a message is lost between the account and the screen when the app's simulations cannot.
            |
            |Usage: tools/transcript-verify/run.sh --agent <bc-id> --key-file <path> [options]
            |       tools/transcript-verify/run.sh --replay [options]
            |
            |Options
            |  --agent <bc-id>           The chat to read. Required for a live run.
            |  --key-file <path>         A file whose whole content (trimmed) is a Cursor user API key (cursor.com/dashboard/api).
            |                            Required for a live run. The key is read from this file only: it is never taken from the
            |                            command line, the environment or the repository, and never printed or logged.
            |  --mode extended|default   Which of the app's two paths to run (default: extended — the account's record is the
            |                            transcript, as in the app with Extended mode on).
            |  --turns <n>               Page in only the newest <n> turns (default: everything, back to the chat's start).
            |  --follow-seconds <n>      How long to follow a turn still running before the report is written (default: 120).
            |  --send <text>             Send <text> as a follow-up through the composer's own path (SendGate → POST /v1/agents/{id}/runs),
            |                            then follow the run. Off unless given. Refused, and nothing sent, when the send gate reads
            |                            the agent as busy.
            |  --show-text               Print the first 80 characters of prompts and messages beside their lengths (default: lengths only).
            |  --replay                  Run the same pipeline on the fixtures under app/src/test/resources/fixtures/coordinator
            |                            (record_coordinator_pages.json, coordinator_send_fragments.sse, event_wall.json), served by an
            |                            in-process server: no key, no network. This is what CI runs.
            |  --out <path>              Also write the report to <path>.
            |  --help                    This text.
            |
            |What a live run does, in order (every call read-only unless --send is given)
            |  1. POST https://api2.cursor.sh/auth/exchange_user_api_key          (Extended mode only) the app's SessionTokenProvider:
            |                                                                      the key for the short-lived account session every
            |                                                                      aiserver.v1 call below carries. Not made in --mode default.
            |  2. GET  https://api.cursor.com/v1/agents/{id}                        the chat's row (AgentRepository.loadDetail).
            |  3. POST …/aiserver.v1.BackgroundComposerService/ListBackgroundComposers   (Extended) one page of the account list, as the
            |                                                                      app's account round reads it: the account's word on the
            |                                                                      chat's status, which the send gate and the status
            |                                                                      precedence read.
            |  4. The app's ConversationRepository opens the chat exactly as a screen would (attach), then pages older turns as a
            |     reader scrolling to the top would:
            |       Extended: POST …/FetchBackgroundComposer (200 steps a page), POST …/GetLatestAgentConversationState,
            |                 GET /v1/agents/{id}/runs (paged until the record's window is covered), and for a turn whose record
            |                 holds no body (or, in a coordinator's chat, no message to the user) GET /v1/agents/{id}/runs/{runId}/stream
            |                 for the run's retained log.
            |       Default:  GET /v1/agents/{id}/runs (paged), GET /v0/agents/{id}/conversation, and the runs' logs as above.
            |       A run still going is followed on its stream (GET …/stream) for --follow-seconds, with GET /v1/agents/{id}/runs/{runId}
            |       when the stream drops, as the app does.
            |  5. The items the repository publishes go through TranscriptPresenter (the same presenter the screen uses) into rows.
            |  6. Independently of the repository: the record is read again from its end (FetchBackgroundComposer) and the run list
            |     paged whole (GET /v1/agents/{id}/runs) so every turn's shape can be dumped and the by-position pairing checked
            |     against the turns' timestamps.
            |  7. With --send: SendGate is read as the composer reads it; if idle, POST /v1/agents/{id}/runs with the text, and the run
            |     the server names is followed to its end or for --follow-seconds. This is the only call that writes anything.
            |
            |What it never does: store the key anywhere (the app's key store is not written), pin, archive, rename, steer, queue,
            |cancel, or touch the account's stores; write to the repository; or print any prompt, message or argument text
            |unless --show-text is given (ids are printed whole, since the report is for the operator's own machine). The
            |app's caches (the transcript window, the trace files) go to a temporary directory deleted when the run ends.
            |
            |Report sections: agent, load (the app's own load block), turns (one line per turn of the window: kind, prompt,
            |run pairing by position and by time, trace source, the coordinator's message through its stages), rows (the
            |presenter's output in order), independent pass (shapes of every turn read, message stage per turn), the app's
            |transcript diagnostics export as Settings would share it, send (when asked), and the ledger of every HTTP call.
            |
            |Exit code 0 when the run completed (whatever it found), 2 for bad options, 1 for a failure of the run itself.
            """.trimMargin()

        /** Parses the command line; a problem is reported as an [IllegalArgumentException] with the words for the user. */
        fun parse(args: Array<String>): TranscriptVerifyOptions {
            var options = TranscriptVerifyOptions()
            var i = 0
            fun value(name: String): String {
                i++
                return args.getOrNull(i)?.takeIf { !it.startsWith("--") || name == "--send" } ?: throw IllegalArgumentException("$name needs a value.")
            }
            while (i < args.size) {
                when (val arg = args[i]) {
                    "--agent" -> options = options.copy(agentId = value(arg).trim())
                    "--key-file" -> options = options.copy(keyFile = File(value(arg)))
                    "--mode" -> options = when (val mode = value(arg).trim().lowercase()) {
                        "extended" -> options.copy(extended = true)
                        "default", "documented" -> options.copy(extended = false)
                        else -> throw IllegalArgumentException("--mode takes extended or default, not '$mode'.")
                    }
                    "--turns" -> options = options.copy(turns = value(arg).trim().toIntOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("--turns needs a positive number."))
                    "--follow-seconds" -> options = options.copy(followSeconds = value(arg).trim().toIntOrNull()?.takeIf { it >= 0 } ?: throw IllegalArgumentException("--follow-seconds needs a number of seconds."))
                    "--send" -> options = options.copy(send = value(arg).takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("--send needs the text to send."))
                    "--show-text" -> options = options.copy(showText = true)
                    "--replay" -> options = options.copy(replay = true)
                    "--out" -> options = options.copy(out = File(value(arg)))
                    "--help", "-h" -> options = options.copy(help = true)
                    else -> throw IllegalArgumentException("Unknown option '$arg'. See --help.")
                }
                i++
            }
            if (options.help) return options
            if (options.replay) {
                if (options.agentId != null || options.keyFile != null) throw IllegalArgumentException("--replay takes no --agent or --key-file: it runs on the fixtures.")
                return options
            }
            val agent = options.agentId ?: throw IllegalArgumentException("--agent is required for a live run (or pass --replay).")
            if (!agent.startsWith("bc-")) throw IllegalArgumentException("--agent should be a chat id of the form bc-….")
            val keyFile = options.keyFile ?: throw IllegalArgumentException("--key-file is required for a live run: a file holding the API key.")
            if (!keyFile.isFile) throw IllegalArgumentException("--key-file ${keyFile.path} is not a file.")
            if (!keyFile.canRead()) throw IllegalArgumentException("--key-file ${keyFile.path} cannot be read.")
            return options
        }

        /** The options [TranscriptVerifyMain] set, read back inside the harness; null when the harness was not invoked through it. */
        fun fromProperties(): TranscriptVerifyOptions? {
            if (System.getProperty(PROP_INVOKED) != "true") return null
            return TranscriptVerifyOptions(
                agentId = System.getProperty(PROP_AGENT),
                keyFile = System.getProperty(PROP_KEY_FILE)?.let(::File),
                extended = System.getProperty(PROP_MODE, "extended") == "extended",
                turns = System.getProperty(PROP_TURNS)?.toIntOrNull(),
                followSeconds = System.getProperty(PROP_FOLLOW_SECONDS)?.toIntOrNull() ?: 120,
                send = System.getProperty(PROP_SEND),
                showText = System.getProperty(PROP_SHOW_TEXT) == "true",
                replay = System.getProperty(PROP_REPLAY) == "true",
                out = System.getProperty(PROP_OUT)?.let(::File),
            )
        }
    }
}
