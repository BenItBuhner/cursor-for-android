package com.cursorforandroid.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.SlashCatalog
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The popover rides on the composer, which cannot be skipped over while a run streams, so its search has to be held
 * against the query rather than run again for every frame of somebody else's work.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SlashPopoverSearchTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * The composer's recent names, as it hands them over: a fresh list on every pass, equal to the last one but not
     * the same object, so the popover cannot be skipped. Counts the walks `SlashCatalog.search` takes over it.
     */
    private class CountingRecent(private val backing: List<String>, private val walks: IntArray) : List<String> by backing {
        override fun iterator(): Iterator<String> {
            walks[0]++
            return backing.iterator()
        }

        override fun equals(other: Any?): Boolean = other is CountingRecent && other.backing == backing

        override fun hashCode(): Int = backing.hashCode()
    }

    @Test
    fun `the search does not run again when the composer recomposes around it`() {
        val walks = IntArray(1)
        val catalog = SlashCatalog.BUILT_IN
        var deltas by mutableStateOf(0)

        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                Column {
                    Text("delta $deltas")
                    SlashCommandPopover(
                        token = SlashToken(0, 3, "go"),
                        catalog = catalog,
                        recent = CountingRecent(listOf("land-it"), walks),
                        onPick = {},
                        onDismiss = {},
                    )
                }
            }
        }
        compose.waitUntil { compose.onAllNodesWithText("/goal").fetchSemanticsNodes().isNotEmpty() }
        val afterFirstSearch = walks[0]
        assertThat(afterFirstSearch).isGreaterThan(0)

        repeat(5) {
            deltas++
            compose.waitForIdle()
        }

        compose.onNodeWithText("/goal").assertExists()
        assertThat(walks[0]).isEqualTo(afterFirstSearch)
    }
}
