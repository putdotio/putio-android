package io.putdotio.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesBrowserEffect
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserReducer
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderOperation
import io.putdotio.android.files.FilesFolderOperationIntent
import io.putdotio.android.files.FilesFolderOperationPhase
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.FilesRequestId
import io.putdotio.android.files.FilesSort
import io.putdotio.android.files.FilesViewportPosition
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileFilesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun readyContentPreservesNamesAndOpensFolders() {
        val folder = filesItem(
            id = 7L,
            name = "Season 01 [raw]",
            type = PutioFileType.FOLDER,
        )
        val video = filesItem(
            id = 8L,
            name = "episode.01.1080p.mkv",
            type = PutioFileType.VIDEO,
            sizeBytes = 1_048_576L,
        )
        val events = mutableListOf<FilesBrowserEvent>()

        setFilesContent(
            state = browserState(
                FilesContent.Ready(
                    items = listOf(folder, video),
                    paging = FilesPaging.Complete,
                ),
            ),
            onEvent = events::add,
        )

        compose.onNodeWithText(folder.name).assertIsDisplayed().performClick()
        compose.onNodeWithText(video.name).assertIsDisplayed()
        compose.onNodeWithText("MB", substring = true).assertIsDisplayed()

        assertEquals(FilesBrowserEvent.OpenFolder(folder.id), events.last())
    }

    @Test
    fun loadingEmptyAndFailedStatesStayRecoverable() {
        var state by mutableStateOf(
            browserState(FilesContent.Loading(FilesRequestId(1L))),
        )
        val events = mutableListOf<FilesBrowserEvent>()
        compose.setContent {
            PutioTheme {
                MobileFilesScreen(state = state, onEvent = events::add)
            }
        }

        compose.onNodeWithText("Loading").assertIsDisplayed()

        compose.runOnIdle {
            state = browserState(FilesContent.Empty(FilesPaging.Complete))
        }
        compose.onNodeWithText("This folder is empty.").assertIsDisplayed()
        val emptyRefreshAction = compose.onNodeWithTag(MOBILE_FILES_REFRESH_TAG)
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == "Refresh files" }
        compose.runOnIdle { emptyRefreshAction.action() }
        assertEquals(FilesBrowserEvent.Refresh, events.last())

        compose.runOnIdle {
            state = browserState(
                FilesContent.Failed(FilesFailure.Unexpected(IllegalStateException("broken"))),
            )
        }
        compose.onNodeWithText("Try again").performClick()

        assertEquals(FilesBrowserEvent.Retry, events.last())
    }

    @Test
    fun pagingOffersContinuationAndRetry() {
        var state by mutableStateOf(
            browserState(
                FilesContent.Ready(
                    items = listOf(filesItem(id = 1L, name = "first.txt")),
                    paging = FilesPaging.Available(FilesCursor("next-page")),
                ),
            ),
        )
        val events = mutableListOf<FilesBrowserEvent>()
        compose.setContent {
            PutioTheme {
                MobileFilesScreen(state = state, onEvent = events::add)
            }
        }

        compose.onNodeWithText("Load more").performClick()
        assertTrue(events.contains(FilesBrowserEvent.LoadNextPage))

        compose.runOnIdle {
            state = browserState(
                FilesContent.Ready(
                    items = listOf(filesItem(id = 1L, name = "first.txt")),
                    paging = FilesPaging.Failed(
                        cursor = FilesCursor("next-page"),
                        failure = FilesFailure.Unexpected(IllegalStateException("broken")),
                    ),
                ),
            )
        }
        compose.onNodeWithText("Couldn’t load more files.").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()

        assertEquals(FilesBrowserEvent.Retry, events.last())
    }

    @Test
    fun restoresAndReportsTheFolderViewport() {
        val events = mutableListOf<FilesBrowserEvent>()
        val items = (0L until 30L).map { index ->
            filesItem(id = index + 1L, name = "file-$index.txt")
        }
        setFilesContent(
            state = browserState(
                FilesContent.Ready(
                    items = items,
                    paging = FilesPaging.Complete,
                    viewport = FilesViewportPosition(firstVisibleItemIndex = 12),
                ),
            ),
            onEvent = events::add,
        )

        compose.onNodeWithText("file-12.txt").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_FILES_LIST_TAG).performScrollToIndex(20)
        compose.waitUntil(timeoutMillis = 5_000L) {
            events
                .filterIsInstance<FilesBrowserEvent.ViewportChanged>()
                .any { it.position.firstVisibleItemIndex == 20 }
        }
    }

    @Test
    fun aCompletedSortResetsTheVisibleFolderViewport() {
        val initialItems = (0L until 30L).map { index ->
            filesItem(id = index + 1L, name = "initial-$index.txt")
        }
        val events = mutableListOf<FilesBrowserEvent>()
        var state by mutableStateOf(
            browserState(
                FilesContent.Ready(
                    items = initialItems,
                    paging = FilesPaging.Complete,
                    viewport = FilesViewportPosition(firstVisibleItemIndex = 12),
                ),
                sort = FilesSort.NAME_ASCENDING,
            ),
        )
        compose.setContent {
            PutioTheme {
                MobileFilesScreen(state = state, onEvent = events::add)
            }
        }

        compose.onNodeWithTag(MOBILE_FILES_LIST_TAG).performScrollToIndex(20)
        compose.runOnIdle {
            events.clear()
            state = browserState(
                FilesContent.Ready(
                    items = initialItems.reversed().mapIndexed { index, item ->
                        item.copy(name = "sorted-$index.txt")
                    },
                    paging = FilesPaging.Complete,
                    viewport = FilesViewportPosition(),
                ),
                sort = FilesSort.SIZE_DESCENDING,
                viewportGeneration = 1L,
            )
        }

        compose.onNodeWithText("sorted-0.txt").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(events.none { it is FilesBrowserEvent.ViewportChanged })
        }
    }

    @Test
    fun viewportReportsDoNotOverrideActiveListPosition() {
        val items = (0L until 60L).map { index ->
            filesItem(id = index + 1L, name = "file-$index.txt")
        }
        var state by mutableStateOf(
            browserState(
                FilesContent.Ready(
                    items = items,
                    paging = FilesPaging.Complete,
                    viewport = FilesViewportPosition(firstVisibleItemIndex = 5),
                ),
            ),
        )
        val events = mutableListOf<FilesBrowserEvent>()
        compose.setContent {
            PutioTheme {
                MobileFilesScreen(state = state, onEvent = events::add)
            }
        }

        compose.onNodeWithTag(MOBILE_FILES_LIST_TAG).performScrollToIndex(40)
        compose.waitUntil(timeoutMillis = 5_000L) {
            events.filterIsInstance<FilesBrowserEvent.ViewportChanged>()
                .any { it.position.firstVisibleItemIndex == 40 }
        }
        var refreshRequestValue = -1L
        compose.runOnIdle {
            val viewportEvent = events.filterIsInstance<FilesBrowserEvent.ViewportChanged>()
                .last { it.position.firstVisibleItemIndex == 40 }
            state = FilesBrowserReducer.reduce(state, viewportEvent).state
            events.clear()
        }
        compose.runOnIdle {
            val refresh = FilesBrowserReducer.reduce(state, FilesBrowserEvent.Refresh)
            state = refresh.state
            refreshRequestValue = (refresh.effect as FilesBrowserEffect.LoadFolder).requestId.value
        }
        compose.onNodeWithText("file-40.txt").assertIsDisplayed()
        compose.runOnIdle {
            check(refreshRequestValue >= 0L)
            state = FilesBrowserReducer.reduce(
                state,
                FilesBrowserEvent.LoadSucceeded(
                    FilesRequestId(refreshRequestValue),
                    FilesPage(items = items, nextCursor = null),
                ),
            ).state
        }

        compose.onNodeWithText("file-40.txt").assertIsDisplayed()
        compose.runOnIdle { assertTrue(events.isEmpty()) }
    }

    @Test
    fun readyAndEmptyFoldersRefreshFromPullGestures() {
        val ready = FilesContent.Ready(
            items = (0L until 30L).map { index -> filesItem(id = index + 1L, name = "file-$index.txt") },
            paging = FilesPaging.Complete,
        )
        var state by mutableStateOf(browserState(ready))
        val events = mutableListOf<FilesBrowserEvent>()
        compose.setContent {
            PutioTheme {
                MobileFilesScreen(state = state, onEvent = events::add)
            }
        }

        compose.onNodeWithTag(MOBILE_FILES_REFRESH_TAG).performTouchInput { swipeDown() }
        compose.waitUntil(timeoutMillis = 5_000L) { FilesBrowserEvent.Refresh in events }

        compose.runOnIdle {
            events.clear()
            state = browserState(
                content = ready,
                operation = FilesFolderOperation.Loading(
                    requestId = FilesRequestId(4L),
                    intent = FilesFolderOperationIntent.Refresh,
                    phase = FilesFolderOperationPhase.RELOADING,
                ),
            )
        }
        compose.onNode(
            hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate),
            useUnmergedTree = true,
        ).assertIsDisplayed()

        compose.runOnIdle {
            state = browserState(FilesContent.Empty(FilesPaging.Complete))
        }
        val refreshBounds = compose.onNodeWithTag(MOBILE_FILES_REFRESH_TAG).fetchSemanticsNode().boundsInRoot
        val emptyMessageBounds = compose.onNodeWithText("This folder is empty.").fetchSemanticsNode().boundsInRoot
        assertTrue(
            kotlin.math.abs(emptyMessageBounds.center.y - refreshBounds.center.y) < refreshBounds.height / 4f,
        )
        compose.onNodeWithTag(MOBILE_FILES_REFRESH_TAG).performTouchInput { swipeDown() }
        compose.waitUntil(timeoutMillis = 5_000L) { FilesBrowserEvent.Refresh in events }
    }

    @Test
    fun pagingActionsAreDisabledDuringFolderOperations() {
        val pagingFailure = FilesFailure.Unexpected(IllegalStateException("paging failed"))
        val operationFailure = FilesFailure.Unexpected(IllegalStateException("refresh failed"))
        var state by mutableStateOf(
            browserState(
                content = FilesContent.Empty(FilesPaging.Available(FilesCursor("next-page"))),
                operation = FilesFolderOperation.Loading(
                    requestId = FilesRequestId(5L),
                    intent = FilesFolderOperationIntent.Refresh,
                    phase = FilesFolderOperationPhase.RELOADING,
                ),
            ),
        )
        compose.setContent {
            PutioTheme {
                MobileFilesScreen(state = state, onEvent = {})
            }
        }

        compose.onNodeWithTag(MOBILE_FILES_PAGING_ACTION_TAG).assertIsNotEnabled()

        compose.runOnIdle {
            state = browserState(
                content = FilesContent.Empty(
                    FilesPaging.Failed(FilesCursor("next-page"), pagingFailure),
                ),
                operation = FilesFolderOperation.Failed(
                    failure = operationFailure,
                    intent = FilesFolderOperationIntent.Refresh,
                    phase = FilesFolderOperationPhase.RELOADING,
                ),
            )
        }
        compose.onNodeWithTag(MOBILE_FILES_PAGING_ACTION_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(MOBILE_FILES_OPERATION_RETRY_TAG).assertIsEnabled()
    }

    @Test
    fun refreshIsAccessibleAndOperationFailureKeepsRowsRecoverable() {
        val content = FilesContent.Ready(
            items = listOf(filesItem(id = 1L, name = "visible.txt")),
            paging = FilesPaging.Complete,
        )
        var state by mutableStateOf(browserState(content))
        val events = mutableListOf<FilesBrowserEvent>()
        compose.setContent {
            PutioTheme {
                MobileFilesScreen(state = state, onEvent = events::add)
            }
        }

        val refreshAction = compose.onNodeWithTag(MOBILE_FILES_REFRESH_TAG)
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == "Refresh files" }
        compose.runOnIdle { refreshAction.action() }
        assertEquals(FilesBrowserEvent.Refresh, events.last())

        compose.runOnIdle {
            state = browserState(
                content = content,
                operation = FilesFolderOperation.Loading(
                    requestId = FilesRequestId(3L),
                    intent = FilesFolderOperationIntent.Sort(io.putdotio.android.files.FilesSort.NAME_ASCENDING),
                    phase = FilesFolderOperationPhase.PERSISTING_SORT,
                ),
            )
        }
        compose.onNodeWithText("visible.txt").assertIsDisplayed()
        compose.onNodeWithText("Updating file order").assertIsDisplayed()
        val activeRefreshConfig = compose.onNodeWithTag(MOBILE_FILES_REFRESH_TAG)
            .fetchSemanticsNode().config
        assertTrue(SemanticsActions.CustomActions !in activeRefreshConfig)
        compose.runOnIdle { events.clear() }
        compose.onNodeWithTag(MOBILE_FILES_REFRESH_TAG).performTouchInput { swipeDown() }
        compose.runOnIdle { assertTrue(FilesBrowserEvent.Refresh !in events) }

        compose.runOnIdle {
            state = browserState(
                content = content,
                operation = FilesFolderOperation.Failed(
                    failure = FilesFailure.Unexpected(IllegalStateException("offline")),
                    intent = FilesFolderOperationIntent.Refresh,
                    phase = FilesFolderOperationPhase.RELOADING,
                ),
            )
        }
        compose.onNodeWithText("visible.txt").assertIsDisplayed()
        compose.onNodeWithText("Couldn’t refresh files.").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(FilesBrowserEvent.Retry, events.last())

        compose.runOnIdle {
            state = browserState(
                content = content,
                operation = FilesFolderOperation.Failed(
                    failure = FilesFailure.Unexpected(IllegalStateException("offline")),
                    intent = FilesFolderOperationIntent.Sort(
                        io.putdotio.android.files.FilesSort.NAME_ASCENDING,
                    ),
                    phase = FilesFolderOperationPhase.RELOADING,
                ),
            )
        }
        compose.onNodeWithText("Couldn’t reload files.").assertIsDisplayed()

        compose.runOnIdle {
            state = browserState(
                content = content,
                operation = FilesFolderOperation.Failed(
                    failure = FilesFailure.Unexpected(IllegalStateException("offline")),
                    intent = FilesFolderOperationIntent.Sort(
                        io.putdotio.android.files.FilesSort.NAME_ASCENDING,
                    ),
                    phase = FilesFolderOperationPhase.PERSISTING_SORT,
                ),
            )
        }
        compose.onNodeWithText("Couldn’t change sorting.").assertIsDisplayed()
    }

    private fun setFilesContent(
        state: FilesBrowserState,
        onEvent: (FilesBrowserEvent) -> Unit,
    ) {
        compose.setContent {
            PutioTheme {
                MobileFilesScreen(state = state, onEvent = onEvent)
            }
        }
    }

    private fun browserState(
        content: FilesContent,
        operation: FilesFolderOperation = FilesFolderOperation.Idle,
        sort: FilesSort? = null,
        viewportGeneration: Long = 0L,
    ): FilesBrowserState =
        FilesBrowserState(
            stack = listOf(
                FilesFolderState(
                    folder = FilesFolder.Root.copy(sort = sort),
                    content = content,
                    operation = operation,
                    viewportGeneration = viewportGeneration,
                ),
            ),
            nextRequestValue = 2L,
        )

    private fun filesItem(
        id: Long,
        name: String,
        type: PutioFileType = PutioFileType.TEXT,
        sizeBytes: Long = 128L,
    ): FilesItem =
        FilesItem(
            id = FilesItemId(id),
            parentId = FilesFolder.Root.id,
            name = name,
            type = type,
            sizeBytes = sizeBytes,
            createdAt = "2026-04-20T10:00:00Z",
        )
}
