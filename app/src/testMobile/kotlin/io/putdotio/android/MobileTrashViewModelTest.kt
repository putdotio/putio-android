package io.putdotio.android

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.android.trash.FakeTrashRepository
import io.putdotio.android.trash.TrashContent
import io.putdotio.android.trash.TrashEvent
import io.putdotio.android.trash.TrashRestoreCheck
import io.putdotio.android.trash.TrashRestoreSubmission
import io.putdotio.android.trash.confirm
import io.putdotio.android.trash.liveItem
import io.putdotio.android.trash.trashItem
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MobileTrashViewModelTest {
    @Test
    fun acknowledgedRestoreWithUnavailableReadSurvivesRecreationAndRecoversWithoutResubmitting() {
        val repository = FakeTrashRepository()
        val authState = MutableStateFlow<MobileAuthState>(signedIn(SessionOne))
        val activity = Robolectric.buildActivity(TrashHostActivity::class.java).setup()
        try {
            val beforeActivity = activity.get()
            val beforeViewModel = beforeActivity.trashViewModel(authState)
            val before = checkNotNull(beforeViewModel.controllerFor(USER_ID, SessionOne, repository))
            before.dispatch(TrashEvent.Open)
            shadowOf(Looper.getMainLooper()).idle()
            before.confirm()
            shadowOf(Looper.getMainLooper()).idle()
            val pending = before.state.value
            assertEquals(TrashRestoreSubmission.ACKNOWLEDGED, pending.restoreOutcome?.submission)
            assertEquals(TrashRestoreCheck.UNAVAILABLE, pending.restoreOutcome?.check)
            assertTrue(pending.hasPendingRestore)

            activity.recreate()

            val afterViewModel = activity.get().trashViewModel(authState)
            val after = checkNotNull(afterViewModel.controllerFor(USER_ID, SessionOne, repository))
            assertNotSame(beforeActivity, activity.get())
            assertSame(beforeViewModel, afterViewModel)
            assertSame(before, after)
            assertEquals(pending, after.state.value)
            assertFalse(after.dispatch(TrashEvent.Open))
            assertFalse(after.dispatch(TrashEvent.SelectRestore(trashItem().id)))
            assertEquals(1, repository.loadCount)

            val resolved = liveItem().copy(parentId = FilesItemId(0L), name = "collision-2.txt")
            repository.onResolve = { FilesRepositoryResult.Success(resolved) }
            assertTrue(after.dispatch(TrashEvent.CheckRestore))
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(TrashRestoreCheck.AVAILABLE, after.state.value.restoreOutcome?.check)
            assertEquals(resolved, after.state.value.restoreOutcome?.resolvedItem)
            assertEquals(resolved, after.state.value.lastRestoredItem)
            assertEquals(1L, after.state.value.restoredVersion)
            assertEquals(listOf(trashItem().id), repository.restoredIds)
            assertEquals(listOf(trashItem().id, trashItem().id), repository.resolvedIds)
        } finally {
            activity.close()
        }
    }

    @Test
    fun sameAccountReauthenticationRejectsOldCallbacksAndLateExactReadWithoutChangingNewOutcome() {
        var lateRead: Continuation<FilesRepositoryResult<FilesItem>>? = null
        val oldRepository = FakeTrashRepository().apply {
            // A noncooperative response must still be rejected after its session closes.
            onResolve = { suspendCoroutine { lateRead = it } }
        }
        val newRepository = FakeTrashRepository()
        val authState = MutableStateFlow<MobileAuthState>(signedIn(SessionOne))
        val activity = Robolectric.buildActivity(TrashHostActivity::class.java).setup()
        try {
            val viewModel = activity.get().trashViewModel(authState)
            val first = checkNotNull(viewModel.controllerFor(USER_ID, SessionOne, oldRepository))
            first.dispatch(TrashEvent.Open)
            shadowOf(Looper.getMainLooper()).idle()
            first.confirm()
            shadowOf(Looper.getMainLooper()).idle()
            val oldPending = first.state.value
            assertEquals(TrashRestoreCheck.CHECKING, oldPending.restoreOutcome?.check)
            val staleCheckCallback = { first.dispatch(TrashEvent.CheckRestore) }

            runBlocking(Dispatchers.Default) {
                authState.value = MobileAuthState.SignedOut()
                authState.value = signedIn(SessionTwo)
            }
            shadowOf(Looper.getMainLooper()).idle()

            assertFalse(staleCheckCallback())
            assertFalse(first.dispatch(TrashEvent.Refresh))
            assertNull(viewModel.controllerFor(USER_ID, SessionOne, oldRepository))
            val second = checkNotNull(viewModel.controllerFor(USER_ID, SessionTwo, newRepository))
            assertNotSame(first, second)
            assertTrue(second.dispatch(TrashEvent.Open))
            shadowOf(Looper.getMainLooper()).idle()
            second.confirm()
            shadowOf(Looper.getMainLooper()).idle()
            val newPending = second.state.value
            assertEquals(TrashRestoreCheck.UNAVAILABLE, newPending.restoreOutcome?.check)

            checkNotNull(lateRead).resume(FilesRepositoryResult.Success(liveItem().copy(name = "old-session.txt")))
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(oldPending, first.state.value)
            assertEquals(newPending, second.state.value)
            assertEquals(0L, second.state.value.restoredVersion)
            assertNull(second.state.value.lastRestoredItem)
            assertTrue((second.state.value.content as TrashContent.Loaded).items.contains(trashItem()))
            assertEquals(listOf(trashItem().id), oldRepository.restoredIds)
            assertEquals(1, oldRepository.loadCount)

            val currentResult = liveItem().copy(name = "current-session.txt")
            newRepository.onResolve = { FilesRepositoryResult.Success(currentResult) }
            assertTrue(second.dispatch(TrashEvent.CheckRestore))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(currentResult, second.state.value.lastRestoredItem)
            assertEquals(1L, second.state.value.restoredVersion)
            assertEquals(listOf(trashItem().id), newRepository.restoredIds)
        } finally {
            activity.close()
        }
    }

    private fun TrashHostActivity.trashViewModel(
        authState: MutableStateFlow<MobileAuthState>,
    ): MobileTrashViewModel =
        ViewModelProvider(this, mobileTrashViewModelFactory(authState))[MobileTrashViewModel::class.java]

    class TrashHostActivity : ComponentActivity()

    private companion object {
        const val USER_ID = 42L
        val Account = MobileAccount(USER_ID, "user", "user@example.com")
        val SessionOne = MobileAuthSessionId(1L)
        val SessionTwo = MobileAuthSessionId(2L)

        fun signedIn(sessionId: MobileAuthSessionId): MobileAuthState.SignedIn =
            MobileAuthState.SignedIn(Account, sessionId)
    }
}
