package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.dto.PoolDto
import com.cursorforandroid.data.api.dto.WorkerDto
import com.cursorforandroid.data.api.dto.WorkerLabelDto
import com.cursorforandroid.data.faults.FaultServer.Fault
import com.cursorforandroid.data.faults.FaultServer.Route
import com.cursorforandroid.data.repo.CatalogRepository
import com.cursorforandroid.domain.DeviceTarget
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The device picker's listing as New chat asks for it (`CatalogRepository.loadDevices`), over the app's own client at
 * a phone's 300–900 ms round trips on HTTP/2: the three fleet calls side by side, so the rows land about one round
 * trip after the open rather than three; the next New chat inside the minute asks nothing; and a fleet that answers
 * 429 costs one open's bounded retries, however many opens meet it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NewChatDevicesTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private lateinit var rig: FaultRig
    private lateinit var catalog: CatalogRepository

    private val fleet = listOf(Route.FleetWorkers, Route.FleetPools)

    @Before
    fun setUp() {
        server = FaultServer(rttMillis = 300L..900L, http2 = true).start()
        server.fleetWorkers = listOf(
            WorkerDto(workerId = "w1", name = "studio", scope = "personal"),
            WorkerDto(workerId = "w2", name = "gpu-1", scope = "team_pool", labels = listOf(WorkerLabelDto("pool", "gpu"))),
        )
        server.fleetPools = listOf(PoolDto(name = "payments", connectedWorkerCount = 1))
        rig = FaultRig(server.baseUrl, folder.newFolder("rig"), readTimeoutMs = 10_000L, http2 = true)
        catalog = CatalogRepository(rig.session)
    }

    @After
    fun tearDown() {
        rig.close()
        server.close()
    }

    private fun fleetRequests() = server.seen.filter { it.route in fleet }

    @Test
    fun `the devices land about one round trip after New chat opens, and the next open inside the minute asks nothing`() = runBlocking<Unit> {
        val started = System.nanoTime()
        val listed = catalog.loadDevices().getOrThrow()
        val firstMs = (System.nanoTime() - started) / 1_000_000
        assertThat(listed.map { it.target }).containsExactly(DeviceTarget.machine("studio"), DeviceTarget.pool("payments"), DeviceTarget.pool("gpu")).inOrder()
        assertThat(fleetRequests()).hasSize(3)
        // Side by side: the three went out together, not each behind the last one's answer.
        val sent = fleetRequests().map { it.atMillis }
        assertThat(sent.max() - sent.min()).isLessThan(300L)

        val reopened = System.nanoTime()
        assertThat(catalog.loadDevices().getOrThrow()).isEqualTo(listed)
        val reopenMs = (System.nanoTime() - reopened) / 1_000_000
        assertThat(fleetRequests()).hasSize(3)

        rig.now += CatalogRepository.DEVICES_FRESH_MS
        catalog.loadDevices().getOrThrow()
        assertThat(fleetRequests()).hasSize(6)
        println("new-chat devices: first=$firstMs ms reopen=$reopenMs ms fleet requests=${fleetRequests().size}")
    }

    @Test
    fun `a fleet answering 429 costs one open's bounded retries however many opens meet it`() = runBlocking<Unit> {
        val refusal = Fault.Status(429, "resource_exhausted", "Too many requests", retryAfter = "1")
        fleet.forEach { server.outage(it, refusal) }

        val started = System.nanoTime()
        // A burst of New chat opens — the composer, the quick composer, reopening — while the fleet refuses.
        val answers = (1..6).map { async(Dispatchers.Default) { catalog.loadDevices() } }.awaitAll()
        repeat(4) { assertThat(catalog.loadDevices().getOrThrow()).isEmpty() }
        val burstMs = (System.nanoTime() - started) / 1_000_000
        answers.forEach { assertThat(it.getOrThrow()).isEmpty() }

        // Three calls, each tried at most three times by the client's retry, each retry after the Retry-After.
        val refused = fleetRequests()
        assertThat(refused.size).isAtMost(9)
        for ((_, byCall) in refused.groupBy { it.path }) {
            assertThat(byCall.size).isAtMost(3)
            byCall.zipWithNext { a, b -> assertThat(b.atMillis - a.atMillis).isAtLeast(1_000L) }
        }
        println("new-chat devices under 429: ${refused.size} fleet requests for 10 opens in $burstMs ms")

        // The fleet recovers; the picker's refresh lists at once.
        fleet.forEach { server.clear(it) }
        assertThat(catalog.loadDevices(force = true).getOrThrow()).hasSize(3)
        assertThat(fleetRequests().size).isEqualTo(refused.size + 3)
    }

    @Test
    fun `a fleet call that fails leaves the others' rows listed`() = runBlocking<Unit> {
        server.outage(Route.FleetPools, Fault.Status(403, "permission_denied", "Pools need a service account"))

        val listed = catalog.loadDevices().getOrThrow()

        assertThat(listed.map { it.target }).containsExactly(DeviceTarget.machine("studio"), DeviceTarget.pool("gpu")).inOrder()
    }
}
