package com.cursorforandroid.crash

import android.content.Context
import com.cursorforandroid.BuildConfig
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import io.sentry.android.core.SentryAndroidOptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opt-in crash reports, through Sentry.
 *
 * Nothing here runs unless two things hold: the build carries a project to report to ([dsn], baked in from the
 * `SENTRY_DSN` repository secret by the release workflow — a PR or debug build has none and this class is inert), and
 * the user has turned "Send crash reports" on in Settings. The SDK's own start-up hooks are removed from the manifest,
 * so it is only ever initialised from [apply], and [apply]'s `false` closes it again.
 *
 * What a report carries: the stack trace of the crash or ANR, the app version and build, the device model and OS
 * version, and the screens that were open on the way there. What it never carries: who the user is (no id, no email,
 * no IP address — [scrub] drops the user and the server is told not to infer one), what they typed, any prompt or
 * reply, any key. Sessions, screenshots, view hierarchies, touch breadcrumbs and performance tracing are all off:
 * this is crash reporting, not analytics.
 */
class CrashReporting(
    private val context: Context,
    private val dsn: String = BuildConfig.SENTRY_DSN,
    private val proguardUuid: String = BuildConfig.SENTRY_PROGUARD_UUID,
    /** Test seams; the defaults are the SDK. */
    private val init: (Context, (SentryAndroidOptions) -> Unit) -> Unit = { c, configure -> SentryAndroid.init(c) { configure(it) } },
    private val close: () -> Unit = { Sentry.close() },
) {
    /** False in a build without a DSN: Settings shows the toggle as unavailable and [apply] never starts anything. */
    val isAvailable: Boolean get() = dsn.isNotBlank()

    /** Starts or stops reporting to match the user's choice. Idempotent; called on every change of the setting. */
    fun apply(enabled: Boolean) {
        if (enabled && isAvailable) init(context) { configure(it) } else close()
    }

    private val following = AtomicBoolean(false)

    /**
     * Keeps the SDK in step with the setting for as long as [scope] lives, from the first value read on. Only the
     * first call starts the collector — it is armed from every activity creation — and later ones return null.
     * The SDK is started and stopped off the main thread: closing it flushes what it has not sent yet, and waits.
     */
    fun follow(enabled: Flow<Boolean>, scope: CoroutineScope, dispatcher: CoroutineDispatcher = Dispatchers.IO): Job? {
        if (!following.compareAndSet(false, true)) return null
        return scope.launch { enabled.distinctUntilChanged().collect { withContext(dispatcher) { apply(it) } } }
    }

    internal fun configure(options: SentryAndroidOptions) {
        options.dsn = dsn
        options.environment = if (BuildConfig.DEBUG) "debug" else "release"
        options.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
        // The id this build's R8 mapping was uploaded under, so an obfuscated trace can be read back; see release.yml.
        options.proguardUuid = proguardUuid.ifBlank { null }

        // Crashes and ANRs, nothing about the person or the session.
        options.isSendDefaultPii = false
        options.isEnableAutoSessionTracking = false
        options.isSendClientReports = false
        options.isAttachScreenshot = false
        options.isAttachViewHierarchy = false
        options.isEnableUserInteractionBreadcrumbs = false
        options.isEnableUserInteractionTracing = false
        options.isEnableNetworkEventBreadcrumbs = false
        options.isEnableSystemEventBreadcrumbs = false
        options.tracesSampleRate = null
        options.isEnableAutoActivityLifecycleTracing = false
        options.isEnableAppStartProfiling = false
        options.isEnableNdk = false
        options.isAnrEnabled = true
        options.beforeSend = SentryOptions.BeforeSendCallback { event, _ -> scrub(event) }
    }

    /** The last word before anything leaves the device: no user, no host name, no request, whatever an integration set. */
    internal fun scrub(event: SentryEvent): SentryEvent {
        event.user = null
        event.serverName = null
        event.request = null
        return event
    }
}
