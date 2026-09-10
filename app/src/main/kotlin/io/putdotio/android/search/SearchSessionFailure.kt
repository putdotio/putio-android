package io.putdotio.android.search

import io.putdotio.android.files.FilesFailure

/** A 401 from a search request; a session verdict that outranks every other failure. */
internal fun SearchState.authoritativeSessionFailure(): FilesFailure? =
    when (val value = content) {
        is SearchContent.Failed -> value.failure
        is SearchContent.Empty -> (value.paging as? SearchPaging.Failed)?.failure
        is SearchContent.Ready -> (value.paging as? SearchPaging.Failed)?.failure
        SearchContent.Idle,
        is SearchContent.Debouncing,
        is SearchContent.Loading,
        -> null
    }?.takeIf { it is FilesFailure.AuthenticationRequired }
