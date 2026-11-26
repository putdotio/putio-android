package io.putdotio.android.auth

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

public const val COIL_DISK_CACHE_DIR: String = "coil3_disk_cache"

private val COIL_JOURNAL_FILES: Set<String> = setOf("journal", "journal.tmp", "journal.bkp")

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
