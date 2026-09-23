package com.cursorforandroid.data.api

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * The system's resolver, each host's last good answer kept for [keepMs]: a lookup the resolver fails on a phone that
 * has a network is answered with the addresses the host had a moment ago rather than failed — which the app used to
 * say as "You're offline" with Wi-Fi and cellular both up, and which lasts, since Android keeps a failed lookup for
 * two seconds and a resolver that did not answer once seldom answers the next try. HTTP/2 puts every call to a host
 * on one connection, so a lookup is made only when that connection is replaced (a network handoff, the server
 * closing it); and the address is only where to connect — TLS still proves the host, so an address the host has
 * since given up fails to connect, never reaches another server. Offline, by the phone's own word, the failure stands.
 */
class LastGoodDns(
    private val system: Dns = Dns.SYSTEM,
    private val keepMs: Long = KEEP_MS,
    private val now: () -> Long = System::currentTimeMillis,
) : Dns {
    private class Answer(val addresses: List<InetAddress>, val atMillis: Long)

    private val known = ConcurrentHashMap<String, Answer>()

    /** How many lookups were answered from what was kept: for the diagnostics. */
    @Volatile var answeredFromKept = 0
        private set

    override fun lookup(hostname: String): List<InetAddress> = try {
        system.lookup(hostname).also { if (it.isNotEmpty()) known[hostname] = Answer(it, now()) }
    } catch (e: UnknownHostException) {
        val kept = known[hostname]?.takeIf { now() - it.atMillis <= keepMs }
        if (kept == null || DeviceNetwork.isOnline() == false) throw e
        answeredFromKept++
        kept.addresses
    }

    companion object {
        /** How long a host's addresses stand in for a failed lookup: an hour, well inside how long Cursor's hosts keep theirs. */
        const val KEEP_MS = 60 * 60_000L

        /** One for Cursor's hosts, shared by every client that talks to them, so whichever looked a host up last answers for the rest. */
        val CURSOR = LastGoodDns()
    }
}
