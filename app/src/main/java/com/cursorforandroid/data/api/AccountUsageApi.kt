package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.domain.AccountUsage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant

/** The two DashboardService usage RPCs; an interface so the monitor can be tested against a fake. */
interface AccountUsageApi {
    /** `GetCurrentPeriodUsage`: included-model and API used-percents for the current billing cycle. */
    suspend fun currentPeriod(): PeriodUsage

    /**
     * `GetCreditGrantsBalance`: prepaid grant dollars, distinct from the monthly percentages.
     * Null when the account has no grants (or the call is unavailable).
     */
    suspend fun creditGrants(): CreditGrants?
}

/** One `GetCurrentPeriodUsage` answer, before remaining percentages are derived. */
data class PeriodUsage(
    val autoPercentUsed: Double?,
    val apiPercentUsed: Double?,
    val billingCycleEndMs: Long?,
)

/** One `GetCreditGrantsBalance` answer. */
data class CreditGrants(
    val hasCreditGrants: Boolean,
    val creditBalanceCents: Long?,
    val totalCents: Long?,
    val usedCents: Long?,
) {
    /** A balance to show: grants exist and a cents field was named. */
    val displayCents: Long? get() = creditBalanceCents.takeIf { hasCreditGrants && it != null }
}

/**
 * `aiserver.v1.DashboardService/GetCurrentPeriodUsage` and `GetCreditGrantsBalance`, the calls behind
 * cursor.com/dashboard usage: Connect JSON, session bearer, empty request messages. Stripe's prepaid
 * customer balance (`GET /api/auth/stripe`) needs the cursor.com cookie session this app does not hold,
 * so only the grants RPC can name $900-style remaining credits.
 */
class DashboardAccountUsageApi(
    private val rpc: ConnectJsonClient,
    private val tokens: SessionTokenProvider,
) : AccountUsageApi {

    override suspend fun currentPeriod(): PeriodUsage {
        val dto = rpc.unaryWithSession(
            SERVICE, "GetCurrentPeriodUsage", tokens,
            EmptyDto(), EmptyDto.serializer(), PeriodUsageDto.serializer(),
        )
        val plan = dto.planUsage
        return PeriodUsage(
            autoPercentUsed = jsonDouble(plan?.autoPercentUsed),
            apiPercentUsed = jsonDouble(plan?.apiPercentUsed),
            billingCycleEndMs = jsonInstantMs(dto.billingCycleEnd),
        )
    }

    override suspend fun creditGrants(): CreditGrants? {
        val dto = rpc.unaryWithSession(
            SERVICE, "GetCreditGrantsBalance", tokens,
            EmptyDto(), EmptyDto.serializer(), CreditGrantsDto.serializer(),
        )
        return CreditGrants(
            hasCreditGrants = dto.hasCreditGrants,
            creditBalanceCents = jsonLong(dto.creditBalanceCents),
            totalCents = jsonLong(dto.totalCents),
            usedCents = jsonLong(dto.usedCents),
        )
    }

    @Serializable
    private class EmptyDto

    @Serializable
    private data class PeriodUsageDto(
        val billingCycleStart: JsonElement? = null,
        val billingCycleEnd: JsonElement? = null,
        val planUsage: PlanUsageDto? = null,
    )

    @Serializable
    private data class PlanUsageDto(
        val autoPercentUsed: JsonElement? = null,
        val apiPercentUsed: JsonElement? = null,
        val totalPercentUsed: JsonElement? = null,
    )

    @Serializable
    private data class CreditGrantsDto(
        val hasCreditGrants: Boolean = false,
        val creditBalanceCents: JsonElement? = null,
        val totalCents: JsonElement? = null,
        val usedCents: JsonElement? = null,
    )

    companion object {
        const val SERVICE = "aiserver.v1.DashboardService"
        const val METHOD_PERIOD = "GetCurrentPeriodUsage"
        const val METHOD_GRANTS = "GetCreditGrantsBalance"
    }
}

internal fun jsonDouble(value: JsonElement?): Double? {
    val primitive = value.asPrimitive() ?: return null
    primitive.doubleOrNull?.let { return it.takeIf { n -> n.isFinite() } }
    return primitive.contentOrNull?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }
}

internal fun jsonLong(value: JsonElement?): Long? {
    val primitive = value.asPrimitive() ?: return null
    primitive.longOrNull?.let { return it }
    val text = primitive.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    text.toLongOrNull()?.let { return it }
    return text.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong()
}

/**
 * A billing-cycle instant: proto JSON int64 (millis or seconds, number or string), RFC 3339, or
 * `{seconds, nanos}`.
 */
internal fun jsonInstantMs(value: JsonElement?): Long? = when (value) {
    null, JsonNull -> null
    is JsonPrimitive -> {
        jsonLong(value)?.let { epochToMs(it) }
            ?: value.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
                runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
            }
    }
    is JsonObject -> {
        val seconds = jsonLong(value["seconds"]) ?: return null
        val nanos = jsonLong(value["nanos"]) ?: 0L
        seconds * 1000L + nanos / 1_000_000L
    }
    else -> null
}

private fun JsonElement?.asPrimitive(): JsonPrimitive? = when (this) {
    null, JsonNull -> null
    is JsonPrimitive -> this
    else -> runCatching { this.jsonPrimitive }.getOrNull()
}

/** Unix seconds if the value is too small to be millis since 2001. */
private fun epochToMs(value: Long): Long = when {
    value <= 0L -> 0L
    value < 1_000_000_000_000L -> value * 1000L
    else -> value
}

internal fun PeriodUsage.toAccountUsage(grants: CreditGrants?, fetchedAtMs: Long): AccountUsage = AccountUsage(
    includedRemainingPercent = AccountUsage.remainingPercent(autoPercentUsed),
    apiRemainingPercent = AccountUsage.remainingPercent(apiPercentUsed),
    creditBalanceCents = grants?.displayCents,
    creditTotalCents = grants?.totalCents.takeIf { grants?.hasCreditGrants == true },
    billingCycleEndMs = billingCycleEndMs?.takeIf { it > 0L },
    fetchedAtMs = fetchedAtMs,
)
