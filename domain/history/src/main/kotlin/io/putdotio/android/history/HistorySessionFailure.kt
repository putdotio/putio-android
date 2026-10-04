package io.putdotio.android.history

import io.putdotio.android.PutioFailure

/** A 401 from any history request; a session verdict that outranks every other failure. */
public fun HistoryState.authoritativeSessionFailure(): PutioFailure? =
    listOfNotNull(
        authoritativeFailure,
        when (val value = content) {
            is HistoryContent.Failed -> value.failure
            is HistoryContent.Ready -> (value.paging as? HistoryPaging.Failed)?.failure
            HistoryContent.Disabled,
            HistoryContent.Empty,
            is HistoryContent.Loading,
            -> null
        },
        (clearing as? HistoryClearing.Failed)?.failure,
    ).firstOrNull { it is PutioFailure.AuthenticationRequired }
