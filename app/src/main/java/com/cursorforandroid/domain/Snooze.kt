package com.cursorforandroid.domain

/**
 * How long a chat stays off the list after a snooze. Durations are relative to the moment the user picks one;
 * [Forever] stays gone until they unsnooze it (or open it).
 */
enum class SnoozeDuration(val title: String, private val durationMillis: Long?) {
    FiveMinutes("5 minutes", 5 * 60_000L),
    FifteenMinutes("15 minutes", 15 * 60_000L),
    ThirtyMinutes("30 minutes", 30 * 60_000L),
    OneHour("1 hour", 60 * 60_000L),
    ThreeHours("3 hours", 3 * 60 * 60_000L),
    SixHours("6 hours", 6 * 60 * 60_000L),
    TwelveHours("12 hours", 12 * 60 * 60_000L),
    OneDay("1 day", 24 * 60 * 60_000L),
    Forever("Forever", null);

    /** Epoch millis the chat should reappear; [FOREVER] never does on its own. */
    fun untilMillis(nowMillis: Long): Long = durationMillis?.let { nowMillis + it } ?: FOREVER

    companion object {
        const val FOREVER = Long.MAX_VALUE

        /** Nearby times share a heading so a scan finds the right bucket first. */
        val groups: List<Pair<String, List<SnoozeDuration>>> = listOf(
            "Soon" to listOf(FiveMinutes, FifteenMinutes, ThirtyMinutes),
            "Later" to listOf(OneHour, ThreeHours, SixHours, TwelveHours),
            "Longer" to listOf(OneDay, Forever),
        )
    }
}
