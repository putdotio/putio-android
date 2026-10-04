package io.putdotio.android

import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.PutioFileType

internal fun row(id: Long, name: String) = FilesItem(
    id = FilesItemId(id),
    parentId = FilesFolder.Root.id,
    name = name,
    type = PutioFileType.TEXT,
    sizeBytes = 1L,
    createdAt = "2026-04-20T10:00:00Z",
)
