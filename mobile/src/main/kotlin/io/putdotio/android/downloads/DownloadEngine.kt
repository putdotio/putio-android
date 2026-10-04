package io.putdotio.android.downloads

import io.putdotio.android.files.FilesItemId

/**
 * The platform downloader (Media3 on mobile). It owns transfer, persistence of
 * progress, and the foreground notification; the controller only issues intents
 * and mirrors reported status into [DownloadStore].
 */
internal interface DownloadEngine {
    /** Starts or resumes; a retry after failure or a missing copy is the same call. */
    fun start(entry: DownloadEntry)

    /** Removes cached bytes and any in-flight transfer; the index row goes when the engine confirms. */
    fun remove(fileId: FilesItemId)

    /** True while a removal is still in progress for this file. */
    fun isRemoving(fileId: FilesItemId): Boolean = false

    /** Mirrors live byte progress into the store; the controller calls it while the Downloads screen is visible. */
    fun refreshProgress() = Unit

    /** How many transfers run at once, one of [DOWNLOAD_CONCURRENCY_CHOICES]. */
    val concurrency: Int get() = DOWNLOAD_CONCURRENCY_DEFAULT

    fun setConcurrency(limit: Int) = Unit
}
