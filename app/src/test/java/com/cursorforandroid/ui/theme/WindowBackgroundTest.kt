package com.cursorforandroid.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The one colour drawn before Compose is: it is a resource, so it can only follow the system's night mode, and it
 * has to agree with the canvas [CursorTheme] paints a moment later or a cold start flashes the wrong colour.
 */
@RunWith(AndroidJUnit4::class)
class WindowBackgroundTest {

    private fun canvasResource(): Color {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return Color(context.getColor(R.color.cursor_canvas))
    }

    @Test
    @Config(qualifiers = "notnight")
    fun `a light phone gets the light canvas behind the splash`() {
        assertThat(canvasResource().toArgb()).isEqualTo(CursorLightColors.canvas.toArgb())
    }

    @Test
    @Config(qualifiers = "night")
    fun `a dark phone gets the dark canvas behind the splash`() {
        assertThat(canvasResource().toArgb()).isEqualTo(CursorDarkColors.canvas.toArgb())
    }
}
