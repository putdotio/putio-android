package io.putdotio.android.history

public fun historyState(
    content: HistoryContent,
    clearing: HistoryClearing = HistoryClearing.Idle,
): HistoryState = HistoryState(content, clearing)
