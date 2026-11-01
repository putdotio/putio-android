package io.putdotio.android.tv.search

import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.search.SearchFilter
import io.putdotio.android.tv.TvPlayStart
import io.putdotio.android.tv.files.TvFilesActionHandlers
import io.putdotio.android.tv.files.TvFilesDetailSources

internal fun tvSearchResults(
    onFilter: (SearchFilter) -> Unit = {},
    onPlay: (FilesItem, TvPlayStart) -> Unit = { _, _ -> },
) = TvSearchResultActions(
    onFilter = onFilter,
    onPlay = onPlay,
    itemActions = TvSearchItemActions(
        actionsOf = { emptyList() },
        handlers = TvFilesActionHandlers(
            onPlay = onPlay,
            onPickSubtitles = { _, _ -> },
            onOpenInVlc = {},
            onSetWatched = { _, _ -> },
            onSetPinned = { _, _ -> },
        ),
    ),
)

internal fun tvSearchDetail(folderNames: Map<FilesItemId, String> = emptyMap()) =
    TvSearchDetail(folderNames = folderNames, sources = TvFilesDetailSources.None, resumePlayback = true)
