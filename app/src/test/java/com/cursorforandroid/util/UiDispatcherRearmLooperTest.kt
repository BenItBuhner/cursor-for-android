package com.cursorforandroid.util

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [UiDispatcherRearm.mend] for a test that runs the main looper by hand, where Espresso's idling resources are never
 * asked: after a worker's snapshot writes straddled the reset between two tests, an activity's own composition (on
 * [androidx.compose.ui.platform.AndroidUiDispatcher], waiting for its frames) still happens. Run by name, the order
 * being what is tested.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class UiDispatcherRearmLooperTest {

    @Test
    fun `a - a worker keeps writing snapshot state past the end of its test`() {
        val count = mutableIntStateOf(0)
        val until = System.nanoTime() + WRITER_MILLIS * 1_000_000
        Thread({ while (System.nanoTime() < until) { count.intValue++; Thread.sleep(1) } }, "outliving-writer").apply {
            isDaemon = true
            start()
        }
        Robolectric.buildActivity(ComponentActivity::class.java).setup().get().setContent { Text("${count.intValue}") }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `b - an activity composed by hand still runs its effects and recomposes`() {
        Thread.sleep(WRITER_MILLIS + 500)
        var composed = false
        Robolectric.buildActivity(ComponentActivity::class.java).setup().get().setContent {
            // Needs both halves of the dispatcher: the effect is dispatched to it, the recomposition waits for its frame.
            var started by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { started = true }
            if (started) SideEffect { composed = true }
        }
        val looper = shadowOf(Looper.getMainLooper())
        repeat(200) {
            if (composed) return@repeat
            UiDispatcherRearm.mend()
            looper.idleFor(Duration.ofMillis(16))
        }
        assertThat(composed).isTrue()
    }

    private companion object {
        const val WRITER_MILLIS = 3_000L
    }
}
