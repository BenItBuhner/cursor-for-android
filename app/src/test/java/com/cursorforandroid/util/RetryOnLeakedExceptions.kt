package com.cursorforandroid.util

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Runs a Compose test once more when its first attempt was refused before the test body ran: the compose rule's
 * `runTest` opening on uncaught exceptions already on record (`kotlinx.coroutines.test.UncaughtExceptionsBeforeTest`,
 * an internal class, so it is known here by name).
 *
 * Those exceptions belong to a coroutine an earlier test left running. The view-model tests build an [AppGraph]
 * per test and never stop its repositories, whose scopes run on `Dispatchers.IO`; one of their deferreds finishing
 * while `Dispatchers.Main` is some test's unconfined dispatcher cannot dispatch its awaiter and throws into the
 * coroutine-test collector, which hands the exception to whichever `runTest` opens next. A class whose tests switch
 * Robolectric configuration between them opens its `runTest`s a second or so apart, which is exactly the window a
 * late deferred lands in. None of that is the refused test's doing, and the refusal has already drained the record,
 * so the second attempt starts clean; a second refusal is a fresh leak and is let through, as is every other error.
 *
 * Chain it outside the compose rule: `RuleChain.outerRule(RetryOnLeakedExceptions()).around(compose)`. The retried
 * statement is the whole of `@Before`, the test and `@After` under a fresh activity, which is what the rule wraps.
 */
class RetryOnLeakedExceptions : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            try {
                base.evaluate()
            } catch (refusal: IllegalStateException) {
                if (refusal.javaClass.name != REFUSED_BEFORE_TEST) throw refusal
                System.err.println(
                    "${description.displayName} was refused before it ran, by exceptions a test before it leaked; running it again.\n" +
                        refusal.suppressed.joinToString("\n") { "  leaked: $it" },
                )
                base.evaluate()
            }
        }
    }

    companion object {
        const val REFUSED_BEFORE_TEST = "kotlinx.coroutines.test.UncaughtExceptionsBeforeTest"
    }
}
