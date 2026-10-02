package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.domain.AccountUsage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** `DashboardService/GetCurrentPeriodUsage` and `GetCreditGrantsBalance` over Connect JSON. */
class AccountUsageApiTest {

    private val server = MockWebServer()
    private lateinit var api: DashboardAccountUsageApi

    @Before
    fun setUp() {
        server.start()
        val client = OkHttpClient()
        val base = server.url("/").toString()
        api = DashboardAccountUsageApi(
            ConnectJsonClient(client, base),
            SessionTokenProvider(client, apiKeyProvider = { "key_abc" }, apiUrl = base, now = { 0L }),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `reads used percents and a prepaid grant balance as remaining meters`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"accessToken":"session-1","refreshToken":"rt"}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"billingCycleEnd":"2026-11-01T00:00:00Z","planUsage":{"autoPercentUsed":17,"apiPercentUsed":0}}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"hasCreditGrants":true,"creditBalanceCents":"90000","totalCents":90000,"usedCents":0}""",
            ),
        )

        val period = api.currentPeriod()
        val grants = api.creditGrants()
        val usage = period.toAccountUsage(grants, fetchedAtMs = 1L)

        assertThat(period.autoPercentUsed).isEqualTo(17.0)
        assertThat(period.apiPercentUsed).isEqualTo(0.0)
        assertThat(period.billingCycleEndMs).isEqualTo(java.time.Instant.parse("2026-11-01T00:00:00Z").toEpochMilli())
        assertThat(grants!!.hasCreditGrants).isTrue()
        assertThat(grants.displayCents).isEqualTo(90_000L)
        assertThat(usage.includedRemainingPercent).isEqualTo(83)
        assertThat(usage.apiRemainingPercent).isEqualTo(100)
        assertThat(usage.creditBalanceCents).isEqualTo(90_000L)
        assertThat(AccountUsage.formatCredits(usage.creditBalanceCents!!)).isEqualTo("$900.00")

        server.takeRequest() // the exchange
        val periodRequest = server.takeRequest()
        assertThat(periodRequest.method).isEqualTo("POST")
        assertThat(periodRequest.path).isEqualTo("/${DashboardAccountUsageApi.SERVICE}/${DashboardAccountUsageApi.METHOD_PERIOD}")
        assertThat(periodRequest.getHeader("Authorization")).isEqualTo("Bearer session-1")
        assertThat(periodRequest.getHeader("Connect-Protocol-Version")).isEqualTo("1")
        assertThat(periodRequest.body.readUtf8()).isEqualTo("{}")
        val grantsRequest = server.takeRequest()
        assertThat(grantsRequest.path).isEqualTo("/${DashboardAccountUsageApi.SERVICE}/${DashboardAccountUsageApi.METHOD_GRANTS}")
        assertThat(grantsRequest.body.readUtf8()).isEqualTo("{}")
    }

    @Test
    fun `string percents and a proto timestamp still read`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"accessToken":"s","refreshToken":"rt"}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"billingCycleEnd":{"seconds":"1761955200","nanos":0},"planUsage":{"autoPercentUsed":"17.4","apiPercentUsed":"0"}}""",
            ),
        )

        val period = api.currentPeriod()
        assertThat(period.autoPercentUsed).isEqualTo(17.4)
        assertThat(period.apiPercentUsed).isEqualTo(0.0)
        assertThat(period.billingCycleEndMs).isEqualTo(1_761_955_200_000L)
        assertThat(AccountUsage.remainingPercent(period.autoPercentUsed)).isEqualTo(83)
    }

    @Test
    fun `no grants means no credits row even when a cents field is named`() {
        val grants = CreditGrants(hasCreditGrants = false, creditBalanceCents = 90_000L, totalCents = 90_000L, usedCents = 0L)
        assertThat(grants.displayCents).isNull()
        val usage = PeriodUsage(autoPercentUsed = 0.0, apiPercentUsed = 0.0, billingCycleEndMs = null)
            .toAccountUsage(grants, fetchedAtMs = 1L)
        assertThat(usage.creditBalanceCents).isNull()
        assertThat(usage.includedRemainingPercent).isEqualTo(100)
        assertThat(usage.apiRemainingPercent).isEqualTo(100)
    }

    @Test
    fun `json numbers and strings both parse, and junk does not`() {
        assertThat(jsonDouble(JsonPrimitive(17))).isEqualTo(17.0)
        assertThat(jsonDouble(JsonPrimitive("17.4"))).isEqualTo(17.4)
        assertThat(jsonDouble(JsonPrimitive("nope"))).isNull()
        assertThat(jsonDouble(JsonNull)).isNull()
        assertThat(jsonLong(JsonPrimitive(90_000))).isEqualTo(90_000L)
        assertThat(jsonLong(JsonPrimitive("90000"))).isEqualTo(90_000L)
        assertThat(jsonLong(JsonPrimitive("90000.9"))).isEqualTo(90_000L)
        assertThat(jsonInstantMs(JsonPrimitive("2026-11-01T00:00:00Z")))
            .isEqualTo(java.time.Instant.parse("2026-11-01T00:00:00Z").toEpochMilli())
        assertThat(jsonInstantMs(JsonPrimitive(1_761_955_200_000L))).isEqualTo(1_761_955_200_000L)
        assertThat(jsonInstantMs(JsonPrimitive(1_761_955_200L))).isEqualTo(1_761_955_200_000L)
        assertThat(jsonInstantMs(buildJsonObject { put("seconds", 1_761_955_200L) }))
            .isEqualTo(1_761_955_200_000L)
        assertThat(jsonInstantMs(JsonNull)).isNull()
    }

    @Test
    fun `remaining percent rounds from used and clamps`() {
        assertThat(AccountUsage.remainingPercent(null)).isNull()
        assertThat(AccountUsage.remainingPercent(Double.NaN)).isNull()
        assertThat(AccountUsage.remainingPercent(0.0)).isEqualTo(100)
        assertThat(AccountUsage.remainingPercent(17.4)).isEqualTo(83)
        assertThat(AccountUsage.remainingPercent(100.0)).isEqualTo(0)
        assertThat(AccountUsage.remainingPercent(140.0)).isEqualTo(0)
        assertThat(AccountUsage.remainingPercent(-5.0)).isEqualTo(100)
    }
}
