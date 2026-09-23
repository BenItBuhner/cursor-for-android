package com.cursorforandroid.widget

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.cursorforandroid.appGraph
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * When the widget picker's Android 15 previews are published: once for each installed build and boot, never again
 * for the same pair, again the moment either changes, and again after the system refused — the two publishes an hour
 * it allows are what a reinstall needs, so a start of the app must not spend them. An update of the app enqueues one
 * at once.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WidgetPreviewsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val graph get() = app.appGraph

    private lateinit var realPublisher: suspend (Context, List<WidgetKind>) -> Set<WidgetKind>

    /** Every kind the publisher was asked for, call by call. */
    private val asked = mutableListOf<List<WidgetKind>>()

    /** What the publisher answers: by default, that every kind went through. */
    private var answer: (List<WidgetKind>) -> Set<WidgetKind> = { it.toSet() }

    @Before
    fun seam() {
        realPublisher = WidgetPreviews.publisher
        WidgetPreviews.publisher = { _, kinds -> asked += kinds; answer(kinds) }
        runBlocking { graph.prefs.setWidgetPreviewsPublished(emptySet()) }
        // WorkManager outlives a test in this JVM; the previous test's publish must not be this one's evidence.
        WorkManager.getInstance(app).apply { cancelUniqueWork(WidgetPreviewsWorker.NAME).result.get(); pruneWork().result.get() }
    }

    @After
    fun restore() {
        WidgetPreviews.publisher = realPublisher
    }

    @Test
    fun `previews are published once for a build and boot, and not again for the same pair`() = runBlocking {
        assertThat(WidgetPreviews.publishIfNeeded(app, graph, stamp = "build-1/boot-1")).isTrue()
        assertThat(asked).hasSize(1)
        assertThat(asked.single()).containsExactlyElementsIn(WidgetKinds.all)

        assertThat(WidgetPreviews.publishIfNeeded(app, graph, stamp = "build-1/boot-1")).isTrue()
        assertThat(asked).hasSize(1)
        Unit
    }

    @Test
    fun `a new build or a new boot publishes again, and the old pair's record goes`() = runBlocking {
        WidgetPreviews.publishIfNeeded(app, graph, stamp = "build-1/boot-1")
        WidgetPreviews.publishIfNeeded(app, graph, stamp = "build-2/boot-1")
        WidgetPreviews.publishIfNeeded(app, graph, stamp = "build-2/boot-2")

        assertThat(asked).hasSize(3)
        val recorded = graph.prefs.widgetPreviewsPublished.first()
        assertThat(recorded).hasSize(WidgetKinds.all.size)
        assertThat(recorded.all { it.startsWith("build-2/boot-2|") }).isTrue()
        Unit
    }

    @Test
    fun `a publish the system refused is not recorded, so the next start tries again`() = runBlocking {
        answer = { emptySet() }
        assertThat(WidgetPreviews.publishIfNeeded(app, graph, stamp = "build-1/boot-1")).isFalse()
        assertThat(graph.prefs.widgetPreviewsPublished.first()).isEmpty()

        answer = { it.toSet() }
        assertThat(WidgetPreviews.publishIfNeeded(app, graph, stamp = "build-1/boot-1")).isTrue()
        assertThat(asked).hasSize(2)
        Unit
    }

    @Test
    fun `the stamp moves with the boot count`() {
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 3)
        val before = WidgetPreviews.stamp(app)
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 4)
        assertThat(WidgetPreviews.stamp(app)).isNotEqualTo(before)
        assertThat(WidgetPreviews.stamp(app)).endsWith("/4")
    }

    /** The update itself sets the publish going — as WorkManager's, off the broadcast's clock; the picker does not wait for the app to be opened. */
    @Test
    fun `an update of the app enqueues the new build's publish at once`() {
        WidgetPackageReplacedReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        val work = WorkManager.getInstance(app).getWorkInfosForUniqueWork(WidgetPreviewsWorker.NAME).get()
        assertThat(work).hasSize(1)
        // WorkManager starts the worker from the main looper, which this thread is; let it turn until the publish
        // the worker makes has reached the seam.
        val deadline = System.nanoTime() + 10_000_000_000L
        while (asked.isEmpty() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertThat(asked).hasSize(1)
        assertThat(asked.single()).containsExactlyElementsIn(WidgetKinds.all)
    }

    @Test
    fun `another broadcast is ignored`() {
        WidgetPackageReplacedReceiver().onReceive(app, Intent(Intent.ACTION_PACKAGE_REPLACED))

        assertThat(WorkManager.getInstance(app).getWorkInfosForUniqueWork(WidgetPreviewsWorker.NAME).get()).isEmpty()
    }
}
