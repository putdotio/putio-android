package io.putdotio.android

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * Asks for the next page once a row within [threshold] of the list's end is laid out, as web does.
 * It fires when [canLoad] turns on near the end, so a page that lands with the end still in
 * view asks for the next one. A failed page is left to its Retry: the caller's paging is no
 * longer available, so nothing retries on its own.
 */
@Composable
internal fun LoadNextPageNearEnd(
    listState: LazyListState,
    canLoad: Boolean,
    onLoadNextPage: () -> Unit,
    threshold: Int = MOBILE_PAGING_THRESHOLD,
) {
    val currentCanLoad by rememberUpdatedState(canLoad)
    val currentOnLoadNextPage by rememberUpdatedState(onLoadNextPage)
    LaunchedEffect(listState, threshold) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index
            currentCanLoad && lastVisible != null && lastVisible >= layout.totalItemsCount - 1 - threshold
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { currentOnLoadNextPage() }
    }
}

/** Rows left below the viewport when the next page is requested; web uses the same margin. */
internal const val MOBILE_PAGING_THRESHOLD = 25
