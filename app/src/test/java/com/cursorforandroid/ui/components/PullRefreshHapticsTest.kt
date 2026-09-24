package com.cursorforandroid.ui.components

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The sidebar's pull to refresh as the finger feels it: the threshold tick arming, and nothing after the release —
 * not while the refresh it asked for is on its way (the list's state says so frames later), not as the indicator
 * springs to the threshold and spins, not as it rests there when the refresh ends, not as it hides.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PullRefreshHapticsTest {

    @get:Rule
    val compose = createComposeRule()

    private val recorder = RecordingView(ApplicationProvider.getApplicationContext())

    /** A list under the sidebar's pull, whose refresh is under way [startMs] after it is asked for and lasts [lastsMs]. */
    @OptIn(ExperimentalMaterial3Api::class)
    private fun sidebar(startMs: Long, lastsMs: Long) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                var refreshing by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                val pull = rememberPullToRefreshState()
                CompositionLocalProvider(LocalView provides recorder) { PullRefreshHaptics(pull, refreshing) }
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = {
                        scope.launch {
                            delay(startMs)
                            refreshing = true
                            delay(lastsMs)
                            refreshing = false
                        }
                    },
                    state = pull,
                    modifier = Modifier.fillMaxSize().testTag("list"),
                ) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(40) { Text("Chat $it", Modifier.fillMaxWidth().height(56.dp)) }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun pullDownAndLetGo() {
        compose.onNodeWithTag("list").performTouchInput { swipeDown(startY = top + 4f, endY = bottom - 4f, durationMillis = 900) }
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
    }

    @Test
    fun `a refresh that starts a few frames after the release and ends quickly is felt only arming`() {
        sidebar(startMs = 48, lastsMs = 64)
        pullDownAndLetGo()
        assertThat(recorder.played).containsExactly(Haptic.ThresholdActivate.constant())
    }

    @Test
    fun `a refresh that holds the indicator a while is felt only arming`() {
        sidebar(startMs = 48, lastsMs = 1_200)
        pullDownAndLetGo()
        assertThat(recorder.played).containsExactly(Haptic.ThresholdActivate.constant())
    }

    /** A view that keeps every haptic it is asked for. */
    private class RecordingView(context: Context) : View(context) {
        val played = mutableListOf<Int>()

        override fun performHapticFeedback(feedbackConstant: Int): Boolean {
            played += feedbackConstant
            return true
        }
    }
}
