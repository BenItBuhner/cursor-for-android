package com.cursorforandroid.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager
import java.io.File

/**
 * What the next launch finds after the app died: the uncaught exception with the app's state at that moment, and the
 * deaths only Android saw (low memory, ANR), each once, the newest offered on the card until it is closed.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CrashLogTest {

    @get:Rule val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private var now = 1_800_000_000_000L

    private fun log(dir: File) = CrashLog(dir, "0.3.99", nowMillis = { now })

    @Test
    fun `an uncaught exception is written with its trace and the state, and offered on the next launch`() {
        val dir = folder.newFolder("crash")
        val first = log(dir)
        first.install { "live sync: on held=20\nthreads: 180" }
        val error = IllegalStateException("boom", java.util.ConcurrentModificationException())
        first.record("DefaultDispatcher-worker-7", error)

        // The next process: a new log over the same directory.
        val next = log(dir).also { it.load() }
        val report = next.pending.value!!
        assertThat(report.headline).contains("java.lang.IllegalStateException: boom")
        assertThat(report.text).contains("0.3.99")
        assertThat(report.text).contains("Uncaught on thread \"DefaultDispatcher-worker-7\"")
        assertThat(report.text).contains("Caused by: java.util.ConcurrentModificationException")
        assertThat(report.text).contains("live sync: on held=20")
        assertThat(next.export()).contains("boom")
    }

    @Test
    fun `the heap past its mark leaves a snapshot of the state, which the crash that follows carries`() {
        val dir = folder.newFolder("crash")
        val log = log(dir)
        var state = "chats=24 held=20 traces=400"
        log.install { state }
        var used = 100L shl 20
        log.watchMemory(periodMs = 20L, heap = { used to (256L shl 20) })
        Thread.sleep(150)
        assertThat(File(dir, CrashLog.SNAPSHOT).exists()).isFalse()

        used = 230L shl 20
        val deadline = System.currentTimeMillis() + 5_000
        while (!File(dir, CrashLog.SNAPSHOT).exists() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertThat(File(dir, CrashLog.SNAPSHOT).readText()).contains("heap 230 of 256 MB")

        // Memory gone: the state can no longer be read at the crash, but the snapshot taken on the way there can.
        state = "never read"
        log.install { throw OutOfMemoryError("Failed to allocate a 8208 byte allocation") }
        now += 60_000L
        log.record("OkHttp TaskRunner", OutOfMemoryError("Failed to allocate a 8208 byte allocation"))
        val report = log(dir).also { it.load() }.pending.value!!
        assertThat(report.headline).contains("OutOfMemoryError")
        assertThat(report.text).contains("---- Last memory snapshot ----")
        assertThat(report.text).contains("chats=24 held=20 traces=400")
        // The snapshot is not a report of its own.
        assertThat(log(dir).reports()).hasSize(1)
    }

    @Test
    fun `the installed handler records and still hands the exception on`() {
        val dir = folder.newFolder("crash")
        val handed = mutableListOf<Throwable>()
        val before = Thread.getDefaultUncaughtExceptionHandler()
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, e -> handed += e }
            resetInstall()
            log(dir).install { "state" }
            val thread = Thread({ throw OutOfMemoryError("Failed to allocate") }, "stream-io")
            thread.start()
            thread.join()
            assertThat(handed.map { it.message }).containsExactly("Failed to allocate")
            val next = log(dir).also { it.load() }
            assertThat(next.pending.value!!.headline).contains("OutOfMemoryError")
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
            resetInstall()
        }
    }

    @Test
    fun `a state reader that blocks does not keep the trace from being written`() {
        val dir = folder.newFolder("crash")
        val lock = Object()
        val holder = Thread { synchronized(lock) { Thread.sleep(5_000) } }.apply { isDaemon = true; start() }
        Thread.sleep(50)
        val log = log(dir)
        log.install { synchronized(lock) { "never" } }
        val started = System.nanoTime()
        log.record("main", RuntimeException("deadlocked"))
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(4_000L)
        holder.interrupt()
        val text = log(dir).also { it.load() }.pending.value!!.text
        assertThat(text).contains("deadlocked")
        assertThat(text).contains("state unreadable")
    }

    @Test
    fun `closing the card keeps the report for the export and does not offer it again`() {
        val dir = folder.newFolder("crash")
        log(dir).record("main", RuntimeException("once"))
        val second = log(dir).also { it.load() }
        second.dismiss()
        val third = log(dir).also { it.load() }
        assertThat(third.pending.value).isNull()
        assertThat(third.export()).contains("once")
        // A newer crash is offered again.
        now += 60_000
        third.record("main", RuntimeException("twice"))
        assertThat(log(dir).also { it.load() }.pending.value!!.headline).contains("twice")
    }

    @Test
    fun `only the newest reports are kept, and the share text is bounded`() {
        val dir = folder.newFolder("crash")
        val log = log(dir)
        repeat(CrashLog.MAX_REPORTS + 4) { i ->
            now += 1_000
            log.record("main", RuntimeException("crash $i " + "x".repeat(50_000)))
        }
        assertThat(log.reports()).hasSize(CrashLog.MAX_REPORTS)
        assertThat(log.reports().first().headline).contains("crash ${CrashLog.MAX_REPORTS + 3}")
        assertThat(log.export(CrashLog.SHARE_MAX_CHARS).length).isAtMost(CrashLog.SHARE_MAX_CHARS + 100)
    }

    @Test
    fun `Android's record of a low-memory kill and an ANR is kept once each, and a crash already written is not doubled`() {
        val dir = folder.newFolder("crash")
        val shadow: ShadowActivityManager = shadowOf(activityManager)
        val crashAt = now - 300_000
        log(dir).also { it.install { "" } }.let { l -> now = crashAt; l.record("main", IllegalStateException("seen by the handler")) }
        now = 1_800_000_000_000L
        shadow.addApplicationExitInfo(exit(ApplicationExitInfo.REASON_CRASH, crashAt + 200, "java.lang.IllegalStateException"))
        shadow.addApplicationExitInfo(exit(ApplicationExitInfo.REASON_LOW_MEMORY, now - 200_000, null, pssKb = 900_000))
        shadow.addApplicationExitInfo(exit(ApplicationExitInfo.REASON_ANR, now - 100_000, "Input dispatching timed out"))
        shadow.addApplicationExitInfo(exit(ApplicationExitInfo.REASON_USER_REQUESTED, now - 50_000, null))

        val log = log(dir)
        log.recordExitReasons(activityManager, context.packageName)
        log.load()
        val reports = log.reports()
        assertThat(reports.map { it.headline }).containsExactly("ANR", "LOW_MEMORY", "java.lang.IllegalStateException: seen by the handler").inOrder()
        assertThat(reports.last().text).contains("Android's exit record")
        assertThat(reports[1].text).contains("pss=878 MB")
        assertThat(log.pending.value!!.headline).isEqualTo("ANR")

        // Read again on the next launch: nothing new.
        log(dir).recordExitReasons(activityManager, context.packageName)
        assertThat(log(dir).reports()).hasSize(3)
    }

    @Test
    fun `an ANR dump is reordered with the main thread first`() {
        val dump = "\"Signal Catcher\" daemon\n  at a.b\n\n\"main\" prio=5 tid=1 Blocked\n  at com.cursorforandroid.X.y\n\n\"OkHttp\" x\n"
        val out = CrashLog.anrMainFirst(dump)
        assertThat(out).startsWith("\"main\" prio=5 tid=1 Blocked")
        assertThat(out).contains("Signal Catcher")
    }

    @Test
    fun `threads are counted by pool`() {
        assertThat(CrashContext.pool("DefaultDispatcher-worker-12")).isEqualTo("DefaultDispatcher-worker-#")
        assertThat(CrashContext.pool("OkHttp https://api.cursor.com/...")).isEqualTo("OkHttp <call>")
        assertThat(CrashContext.threads()).contains("threads: ")
    }

    private fun exit(reason: Int, at: Long, description: String?, pssKb: Long = 0): ApplicationExitInfo {
        // Built through its hidden setters, as the system server builds it.
        return ApplicationExitInfo::class.java.getDeclaredConstructor().newInstance().also { e ->
            fun set(name: String, type: Class<*>, value: Any?) = ApplicationExitInfo::class.java.getDeclaredMethod(name, type).also { it.isAccessible = true }.invoke(e, value)
            set("setReason", Int::class.javaPrimitiveType!!, reason)
            set("setTimestamp", Long::class.javaPrimitiveType!!, at)
            set("setDescription", String::class.java, description)
            set("setPss", Long::class.javaPrimitiveType!!, pssKb)
            set("setProcessName", String::class.java, context.packageName)
            set("setPackageName", String::class.java, context.packageName)
            set("setImportance", Int::class.javaPrimitiveType!!, ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
        }
    }

    private fun resetInstall() = CrashLog.forgetHandlerForTest()
}
