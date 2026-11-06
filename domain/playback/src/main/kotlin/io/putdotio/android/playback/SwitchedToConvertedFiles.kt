package io.putdotio.android.playback

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

public interface SwitchedToConvertedFiles {
    public suspend fun contains(fileId: Long): Boolean

    public fun add(fileId: Long)

    public object None : SwitchedToConvertedFiles {
        override suspend fun contains(fileId: Long): Boolean = false

        override fun add(fileId: Long): Unit = Unit
    }
}

internal fun List<Long>.withSwitched(fileId: Long, limit: Int = SWITCHED_TO_CONVERTED_LIMIT): List<Long> =
    (filterNot { it == fileId } + fileId).takeLast(limit)

public class DataStoreSwitchedToConvertedFiles internal constructor(
    private val store: DataStore<Preferences>,
    private val scope: CoroutineScope,
    private val limit: Int = SWITCHED_TO_CONVERTED_LIMIT,
) : SwitchedToConvertedFiles {
    override suspend fun contains(fileId: Long): Boolean = fileId in read()

    override fun add(fileId: Long) {
        write { it[FILE_IDS] = it.fileIds().withSwitched(fileId, limit).joinToString(SEPARATOR) }
    }

    public fun clear() {
        write { it.clear() }
    }

    internal suspend fun read(): List<Long> =
        store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.first().fileIds()

    private val writing = Mutex()

    // A lost write only opens the original next time; the switch it records has already played.
    @Suppress("SwallowedException")
    private fun write(change: (MutablePreferences) -> Unit): Job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            writing.withLock {
                try {
                    store.edit(change)
                } catch (_: IOException) {
                }
            }
        }

    public companion object {
        @Volatile
        private var shared: DataStoreSwitchedToConvertedFiles? = null

        public fun get(context: Context): DataStoreSwitchedToConvertedFiles =
            shared ?: synchronized(this) {
                shared ?: DataStoreSwitchedToConvertedFiles(
                    context.applicationContext.switchedToConverted,
                    CoroutineScope(SupervisorJob() + Dispatchers.IO),
                ).also { shared = it }
            }
    }
}

private val Context.switchedToConverted: DataStore<Preferences> by preferencesDataStore(
    name = "io.putdotio.android.playback.switched-to-converted",
)

private fun Preferences.fileIds(): List<Long> =
    this[FILE_IDS]?.split(SEPARATOR)?.mapNotNull { id -> id.toLongOrNull()?.takeIf { it > 0L } }.orEmpty()

private val FILE_IDS = stringPreferencesKey("file_ids")
private const val SEPARATOR = ","
internal const val SWITCHED_TO_CONVERTED_LIMIT = 200
