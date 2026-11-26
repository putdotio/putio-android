package io.putdotio.android

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

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

@Composable
internal fun LoadNextPageNow(nextPage: Any?, onLoadNextPage: () -> Unit) {
    val currentOnLoadNextPage by rememberUpdatedState(onLoadNextPage)
    LaunchedEffect(nextPage) {
        if (nextPage != null) currentOnLoadNextPage()
    }
}

internal const val MOBILE_PAGING_THRESHOLD = 25
