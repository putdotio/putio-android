package io.putdotio.android

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

/**
 * Asks for [nextPage] once a row within [threshold] of the list's end is laid out, as web does.
 * [nextPage] identifies the page that may load now (its cursor), or is null when none may. It
 * fires once per page, so a page that lands with the end still in view asks for the one after it
 * even if its loading state was never drawn. A failed page is left to its Retry: the caller has
 * no next page to offer, so nothing retries on its own.
 */
@Composable
internal fun LoadNextPageNearEnd(
    listState: LazyListState,
    nextPage: Any?,
    onLoadNextPage: () -> Unit,
    threshold: Int = MOBILE_PAGING_THRESHOLD,
) {
    val currentNextPage by rememberUpdatedState(nextPage)
    val currentOnLoadNextPage by rememberUpdatedState(onLoadNextPage)
    LaunchedEffect(listState, threshold) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index
            currentNextPage?.takeIf { lastVisible != null && lastVisible >= layout.totalItemsCount - 1 - threshold }
        }
            .distinctUntilChanged()
            .filterNotNull()
            .collect { currentOnLoadNextPage() }
    }
}

/** An empty page that still has a next one asks for it at once: there is no list to scroll. */
@Composable
internal fun LoadNextPageNow(nextPage: Any?, onLoadNextPage: () -> Unit) {
    val currentOnLoadNextPage by rememberUpdatedState(onLoadNextPage)
    LaunchedEffect(nextPage) {
        if (nextPage != null) currentOnLoadNextPage()
    }
}

/** Rows left below the viewport when the next page is requested; web uses the same margin. */
internal const val MOBILE_PAGING_THRESHOLD = 25
