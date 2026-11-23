package io.putdotio.android.tv

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.FilesRequestId
import io.putdotio.android.files.StubFilesRepository
import io.putdotio.android.history.HistoryContent
import io.putdotio.android.history.HistoryEventId
import io.putdotio.android.history.HistoryEventKind
import io.putdotio.android.history.HistoryFileId
import io.putdotio.android.history.HistoryItem
import io.putdotio.android.history.HistoryPaging
import io.putdotio.android.history.HistoryState
import io.putdotio.android.history.historyState
import io.putdotio.android.search.RecentSearchEdit
import io.putdotio.android.search.SearchContent
import io.putdotio.android.search.SearchPaging
import io.putdotio.android.search.SearchTerm
import io.putdotio.android.search.searchState
import io.putdotio.android.trash.TrashContent
import io.putdotio.android.trash.TrashItem
import io.putdotio.android.trash.TrashState
import io.putdotio.android.tv.files.TV_FILES_DETAIL_PRIMARY_TAG
import io.putdotio.android.tv.files.TV_FILES_LIST_TAG
import io.putdotio.android.tv.files.TvFilesEdits
import io.putdotio.android.tv.files.TvFilesList
import io.putdotio.android.tv.files.TvFilesScreen
import io.putdotio.android.tv.files.item
import io.putdotio.android.tv.files.networkFailure
import io.putdotio.android.tv.files.ready
import io.putdotio.android.tv.files.rememberTvFilesRowText
import io.putdotio.android.tv.history.TV_HISTORY_LIST_TAG
import io.putdotio.android.tv.history.TvHistoryScreen
import io.putdotio.android.tv.search.TV_SEARCH_FIELD_TAG
import io.putdotio.android.tv.search.TvRecentSearchActions
import io.putdotio.android.tv.search.TvSearchActions
import io.putdotio.android.tv.search.TvSearchScreen
import io.putdotio.android.tv.search.tvSearchDetail
import io.putdotio.android.tv.search.tvSearchResults
import io.putdotio.android.tv.trash.TV_TRASH_LIST_TAG
import io.putdotio.android.tv.trash.TvTrashScreen
import io.putdotio.sdk.files.PutioFileType
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w960dp-h540dp-television")
class TvCompositionWritesTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun tvFilesListDoesNotMutateFocusMemoryDuringComposition() {
        val page1 = (1L..12L).map { item(it, "file-$it.txt", PutioFileType.TEXT) }
        val page2 = (13L..20L).map { item(it, "file-$it.txt", PutioFileType.TEXT) }
        var state by mutableStateOf(
            ready(*page1.toTypedArray(), paging = FilesPaging.Failed(FilesCursor("c"), networkFailure())),
        )
        val writesInComposition = mutableListOf<Pair<Long, Long>>()
        val backing = mutableMapOf<Long, Long>()
        val spyMemory = object : MutableMap<Long, Long> by backing {
            override fun put(key: Long, value: Long): Long? {
                val calledFromComposition = Thread.currentThread().stackTrace.any { frame ->
                    (frame.className == "androidx.compose.runtime.GapComposer" ||
                        frame.className == "androidx.compose.runtime.ComposerImpl") &&
                        (frame.methodName.startsWith("doCompose") ||
                            frame.methodName.startsWith("recomposeToGroupEnd"))
                }
                if (calledFromComposition) writesInComposition += key to value
                return backing.put(key, value)
            }
        }
        compose.setTvContent {
            TvFilesScreen(state = state, onEvent = { true }, onPlayMedia = {}, focusMemory = spyMemory)
        }
        compose.onNodeWithTag(TV_FILES_LIST_TAG).performScrollToIndex(12)
        compose.onNode(hasText("Try again") and hasClickAction()).requestFocus().assertIsFocused()
        assertEquals(-1L, backing[0L])
        assertTrue("Write during composition: $writesInComposition", writesInComposition.isEmpty())

        state = ready(*(page1 + page2).toTypedArray(), paging = FilesPaging.Complete)
        compose.waitForIdle()

