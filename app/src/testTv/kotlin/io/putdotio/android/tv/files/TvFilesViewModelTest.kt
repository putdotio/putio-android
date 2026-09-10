package io.putdotio.android.tv.files

import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.android.files.StubFilesRepository
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TvFilesViewModelTest {
    private val auth = MutableStateFlow<TvAuthState>(signedIn(1))
    private val repository = object : StubFilesRepository() {
        override suspend fun loadFolder(folderId: FilesItemId) =
            FilesRepositoryResult.Success(FilesPage(emptyList(), null))
    }

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    @Test
    fun `the same session reuses its controller and a new session replaces it`() {
        val viewModel = TvFilesViewModel(auth)
        val first = viewModel.controllerFor(42, TvAuthSessionId(1), repository)

        assertSame(first, viewModel.controllerFor(42, TvAuthSessionId(1), repository))

        auth.value = signedIn(2)
        val second = viewModel.controllerFor(42, TvAuthSessionId(2), repository)
        assertNotSame(first, second)
        assertFalse(checkNotNull(first).dispatch(io.putdotio.android.files.FilesBrowserEvent.Refresh))
    }

    @Test
    fun `a controller is refused for a session that is not the signed-in one`() {
        val viewModel = TvFilesViewModel(auth)

        assertNull(viewModel.controllerFor(42, TvAuthSessionId(9), repository))
        assertNull(viewModel.controllerFor(7, TvAuthSessionId(1), repository))
    }

    @Test
    fun `signing out closes the live controller`() {
        val viewModel = TvFilesViewModel(auth)
        val controller = checkNotNull(viewModel.controllerFor(42, TvAuthSessionId(1), repository))

        auth.value = TvAuthState.Initializing

        assertFalse(controller.dispatch(io.putdotio.android.files.FilesBrowserEvent.Refresh))
        assertNull(viewModel.controllerFor(42, TvAuthSessionId(1), repository))
    }

    private fun signedIn(session: Long) =
        TvAuthState.SignedIn(TvAccount(userId = 42, username = "u", email = "u@example.com"), TvAuthSessionId(session))
}
