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

    /**
     * A big Project opening: the refresh fills the control lane and the record's prefetch fills the blobs lane with a
     * backlog behind each. A figure on screen asks for its store and its link in a lane of its own and is answered at
     * once, and what waits where is said.
     */
    @Test
    fun `a figure's reads go out while the refresh and the blob prefetch have every other permit and a backlog`() = runTest {
        val throttle = ApiThrottle(maxInFlight = 3, now = { testScheduler.currentTime }, blobsInFlight = 8)
        val held = CompletableDeferred<Unit>()
        val background = List(10) { async { throttle.call { held.await() } } } +
            List(150) { async { throttle.call(lane = ApiThrottle.Lane.BLOBS) { held.await() } } }
        runCurrent()
        assertThat(throttle.inFlight(ApiThrottle.Lane.CONTROL)).isEqualTo(3)
        assertThat(throttle.waiting(ApiThrottle.Lane.BLOBS)).isEqualTo(142)

        val presigned = async { throttle.call(lane = ApiThrottle.Lane.MEDIA) { "https://files.cursor.sh/media/shot.png" } }
        runCurrent()
        assertThat(presigned.isCompleted).isTrue()
        assertThat(presigned.await()).isEqualTo("https://files.cursor.sh/media/shot.png")

        val shown = List(6) { CompletableDeferred<Unit>() }
        val figures = shown.map { gate -> async { throttle.call(lane = ApiThrottle.Lane.MEDIA) { gate.await() } } }
        runCurrent()
        assertThat(throttle.describe()).isEqualTo("control 3/3 +7 waiting · blobs 8/8 +142 waiting · watch 0/2 · media 4/4 +2 waiting")

        held.complete(Unit)
        shown.forEach { it.complete(Unit) }
        advanceUntilIdle()
        (background + figures).forEach { it.await() }
        assertThat(throttle.describe()).isEqualTo("control 0/3 · blobs 0/8 · watch 0/2 · media 0/4")
    }

    @Test
    fun `a caller that gives up while waiting for a permit leaves the count and takes no permit with it`() = runTest {
        val throttle = ApiThrottle(maxInFlight = 1, now = { testScheduler.currentTime })
        val held = CompletableDeferred<Unit>()
        val first = async { throttle.call { held.await() } }
        val second = async { throttle.call { "never" } }
        runCurrent()
        assertThat(throttle.waiting(ApiThrottle.Lane.CONTROL)).isEqualTo(1)
        second.cancel()
        runCurrent()
        assertThat(throttle.waiting(ApiThrottle.Lane.CONTROL)).isEqualTo(0)
        held.complete(Unit)
        first.await()
        assertThat(async { throttle.call { "next" } }.await()).isEqualTo("next")
        assertThat(throttle.inFlight(ApiThrottle.Lane.CONTROL)).isEqualTo(0)
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
