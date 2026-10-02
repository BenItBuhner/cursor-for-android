package com.cursorforandroid.domain

import kotlinx.serialization.Serializable
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlin.math.roundToInt

/**
 * One account's usage snapshot as Settings shows it: remaining percentages for Cursor's included models
 * (Composer, Grok) and for API / other models, plus prepaid grant credits when the account has them.
 *
 * Percentages come from `DashboardService/GetCurrentPeriodUsage` (`planUsage.autoPercentUsed` /
 * `apiPercentUsed`). Credits come from `GetCreditGrantsBalance` (`creditBalanceCents`) and are not the
 * monthly included-usage remaining — those are distinct RPCs.
 */
@Serializable
data class AccountUsage(
    /** Remaining included-model allowance, 0–100. Null when the period call did not name `autoPercentUsed`. */
    val includedRemainingPercent: Int?,
    /** Remaining API / other-models allowance, 0–100. Null when the period call did not name `apiPercentUsed`. */
    val apiRemainingPercent: Int?,
    /** Prepaid grant balance in USD cents. Null when the account has no credit grants. */
    val creditBalanceCents: Long?,
    /** Original grant total in USD cents, when the grants call named one. */
    val creditTotalCents: Long? = null,
    /** When the current billing cycle ends, epoch millis. Null when the period call did not name it. */
    val billingCycleEndMs: Long? = null,
    /** When this snapshot was fetched, epoch millis. */
    val fetchedAtMs: Long = 0L,
) {
    val hasMeters: Boolean
        get() = includedRemainingPercent != null || apiRemainingPercent != null || creditBalanceCents != null

    companion object {
        /**
         * What the demo Settings page shows: included models part-way through the cycle, API unused, and a
         * $900 prepaid grant like the dashboard's Credits card.
         */
        val SAMPLE: AccountUsage = AccountUsage(
            includedRemainingPercent = 83,
            apiRemainingPercent = 100,
            creditBalanceCents = 90_000L,
            creditTotalCents = 90_000L,
            billingCycleEndMs = null,
            fetchedAtMs = 0L,
        )

        /**
         * Remaining allowance from Cursor's used-percent field (`autoPercentUsed` / `apiPercentUsed`),
         * rounded to a whole percent and clamped to 0–100.
         */
        fun remainingPercent(used: Double?): Int? {
            if (used == null || !used.isFinite()) return null
            return (100.0 - used).roundToInt().coerceIn(0, 100)
        }

        /** "$900.00" — USD, two decimals, as the dashboard prints a credit balance. */
        fun formatCredits(cents: Long): String {
            val format = NumberFormat.getCurrencyInstance(Locale.US)
            format.currency = Currency.getInstance("USD")
            format.minimumFractionDigits = 2
            format.maximumFractionDigits = 2
            return format.format(cents / 100.0)
        }
    }
}

/** Which remaining-percent meters just returned to 100%. */
data class UsageReset(
    val included: Boolean,
    val api: Boolean,
) {
    val any: Boolean get() = included || api
}
