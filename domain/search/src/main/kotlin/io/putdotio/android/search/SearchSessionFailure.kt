package io.putdotio.android.search

import io.putdotio.android.PutioFailure

/** A 401 from a search request; a session verdict that outranks every other failure. */
public fun SearchState.authoritativeSessionFailure(): PutioFailure? =
    when (val value = content) {
        is SearchContent.Failed -> value.failure
        else -> (value.pagingOrNull() as? SearchPaging.Failed)?.failure
    }?.takeIf { it is PutioFailure.AuthenticationRequired }
