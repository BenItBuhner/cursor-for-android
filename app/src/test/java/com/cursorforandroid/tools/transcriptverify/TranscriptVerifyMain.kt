package com.cursorforandroid.tools.transcriptverify

import org.junit.runner.JUnitCore
import org.junit.runner.Request
import kotlin.system.exitProcess

/**
 * The command line of `tools/transcript-verify` (`./gradlew :app:transcriptVerify --args="…"`, or the `run.sh` beside
 * it). Parses the options, then runs [TranscriptVerifyHarness] under Robolectric through JUnit — the app's stores
 * take an Android `Context`, and Robolectric is what the unit tests already stand one up with — handing the options
 * over as system properties (see [TranscriptVerifyOptions.toProperties]: the key's path, never the key). The report
 * is written to standard output as the run goes; the exit code says whether the run itself completed.
 */
fun main(args: Array<String>) {
    val options = try {
        TranscriptVerifyOptions.parse(args)
    } catch (e: IllegalArgumentException) {
        System.err.println("transcript-verify: ${e.message}")
        System.err.println("Run with --help for the options.")
        exitProcess(2)
    }
    if (options.help) {
        println(TranscriptVerifyOptions.HELP)
        exitProcess(0)
    }
    options.toProperties().forEach { (k, v) -> System.setProperty(k, v) }
    System.setProperty(TranscriptVerifyOptions.PROP_INVOKED, "true")
    // Robolectric's own chatter goes to standard error; the report alone is on standard output.
    System.setProperty("robolectric.logging", "stderr")
    System.setProperty("robolectric.logging.enabled", "false")
    val method = if (options.replay) "replay" else "live"
    val result = JUnitCore().run(Request.method(TranscriptVerifyHarness::class.java, method))
    result.failures.forEach { failure ->
        System.err.println("transcript-verify: the run failed: ${failure.message}")
        System.err.println(failure.trace)
    }
    exitProcess(if (result.wasSuccessful()) 0 else 1)
}
