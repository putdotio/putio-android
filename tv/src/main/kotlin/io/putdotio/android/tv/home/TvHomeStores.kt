package io.putdotio.android.tv.home

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.putdotio.android.PutioFailure
import io.putdotio.android.files.ContinueWatchingEntry
import io.putdotio.android.files.ContinueWatchingStore
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.PinnedFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal interface TvShowHomeStore {
    fun read(): Boolean

    fun write(show: Boolean)

    class Memory(private var show: Boolean = true) : TvShowHomeStore {
        override fun read(): Boolean = show

        override fun write(show: Boolean) {
            this.show = show
        }
    }
}

internal class TvShowHomeSetting(private val store: TvShowHomeStore) {
    private val mutableShown = MutableStateFlow(store.read())
    val shown: StateFlow<Boolean> = mutableShown.asStateFlow()

    fun set(show: Boolean) {
        store.write(show)
        mutableShown.value = show
    }
}

internal class TvDeviceShowHomeStore(context: Context, userId: Long) : TvShowHomeStore {
    private val preferences = preferences(context)
    private val key = "show-home-$userId"

    override fun read(): Boolean = preferences.getBoolean(key, true)

    override fun write(show: Boolean) {
        preferences.edit { putBoolean(key, show) }
    }

    companion object {
        private const val PREFERENCES_NAME = "io.putdotio.android.tv.home"

        private fun preferences(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

        fun clearAll(context: Context) {
            preferences(context).edit { clear() }
        }
    }
}

internal class TvHomeReturn {
    private val mutableFile = MutableStateFlow<FilesItemId?>(null)
    val file: StateFlow<FilesItemId?> = mutableFile.asStateFlow()

    fun played(fileId: FilesItemId) {
        mutableFile.value = fileId
    }

    fun handled() {
        mutableFile.value = null
    }
}

internal class TvMemoryContinueWatchingStore : ContinueWatchingStore {
    private var entries = emptyList<ContinueWatchingEntry>()

    override fun read(): List<ContinueWatchingEntry> = entries

    override fun write(entries: List<ContinueWatchingEntry>) {
        this.entries = entries
    }
}

internal object TvNoPinnedFiles : PinnedFiles {
    override val pins: StateFlow<List<FilesItemId>?> = MutableStateFlow(emptyList())
    override val failure: StateFlow<PutioFailure?> = MutableStateFlow(null)
    override val editFailure: StateFlow<PutioFailure?> = MutableStateFlow(null)

    override fun pin(id: FilesItemId) = Unit

    override fun unpin(id: FilesItemId) = Unit

    override fun reload() = Unit

    override fun dismissEditFailure() = Unit

    override fun close() = Unit
}
