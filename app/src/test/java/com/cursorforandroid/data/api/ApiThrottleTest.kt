package com.cursorforandroid.data.api

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The account service's throttle, on the test scheduler's clock: never more than its permits on the wire at once, a
 * 429 pausing every caller for the `Retry-After` it names and the refused call made once more after it, a second
 * refusal handed to the caller.
 */
class ApiThrottleTest {

    @Test
    fun `no more calls in flight than the permits, whoever asks`() = runTest {
        val throttle = ApiThrottle(maxInFlight = 3, now = { testScheduler.currentTime })
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val gates = List(8) { CompletableDeferred<Unit>() }
        val calls = gates.map { gate ->
            async {
                throttle.call {
                    peak.updateAndGet { maxOf(it, inFlight.incrementAndGet()) }
                    try { gate.await() } finally { inFlight.decrementAndGet() }
                }
            }
        }
        runCurrent()
        assertThat(inFlight.get()).isEqualTo(3)
        gates.forEach { it.complete(Unit); runCurrent() }
        calls.forEach { it.await() }
        assertThat(peak.get()).isEqualTo(3)
    }

    @Test
    fun `a 429 pauses every caller for the wait the server named, and the refused call is made once more`() = runTest {
        val throttle = ApiThrottle(maxInFlight = 3, now = { testScheduler.currentTime })
        var attempts = 0
        val refused = async {
            throttle.call {
                attempts++
                if (attempts == 1) throw ConnectRpcException(429, "resource_exhausted", "Rate limited.", retryAfterMillis = 2_000L)
                "answered"
            }
        }
        runCurrent()
        assertThat(attempts).isEqualTo(1)
        assertThat(throttle.pausedUntil()).isEqualTo(2_000L)
        assertThat(throttle.refusalCount).isEqualTo(1)
        // Another caller arriving during the pause waits it out rather than meeting the same refusal.
        var otherRan = -1L
        val other = async { throttle.call { otherRan = testScheduler.currentTime } }
        advanceTimeBy(1_000)
        runCurrent()
        assertThat(attempts).isEqualTo(1)
        assertThat(otherRan).isEqualTo(-1L)
        advanceTimeBy(1_001)
        runCurrent()
        assertThat(refused.await()).isEqualTo("answered")
        assertThat(attempts).isEqualTo(2)
        other.await()
        assertThat(otherRan).isAtLeast(2_000L)
        assertThat(throttle.pausedUntil()).isNull()
    }

    @Test
    fun `a second refusal is the caller's to hear, a refusal without a wait pauses a moment, and anything else passes through`() = runTest {
        val throttle = ApiThrottle(maxInFlight = 3, now = { testScheduler.currentTime })
        var attempts = 0
        val twice = async {
            runCatching { throttle.call<String> { attempts++; throw ConnectRpcException(429, "resource_exhausted", "Rate limited.") } }
        }
        advanceUntilIdle()
        val failure = twice.await().exceptionOrNull() as ConnectRpcException
        assertThat(attempts).isEqualTo(2)
        assertThat(failure.isRateLimited).isTrue()
        // No `Retry-After`: the default pause, twice over (once per refusal).
        assertThat(testScheduler.currentTime).isEqualTo(ApiThrottle.DEFAULT_PAUSE_MS)
        assertThat(throttle.refusalCount).isEqualTo(2)

        var plainAttempts = 0
        val plain = async { runCatching { throttle.call<String> { plainAttempts++; throw ConnectRpcException(500, "internal", "boom") } } }
        advanceUntilIdle()
        assertThat(plain.await().isFailure).isTrue()
        assertThat(plainAttempts).isEqualTo(1)
    }
}
