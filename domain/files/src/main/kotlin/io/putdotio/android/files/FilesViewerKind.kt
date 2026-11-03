package io.putdotio.android.files

import io.putdotio.sdk.files.PutioFileType

/** The kinds the apps show themselves; every other file the players don't take goes elsewhere. */
public enum class FilesViewerKind { Image, Pdf, Text }

public val FilesItem.viewerKind: FilesViewerKind?
    get() = when {
        isFolder || id.value <= 0L -> null
        type == PutioFileType.IMAGE -> FilesViewerKind.Image
        type == PutioFileType.PDF -> FilesViewerKind.Pdf
        type == PutioFileType.TEXT -> FilesViewerKind.Text
        else -> null
    }
