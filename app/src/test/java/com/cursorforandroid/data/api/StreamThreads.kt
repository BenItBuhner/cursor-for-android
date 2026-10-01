package com.cursorforandroid.data.api

/**
 * The process's threads at one moment, each thread's state and stack taken together (`ThreadMXBean.dumpAllThreads`,
 * through reflection: the tests compile against the Android SDK). A thread's state read apart from its stack can
 * name one that has already left the code the stack shows.
 */
internal object StreamThreads {
    private val bean = runCatching { Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null) }.getOrNull()
    private val dump = runCatching {
        Class.forName("java.lang.management.ThreadMXBean").getMethod("dumpAllThreads", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
    }.getOrNull()
    private val info = runCatching { Class.forName("java.lang.management.ThreadInfo") }.getOrNull()
    private val name = info?.getMethod("getThreadName")
    private val state = info?.getMethod("getThreadState")
    private val stack = info?.getMethod("getStackTrace")

    /** The stream reader's classes: a thread parked in one of them is held by a stream it waits to read. */
    private val READERS = listOf("SseRunStreamer", "SseStreamReader", "SseParser", "RunStreamMux").map { "com.cursorforandroid.data.api.$it" }
    private val SERVERS = listOf("okhttp3.mockwebserver.", "com.cursorforandroid.data.faults.FaultServer", "com.cursorforandroid.ui.scale.WireStreams")

    /** Where a stream's bytes are waited for: the reader's own classes, and OkHttp's stream under it. */
    private val AWAITS_BYTES = READERS + "okhttp3.internal.http2.Http2Stream"

    /** The frames of a wait itself, above whoever called it. A lock's acquisition is not one: its caller is the lock. */
    private val WAIT_FRAMES = listOf("java.lang.Object", "jdk.internal.misc.Unsafe", "java.util.concurrent.locks.LockSupport", "java.util.concurrent.locks.AbstractQueuedSynchronizer\$ConditionObject")

    /**
     * Whether a parked thread, its stack [frames] top first, waits for a stream's bytes: its wait was called from the
     * reader. Parked with the reader lower down is something else — a class the reader touched being initialized on
     * another thread (no wait frame at all), a lock it takes on its way — and lets go without the stream's say.
     */
    internal fun awaitsStream(frames: List<StackTraceElement>): Boolean {
        if (frames.firstOrNull()?.className !in WAIT_FRAMES) return false
        val caller = frames.firstOrNull { it.className !in WAIT_FRAMES } ?: return false
        return AWAITS_BYTES.any { caller.className == it || caller.className.startsWith("$it$") } &&
            frames.any { f -> READERS.any { f.className == it || f.className.startsWith("$it$") } }
    }

    /**
     * [readers]: the stream multiplexer's own threads and every thread parked waiting for a stream's bytes (through
     * OkHttp, a stream holds one for as long as it is open). [servers]: threads serving the test's streams, its
     * server's idle pool included. [total]: every live thread; [app], those that are not the server's.
     */
    class Census(val readers: List<String>, val servers: Int, val total: Int) {
        val app: Int get() = total - servers
    }

    fun census(): Census {
        val infos = runCatching { dump?.invoke(bean, false, false) as? Array<*> }.getOrNull().orEmpty().filterNotNull()
        val readers = ArrayList<String>()
        var servers = 0
        for (i in infos) {
            val threadName = name?.invoke(i) as? String ?: continue
            val threadState = state?.invoke(i) as? Thread.State
            @Suppress("UNCHECKED_CAST")
            val frames = (stack?.invoke(i) as? Array<StackTraceElement>).orEmpty()
            val parked = threadState == Thread.State.WAITING || threadState == Thread.State.TIMED_WAITING
            if (threadName.startsWith("RunStreams") || parked && awaitsStream(frames.asList())) {
                readers += "$threadName/$threadState"
            } else if (threadName.startsWith("MockWebServer") || frames.any { f -> SERVERS.any { f.className.startsWith(it) } }) {
                servers++
            }
        }
        return Census(readers, servers, infos.size)
    }
}
