package com.cursorforandroid

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.cursorforandroid.data.api.CursorApiFactory
import com.cursorforandroid.data.api.SseRunStreamer
import com.cursorforandroid.data.demo.DemoBackendFactory
import com.cursorforandroid.data.local.AppCaches
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.McpServerStore
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.data.repo.AgentRepository
import com.cursorforandroid.data.repo.ArtifactRepository
import com.cursorforandroid.data.repo.CatalogRepository
import com.cursorforandroid.data.repo.ConversationRepository
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.data.repo.LiveRunHub
import com.cursorforandroid.data.repo.RunMonitor
import com.cursorforandroid.data.repo.SessionManager
import com.cursorforandroid.data.repo.parseIsoMillis
import java.io.File

/** Hand-rolled dependency graph. Small enough that a DI framework would only add build time. */
class AppGraph(context: Context) {
    val keyStore = SecureKeyStore(context)
    val prefs = PreferencesStore(context)
    /** Disk copies of what the API last returned; the app opens on them and revalidates in the background. */
    val caches = AppCaches(JsonDiskCache(File(context.applicationContext.cacheDir, "cursor")))
    /** MCP servers defined in the app; enabled ones are sent inline with every prompt. */
    val mcpServers = McpServerStore(keyStore)

    private val okHttp = CursorApiFactory.okHttp { keyStore.apiKey() }
    private val realBackend = CursorBackend(
        api = CursorApiFactory.retrofit(okHttp),
        streamer = SseRunStreamer(CursorApiFactory.sseClient(okHttp), { keyStore.apiKey() }),
        isDemo = false,
    )
    private val demoBackend = DemoBackendFactory.create().let { (api, streamer) -> CursorBackend(api, streamer, isDemo = true) }

    val session = SessionManager(keyStore, prefs, realBackend, demoBackend)
    val agents = AgentRepository(session, prefs, caches.agents)
    val catalog = CatalogRepository(session, caches.catalog)
    /** One shared live stream per run, consumed by both the conversation screen and the live notification. */
    val liveRuns = LiveRunHub(session, agents)
    val conversations = ConversationRepository(
        session = session,
        agents = agents,
        prefs = prefs,
        hub = liveRuns,
        cache = caches.conversations,
        isForeground = { runCatching { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }.getOrDefault(true) },
    )
    /** Presigned URLs for `/opt/cursor/artifacts/…` references in replies, and the loader that draws them. */
    val artifacts = ArtifactRepository(session)
    val media = MediaLoader(context, CursorApiFactory.mediaClient(), artifacts)
    val runMonitor = RunMonitor(
        agents = agents,
        hub = liveRuns,
        runStartedAt = { agentId, runId -> parseIsoMillis(session.current.api.getRun(agentId, runId).createdAt).takeIf { it > 0 } },
    )

    init {
        // Whether the user signs out or the key is rejected, nothing of the account stays on disk.
        session.onSignedOut = {
            runMonitor.stop()
            liveRuns.resetAll()
            conversations.resetAll()
            // Signing out of one real account and into another keeps the same backend, so the list must be
            // reset explicitly or the previous account's agents would show.
            agents.reset()
            catalog.reset()
            artifacts.resetAll()
            media.clearCaches()
            caches.clear()
        }
    }

    suspend fun signOut() = session.signOut()
}
