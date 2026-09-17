package com.cursorforandroid.domain

/** Placeholder for the baseline measurement: the legacy presentation, whole, on every call. */
class TranscriptPresenter {
    class Presented(val items: List<TimelineItem>, val rows: List<TranscriptRow>, val coordinatorMode: Boolean)

    fun present(items: List<TimelineItem>, coordinatorMode: Boolean, runActive: Boolean): Presented {
        val mode = coordinatorMode || CoordinatorTranscript.hasCoordinatorContent(items)
        val presented = GoalTranscript.lift(CoordinatorTranscript.present(items, mode))
        return Presented(presented, TranscriptRows.of(presented, mode, runActive), mode)
    }
}
