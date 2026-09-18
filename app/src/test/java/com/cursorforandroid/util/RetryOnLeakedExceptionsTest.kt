package com.cursorforandroid.util

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.Statement

class RetryOnLeakedExceptionsTest {

    private val description = Description.createTestDescription("Suite", "test")

    /** The refusal as coroutine-test throws it; internal to that library, so built by name as the rule matches it. */
    private fun refusal(): IllegalStateException =
        Class.forName(RetryOnLeakedExceptions.REFUSED_BEFORE_TEST).getDeclaredConstructor().newInstance() as IllegalStateException

    private fun statement(vararg outcomes: Throwable?): Pair<Statement, () -> Int> {
        var attempts = 0
        val statement = object : Statement() {
            override fun evaluate() {
                val outcome = outcomes[attempts]
                attempts++
                if (outcome != null) throw outcome
            }
        }
        return statement to { attempts }
    }

    @Test
    fun `a test refused by exceptions leaked before it ran is run once more`() {
        val (statement, attempts) = statement(refusal(), null)
        RetryOnLeakedExceptions().apply(statement, description).evaluate()
        assertThat(attempts()).isEqualTo(2)
    }

    @Test
    fun `a second refusal is a fresh leak and is let through`() {
        val (statement, attempts) = statement(refusal(), refusal(), null)
        assertThrows(IllegalStateException::class.java) { RetryOnLeakedExceptions().apply(statement, description).evaluate() }
        assertThat(attempts()).isEqualTo(2)
    }

    @Test
    fun `the test's own failures are not retried`() {
        val (statement, attempts) = statement(AssertionError("bottom gap 10.0 is less than 12.0"), null)
        assertThrows(AssertionError::class.java) { RetryOnLeakedExceptions().apply(statement, description).evaluate() }
        assertThat(attempts()).isEqualTo(1)
    }

    @Test
    fun `a test that passes runs once`() {
        val (statement, attempts) = statement(null)
        RetryOnLeakedExceptions().apply(statement, description).evaluate()
        assertThat(attempts()).isEqualTo(1)
    }
}
