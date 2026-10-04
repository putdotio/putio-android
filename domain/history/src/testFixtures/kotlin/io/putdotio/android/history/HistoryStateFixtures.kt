package io.putdotio.android.history

/** A History state as the reducer would hold it, for tests outside this module. */
fun historyState(
    content: HistoryContent,
    clearing: HistoryClearing = HistoryClearing.Idle,
): HistoryState = HistoryState(content, clearing)
