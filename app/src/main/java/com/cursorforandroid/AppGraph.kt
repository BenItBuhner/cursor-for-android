package com.cursorforandroid

import android.content.Context
import com.cursorforandroid.data.api.CursorApiFactory
import com.cursorforandroid.data.api.SseRunStreamer
import com.cursorforandroid.data.demo.DemoBackendFactory
import com.cursorforandroid.data.local.AttachmentStore
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

/** Hand-rolled dependency graph. Small enough that a DI framework would only add build time. */
class AppGraph(context: Context) {
    val keyStore = SecureKeyStore(context)
    val prefs = PreferencesStore(context)
    /** Images attached to prompts, kept on-device because the transcript API never returns them. */
    val attachments = AttachmentStore(context)

    private val okHttp = CursorApiFactory.okHttp { keyStore.apiKey() }
    private val realBackend = CursorBackend(
        api = CursorApiFactory.retrofit(okHttp),
        streamer = SseRunStreamer(CursorApiFactory.sseClient(okHttp), { keyStore.apiKey() }),
        isDemo = false,
    )
    private val demoBackend = DemoBackendFactory.create().let { (api, streamer) -> CursorBackend(api, streamer, isDemo = true) }

    val session = SessionManager(keyStore, prefs, realBackend, demoBackend)
    val agents = AgentRepository(session, prefs, attachments)
    val catalog = CatalogRepository(session)
    /** One shared live stream per run, consumed by both the conversation screen and the live notification. */
    val liveRuns = LiveRunHub(session, agents)
    val conversations = ConversationRepository(session, agents, prefs, liveRuns, attachments)
    /** Presigned URLs for `/opt/cursor/artifacts/…` references in replies, and the loader that draws them. */
    val artifacts = ArtifactRepository(session)
    val media = MediaLoader(context, CursorApiFactory.mediaClient(), artifacts)
    val runMonitor = RunMonitor(
        agents = agents,
        hub = liveRuns,
        runStartedAt = { agentId, runId -> parseIsoMillis(session.current.api.getRun(agentId, runId).createdAt).takeIf { it > 0 } },
    )

    suspend fun signOut() {
        runMonitor.stop()
        liveRuns.resetAll()
        conversations.resetAll()
        catalog.reset()
        artifacts.resetAll()
        media.clearCaches()
        // The list is only reset automatically when the backend changes; signing out of one real account and into
        // another keeps the same backend and must not show the previous account's agents.
        agents.reset()
        attachments.clear()
        session.signOut()
    }
}