        assertTrue("Write during composition: $writesInComposition", writesInComposition.isEmpty())
        assertEquals(20L, backing[0L])
        compose.onNodeWithContentDescription("file-20.txt").assertIsFocused()
    }

    @Test
    fun tvFilesListWritesHandoffToReplacedEqualFocusMemory() {
        val page1 = (1L..12L).map { item(it, "file-$it.txt", PutioFileType.TEXT) }
        val page2 = (13L..20L).map { item(it, "file-$it.txt", PutioFileType.TEXT) }
        val firstMap = mutableMapOf<Long, Long>()
        var memory by mutableStateOf(FocusMap(firstMap))
        var state by mutableStateOf(
            ready(*page1.toTypedArray(), paging = FilesPaging.Failed(FilesCursor("c"), networkFailure())),
        )
        compose.setTvContent {
            TvFilesScreen(state = state, onEvent = { true }, onPlayMedia = {}, focusMemory = memory.map)
        }
        compose.onNodeWithTag(TV_FILES_LIST_TAG).performScrollToIndex(12)
        compose.onNode(hasText("Try again") and hasClickAction()).requestFocus().assertIsFocused()
        assertEquals(-1L, firstMap[0L])

        val replacement = mutableMapOf(0L to -1L)
        memory = FocusMap(replacement)
        state = ready(*(page1 + page2).toTypedArray(), paging = FilesPaging.Complete)
        compose.waitForIdle()

        assertEquals(-1L, firstMap[0L])
        assertEquals(20L, replacement[0L])
    }

    @Test
    fun tvFilesListPagingHandoffPreservesDetailFocusAcrossPageArrival() {
        val page1 = (1L..12L).map { item(it, "file-$it.txt", PutioFileType.TEXT) }
        val page2 = (13L..20L).map { item(it, "file-$it.txt", PutioFileType.TEXT) }
        var content by mutableStateOf(FilesContent.Ready(page1, FilesPaging.Failed(FilesCursor("c"), networkFailure())))
        val paneHasFocus = mutableStateOf(true)
        val headerHasFocus = mutableStateOf(false)
        val detailOwnsFocus = mutableStateOf(false)
        val keyHeld = mutableStateOf(false)
        val detailFocus = FocusRequester()
        compose.setTvContent {
            Row {
                TvFilesList(
                    folderId = 0L,
                    content = content,
                    rowText = rememberTvFilesRowText(content.items),
                    pagingEnabled = true,
                    focusMemory = mutableMapOf(),
                    paneHasFocus = paneHasFocus,
                    headerHasFocus = headerHasFocus,
                    detailOwnsFocus = detailOwnsFocus,
                    downFromLast = null,
                    keyHeld = keyHeld,
                    onEntryTarget = {},
                    onEvent = { true },
                    onOpen = {},
                    onActions = {},
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .size(48.dp)
                        .testTag("detail")
                        .focusRequester(detailFocus)
                        .onFocusChanged { detailOwnsFocus.value = it.isFocused }
                        .focusable(),
                )
            }
        }
        compose.onNodeWithTag(TV_FILES_LIST_TAG).performScrollToIndex(12)
        compose.onNode(hasText("Try again") and hasClickAction()).requestFocus().assertIsFocused()

        compose.mainClock.autoAdvance = false
        content = FilesContent.Ready(page1, FilesPaging.Loading(FilesCursor("c"), FilesRequestId(9)))
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        compose.runOnIdle { detailFocus.requestFocus() }
        content = FilesContent.Ready(page1 + page2, FilesPaging.Complete)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        compose.onNodeWithTag("detail").assertIsFocused()
        compose.onNodeWithContentDescription("file-20.txt").assertIsNotFocused()
    }

    @Test
    fun tvFilesMoveSheetPagingHandoffAndAwayFocusGuard() {
        val gate = CompletableDeferred<Unit>()
        val repository = object : StubFilesRepository() {
            override suspend fun loadFolder(folderId: FilesItemId) =
                PutioResult.Success(FilesPage(emptyList(), null))

            override suspend fun loadMoveDestinations(
                folderId: FilesItemId,
                cursor: FilesCursor?,
            ): PutioResult<FilesPage> {
                if (cursor != null) gate.await()
                val folder = if (cursor == null) {
                    item(2L, "Archive", PutioFileType.FOLDER)
                } else {
                    item(3L, "Old", PutioFileType.FOLDER)
                }
                return PutioResult.Success(
                    FilesPage(listOf(folder), nextCursor = if (cursor == null) FilesCursor("m") else null),
                )
            }
        }
        val state = ready(item(1L, "doc.txt", PutioFileType.TEXT))
        compose.setTvContent {
            TvFilesScreen(
                state = state,
                onEvent = { true },
                onPlayMedia = {},
                edits = TvFilesEdits(repository, CoroutineScope(Dispatchers.Unconfined)),
            )
        }
        compose.onNodeWithContentDescription("doc.txt").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag(TV_FILES_DETAIL_PRIMARY_TAG).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.onNode(hasText("Move") and hasClickAction()).performKeyInput { pressCenter() }
        compose.awaitText("Archive")
        compose.onNode(hasText("Archive") and hasClickAction()).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
        }
        compose.onNode(hasText("More folders") and hasClickAction()).assertIsFocused().performKeyInput {
            pressCenter()
        }
        compose.onNode(hasText("Loading more files") and hasClickAction()).performKeyInput {
            pressKey(Key.DirectionUp)
        }
        compose.onNode(hasText("Archive") and hasClickAction()).assertIsFocused()
        compose.runOnIdle { gate.complete(Unit) }
        compose.awaitText("Old")
        compose.waitForIdle()
        compose.onNode(hasText("Archive") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun tvSearchResultsDirectFailedToCompleteAndAwayFocusGuard() {
        val page1 = (1L..12L).map { resultVideo(it, "row-$it.mkv") }
        val page2 = (13L..35L).map { resultVideo(it, "row-$it.mkv") }
        var state by mutableStateOf(searchReady(page1, SearchPaging.Failed(FilesCursor("c"), networkFailure())))
        compose.setTvContent {
            TvSearchScreen(
                state = state,
                actions = searchActions(),
                notice = null,
                sessionKey = null,
                detail = tvSearchDetail(),
                results = tvSearchResults(),
            )
        }
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus()
        moveDownUntilFocused(hasText("Try again") and hasClickAction())
        compose.onNode(hasText("Try again") and hasClickAction()).assertIsFocused()

        state = searchReady(page1 + page2, SearchPaging.Complete)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Play row-35.mkv").assertIsFocused()

        state = searchReady(page1, SearchPaging.Failed(FilesCursor("c"), networkFailure()))
        compose.waitForIdle()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus()
        moveDownUntilFocused(hasText("Try again") and hasClickAction())
        compose.onNode(hasText("Try again") and hasClickAction()).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionUp)
        }
        compose.onNodeWithContentDescription("Play row-12.mkv").assertIsFocused()
        state = searchReady(page1 + page2, SearchPaging.Complete)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Play row-12.mkv").assertIsFocused()
    }

    @Test
    fun tvTrashListDirectFailedToCompleteAndAwayFocusGuard() {
        val page1 = (1L..15L).map { trashRow(it) }
        val page2 = (16L..35L).map { trashRow(it) }
        var state by mutableStateOf(
            TrashState(content = TrashContent.Loaded(page1, FilesCursor("c"), 35, 10, pageFailure = networkFailure())),
        )
        compose.setTvContent { TvTrashScreen(state = state, onEvent = { true }) }
        compose.onNodeWithTag(TV_TRASH_LIST_TAG).performScrollToIndex(15)
        compose.onNode(hasText("Try again") and hasClickAction()).requestFocus().assertIsFocused()

        state = TrashState(content = TrashContent.Loaded(page1 + page2, nextCursor = null, 35, 10))
        compose.waitForIdle()
        compose.onNode(hasText("trash-35.txt") and hasClickAction()).assertIsFocused()

        state = TrashState(
            content = TrashContent.Loaded(page1, FilesCursor("c"), 35, 10, pageFailure = networkFailure()),
        )
        compose.waitForIdle()
        compose.onNode(hasText("Try again") and hasClickAction()).requestFocus().assertIsFocused().performKeyInput {
            pressKey(Key.DirectionUp)
        }
        compose.onNode(hasText("trash-15.txt") and hasClickAction()).assertIsFocused()
        state = TrashState(content = TrashContent.Loaded(page1 + page2, nextCursor = null, 35, 10))
        compose.waitForIdle()
        compose.onNode(hasText("trash-15.txt") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun tvHistoryListDirectFailedToCompleteAndAwayFocusGuard() {
        val clock = Clock.fixed(Instant.parse("2026-09-12T12:00:00Z"), ZoneOffset.UTC)
        val page1 = (1L..15L).map { historyRow(it, clock) }
        val page2 = (16L..25L).map { historyRow(it, clock) }
        var state by mutableStateOf(
            historyState(
                HistoryContent.Ready(
                    page1,
                    HistoryPaging.Failed(HistoryEventId(15L), networkFailure()),
                ),
            ),
        )
        compose.setTvContent { TvHistoryScreen(state = state, onEvent = { true }, clock = clock) }
        compose.onNodeWithContentDescription("Open hist-1.mkv").assertIsFocused()
        moveDownUntilFocused(hasText("Try again") and hasClickAction())
        compose.onNode(hasText("Try again") and hasClickAction()).assertIsFocused()

        state = historyState(HistoryContent.Ready(page1 + page2, HistoryPaging.Complete))
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Open hist-25.mkv").assertIsFocused()
    }

    @Test
    fun tvHistoryListAwayFocusDoesNotStealOnPagingComplete() {
        val clock = Clock.fixed(Instant.parse("2026-09-12T12:00:00Z"), ZoneOffset.UTC)
        val page1 = (1L..15L).map { historyRow(it, clock) }
        val page2 = (16L..25L).map { historyRow(it, clock) }
        var state by mutableStateOf(
            historyState(
                HistoryContent.Ready(
                    page1,
                    HistoryPaging.Failed(HistoryEventId(15L), networkFailure()),
                ),
            ),
        )
        compose.setTvContent { TvHistoryScreen(state = state, onEvent = { true }, clock = clock) }
        compose.onNodeWithContentDescription("Open hist-1.mkv").assertIsFocused()
        moveDownUntilFocused(hasText("Try again") and hasClickAction())
        compose.onNode(hasText("Try again") and hasClickAction()).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionUp)
        }
        compose.onNodeWithContentDescription("Open hist-15.mkv").assertIsFocused()
        state = historyState(HistoryContent.Ready(page1 + page2, HistoryPaging.Complete))
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Open hist-15.mkv").assertIsFocused()
    }

    @Test
    fun tvRecentSearchesUnfocusedChipRemovalDoesNotStealFocus() {
        var recent by mutableStateOf(listOf(SearchTerm("first"), SearchTerm("second"), SearchTerm("third")))
        compose.setTvContent {
            TvSearchScreen(
                state = searchState(
                    query = "",
                    content = SearchContent.Idle,
                    recentTerms = recent,
                    nextRequestValue = 5L,
                ),
                actions = searchActions(onEdit = {
                    if (it is RecentSearchEdit.Remove) recent = recent.filterNot { term -> term == it.term }
                }),
                notice = null,
                sessionKey = null,
                detail = tvSearchDetail(),
                results = tvSearchResults(),
            )
        }
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithContentDescription("Search again for first").assertIsFocused()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus().assertIsFocused()

        recent = listOf(SearchTerm("second"), SearchTerm("third"))
        compose.waitForIdle()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).assertIsFocused()

        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithContentDescription("Search again for second").assertIsFocused()
        recent = listOf(SearchTerm("third"))
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Search again for third").assertIsFocused()
    }

    private fun searchReady(items: List<FilesItem>, paging: SearchPaging) = searchState(
        query = "r",
        content = SearchContent.Ready(SearchTerm("r"), items, paging),
        recentTerms = emptyList(),
        nextRequestValue = 5L,
    )

    private fun moveDownUntilFocused(matcher: SemanticsMatcher, limit: Int = 25) {
        repeat(limit) {
            if (compose.onAllNodes(matcher and isFocused()).fetchSemanticsNodes().isNotEmpty()) return
            compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
            compose.waitForIdle()
        }
    }

    private fun searchActions(onEdit: (RecentSearchEdit) -> Unit = {}) = TvSearchActions(
        onQueryChanged = {},
        onSubmit = {},
        onResult = {},
        onNextPage = {},
        onRetry = {},
        recent = TvRecentSearchActions(onSearch = {}, onEdit = onEdit, onRetry = {}),
    )

    private fun resultVideo(id: Long, name: String) = FilesItem(
        id = FilesItemId(id),
        parentId = FilesItemId(0L),
        name = name,
        type = PutioFileType.VIDEO,
        sizeBytes = 1_000L,
        createdAt = "2026-04-20T10:00:00Z",
    )

    private fun trashRow(id: Long) = TrashItem(
        id = FilesItemId(id),
        parentId = FilesFolder.Root.id,
        name = "trash-$id.txt",
        sizeBytes = 64L,
        type = PutioFileType.TEXT,
        deletedAt = "2026-09-12T10:00:00",
        expirationDate = null,
    )

    private fun historyRow(id: Long, clock: Clock) = HistoryItem(
        HistoryEventId(id),
        clock.instant().minusSeconds(id * 60).toString().removeSuffix("Z"),
        HistoryEventKind.File(HistoryFileId(id), "hist-$id.mkv"),
    )
}

private class FocusMap(val map: MutableMap<Long, Long>)
