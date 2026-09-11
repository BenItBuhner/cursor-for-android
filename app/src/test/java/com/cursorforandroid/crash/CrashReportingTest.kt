package com.cursorforandroid.crash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.BuildConfig
import com.google.common.truth.Truth.assertThat
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.android.core.SentryAndroidOptions
import io.sentry.protocol.Request
import io.sentry.protocol.User
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The two gates in front of the SDK — a project to report to, and the user's word — and what a report is allowed to
 * carry once both are open.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CrashReportingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val configured = mutableListOf<SentryAndroidOptions>()
    private var closes = 0

    private fun reporting(dsn: String, uuid: String = "") = CrashReporting(
        context,
        dsn = dsn,
        proguardUuid = uuid,
        init = { _, configure -> configured += SentryAndroidOptions().also(configure) },
        close = { closes++ },
    )

    @After
    fun tearDown() = Sentry.close()

    @Test
    fun `a build without a project is unavailable and never starts the sdk, whatever the setting says`() {
        val reporting = reporting(dsn = "")
        assertThat(reporting.isAvailable).isFalse()
        reporting.apply(true)
        reporting.apply(false)
        assertThat(configured).isEmpty()
        assertThat(closes).isEqualTo(2)
    }

    @Test
    fun `the release build carries no project unless the workflow was given one`() {
        // PR and debug builds are made without -Papp.sentryDsn, so the app's own default is inert.
        assertThat(BuildConfig.SENTRY_DSN).isEmpty()
        assertThat(BuildConfig.SENTRY_PROGUARD_UUID).isEmpty()
        assertThat(CrashReporting(context).isAvailable).isFalse()
    }

    @Test
    fun `turning it on starts the sdk against the project, with nothing about the person or the session`() {
        reporting(dsn = DSN, uuid = UUID).apply(true)
        val options = configured.single()
        assertThat(options.dsn).isEqualTo(DSN)
        assertThat(options.proguardUuid).isEqualTo(UUID)
        assertThat(options.release).isEqualTo("${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}")
        assertThat(options.isSendDefaultPii).isFalse()
        assertThat(options.isEnableAutoSessionTracking).isFalse()
        assertThat(options.isSendClientReports).isFalse()
        assertThat(options.isAttachScreenshot).isFalse()
        assertThat(options.isAttachViewHierarchy).isFalse()
        assertThat(options.isEnableUserInteractionBreadcrumbs).isFalse()
        assertThat(options.isEnableNetworkEventBreadcrumbs).isFalse()
        assertThat(options.isEnableSystemEventBreadcrumbs).isFalse()
        assertThat(options.tracesSampleRate).isNull()
        assertThat(options.isEnableAutoActivityLifecycleTracing).isFalse()
        assertThat(options.isEnableNdk).isFalse()
        // The two things it is for.
        assertThat(options.isEnableUncaughtExceptionHandler).isTrue()
        assertThat(options.isAnrEnabled).isTrue()
        assertThat(options.beforeSend).isNotNull()
    }

    @Test
    fun `a build without a mapping id sends none`() {
        reporting(dsn = DSN).apply(true)
        assertThat(configured.single().proguardUuid).isNull()
    }

    @Test
    fun `turning it off closes the sdk`() {
        val reporting = reporting(dsn = DSN)
        reporting.apply(true)
        reporting.apply(false)
        assertThat(configured).hasSize(1)
        assertThat(closes).isEqualTo(1)
    }

    @Test
    fun `following the setting applies each change once, the first value included, from one collector`() = runTest(UnconfinedTestDispatcher()) {
        val setting = MutableStateFlow(false)
        val reporting = reporting(dsn = DSN)
        val job = reporting.follow(setting, this, dispatcher = UnconfinedTestDispatcher(testScheduler))
        assertThat(job).isNotNull()
        assertThat(closes).isEqualTo(1)
        setting.value = true
        setting.value = true
        assertThat(configured).hasSize(1)
        setting.value = false
        assertThat(closes).isEqualTo(2)
        // Armed again by the next activity: nothing doubles up.
        assertThat(reporting.follow(setting, this, dispatcher = UnconfinedTestDispatcher(testScheduler))).isNull()
        setting.value = true
        assertThat(configured).hasSize(2)
        job!!.cancel()
    }

    @Test
    fun `a report leaves without the user, the host or the request, whatever an integration put there`() {
        val event = SentryEvent(IllegalStateException("boom")).apply {
            user = User().apply { email = "a@b.c"; ipAddress = "10.0.0.1" }
            serverName = "pixel-of-bennett"
            request = Request().apply { url = "https://api.cursor.com/v0/agents"; headers = mapOf("Authorization" to "Bearer key") }
        }
        val scrubbed = reporting(dsn = DSN).scrub(event)
        assertThat(scrubbed.user).isNull()
        assertThat(scrubbed.serverName).isNull()
        assertThat(scrubbed.request).isNull()
        assertThat(scrubbed.throwable).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `the real sdk starts on request and is gone again when asked`() {
        val reporting = CrashReporting(context, dsn = DSN)
        assertThat(Sentry.isEnabled()).isFalse()
        reporting.apply(true)
        assertThat(Sentry.isEnabled()).isTrue()
        reporting.apply(false)
        assertThat(Sentry.isEnabled()).isFalse()
    }

    private companion object {
        // A well-formed DSN for a project that does not exist; nothing in these tests sends anything.
        const val DSN = "https://0123456789abcdef0123456789abcdef@o0.ingest.sentry.io/0"
        const val UUID = "2f1e6d7c-4b3a-4a9e-8c1d-0f9e8d7c6b5a"
    }
}
