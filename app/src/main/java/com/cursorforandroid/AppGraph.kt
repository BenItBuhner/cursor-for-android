package com.cursorforandroid

import android.content.Context
import com.cursorforandroid.data.api.CursorApiFactory
import com.cursorforandroid.data.api.SseRunStreamer
import com.cursorforandroid.data.demo.DemoBackendFactory
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.AgentRepository
import com.cursorforandroid.data.repo.CatalogRepository
import com.cursorforandroid.data.repo.ConversationRepository
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.data.repo.SessionManager

/** Hand-rolled dependency graph. Small enough that a DI framework would only add build time. */
class AppGraph(context: Context) {
    val keyStore = SecureKeyStore(context)
    val prefs = PreferencesStore(context)

    private val okHttp = CursorApiFactory.okHttp { keyStore.apiKey() }
    private val realBackend = CursorBackend(
        api = CursorApiFactory.retrofit(okHttp),
        streamer = SseRunStreamer(CursorApiFactory.sseClient(okHttp), { keyStore.apiKey() }),
        isDemo = false,
    )
    private val demoBackend = DemoBackendFactory.create().let { (api, streamer) -> CursorBackend(api, streamer, isDemo = true) }

    val session = SessionManager(keyStore, prefs, realBackend, demoBackend)
    val agents = AgentRepository(session, prefs)
    val catalog = CatalogRepository(session)
    val conversations = ConversationRepository(session, agents, prefs)

    suspend fun signOut() {
        conversations.resetAll()
        catalog.reset()
        session.signOut()
    }
}
