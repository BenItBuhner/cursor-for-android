package com.cursorforandroid.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.atomic.AtomicInteger

/**
 * A settings store for `PreferencesStore(context, store)` whose writes, from the [holdFrom]th on, wait for
 * [release]: parks a caller inside a write it makes along the way — a chat's load inside the read marker it writes
 * between rendering its run page and following that page's latest run — so what lands in between can be arranged.
 * Reads are never held. One file per instance name, so two tests in one process do not share a DataStore.
 */
class HeldSettings(context: Context, name: String = "held_settings") : DataStore<Preferences> {
    private val delegate = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        produceFile = { context.preferencesDataStoreFile(name) },
    )
    private val gate = CompletableDeferred<Unit>()
    private val writes = AtomicInteger()

    /** Writes numbered from this one (1-based) wait for [release]; 0 holds none. */
    @Volatile var holdFrom = 0

    /** Writers waiting at the gate. */
    val waiting = AtomicInteger()

    override val data: Flow<Preferences> get() = delegate.data

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        val n = writes.incrementAndGet()
        val from = holdFrom
        if (from > 0 && n >= from && !gate.isCompleted) {
            waiting.incrementAndGet()
            gate.await()
        }
        return delegate.updateData(transform)
    }

    fun release() {
        holdFrom = 0
        gate.complete(Unit)
    }
}
