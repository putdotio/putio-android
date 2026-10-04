package io.putdotio.android.tv.files

import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesContent
import io.putdotio.android.PutioFailure
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderOperation
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.FilesPlaybackProgress
import io.putdotio.android.files.FilesSort
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.files.PutioFileType
import io.putdotio.android.files.filesBrowserState

internal fun ready(
    vararg items: FilesItem,
    sort: FilesSort? = null,
    paging: FilesPaging = FilesPaging.Complete,
): FilesBrowserState = state(FilesContent.Ready(items.toList(), paging), sort)

internal fun state(
    content: FilesContent,
    sort: FilesSort? = null,
    operation: FilesFolderOperation = FilesFolderOperation.Idle,
): FilesBrowserState =
    filesBrowserState(
        stack = listOf(
            FilesFolderState(
                folder = FilesFolder.Root.copy(sort = sort),
                content = content,
                operation = operation,
            ),
        ),
        nextRequestValue = 10L,
    )

internal fun item(
    id: Long,
    name: String,
    type: PutioFileType,
    sizeBytes: Long = 128L,
    playback: FilesPlaybackProgress? = null,
): FilesItem =
    FilesItem(
        id = FilesItemId(id),
        parentId = FilesFolder.Root.id,
        name = name,
        type = type,
        sizeBytes = sizeBytes,
        createdAt = "2026-04-20T10:00:00Z",
        playback = playback,
    )

internal fun networkFailure() = PutioFailure.NetworkUnavailable(PutioConfigurationException("x"))
