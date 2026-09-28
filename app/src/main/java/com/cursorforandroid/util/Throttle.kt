package com.cursorforandroid.util

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * At most one value per [periodMs]: the first at once, then the latest of each window when it closes. Nothing is
 * lost at the end — the last value upstream is always emitted, after at most one period.
 */
fun <T> Flow<T>.throttleLatest(periodMs: Long): Flow<T> = channelFlow {
    val latest = Channel<T>(Channel.CONFLATED)
    launch {
        collect { latest.send(it) }
        latest.close()
    }
    for (value in latest) {
        send(value)
        delay(periodMs)
    }
}.buffer(Channel.RENDEZVOUS)
