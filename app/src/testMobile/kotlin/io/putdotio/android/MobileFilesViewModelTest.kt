package io.putdotio.android

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.StubFilesRepository
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.android.files.FilesViewportPosition
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MobileFilesViewModelTest {

    @Test
    fun nestedFolderAndViewportSurviveActivityRecreation() {
        val repository = NestedFolderRepository()
        val authState = MutableStateFlow<MobileAuthState>(signedIn(SessionOne))
        val activityController = Robolectric.buildActivity(FilesHostActivity::class.java).setup()

        try {
            val beforeActivity = activityController.get()
            val beforeViewModel = beforeActivity.filesViewModel(authState)
            val beforeController = checkNotNull(beforeViewModel.controllerFor(USER_ID, SessionOne, repository))
            shadowOf(Looper.getMainLooper()).idle()

            beforeController.dispatch(FilesBrowserEvent.OpenFolder(Folder.id))
            shadowOf(Looper.getMainLooper()).idle()
            beforeController.dispatch(FilesBrowserEvent.ViewportChanged(Viewport))

            activityController.recreate()

            val afterActivity = activityController.get()
            val afterViewModel = afterActivity.filesViewModel(authState)
            val afterController = checkNotNull(afterViewModel.controllerFor(USER_ID, SessionOne, repository))
            val content = afterController.state.value.current.content as FilesContent.Ready
            assertNotSame(beforeActivity, afterActivity)
            assertSame(beforeViewModel, afterViewModel)
            assertSame(beforeController, afterController)
            assertEquals(
                listOf(FilesFolder.Root, FilesFolder(Folder.id, Folder.name)),
                afterController.state.value.path,
            )
            assertEquals(Viewport, content.viewport)
            assertEquals(listOf(FilesFolder.Root.id, Folder.id), repository.loadedFolderIds)
        } finally {
            activityController.close()
        }
    }

    @Test
    fun conflatedSameUserReauthenticationClosesThenReplacesTheController() {
        val repository = NestedFolderRepository()
        val authState = MutableStateFlow<MobileAuthState>(signedIn(SessionOne))
        val activityController = Robolectric.buildActivity(FilesHostActivity::class.java).setup()

        try {
            val activity = activityController.get()
            val viewModel = activity.filesViewModel(authState)
            val first = checkNotNull(viewModel.controllerFor(USER_ID, SessionOne, repository))
            shadowOf(Looper.getMainLooper()).idle()
            runBlocking(Dispatchers.Default) {
                authState.value = MobileAuthState.SignedOut()
                authState.value = signedIn(SessionTwo)
            }
            shadowOf(Looper.getMainLooper()).idle()

            val loadsBeforeReplacement = repository.loadedFolderIds.toList()
            assertEquals(listOf(FilesFolder.Root.id), loadsBeforeReplacement)
            assertFalse(first.dispatch(FilesBrowserEvent.OpenFolder(Folder.id)))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(loadsBeforeReplacement, repository.loadedFolderIds)

            val second = checkNotNull(viewModel.controllerFor(USER_ID, SessionTwo, repository))
            shadowOf(Looper.getMainLooper()).idle()

            assertNotSame(first, second)
            assertEquals(
                listOf(FilesFolder.Root.id, FilesFolder.Root.id),
                repository.loadedFolderIds,
            )
        } finally {
            activityController.close()
        }
    }

    @Test
    fun signedOutTransitionClosesTheActiveController() {
        val repository = NestedFolderRepository()
        val authState = MutableStateFlow<MobileAuthState>(signedIn(SessionOne))
        val activityController = Robolectric.buildActivity(FilesHostActivity::class.java).setup()

        try {
            val controller = checkNotNull(
                activityController
                    .get()
                    .filesViewModel(authState)
                    .controllerFor(USER_ID, SessionOne, repository),
            )
            shadowOf(Looper.getMainLooper()).idle()

            authState.value = MobileAuthState.SignedOut()
            shadowOf(Looper.getMainLooper()).idle()

            val loadsAfterSignOut = repository.loadedFolderIds.toList()
            assertFalse(controller.dispatch(FilesBrowserEvent.OpenFolder(Folder.id)))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(loadsAfterSignOut, repository.loadedFolderIds)
        } finally {
            activityController.close()
        }
    }

    @Test
    fun staleSessionCannotRecreateAControllerAfterSignOutOrNewSession() {
        val repository = NestedFolderRepository()
        val authState = MutableStateFlow<MobileAuthState>(signedIn(SessionOne))
        val activityController = Robolectric.buildActivity(FilesHostActivity::class.java).setup()

        try {
            val viewModel = activityController.get().filesViewModel(authState)
            checkNotNull(viewModel.controllerFor(USER_ID, SessionOne, repository))
            shadowOf(Looper.getMainLooper()).idle()

            authState.value = MobileAuthState.SignedOut()
            shadowOf(Looper.getMainLooper()).idle()
            val loadsAfterSignOut = repository.loadedFolderIds.toList()
            assertNull(viewModel.controllerFor(USER_ID, SessionOne, repository))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(loadsAfterSignOut, repository.loadedFolderIds)

            authState.value = signedIn(SessionTwo)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(viewModel.controllerFor(USER_ID, SessionOne, repository))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(loadsAfterSignOut, repository.loadedFolderIds)
        } finally {
            activityController.close()
        }
    }

    @Test
    fun permanentActivityDestructionClosesTheActiveController() {
        val repository = NestedFolderRepository()
        val authState = MutableStateFlow<MobileAuthState>(signedIn(SessionOne))
        val activityController = Robolectric.buildActivity(FilesHostActivity::class.java).setup()
        val controller = checkNotNull(
            activityController
                .get()
                .filesViewModel(authState)
                .controllerFor(USER_ID, SessionOne, repository),
        )
        shadowOf(Looper.getMainLooper()).idle()
        val loadsBeforeDestruction = repository.loadedFolderIds.toList()

        activityController.close()

        assertFalse(controller.dispatch(FilesBrowserEvent.OpenFolder(Folder.id)))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(loadsBeforeDestruction, repository.loadedFolderIds)
    }

    private fun FilesHostActivity.filesViewModel(
        authState: MutableStateFlow<MobileAuthState>,
    ): MobileFilesViewModel =
        ViewModelProvider(
            this,
            mobileFilesViewModelFactory(authState),
        )[MobileFilesViewModel::class.java]

    class FilesHostActivity : ComponentActivity()

    private class NestedFolderRepository : StubFilesRepository() {
        val loadedFolderIds = mutableListOf<FilesItemId>()

        override suspend fun loadFolder(folderId: FilesItemId): FilesRepositoryResult<FilesPage> {
            loadedFolderIds += folderId
            return FilesRepositoryResult.Success(
                when (folderId) {
                    FilesFolder.Root.id -> FilesPage(listOf(Folder), nextCursor = null)
                    Folder.id -> FilesPage(listOf(Episode), nextCursor = null)
                    else -> error("Unexpected folder: $folderId")
                },
            )
        }

        override suspend fun persistSort(
            folderId: FilesItemId,
            sort: io.putdotio.android.files.FilesSort,
        ): FilesRepositoryResult<Unit> = FilesRepositoryResult.Success(Unit)
    }

    private companion object {
        const val USER_ID = 42L
        val Account = MobileAccount(USER_ID, "user", "user@example.com")
        val SessionOne = MobileAuthSessionId(1L)
        val SessionTwo = MobileAuthSessionId(2L)
        val Viewport = FilesViewportPosition(
            firstVisibleItemIndex = 12,
            firstVisibleItemScrollOffset = 34,
        )
        val Folder = FilesItem(
            id = FilesItemId(7L),
            parentId = FilesFolder.Root.id,
            name = "Shows",
            type = PutioFileType.FOLDER,
            sizeBytes = 0L,
            createdAt = "2026-08-29T00:00:00Z",
        )
        val Episode = FilesItem(
            id = FilesItemId(8L),
            parentId = Folder.id,
            name = "episode.mkv",
            type = PutioFileType.VIDEO,
            sizeBytes = 1L,
            createdAt = "2026-08-29T00:00:00Z",
        )

        fun signedIn(sessionId: MobileAuthSessionId): MobileAuthState.SignedIn =
            MobileAuthState.SignedIn(Account, sessionId)
    }
}
