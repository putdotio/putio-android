package io.putdotio.android.search

import io.putdotio.android.PutioFailure

public fun SearchState.authoritativeSessionFailure(): PutioFailure? =
    when (val value = content) {
        is SearchContent.Failed -> value.failure
        else -> (value.pagingOrNull() as? SearchPaging.Failed)?.failure
    }?.takeIf { it is PutioFailure.AuthenticationRequired }
