package io.putdotio.android.auth

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Default directory Coil 3 writes its disk cache to under `Context.cacheDir`. */
public const val COIL_DISK_CACHE_DIR: String = "coil3_disk_cache"

private val COIL_JOURNAL_FILES: Set<String> = setOf("journal", "journal.tmp", "journal.bkp")

/**
 * Clears the account-scoped caches shared by mobile and TV when a session ends or expires:
 * Continue Watching and SwitchedToConverted immediately, and Coil's memory/disk caches plus
 * the PDF viewer cache off-main on [ioDispatcher].
 *
 * [clearImageDiskCache] clears any live Coil disk cache and returns its directory, or null when
 * absent; leftover files under `cacheDir/coil3_disk_cache` are removed as well so cold-start
 * expiry clears caches left by an earlier process without breaking an open DiskLruCache journal.
 */
public class CommonSessionCaches(
    private val clearImageMemoryCache: () -> Unit,
    private val clearImageDiskCache: () -> File?,
    private val clearPdfCache: () -> Unit,
    private val clearContinueWatching: () -> Unit,
    private val clearSwitchedToConverted: () -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    public fun clear(
        context: Context,
        scope: CoroutineScope,
        clearAppLocalState: () -> Unit = {},
    ): Job {
        clearAppLocalState()
        clearContinueWatching()
        clearSwitchedToConverted()
        val appContext = context.applicationContext
        return scope.launch(ioDispatcher) {
            try {
                clearImageMemoryCache()
                val activeDiskCacheDir = clearImageDiskCache()
                clearLeftoverCoilDiskCache(File(appContext.cacheDir, COIL_DISK_CACHE_DIR), activeDiskCacheDir)
            } finally {
                clearPdfCache()
            }
        }
    }
}

private fun clearLeftoverCoilDiskCache(defaultDir: File, activeDiskCacheDir: File?) {
    if (!defaultDir.exists()) return
    val sameDir = activeDiskCacheDir != null && runCatching {
        activeDiskCacheDir.canonicalFile == defaultDir.canonicalFile
    }.getOrDefault(false)
    if (!sameDir) {
        defaultDir.deleteRecursively()
    } else {
        defaultDir.listFiles()
            ?.filterNot { it.name in COIL_JOURNAL_FILES }
            ?.forEach { it.deleteRecursively() }
    }
}
