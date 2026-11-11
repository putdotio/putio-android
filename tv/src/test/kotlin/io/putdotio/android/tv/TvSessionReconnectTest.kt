package io.putdotio.android.tv

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesItemResolver
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.FilesStreamUrls
import io.putdotio.android.files.FilesWatchedRepository
import io.putdotio.android.files.StubFilesRepository
import io.putdotio.android.history.HistoryEventId
import io.putdotio.android.history.HistoryPage
import io.putdotio.android.history.HistoryRepository
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.search.RecentSearchStoreOwner
import io.putdotio.android.search.SearchPage
import io.putdotio.android.search.SearchRepository
import io.putdotio.android.search.SearchTerm
import io.putdotio.android.settings.AccountSettingsChange
import io.putdotio.android.settings.AccountSettingsFailure
import io.putdotio.android.settings.AccountSettingsRepository
import io.putdotio.android.settings.AccountSettingsRepositoryResult
import io.putdotio.android.settings.AndroidAppConfigChange
import io.putdotio.android.settings.AndroidAppConfigRepository
import io.putdotio.android.trash.TrashBulkSelection
import io.putdotio.android.trash.TrashEvent
import io.putdotio.android.trash.TrashPage
import io.putdotio.android.trash.TrashRepository
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TvSessionReconnectTest {
    private val offline = PutioFailure.NetworkUnavailable(IOException("offline"))
    private val loads = mutableMapOf("files" to 0, "search" to 0, "history" to 0, "trash" to 0)

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    @Test
    fun `each return of the network retries every failed load once`() = runTest {
        val eager = UnconfinedTestDispatcher(testScheduler)
        val session = TvSession(offlineDependencies(), historyEnabled = true, { true }, backgroundScope + eager)
        session.search.updateQuery("clip")
        session.search.submit()
        session.trash.dispatch(TrashEvent.Open)
        val online = MutableStateFlow(true)
        backgroundScope.launch(eager) { session.retryFailedLoadsOnReturn(online) }
        advanceUntilIdle()
        val failed = loads.toMap()
        assertTrue(failed.values.all { it > 0 })

        repeat(2) { returns ->
            online.value = false
            advanceUntilIdle()
            online.value = true
            advanceUntilIdle()

            assertEquals(failed.mapValues { it.value + returns + 1 }, loads.toMap())
        }
    }

    private fun <T> failedLoad(name: String): PutioResult<T> {
        loads.merge(name, 1, Int::plus)
        return PutioResult.Failure(offline)
    }

    private fun offlineDependencies() = TvSessionDependencies(
        filesRepository = object : StubFilesRepository() {
            override suspend fun loadFolder(folderId: FilesItemId): PutioResult<FilesPage> = failedLoad("files")
        },
        searchRepository = object : SearchRepository {
            override suspend fun search(term: SearchTerm): PutioResult<SearchPage> = failedLoad("search")

            override suspend fun loadNextPage(cursor: FilesCursor) = error("No continuation expected")
        },
        historyRepository = object : HistoryRepository {
            override suspend fun load(before: HistoryEventId?): PutioResult<HistoryPage> = failedLoad("history")

            override suspend fun clear() = error("No clear expected")
        },
        trashRepository = OfflineTrash(),
        settingsRepository = OfflineSettings(),
        appConfigRepository = object : AndroidAppConfigRepository {
            override suspend fun load() = PutioResult.Failure(offline)

            override suspend fun save(change: AndroidAppConfigChange) = PutioResult.Failure(offline)
        },
        watchedRepository = object : FilesWatchedRepository {
            override suspend fun setPosition(itemId: FilesItemId, seconds: Double) = PutioResult.Failure(offline)

            override suspend fun clearPosition(itemId: FilesItemId) = PutioResult.Failure(offline)
        },
        streamUrls = FilesStreamUrls { error("No stream expected") },
        filesItemResolver = object : FilesItemResolver {
            override suspend fun resolveItem(itemId: FilesItemId) = PutioResult.Failure(offline)
        },
        recentSearchStore = { NoRecentSearches() },
        playbackRepository = { NoPlayback },
        writePlaybackPosition = { _, _ -> error("No playback expected") },
    )

    private inner class OfflineTrash : TrashRepository {
        override suspend fun load(): PutioResult<TrashPage> = failedLoad("trash")
        override suspend fun loadNextPage(cursor: FilesCursor) = error("No continuation expected")
        override suspend fun restore(itemId: FilesItemId) = error("No restore expected")
        override suspend fun resolveItem(itemId: FilesItemId) = error("No check expected")
        override suspend fun deleteItem(itemId: FilesItemId) = error("No delete expected")
        override suspend fun restoreAll(selection: TrashBulkSelection) = error("No restore expected")
        override suspend fun empty() = error("No empty expected")
    }

    private inner class OfflineSettings : AccountSettingsRepository {
        private val failure = AccountSettingsRepositoryResult.Failure(AccountSettingsFailure.Putio(offline))

        override suspend fun load() = failure

        override suspend fun save(change: AccountSettingsChange) = failure

        override suspend fun loadTunnelRoutes() = failure
    }

    private class NoRecentSearches : RecentSearchStoreOwner {
        override val terms = MutableStateFlow<List<SearchTerm>>(emptyList())
        override val enabled = MutableStateFlow<Boolean?>(false)
        override val failure = MutableStateFlow<PutioFailure?>(null)

        override fun record(term: SearchTerm) = Unit

        override fun remove(term: SearchTerm) = Unit

        override fun clear() = Unit

        override fun setEnabled(enabled: Boolean) = Unit

        override fun retry() = Unit

        override fun close() = Unit
    }

    private object NoPlayback : PlaybackRepository {
        override suspend fun resolve(target: PlaybackTarget, convertedOnly: Boolean) = error("No playback expected")

        override suspend fun findNextVideo(target: PlaybackTarget) = error("No playback expected")
    }
}
