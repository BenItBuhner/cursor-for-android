package com.cursorforandroid

import android.os.Looper
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.window.layout.FoldingFeature
import androidx.window.testing.layout.FoldingFeature
import androidx.window.testing.layout.TestWindowLayoutInfo
import androidx.window.testing.layout.WindowLayoutInfoPublisherRule
import com.cursorforandroid.ui.panel.Hinge
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** That the fold the platform reports for the activity's window reaches the shell ([com.cursorforandroid.ui.panel.LocalHinge]). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w841dp-h701dp-320dpi")
class MainActivityHingeTest {

    @get:Rule
    val publisher = WindowLayoutInfoPublisherRule()

    private var activity: ActivityController<MainActivity>? = null

    /** Torn down with the test, for the reason [MainActivityThemeTest.destroyActivity] gives. */
    @After
    fun destroyActivity() {
        activity?.pause()?.stop()?.destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `the window's fold is followed as the device opens and closes`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().also { activity = it }
        val main = controller.get()
        assertThat(main.hinge.value).isNull()

        val book = FoldingFeature(main, center = 841, size = 0, state = FoldingFeature.State.HALF_OPENED, orientation = FoldingFeature.Orientation.VERTICAL)
        publisher.overrideWindowLayoutInfo(TestWindowLayoutInfo(listOf(book)))
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(main.hinge.value).isEqualTo(Hinge(420.5.dp, 420.5.dp))

        val flat = FoldingFeature(main, center = 841, size = 0, state = FoldingFeature.State.FLAT, orientation = FoldingFeature.Orientation.VERTICAL)
        publisher.overrideWindowLayoutInfo(TestWindowLayoutInfo(listOf(flat)))
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(main.hinge.value).isNull()
    }
}
