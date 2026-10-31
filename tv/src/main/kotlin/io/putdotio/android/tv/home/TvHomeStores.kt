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

/** Show Home for one account on this TV, on until turned off; sign-out clears every account's. */
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

    override fun pin(id: FilesItemId) = Unit

    override fun unpin(id: FilesItemId) = Unit

    override fun retry() = Unit

    override fun close() = Unit
}
