package io.putdotio.android

import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.android.transfers.TransferCursor
import io.putdotio.android.transfers.TransferId
import io.putdotio.android.transfers.TransferItem
import io.putdotio.android.transfers.TransferSubmission
import io.putdotio.android.transfers.TransfersEvent
import io.putdotio.android.transfers.TransfersPage
import io.putdotio.android.transfers.TransfersRepository
import io.putdotio.android.transfers.TransfersRowRefresh
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MobileTransfersViewModelTest {
    @Test
    fun reauthenticationClosesTheOldControllerAndCreatesANewSession() {
        val authState = MutableStateFlow<MobileAuthState>(signedIn(SessionOne))
        val store = ViewModelStore()
        val viewModel = viewModel(store, authState)

        val first = checkNotNull(viewModel.controllerFor(USER_ID, SessionOne, EmptyTransfersRepository))
        shadowOf(Looper.getMainLooper()).idle()
        authState.value = signedIn(SessionTwo)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(first.dispatch(TransfersEvent.Refresh))
        val second = checkNotNull(viewModel.controllerFor(USER_ID, SessionTwo, EmptyTransfersRepository))
        assertNotSame(first, second)
        assertTrue(second.dispatch(TransfersEvent.VisibilityChanged(true)))

        store.clear()
        assertFalse(second.dispatch(TransfersEvent.Refresh))
    }

    @Test
    fun rejectsRepositoriesThatDoNotMatchTheAuthenticatedSession() {
        val authState = MutableStateFlow<MobileAuthState>(signedIn(SessionOne))
        val store = ViewModelStore()
        val viewModel = viewModel(store, authState)

        assertTrue(viewModel.controllerFor(USER_ID, SessionOne, EmptyTransfersRepository) != null)
        assertTrue(viewModel.controllerFor(USER_ID, SessionTwo, EmptyTransfersRepository) == null)
        assertTrue(viewModel.controllerFor(USER_ID + 1, SessionOne, EmptyTransfersRepository) == null)

        store.clear()
    }

    private fun viewModel(
        store: ViewModelStore,
        authState: MutableStateFlow<MobileAuthState>,
    ): MobileTransfersViewModel =
        ViewModelProvider(store, mobileTransfersViewModelFactory(authState))[MobileTransfersViewModel::class.java]

    private object EmptyTransfersRepository : TransfersRepository {
        override suspend fun load(cursor: TransferCursor?): FilesRepositoryResult<TransfersPage> =
            FilesRepositoryResult.Success(TransfersPage(emptyList(), null))

        override suspend fun refresh(ids: List<TransferId>): FilesRepositoryResult<TransfersRowRefresh> =
            error("No refresh expected")

        override suspend fun add(submission: TransferSubmission): FilesRepositoryResult<TransferItem> =
            error("No add expected")

        override suspend fun cancel(id: TransferId): FilesRepositoryResult<Unit> =
            error("No cancel expected")

        override suspend fun retry(id: TransferId): FilesRepositoryResult<TransferItem> =
            error("No retry expected")

        override suspend fun clean(ids: List<TransferId>): FilesRepositoryResult<Set<TransferId>> =
            error("No clean expected")
    }

    private companion object {
        const val USER_ID = 42L
        val Account = MobileAccount(USER_ID, "user", "user@example.com", historyEnabled = true)
        val SessionOne = MobileAuthSessionId(1L)
        val SessionTwo = MobileAuthSessionId(2L)

        fun signedIn(sessionId: MobileAuthSessionId): MobileAuthState.SignedIn =
            MobileAuthState.SignedIn(Account, sessionId)
    }
}
