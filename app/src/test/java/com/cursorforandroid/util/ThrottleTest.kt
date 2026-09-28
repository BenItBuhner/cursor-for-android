package com.cursorforandroid.util

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThrottleTest {

    @Test
    fun `the first value goes out at once, a burst collapses to its latest, and the last value is never lost`() = runTest {
        val source = flow {
            emit(1)
            delay(100); emit(2)
            delay(100); emit(3)
            delay(100); emit(4)
            delay(2_000); emit(5)
        }
        assertThat(source.throttleLatest(1_000).toList()).containsExactly(1, 4, 5).inOrder()
    }

    @Test
    fun `values are spaced by at least the period`() = runTest {
        val times = mutableListOf<Long>()
        flow { repeat(30) { emit(it); delay(100) } }
            .throttleLatest(1_000)
            .collect { times += currentTime }
        assertThat(times.zipWithNext { a, b -> b - a }.all { it >= 1_000 }).isTrue()
        assertThat(times.size).isAtMost(4)
    }

    @Test
    fun `a completed source still delivers its final value`() = runTest {
        val out = flow { emit("a"); emit("b"); emit("c") }.throttleLatest(1_000).toList()
        assertThat(out.first()).isEqualTo("a")
        assertThat(out.last()).isEqualTo("c")
    }
}
