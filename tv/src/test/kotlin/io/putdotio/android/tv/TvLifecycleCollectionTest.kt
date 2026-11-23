package io.putdotio.android.tv

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Text
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesDeleteMode
import io.putdotio.android.files.FilesDeleteOutcome
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderOperationIntent
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.FilesRequestId
import io.putdotio.android.files.StubFilesRepository
import io.putdotio.android.tv.files.TvFilesEdits
import io.putdotio.android.tv.files.TvFilesMovePicker
import io.putdotio.android.tv.files.TvItemEditLayer
import io.putdotio.android.tv.files.TvItemEditTarget
import io.putdotio.android.tv.files.networkFailure
import io.putdotio.android.tv.files.rememberBusy
import io.putdotio.sdk.files.FileDeleteResult
import io.putdotio.sdk.files.FileMoveError
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w960dp-h540dp-television")
class TvLifecycleCollectionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun tvTrashUndoOfferedStopsCollectingWhileCreated() {
        val owner = FakeLifecycleOwner()
        val offer = TvTrashUndoOffer(CoroutineScope(UnconfinedTestDispatcher())) {}
        val snackbar = TvSnackbarState()
        compose.setTvContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                TvTrashUndoOffered(offer, snackbar)
                TvSnackbarHost(snackbar)
            }
        }
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
        }
        compose.waitForIdle()

        compose.runOnIdle {
            offer.offer(
                FilesDeleteOutcome(
                    FilesRequestId(1),
                    FilesFolderOperationIntent.Delete(FilesItemId(1), FilesDeleteMode.TRASH),
                    "movie.mkv",
                ),
            )
        }
        compose.waitForIdle()
        compose.onAllNodesWithText("Undo").assertCountEquals(0)

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.waitForIdle()
        compose.onNodeWithText("Undo").assertIsDisplayed()
    }

    @Test
    fun tvItemEditLayerStopsCollectingEditsStateWhileCreated() {
        val owner = FakeLifecycleOwner()
        val gate = CompletableDeferred<Unit>()
        val repository = GatedFilesRepository(deleteGate = gate, deleteFailure = networkFailure())
        val edits = TvFilesEdits(repository, CoroutineScope(Dispatchers.Unconfined))
        compose.setTvContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                TvItemEditLayer(
                    target = TvItemEditTarget(),
                    edits = edits,
                    trashEnabled = true,
                    snackbar = null,
                    onAuthenticationRequired = {},
                )
            }
        }
        compose.runOnIdle {
            edits.delete(sampleItem(), FilesDeleteMode.TRASH)
            owner.registry.currentState = Lifecycle.State.CREATED
        }
        compose.waitForIdle()

        compose.runOnIdle { gate.complete(Unit) }
        compose.waitForIdle()
        compose.onAllNodes(hasText("OK") and hasClickAction()).assertCountEquals(0)

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.waitForIdle()
        compose.onAllNodes(hasText("OK") and hasClickAction()).assertCountEquals(1)
    }

    @Test
    fun tvFilesEditsRememberBusyStopsCollectingWhileCreated() {
        val owner = FakeLifecycleOwner()
        val gate = CompletableDeferred<Unit>()
        val repository = GatedFilesRepository(deleteGate = gate)
        val edits = TvFilesEdits(repository, CoroutineScope(Dispatchers.Unconfined))
        compose.setTvContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                Text("busy=${edits.rememberBusy()}")
            }
        }
        compose.runOnIdle { edits.delete(sampleItem(), FilesDeleteMode.TRASH) }
        compose.onNodeWithText("busy=true").assertIsDisplayed()

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.waitForIdle()

        compose.runOnIdle { gate.complete(Unit) }
        compose.waitForIdle()
        compose.onNodeWithText("busy=true").assertIsDisplayed()

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.waitForIdle()
        compose.onNodeWithText("busy=false").assertIsDisplayed()
    }

    @Test
    fun tvFilesMovePickerStopsCollectingDestinationsWhileCreated() {
        val owner = FakeLifecycleOwner()
        val gate = CompletableDeferred<Unit>()
        val repository = GatedFilesRepository(moveLoadGate = gate)
        val edits = TvFilesEdits(repository, CoroutineScope(Dispatchers.Unconfined))
        compose.setTvContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                TvFilesMovePicker(
                    item = sampleItem(),
                    sourceFolderId = FilesFolder.Root.id,
                    edits = edits,
                    canSubmit = true,
                    onAuthenticationRequired = {},
                    onConfirm = { true },
                    onDismiss = {},
                )
            }
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.waitForIdle()

        compose.runOnIdle { gate.complete(Unit) }
        compose.waitForIdle()
        compose.onAllNodes(hasText("Archive")).assertCountEquals(0)

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.waitForIdle()
        compose.onNodeWithText("Archive").assertIsDisplayed()
    }

    private fun sampleItem() = FilesItem(
        id = FilesItemId(1L),
        parentId = FilesFolder.Root.id,
        name = "clip.mkv",
        type = PutioFileType.VIDEO,
        sizeBytes = 64L,
        createdAt = "2026-04-20T10:00:00Z",
    )

    private class FakeLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.STARTED }
        override val lifecycle: Lifecycle get() = registry
    }

    private class GatedFilesRepository(
        private val deleteGate: CompletableDeferred<Unit>? = null,
        private val deleteFailure: PutioFailure? = null,
        private val moveLoadGate: CompletableDeferred<Unit>? = null,
    ) : StubFilesRepository() {
        override suspend fun loadFolder(folderId: FilesItemId) =
            PutioResult.Success(FilesPage(emptyList(), null))

        override suspend fun loadMoveDestinations(
            folderId: FilesItemId,
            cursor: FilesCursor?,
        ): PutioResult<FilesPage> {
            moveLoadGate?.await()
            val folder = FilesItem(
                id = FilesItemId(10L),
                parentId = folderId,
                name = "Archive",
                type = PutioFileType.FOLDER,
                sizeBytes = 0L,
                createdAt = "2026-04-20T10:00:00Z",
            )
            return PutioResult.Success(FilesPage(listOf(folder), null))
        }

        override suspend fun rename(itemId: FilesItemId, name: String) = PutioResult.Success(Unit)

        override suspend fun move(
            itemIds: List<FilesItemId>,
            destinationId: FilesItemId,
        ): PutioResult<List<FileMoveError>> = PutioResult.Success(emptyList())

        override suspend fun delete(itemIds: List<FilesItemId>, mode: FilesDeleteMode): PutioResult<FileDeleteResult> {
            deleteGate?.await()
            return deleteFailure?.let { PutioResult.Failure(it) }
                ?: PutioResult.Success(FileDeleteResult(cursor = null, skipped = 0, status = "OK"))
        }

        override suspend fun resolveItem(itemId: FilesItemId): PutioResult<FilesItem> =
            PutioResult.Success(
                FilesItem(
                    id = itemId,
                    parentId = FilesFolder.Root.id,
                    name = "clip.mkv",
                    type = PutioFileType.VIDEO,
                    sizeBytes = 64L,
                    createdAt = "2026-04-20T10:00:00Z",
                ),
            )
    }
}
