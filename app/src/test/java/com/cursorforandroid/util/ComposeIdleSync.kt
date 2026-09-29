package com.cursorforandroid.util

import android.os.Looper
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import org.robolectric.Shadows.shadowOf

/**
 * Robolectric keeps the main looper paused; disk reads and [kotlinx.coroutines.Dispatchers.Default] work finish on
 * other threads and land on the main queue. The Compose harness's frame clock does not advance with the wall clock,
 * and `waitUntil` alone runs on the frame clock — so conditions that need both a repository emission and a
 * recomposition must pump the looper and step one frame between checks, with [ComposeContentTestRule.mainClock] held
 * so infinite animations do not spin [ComposeContentTestRule.waitForIdle] forever.
 */
fun ComposeContentTestRule.awaitSynced(
    timeoutMillis: Long = 30_000,
    condition: () -> Boolean,
) {
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
    val looper = Looper.getMainLooper()
    mainClock.autoAdvance = false
    try {
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Condition was still not satisfied after ${timeoutMillis}ms" }
            shadowOf(looper).idle()
            mainClock.advanceTimeByFrame()
            waitForIdle()
        }
    } finally {
        mainClock.autoAdvance = true
    }
}

/** One pass: main-queue drained, one Compose frame stepped, harness idle. */
fun ComposeContentTestRule.pumpSynced() {
    val wasAutoAdvance = mainClock.autoAdvance
    mainClock.autoAdvance = false
    try {
        shadowOf(Looper.getMainLooper()).idle()
        mainClock.advanceTimeByFrame()
        waitForIdle()
    } finally {
        mainClock.autoAdvance = wasAutoAdvance
    }
}

/** Hold the frame clock before [setContent] when the tree has infinite transitions (running glyphs, spinners). */
fun ComposeContentTestRule.holdFrameClock() {
    mainClock.autoAdvance = false
}
