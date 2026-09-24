package com.cursorforandroid.widget

import android.app.Application
import android.content.res.XmlResourceParser
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.R
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * The layouts a launcher inflates on its own — each widget provider's `initialLayout` (what a placed widget shows
 * until its first render) and `previewLayout` (the Android 12+ picker preview, and Android 15's fallback whenever
 * the generated preview is not in the system's memory) — go through `AppWidgetHostView`'s inflater filter, which
 * admits only classes annotated `@RemoteView`. Anything else fails the whole inflation and the launcher shows
 * "Can't load widget" in the widget's place. A plain `<View>` spacer in the Chats widget's preview did exactly
 * that; this inflates every provider's layouts the way the launcher does, so the next one is caught here.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WidgetProviderLayoutsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** The launcher's rule, as `AppWidgetHostView.INFLATER_FILTER` states it. */
    private val remoteViewFilter = LayoutInflater.Filter { clazz -> clazz.isAnnotationPresent(RemoteViews.RemoteView::class.java) }

    @Test
    fun `every widget provider's initial and preview layouts inflate under the launcher's RemoteView filter`() {
        val providers = appWidgetProviders()
        assertThat(providers.map { it.name }).contains("widget_chats_info")
        providers.forEach { provider ->
            listOf("initialLayout" to provider.initialLayout, "previewLayout" to provider.previewLayout).forEach { (attribute, layout) ->
                if (layout == 0) return@forEach
                val result = runCatching { inflateAsLauncher(layout) }
                assertWithMessage("${provider.name}'s $attribute (${app.resources.getResourceEntryName(layout)}) must inflate under RemoteViews' filter: ${result.exceptionOrNull()?.cause ?: result.exceptionOrNull()}")
                    .that(result.isSuccess).isTrue()
                assertThat(result.getOrThrow()).isNotNull()
            }
        }
    }

    /** The rule itself, so a passing run above means the filter was really applied: a bare View is refused. */
    @Test
    fun `the filter refuses a plain View, as the launcher does`() {
        val inflater = LayoutInflater.from(app).cloneInContext(app).apply { filter = remoteViewFilter }
        val refused = runCatching { inflater.createView("View", "android.view.", null) }
        assertThat(refused.isFailure).isTrue()
        val allowed = runCatching { inflater.createView("FrameLayout", "android.widget.", null) }
        assertThat(allowed.isSuccess).isTrue()
    }

    private fun inflateAsLauncher(layout: Int): View {
        val inflater = LayoutInflater.from(app).cloneInContext(app).apply { filter = remoteViewFilter }
        return inflater.inflate(layout, FrameLayout(app), false)
    }

    private class Provider(val name: String, val initialLayout: Int, val previewLayout: Int)

    /** Every `<appwidget-provider>` among the app's own XML resources, read as the system reads it. */
    private fun appWidgetProviders(): List<Provider> =
        R.xml::class.java.fields.mapNotNull { field ->
            val id = field.getInt(null)
            app.resources.getXml(id).use { parser ->
                while (parser.eventType != XmlPullParser.START_TAG) parser.next()
                if (parser.name != "appwidget-provider") return@mapNotNull null
                Provider(field.name, parser.resource("initialLayout"), parser.resource("previewLayout"))
            }
        }

    private fun XmlResourceParser.resource(attribute: String): Int = getAttributeResourceValue(ANDROID_NS, attribute, 0)

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
