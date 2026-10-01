package io.putdotio.android.files

import io.putdotio.sdk.files.PutioFileType
import io.putdotio.sdk.files.PutioFolderType
import io.putdotio.sdk.sharing.SharedFileCloneInfo
import io.putdotio.sdk.sharing.SharedFileCloneStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesCopyTest {
    private val sharedFolder = item(5L, "Sample folder", PutioFileType.FOLDER, isShared = true)
    private val sharedFile = item(7L, "Harbor film.mp4", PutioFileType.VIDEO, isShared = true)
    private val destination = FilesFolder(FilesItemId(0L), null)
    private val copy = FilesBrowserEvent.Copy(sharedFolder.id, sharedFile.id, destination)

    @Test
    fun onlyItemsSharedWithTheViewerCanBeCopiedAndOnlyOneCopyRunsAtATime() {
        val owned = item(8L, "Owned.mp4", PutioFileType.VIDEO)
        val sharedRoot = item(9L, "Items shared with you", PutioFileType.FOLDER, PutioFolderType.SHARED_ROOT, true)
        val friend = item(10L, "friend", PutioFileType.FOLDER, PutioFolderType.SHARED_FRIEND, true)
        assertTrue(sharedFile.canMakeCopy)
        assertTrue(sharedFolder.canMakeCopy)
        listOf(owned, sharedRoot, friend).forEach { assertFalse(it.name, it.canMakeCopy) }

        val state = inSharedFolder(listOf(sharedFile, owned, sharedRoot, friend))
        val rejected = listOf(
            copy.copy(itemId = owned.id), copy.copy(itemId = sharedRoot.id), copy.copy(itemId = friend.id),
            copy.copy(itemId = FilesItemId(99L)), copy.copy(folderId = FilesFolder.Root.id),
            copy.copy(destination = FilesFolder(FilesItemId(-1L), null)),
        )
        rejected.forEach { assertFalse(it.toString(), FilesBrowserReducer.reduce(state, it).consumed) }

        val starting = FilesBrowserReducer.reduce(state, copy)
        val effect = starting.effect as FilesBrowserEffect.StartCopy
        assertEquals(sharedFile.id, effect.itemId)
        assertEquals(destination.id, effect.destinationId)
        assertEquals(FilesCopyStatus.STARTING, starting.state.copyOutcome?.status)
        assertFalse(starting.state.canStartCopy)
        assertTrue(starting.state.hasRequest(effect.requestId))
        assertFalse(FilesBrowserReducer.reduce(starting.state, copy).consumed)
        assertFalse(FilesBrowserReducer.reduce(starting.state, FilesBrowserEvent.DismissCopyOutcome).consumed)
    }

    @Test
    fun aFinishedCopyReloadsOnlyTheDestinationAlreadyInTheStack() {
        val copying = started(FilesRepositoryResult.Success(FilesCopyId(42L)))
        val check = copying.effect as FilesBrowserEffect.CheckCopy
        assertEquals(FilesCopyId(42L), check.copyId)
        assertEquals(FilesCopyStatus.COPYING, copying.state.copyOutcome?.status)

        val stillRunning = FilesBrowserReducer.reduce(
            copying.state, checked(check.requestId, FilesCopyProgress.Running),
        )
        val nextCheck = stillRunning.effect as FilesBrowserEffect.CheckCopy
        assertFalse(
            "a late answer to the earlier check is ignored",
            FilesBrowserReducer.reduce(stillRunning.state, checked(check.requestId, FilesCopyProgress.Done)).consumed,
        )

        val copied = FilesBrowserReducer.reduce(
            stillRunning.state, checked(nextCheck.requestId, FilesCopyProgress.Done),
        )
        assertNull(copied.effect)
        assertEquals(FilesCopyStatus.COPIED, copied.state.copyOutcome?.status)
        assertEquals(listOf(true, false), copied.state.stack.map { it.needsReload })
        assertTrue(copied.state.canStartCopy)

        val dismissed = FilesBrowserReducer.reduce(copied.state, FilesBrowserEvent.DismissCopyOutcome)
        assertNull(dismissed.state.copyOutcome)
    }

    @Test
    fun failuresKeepTheirReasonAndAnUnansweredCheckLeavesTheCopyUnconfirmed() {
        val rejected = failure()
        val notStarted = started(FilesRepositoryResult.Failure(rejected)).state.copyOutcome
        assertEquals(FilesCopyStatus.FAILED, notStarted?.status)
        assertEquals(rejected, notStarted?.failure)

        val copying = started(FilesRepositoryResult.Success(FilesCopyId(42L)))
        val requestId = checkNotNull(copying.effect).requestId
        val failed = FilesBrowserReducer.reduce(
            copying.state, checked(requestId, FilesCopyProgress.Failed("File(s) size exceed disk limit.")),
        ).state
        assertEquals(FilesCopyStatus.FAILED, failed.copyOutcome?.status)
        assertEquals("File(s) size exceed disk limit.", failed.copyOutcome?.serverMessage)
        assertEquals(listOf(false, false), failed.stack.map { it.needsReload })

        val lost = FilesBrowserReducer.reduce(
            copying.state, FilesBrowserEvent.CopyChecked(requestId, FilesRepositoryResult.Failure(rejected)),
        ).state
        assertEquals(FilesCopyStatus.UNCONFIRMED, lost.copyOutcome?.status)
        assertEquals(listOf(true, false), lost.stack.map { it.needsReload })
    }

    @Test
    fun checksStopAfterTheBudgetWithTheCopyUnconfirmed() {
        var transition = started(FilesRepositoryResult.Success(FilesCopyId(42L)))
        var checks = 0
        while (transition.effect is FilesBrowserEffect.CheckCopy) {
            checks += 1
            transition = FilesBrowserReducer.reduce(
                transition.state, checked(checkNotNull(transition.effect).requestId, FilesCopyProgress.Running),
            )
        }
        assertEquals(MAX_COPY_CHECKS, checks)
        assertEquals(FilesCopyStatus.UNCONFIRMED, transition.state.copyOutcome?.status)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aCheckWaitsTheIntervalBeforeAsking() = runTest {
        var asked = 0
        val repository = object : StubFilesRepository() {
            override suspend fun loadFolder(folderId: FilesItemId): FilesRepositoryResult<FilesPage> =
                error("Unexpected listing")

            override suspend fun checkCopy(copyId: FilesCopyId): FilesRepositoryResult<FilesCopyProgress> {
                asked += 1
                return FilesRepositoryResult.Success(FilesCopyProgress.Done)
            }
        }
        val effect = FilesBrowserEffect.CheckCopy(FilesCopyId(42L), FilesRequestId(3L))
        val event = launch { repository.execute(effect) }
        advanceTimeBy(COPY_CHECK_INTERVAL_MILLIS - 1)
        runCurrent()
        assertEquals(0, asked)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, asked)
        event.join()
    }

    @Test
    fun theControllerRunsACopyToTheEndWhileTheViewerBrowses() = runBlocking {
        val starts = mutableListOf<Pair<FilesItemId, FilesItemId>>()
        val repository = object : StubFilesRepository() {
            override suspend fun loadFolder(folderId: FilesItemId): FilesRepositoryResult<FilesPage> =
                FilesRepositoryResult.Success(
                    FilesPage(if (folderId == FilesFolder.Root.id) listOf(sharedFolder) else listOf(sharedFile), null),
                )

            override suspend fun startCopy(
                itemId: FilesItemId,
                destinationId: FilesItemId,
            ): FilesRepositoryResult<FilesCopyId> {
                starts += itemId to destinationId
                return FilesRepositoryResult.Success(FilesCopyId(42L))
            }

            override suspend fun checkCopy(copyId: FilesCopyId): FilesRepositoryResult<FilesCopyProgress> =
                FilesRepositoryResult.Success(FilesCopyProgress.Done)
        }
        val controller = FilesBrowserController(repository, this)
        try {
            controller.awaitState { it.current.content is FilesContent.Ready }
            assertTrue(controller.dispatch(FilesBrowserEvent.OpenFolder(sharedFolder.id)))
            controller.awaitState { it.stack.size == 2 && it.current.content is FilesContent.Ready }
            assertTrue(controller.dispatch(copy))
            assertTrue(controller.dispatch(FilesBrowserEvent.NavigateBack))
            val copied = controller.awaitState(COPY_CHECK_INTERVAL_MILLIS * 4) {
                it.copyOutcome?.status == FilesCopyStatus.COPIED
            }
            assertEquals(listOf(sharedFile.id to destination.id), starts)
            assertEquals(sharedFile.name, copied.copyOutcome?.itemName)
        } finally {
            controller.close()
        }
    }

    @Test
    fun theSdkRepositoryCopiesOneItemAndReadsEveryStatus() = runBlocking {
        val starts = mutableListOf<Pair<Long, Long>>()
        val infos = ArrayDeque(
            listOf(
                SharedFileCloneInfo(SharedFileCloneStatus.NEW),
                SharedFileCloneInfo(SharedFileCloneStatus.PROCESSING),
                SharedFileCloneInfo(SharedFileCloneStatus("PAUSED")),
                SharedFileCloneInfo(SharedFileCloneStatus.DONE),
                SharedFileCloneInfo(SharedFileCloneStatus.ERROR, "File(s) size exceed disk limit."),
                SharedFileCloneInfo(SharedFileCloneStatus.ERROR, " "),
            ),
        )
        val repository = SdkFilesRepository(
            listFolder = { _, _ -> error("Unexpected listing") },
            continueListing = { _, _ -> error("Unexpected continuation") },
            setSort = { _, _ -> error("Unexpected sort") },
            getFile = { error("Unexpected item lookup") },
            mutations = SdkFilesMutations(
                rename = { _, _ -> error("Unexpected rename") },
                delete = { _, _ -> error("Unexpected delete") },
                move = { _, _ -> error("Unexpected move") },
            ),
            copies = SdkFilesCopies(
                start = { item, parent -> starts += item to parent; 42L },
                info = { id -> assertEquals(42L, id); infos.removeFirst() },
            ),
        )

        assertEquals(
            FilesRepositoryResult.Success(FilesCopyId(42L)), repository.startCopy(sharedFile.id, FilesItemId(9L)),
        )
        for ((item, parent) in listOf(0L to 9L, -1L to 9L, 7L to -1L)) {
            assertTrue(repository.startCopy(FilesItemId(item), FilesItemId(parent)) is FilesRepositoryResult.Failure)
        }
        assertEquals(listOf(7L to 9L), starts)
        val progress = List(6) { (repository.checkCopy(FilesCopyId(42L)) as FilesRepositoryResult.Success).value }
        assertEquals(
            listOf(
                FilesCopyProgress.Running, FilesCopyProgress.Running, FilesCopyProgress.Running,
                FilesCopyProgress.Done, FilesCopyProgress.Failed("File(s) size exceed disk limit."),
                FilesCopyProgress.Failed(null),
            ),
            progress,
        )
    }

    private fun started(result: FilesRepositoryResult<FilesCopyId>): FilesBrowserTransition {
        val starting = FilesBrowserReducer.reduce(inSharedFolder(listOf(sharedFile)), copy)
        return FilesBrowserReducer.reduce(
            starting.state, FilesBrowserEvent.CopyStarted(checkNotNull(starting.effect).requestId, result),
        )
    }

    private fun checked(requestId: FilesRequestId, progress: FilesCopyProgress) =
        FilesBrowserEvent.CopyChecked(requestId, FilesRepositoryResult.Success(progress))

    /** Root lists the shared folder, which is open on top with [items]. */
    private fun inSharedFolder(items: List<FilesItem>): FilesBrowserState {
        val root = loaded(FilesBrowserReducer.start(), listOf(sharedFolder))
        return loaded(FilesBrowserReducer.reduce(root, FilesBrowserEvent.OpenFolder(sharedFolder.id)), items)
    }

    private fun loaded(transition: FilesBrowserTransition, items: List<FilesItem>): FilesBrowserState {
        val requestId = checkNotNull(transition.effect).requestId
        val loaded = FilesBrowserEvent.LoadSucceeded(requestId, FilesPage(items, null))
        return FilesBrowserReducer.reduce(transition.state, loaded).state
    }

    private suspend fun FilesBrowserController.awaitState(
        timeoutMillis: Long = 2_000L,
        predicate: (FilesBrowserState) -> Boolean,
    ): FilesBrowserState = withTimeout(timeoutMillis) { state.first(predicate) }

    private fun item(
        id: Long,
        name: String,
        type: PutioFileType,
        folderType: PutioFolderType = PutioFolderType.REGULAR,
        isShared: Boolean = false,
    ) = FilesItem(
        FilesItemId(id), FilesItemId(1L), name, type, 1L, "2026-09-06", isShared = isShared, folderType = folderType,
    )

    private fun failure() = FilesFailure.Unexpected(IllegalStateException("offline"))
}
