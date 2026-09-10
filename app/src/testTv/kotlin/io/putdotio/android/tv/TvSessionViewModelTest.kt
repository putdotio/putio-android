package io.putdotio.android.tv

import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.android.files.StubFilesRepository
import io.putdotio.android.search.RecentSearchStoreOwner
import io.putdotio.android.search.SearchPage
import io.putdotio.android.search.SearchRepository
import io.putdotio.android.search.SearchTerm
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.android.tv.auth.TvAuthSessionId
import io.putdotio.android.tv.auth.TvAuthState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TvSessionViewModelTest {
    private val auth = MutableStateFlow<TvAuthState>(signedIn(1))
    private val stores = mutableListOf<FakeRecentSearchStore>()
    private val dependencies = TvSessionDependencies(
        filesRepository = object : StubFilesRepository() {
            override suspend fun loadFolder(folderId: FilesItemId) =
                FilesRepositoryResult.Success(FilesPage(emptyList(), null))
        },
        searchRepository = object : SearchRepository {
            override suspend fun search(term: SearchTerm) =
                FilesRepositoryResult.Success(SearchPage(emptyList(), nextCursor = null, total = 0))

            override suspend fun loadNextPage(cursor: FilesCursor) = error("No continuation expected")
        },
        recentSearchStore = { FakeRecentSearchStore().also { stores += it } },
    )

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    @Test
    fun `the same session reuses its controllers and a new session replaces them`() {
        val viewModel = TvSessionViewModel(auth)
        val first = checkNotNull(viewModel.sessionFor(42, TvAuthSessionId(1), dependencies))
        first.filesFocusMemory[0L] = 7L

        assertSame(first, viewModel.sessionFor(42, TvAuthSessionId(1), dependencies))

        auth.value = signedIn(2)
        val second = checkNotNull(viewModel.sessionFor(42, TvAuthSessionId(2), dependencies))
        assertNotSame(first, second)
        assertNull(second.filesFocusMemory[0L])
        assertFalse(first.files.dispatch(FilesBrowserEvent.Refresh))
        assertFalse(first.search.updateQuery("late"))
        assertTrue(stores.first().closed)
        assertFalse(stores.last().closed)
    }

    @Test
    fun `a session is refused when it is not the signed-in one`() {
        val viewModel = TvSessionViewModel(auth)

        assertNull(viewModel.sessionFor(42, TvAuthSessionId(9), dependencies))
        assertNull(viewModel.sessionFor(7, TvAuthSessionId(1), dependencies))
    }

    @Test
    fun `signing out closes the live session`() {
        val viewModel = TvSessionViewModel(auth)
        val session = checkNotNull(viewModel.sessionFor(42, TvAuthSessionId(1), dependencies))

        auth.value = TvAuthState.Initializing

        assertFalse(session.files.dispatch(FilesBrowserEvent.Refresh))
        assertFalse(session.search.updateQuery("late"))
        assertTrue(stores.single().closed)
        assertNull(viewModel.sessionFor(42, TvAuthSessionId(1), dependencies))
    }

    private fun signedIn(session: Long) =
        TvAuthState.SignedIn(TvAccount(userId = 42, username = "u", email = "u@example.com"), TvAuthSessionId(session))

    private class FakeRecentSearchStore : RecentSearchStoreOwner {
        override val terms = MutableStateFlow<List<SearchTerm>>(emptyList())
        override val failure = MutableStateFlow<FilesFailure?>(null)
        var closed = false

        override fun record(term: SearchTerm) {
            terms.value = listOf(term) + terms.value.filterNot { it == term }
        }

        override fun remove(term: SearchTerm) {
            terms.value = terms.value.filterNot { it == term }
        }

        override fun clear() {
            terms.value = emptyList()
        }

        override fun retry() = Unit

        override fun close() {
            closed = true
        }
    }
}
