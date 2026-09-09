package io.putdotio.android.downloads

import io.putdotio.android.files.FilesItemId

/**
 * The platform downloader (Media3 on mobile). It owns transfer, persistence of
 * progress, and the foreground notification; the controller only issues intents
 * and mirrors reported status into [DownloadStore].
 */
interface DownloadEngine {
    /** Starts or resumes; a retry after failure is the same call. */
    fun start(entry: DownloadEntry)

    /** Removes cached bytes and any in-flight transfer; the index row goes when the engine confirms. */
    fun remove(fileId: FilesItemId)

    /** True while a removal is still in progress for this file. */
    fun isRemoving(fileId: FilesItemId): Boolean = false
}
