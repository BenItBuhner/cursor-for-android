package com.cursorforandroid.domain

/**
 * How long a chat stays off the list after a snooze. Durations are relative to the moment the user picks one;
 * [Forever] stays gone until they unsnooze it (or open it).
 */
enum class SnoozeDuration(val label: String, val title: String, private val durationMillis: Long?) {
    FiveMinutes("5m", "5 minutes", 5 * 60_000L),
    FifteenMinutes("15m", "15 minutes", 15 * 60_000L),
    ThirtyMinutes("30m", "30 minutes", 30 * 60_000L),
    OneHour("1h", "1 hour", 60 * 60_000L),
    ThreeHours("3h", "3 hours", 3 * 60 * 60_000L),
    SixHours("6h", "6 hours", 6 * 60 * 60_000L),
    TwelveHours("12h", "12 hours", 12 * 60 * 60_000L),
    OneDay("1d", "1 day", 24 * 60 * 60_000L),
    Forever("Forever", "Forever", null);

    /** Epoch millis the chat should reappear; [FOREVER] never does on its own. */
    fun untilMillis(nowMillis: Long): Long = durationMillis?.let { nowMillis + it } ?: FOREVER

    companion object {
        const val FOREVER = Long.MAX_VALUE

        /** Three short, three mid, three long — the chip pad still lays these out as a 3×3. */
        val rows: List<List<SnoozeDuration>> = listOf(
            listOf(FiveMinutes, FifteenMinutes, ThirtyMinutes),
            listOf(OneHour, ThreeHours, SixHours),
            listOf(TwelveHours, OneDay, Forever),
        )

        /** The list picker groups nearby times so a scan finds the right bucket first. */
        val groups: List<Pair<String, List<SnoozeDuration>>> = listOf(
            "Soon" to listOf(FiveMinutes, FifteenMinutes, ThirtyMinutes),
            "Later" to listOf(OneHour, ThreeHours, SixHours, TwelveHours),
            "Longer" to listOf(OneDay, Forever),
        )
    }
}
