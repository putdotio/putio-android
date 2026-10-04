package io.putdotio.android.search

import io.putdotio.android.PutioFailure

/** A 401 from a search request; a session verdict that outranks every other failure. */
fun SearchState.authoritativeSessionFailure(): PutioFailure? =
    when (val value = content) {
        is SearchContent.Failed -> value.failure
        is SearchContent.Empty -> (value.paging as? SearchPaging.Failed)?.failure
        is SearchContent.Ready -> (value.paging as? SearchPaging.Failed)?.failure
        SearchContent.Idle,
        is SearchContent.Debouncing,
        is SearchContent.Loading,
        -> null
    }?.takeIf { it is PutioFailure.AuthenticationRequired }
