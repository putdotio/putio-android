package io.putdotio.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
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
import io.putdotio.android.files.FilesPlaybackProgress
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.FilesRequestId
import io.putdotio.android.files.FilesSort
import io.putdotio.android.files.FilesViewportPosition
import io.putdotio.sdk.errors.PutioConfigurationException
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
    @Config(sdk = [35], qualifiers = "en-rUS-w640dp-h320dp-land")
    fun landscapeActionSheetKeepsBottomActionReachableAtDoubleFontScale() {
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        val item = filesItem(7L, "Archive 東京 été ".repeat(16))
        compose.setContent {
            PutioTheme {
                MobileFilesActions(
                    item, FilesItemId(0), FilesFolderOperation.Idle, null, {}, {},
                    confirmedTrashEnabled = false, onMoveItem = {},
                )
            }
        }
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(item.name).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(2f, layouts.single().layoutInput.density.fontScale)
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.Expand) { assertTrue(it()) }
        compose.onNodeWithText("Rename").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Move").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Delete").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun renameDraftSurvivesRecreationButDoesNotFollowNavigation() {
        val original = filesItem(7L, "old.mkv", PutioFileType.VIDEO)
        val sentinel = filesItem(0L, "root", PutioFileType.FOLDER)
        var state by mutableStateOf(browserState(FilesContent.Ready(listOf(original, sentinel), FilesPaging.Complete)))
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            PutioTheme {
                MobileFilesScreen(state, onEvent = { state = FilesBrowserReducer.reduce(state, it).state }, onPlayMedia = {})
            }
        }
        compose.onNodeWithContentDescription("Actions for root").assertDoesNotExist()
        compose.onNodeWithContentDescription("Actions for old.mkv").performClick()
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithTag(MOBILE_FILES_RENAME_FIELD_TAG).performTextReplacement("  unsaved.txt  ")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag(MOBILE_FILES_RENAME_FIELD_TAG).assertTextContains("  unsaved.txt  ")
        compose.runOnIdle {
            val navigation = FilesBrowserReducer.reduce(state, FilesBrowserEvent.OpenExternalItem(
                filesItem(44L, "Other folder", PutioFileType.FOLDER),
            ))
            state = FilesBrowserReducer.reduce(navigation.state, FilesBrowserEvent.LoadSucceeded(
                checkNotNull(navigation.effect).requestId,
                FilesPage(listOf(original.copy(parentId = FilesItemId(44L), name = "other.mkv")), null),
            )).state
        }
        compose.onNodeWithTag(MOBILE_FILES_RENAME_FIELD_TAG).assertDoesNotExist()
        compose.onNodeWithText("other.mkv").assertIsDisplayed()
    }

    @Test
    fun overflowRenamePreservesDraftOnFailureAndReloadRetryDoesNotRenameAgain() {
        val original = filesItem(7L, "old.mkv", PutioFileType.VIDEO)
        var state by mutableStateOf(browserState(FilesContent.Ready(listOf(original), FilesPaging.Complete)))
        val effects = mutableListOf<FilesBrowserEffect>()
        compose.setContent {
            PutioTheme {
                MobileFilesScreen(state, onEvent = {
                    val transition = FilesBrowserReducer.reduce(state, it)
                    state = transition.state
                    transition.effect?.let(effects::add)
                }, onPlayMedia = {})
            }
        }
        compose.onNodeWithContentDescription("Actions for old.mkv").performClick()
        compose.onNodeWithText("Rename").performClick()
        val field = compose.onNodeWithTag(MOBILE_FILES_RENAME_FIELD_TAG)
        field.assertTextContains("old.mkv").assertIsFocused()
        field.performTextReplacement("  Türkçe.mp4  ")
        field.performImeAction()
        compose.onNodeWithText("Save").assertDoesNotExist()
        field.assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, effects.size)
            val rename = effects.single() as FilesBrowserEffect.Rename
            assertEquals("  Türkçe.mp4  ", rename.name)
            state = FilesBrowserReducer.reduce(state, FilesBrowserEvent.LoadFailed(
                rename.requestId, FilesFailure.Unexpected(IllegalStateException("offline")),
            )).state
        }
        field.assertIsEnabled().assertTextContains("  Türkçe.mp4  ")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            val renamed = effects.last() as FilesBrowserEffect.Rename
            val reload = FilesBrowserReducer.reduce(state, FilesBrowserEvent.MutationSucceeded(renamed.requestId))
            state = reload.state
            effects += checkNotNull(reload.effect)
        }
        field.assertDoesNotExist()
        compose.runOnIdle {
            state = FilesBrowserReducer.reduce(state, FilesBrowserEvent.LoadFailed(
                effects.last().requestId, FilesFailure.Unexpected(IllegalStateException("reload failed")),
            )).state
        }
        compose.onNodeWithText("Name saved, but couldn’t reload files.").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_FILES_OPERATION_RETRY_TAG).performClick()
        compose.runOnIdle {
            assertTrue(effects.last() is FilesBrowserEffect.LoadFolder)
            assertEquals(2, effects.filterIsInstance<FilesBrowserEffect.Rename>().size)
        }
    }

    @Test
    fun longPressRenameCancelsWithoutChangingTheFolderAndFastSuccessClosesEditor() {
        val original = filesItem(7L, "folder", PutioFileType.FOLDER)
        var state by mutableStateOf(browserState(FilesContent.Ready(listOf(original), FilesPaging.Complete)))
        val renamed = mutableListOf<FilesBrowserEffect.Rename>()
        compose.setContent {
            PutioTheme {
                MobileFilesScreen(state, onEvent = {
                    val transition = FilesBrowserReducer.reduce(state, it)
                    state = transition.state
                    val effect = transition.effect
                    if (effect is FilesBrowserEffect.Rename) {
                        renamed += effect
                        val reload = FilesBrowserReducer.reduce(state, FilesBrowserEvent.MutationSucceeded(effect.requestId))
                        state = FilesBrowserReducer.reduce(reload.state, FilesBrowserEvent.LoadSucceeded(
                            checkNotNull(reload.effect).requestId,
                            FilesPage(listOf(original.copy(name = effect.name)), null),
                        )).state
                    }
                }, onPlayMedia = {})
            }
        }
        compose.onNodeWithText("folder").performTouchInput { longClick() }
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithTag(MOBILE_FILES_RENAME_FIELD_TAG).performTextReplacement("cancelled")
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(renamed.isEmpty()) }
        compose.onNodeWithText("folder").assertIsDisplayed().performTouchInput { longClick() }
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithTag(MOBILE_FILES_RENAME_FIELD_TAG).assertTextContains("folder").performTextReplacement("renamed folder")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithTag(MOBILE_FILES_RENAME_FIELD_TAG).assertDoesNotExist()
        compose.onNodeWithText("renamed folder").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, renamed.size) }
    }

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
        val audio = filesItem(id = 5L, name = "song.mp3", type = PutioFileType.AUDIO)
        val textFile = filesItem(id = 9L, name = "notes.txt")
        val events = mutableListOf<FilesBrowserEvent>()
        val played = mutableListOf<FilesItem>()

        setFilesContent(
            state = browserState(
                FilesContent.Ready(
                    items = listOf(folder, video, audio, textFile),
                    paging = FilesPaging.Complete,
                ),
            ),
            onEvent = events::add,
            onPlayMedia = played::add,
        )

        compose.onNodeWithText(folder.name).assertIsDisplayed().performClick()
        compose.onNodeWithText(video.name).assertIsDisplayed().performClick()
        compose.onNodeWithText(audio.name).assertIsDisplayed().performClick()
        compose.onNodeWithText("MB", substring = true).assertIsDisplayed()

        assertEquals(FilesBrowserEvent.OpenFolder(folder.id), events.last())
        assertEquals(listOf(video, audio), played)
        compose.onNodeWithText(textFile.name).performClick()
        compose.onNodeWithText("Rename").assertIsDisplayed()
    }

    @Test
    fun loadingEmptyAndFailedStatesStayRecoverable() {
        var state by mutableStateOf(
            browserState(FilesContent.Loading(FilesRequestId(1L))),
        )
        val events = mutableListOf<FilesBrowserEvent>()
        compose.setContent {
            PutioTheme {
                MobileFilesScreen(state = state, onEvent = events::add, onPlayMedia = {})
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

        // A forbidden folder names the permission problem and keeps a retry so a
        // later share grant can be picked up without leaving the screen.
        compose.runOnIdle {
            state = browserState(
                FilesContent.Failed(FilesFailure.AccessDenied(PutioConfigurationException("forbidden"))),
            )
        }
        compose.onNodeWithText("You don’t have access to this folder.").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(listOf(FilesBrowserEvent.Retry, FilesBrowserEvent.Retry), events.takeLast(2))
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
                MobileFilesScreen(state = state, onEvent = events::add, onPlayMedia = {})
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
                MobileFilesScreen(state = state, onEvent = events::add, onPlayMedia = {})
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
                MobileFilesScreen(state = state, onEvent = events::add, onPlayMedia = {})
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
                MobileFilesScreen(state = state, onEvent = events::add, onPlayMedia = {})
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
                MobileFilesScreen(state = state, onEvent = {}, onPlayMedia = {})
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
    fun refreshIsAccessibleAndDisabledDuringFolderOperations() {
        val content = FilesContent.Ready(
            items = listOf(filesItem(id = 1L, name = "visible.txt")),
            paging = FilesPaging.Complete,
        )
        var state by mutableStateOf(browserState(content))
        val events = mutableListOf<FilesBrowserEvent>()
        compose.setContent {
            PutioTheme {
                MobileFilesScreen(state = state, onEvent = events::add, onPlayMedia = {})
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
        compose.onAllNodesWithText("Updating file order").assertCountEquals(1)
        compose.onNodeWithText("Updating file order").assertIsDisplayed()
        compose.onNodeWithContentDescription("Updating file order").assertDoesNotExist()
        val activeRefreshConfig = compose.onNodeWithTag(MOBILE_FILES_REFRESH_TAG)
            .fetchSemanticsNode().config
        assertTrue(SemanticsActions.CustomActions !in activeRefreshConfig)
        compose.runOnIdle { events.clear() }
        compose.onNodeWithTag(MOBILE_FILES_REFRESH_TAG).performTouchInput { swipeDown() }
        compose.runOnIdle { assertTrue(FilesBrowserEvent.Refresh !in events) }

    }

    @Test
    fun operationFailuresKeepRowsAndRefreshRecoverable() {
        val content = FilesContent.Ready(
            items = listOf(filesItem(id = 1L, name = "visible.txt")),
            paging = FilesPaging.Complete,
        )
        var state by mutableStateOf(browserState(content))
        val events = mutableListOf<FilesBrowserEvent>()
        compose.setContent {
            PutioTheme {
                MobileFilesScreen(state = state, onEvent = events::add, onPlayMedia = {})
            }
        }

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
        compose.runOnIdle { events.clear() }
        val failedRefreshAction = compose.onNodeWithTag(MOBILE_FILES_REFRESH_TAG)
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == "Refresh files" }
        compose.runOnIdle { failedRefreshAction.action() }
        assertEquals(FilesBrowserEvent.Refresh, events.last())

        compose.runOnIdle { events.clear() }
        compose.onNodeWithTag(MOBILE_FILES_REFRESH_TAG).performTouchInput { swipeDown() }
        compose.waitUntil(timeoutMillis = 5_000L) { FilesBrowserEvent.Refresh in events }

        compose.onNodeWithText("Try again").performClick()
        assertEquals(FilesBrowserEvent.Retry, events.last())

    }

    @Test
    fun sortFailuresDistinguishReloadFromPersistence() {
        val content = FilesContent.Ready(
            items = listOf(filesItem(id = 1L, name = "visible.txt")),
            paging = FilesPaging.Complete,
        )
        var state by mutableStateOf(browserState(content))
        val events = mutableListOf<FilesBrowserEvent>()
        compose.setContent {
            PutioTheme {
                MobileFilesScreen(state = state, onEvent = events::add, onPlayMedia = {})
            }
        }

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
        onPlayMedia: (FilesItem) -> Unit = {},
    ) {
        compose.setContent {
            PutioTheme {
                MobileFilesScreen(state = state, onEvent = onEvent, onPlayMedia = onPlayMedia)
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

    @Test
    fun watchedMediaMergesTheLabelIntoTheRowAndKeepsTheBarDecorative() {
        val partly = filesItem(1L, "partly.mkv", PutioFileType.VIDEO).copy(playback = FilesPlaybackProgress(90.0, 360.0))
        val unknown = filesItem(2L, "unknown.mp3", PutioFileType.AUDIO).copy(playback = FilesPlaybackProgress(5.0, null))
        val fresh = filesItem(3L, "fresh.mkv", PutioFileType.VIDEO).copy(playback = FilesPlaybackProgress(0.0, 100.0))
        val plain = filesItem(4L, "plain.txt")
        val state = browserState(FilesContent.Ready(listOf(partly, unknown, fresh, plain), FilesPaging.Complete))
        compose.setContent { PutioTheme { MobileFilesScreen(state, onEvent = { true }, onPlayMedia = {}) } }
        compose.onAllNodesWithTag(MOBILE_FILES_WATCHED_TAG, useUnmergedTree = true).assertCountEquals(2)
        // One merged row node carries name, metadata, and watched label together.
        compose.onNode(hasText("partly.mkv") and hasText("128 B", substring = true) and hasText("25% watched"))
            .assertIsDisplayed()
        compose.onNode(hasText("unknown.mp3") and hasText("Watched")).assertIsDisplayed()
        compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.25f, 0f..1f))).assertCountEquals(0)
        compose.onAllNodesWithText("watched", substring = true, ignoreCase = true).assertCountEquals(2)
        compose.onNode(hasText("fresh.mkv") and hasText("watched", substring = true, ignoreCase = true))
            .assertDoesNotExist()
    }

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
