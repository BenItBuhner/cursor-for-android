package com.cursorforandroid.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.DeviceOption
import com.cursorforandroid.domain.DeviceSection
import com.cursorforandroid.domain.DeviceTarget
import com.cursorforandroid.domain.KnownDevices
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class DeviceSheetTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val bennett = DeviceOption(
        target = DeviceTarget.machine("bennett"),
        subtitle = "Busy · bennett/codex-poly-bot",
        online = true,
        section = DeviceSection.Machines,
    )
    private val gpu = DeviceOption(
        target = DeviceTarget.pool("gpu"),
        subtitle = "2 connected · 1 in use",
        online = true,
        section = DeviceSection.Pools,
    )
    private val devices = listOf(KnownDevices.cloud, bennett, gpu)

    private var picked: DeviceTarget? = null

    private fun show(selected: DeviceTarget = DeviceTarget.Cloud, rows: List<DeviceOption> = devices) {
        compose.setContent {
            var current by remember { mutableStateOf(selected) }
            CursorTheme(mode = ThemeMode.Dark) {
                DeviceSheet(
                    devices = rows,
                    selected = current,
                    loading = false,
                    onSelect = {
                        picked = it
                        current = it
                    },
                    onRefresh = {},
                    onDismiss = {},
                )
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Device").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `cloud is the default row and this phone is not offered`() {
        show()
        compose.onNodeWithText("Cloud").assertExists()
        compose.onNodeWithText("Cursor-hosted VM").assertExists()
        compose.onNodeWithText("My machines").assertExists()
        compose.onNodeWithText("bennett").assertExists()
        compose.onNodeWithText("Team pools").assertExists()
        compose.onNodeWithText("gpu").assertExists()
        compose.onNodeWithText("This device").assertDoesNotExist()
        compose.onNodeWithText("Local").assertDoesNotExist()
        compose.onNodeWithText("This phone can't run an agent itself", substring = true).assertExists()
    }

    @Test
    fun `picking a machine reports that target`() {
        show()
        compose.onNodeWithText("bennett").performClick()
        assertThat(picked).isEqualTo(DeviceTarget.machine("bennett"))
    }

    @Test
    fun `a typed name is offered as a machine and as a pool`() {
        show()
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("studio")
        compose.onNodeWithText("Use as a machine").assertExists()
        compose.onNodeWithText("Use as a team pool").assertExists()
        compose.onNodeWithText("Use as a machine").performClick()
        assertThat(picked).isEqualTo(DeviceTarget.machine("studio"))
    }

    @Test
    fun `the typed filter survives the process being killed`() {
        val restorer = StateRestorationTester(compose)
        restorer.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                DeviceSheet(
                    devices = devices,
                    selected = DeviceTarget.Cloud,
                    loading = false,
                    onSelect = {},
                    onRefresh = {},
                    onDismiss = {},
                )
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Device").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("gpu")
        compose.onNodeWithText("2 connected \u00b7 1 in use").assertExists()

        restorer.emulateSavedInstanceStateRestore()

        compose.waitUntil(10_000) { compose.onAllNodesWithText("Device").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("2 connected \u00b7 1 in use").assertExists()
        assertThat(compose.onAllNodes(hasText("bennett")).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `search hides unmatched sections`() {
        show()
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("gpu")
        compose.onNodeWithText("2 connected · 1 in use").assertExists()
        assertThat(compose.onAllNodes(hasText("bennett")).fetchSemanticsNodes()).isEmpty()
    }
}
