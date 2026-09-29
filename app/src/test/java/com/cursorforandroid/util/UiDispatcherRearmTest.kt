package com.cursorforandroid.util

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.annotation.Config

/**
 * [UiDispatcherRearm]: a snapshot written by a worker while Robolectric resets between two tests must not leave the
 * next test's Compose waiting for a dispatch that never comes. Without the rearm the second test never gets idle
 * (Espresso's `AppNotIdleException`); the order of the two is what is tested, so they run by name.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class UiDispatcherRearmTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `a - a worker keeps writing snapshot state past the end of its test`() {
        val count = mutableIntStateOf(0)
        compose.setContent { Text("written ${count.intValue}") }
        compose.waitForIdle()
        val until = System.nanoTime() + WRITER_MILLIS * 1_000_000
        Thread({ while (System.nanoTime() < until) { count.intValue++; Thread.sleep(1) } }, "outliving-writer").apply {
            isDaemon = true
            start()
        }
    }

    @Test
    fun `b - the next test's composition still gets idle`() {
        val shown = mutableIntStateOf(0)
        compose.setContent { Text("shown ${shown.intValue}") }
        shown.intValue = 2
        compose.onNodeWithText("shown 2").assertExists()
    }

    private companion object {
        const val WRITER_MILLIS = 3_000L
    }
}
