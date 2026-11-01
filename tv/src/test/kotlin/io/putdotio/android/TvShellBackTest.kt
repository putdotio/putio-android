package io.putdotio.android

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTvTheme
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.filesBrowserState
import io.putdotio.android.history.HistoryContent
import io.putdotio.android.history.HistoryEventId
import io.putdotio.android.history.HistoryEventKind
import io.putdotio.android.history.HistoryFileId
import io.putdotio.android.history.HistoryItem
import io.putdotio.android.history.HistoryPaging
import io.putdotio.android.history.historyState
import io.putdotio.android.search.SearchContent
import io.putdotio.android.search.SearchPaging
import io.putdotio.android.search.SearchTerm
import io.putdotio.android.search.searchState
import io.putdotio.android.settings.AccountSettingsEvent
import io.putdotio.android.settings.AccountSettingsPreferences
import io.putdotio.android.settings.AccountSettingsReducer
import io.putdotio.android.settings.AccountSettingsRequestId
import io.putdotio.android.settings.AndroidAppConfigReducer
import io.putdotio.android.trash.TrashContent
import io.putdotio.android.trash.TrashItem
import io.putdotio.android.trash.TrashState
import io.putdotio.android.tv.TvDestination
import io.putdotio.android.tv.TvPickedRow
import io.putdotio.android.tv.TvShell
import io.putdotio.android.tv.account.TvAccountScreen
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.android.tv.files.TvFilesScreen
import io.putdotio.android.tv.history.TvHistoryScreen
import io.putdotio.android.tv.pressCenter
import io.putdotio.android.tv.search.TV_SEARCH_FIELD_TAG
import io.putdotio.android.tv.search.TvRecentSearchActions
import io.putdotio.android.tv.search.TvSearchActions
import io.putdotio.android.tv.search.TvSearchScreen
import io.putdotio.android.tv.setTvContent
import io.putdotio.android.tv.trash.TvTrashScreen
import io.putdotio.sdk.files.PutioFileType
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television")
class TvShellBackTest {

    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun backPopsTheFolderThenFocusesTheTabBarThenGoesHomeThenLeaves() {
        val events = mutableListOf<FilesBrowserEvent>()
        var exits = 0
        val nested = filesBrowserState(
            stack = listOf(
                FilesFolderState(
                    FilesFolder.Root,
                    FilesContent.Ready(listOf(row(1, "Sample folder")), FilesPaging.Complete),
                ),
                FilesFolderState(
                    FilesFolder(FilesItemId(1), "Sample folder"),
                    FilesContent.Ready(listOf(row(2, "inner.txt")), FilesPaging.Complete),
                ),
            ),
            nextRequestValue = 10L,
        )
        var popped by mutableStateOf(false)
        val root = filesBrowserState(stack = nested.stack.take(1), nextRequestValue = 10L)
        setShell(
            files = { if (popped) root else nested },
            onFilesBack = {
                events += FilesBrowserEvent.NavigateBack
                popped = true
            },
            onExit = { exits += 1 },
        )
        compose.openTab(TvDestination.Files)
        compose.onNodeWithText("Your Files").assertIsDisplayed()
        compose.onNodeWithText("Sample folder").assertIsDisplayed()
        compose.onNodeWithContentDescription("inner.txt").assertIsFocused()

        compose.pressBack()
        assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.NavigateBack), events)
        compose.onNodeWithText("Your Files").assertDoesNotExist()
        compose.onNodeWithContentDescription("Sample folder").assertIsFocused()

        compose.pressBack()
        compose.onTab("Files").assertIsFocused()
        compose.pressBack()
        compose.onTab("Home").assertIsFocused().assertIsSelected()
        assertEquals(0, exits)

        compose.pressBack()
        assertEquals(1, exits)
        assertEquals(1, events.size)
    }

    @Test
    fun anEmptyNestedFolderTakesFocusSoBackLeavesIt() {
        val events = mutableListOf<FilesBrowserEvent>()
        val nested = filesBrowserState(
            stack = listOf(
                FilesFolderState(
                    FilesFolder.Root,
                    FilesContent.Ready(listOf(row(1, "Sample folder")), FilesPaging.Complete),
                ),
                FilesFolderState(
                    FilesFolder(FilesItemId(1), "Sample folder"),
                    FilesContent.Empty(FilesPaging.Complete),
                ),
            ),
            nextRequestValue = 10L,
        )
        var popped by mutableStateOf(false)
        val root = filesBrowserState(stack = nested.stack.take(1), nextRequestValue = 10L)
        setShell(
            files = { if (popped) root else nested },
            onFilesBack = {
                events += FilesBrowserEvent.NavigateBack
                popped = true
            },
        )
        compose.openTab(TvDestination.Files)
        compose.onNodeWithText("Empty folder.").assertIsFocused()

        compose.pressBack()
        assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.NavigateBack), events)
        compose.onNodeWithContentDescription("Sample folder").assertIsFocused()
    }

    @Test
    fun backInTheTabBarGoesHomeWithoutPoppingTheFolderBehindIt() {
        val events = mutableListOf<FilesBrowserEvent>()
        val nested = filesBrowserState(
            stack = listOf(
                FilesFolderState(
                    FilesFolder.Root,
                    FilesContent.Ready(listOf(row(1, "Sample folder")), FilesPaging.Complete),
                ),
                FilesFolderState(
                    FilesFolder(FilesItemId(1), "Sample folder"),
                    FilesContent.Ready(listOf(row(2, "inner.txt")), FilesPaging.Complete),
                ),
            ),
            nextRequestValue = 10L,
        )
        setShell(files = { nested }, onFilesBack = { events += FilesBrowserEvent.NavigateBack })
        compose.openTab(TvDestination.Files)
        compose.onNodeWithContentDescription("inner.txt").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionUp)
            pressKey(Key.DirectionUp)
        }
        compose.onTab("Files").assertIsFocused()

        compose.pressBack()
        compose.onTab("Home").assertIsFocused()
        compose.onTab("Home").performKeyInput {
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithContentDescription("inner.txt").assertIsFocused()
        assertEquals(emptyList<FilesBrowserEvent>(), events)
    }

    @Test
    fun backClosesTheUnsupportedScreenBeforeLeavingThePane() {
        val files = filesBrowserState(
            stack = listOf(
                FilesFolderState(
                    FilesFolder.Root,
                    FilesContent.Ready(listOf(row(1, "notes.txt")), FilesPaging.Complete),
                ),
            ),
            nextRequestValue = 10L,
        )
        setShell(files = { files })
        compose.openTab(TvDestination.Files)
        compose.onNodeWithContentDescription("notes.txt").assertIsFocused().performKeyInput { pressCenter() }
        compose.onNodeWithText("Go back").assertIsFocused()

        compose.pressBack()
        compose.onNodeWithContentDescription("notes.txt").assertIsFocused()
        compose.pressBack()
        compose.onTab("Files").assertIsFocused()
    }

    @Test
    fun aTabKeepsItsOwnBackStackWhileAnotherTabShows() {
        compose.setTvContent {
            TvShell(
                account = TvAccount(userId = 1, username = "user", email = "user@example.com"),
                onSignOut = {},
                accountPane = { paneFocus ->
                    TvAccountScreen(
                        account = TvAccount(userId = 1, username = "user", email = "user@example.com"),
                        settingsState = readySettings(),
                        appConfigState = AndroidAppConfigReducer.start().state,
                        onSettingsEvent = { true },
                        onAppConfigEvent = { true },
                        onSignOut = {},
                        paneFocus = paneFocus,
                        trashPane = { trashFocus ->
                            TvTrashScreen(
                                state = TrashState(content = TrashContent.Loaded(emptyList(), null, 0, 0)),
                                onEvent = { true },
                                modifier = Modifier.focusRequester(trashFocus),
                            )
                        },
                    )
                },
            )
        }
        compose.openTab(TvDestination.Account)
        compose.onNode(hasText("Library") and hasClickAction()).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionRight)
        }
        compose.onNodeWithText("Manage your trash").assertIsFocused().performKeyInput { pressCenter() }
        compose.onNodeWithText("Your trash is empty").assertIsDisplayed()
        compose.onNode(hasText("Restore all") and hasClickAction()).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionUp)
        }

        compose.onTab("Account").assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onTab("History").assertIsFocused()
        compose.onNodeWithText("Your trash is empty").assertDoesNotExist()
        compose.pressBack()
        compose.onTab("Home").assertIsFocused().performKeyInput {
            repeat(TvDestination.Account.ordinal) { pressKey(Key.DirectionRight) }
        }
        compose.onNodeWithText("Your trash is empty").assertIsDisplayed()
        compose.onTab("Account").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNode(hasText("Restore all") and hasClickAction()).assertIsFocused()

        compose.pressBack()
        compose.onNodeWithText("Manage your trash").assertIsFocused()
        compose.pressBack()
        compose.onTab("Account").assertIsFocused()
    }

    @Test
    fun anotherTabAndBackKeepTheFilesRowThatHeldFocus() {
        val memory = mutableMapOf<Long, Long>()
        val files = filesBrowserState(
            stack = listOf(
                FilesFolderState(
                    folder = FilesFolder.Root,
                    content = FilesContent.Ready(
                        listOf(row(1, "first.txt"), row(2, "second.txt"), row(3, "third.txt")),
                        FilesPaging.Complete,
                    ),
                ),
            ),
            nextRequestValue = 10L,
        )
        setShell(files = { files }, memory = memory)
        compose.openTab(TvDestination.Files)
        compose.onNodeWithContentDescription("first.txt").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithContentDescription("third.txt").assertIsFocused()
        listOf(TvDestination.Search, TvDestination.History, TvDestination.Account).forEach { other ->
            compose.pressBack()
            compose.onTab("Files").assertIsFocused().performKeyInput {
                repeat(other.ordinal - TvDestination.Files.ordinal) { pressKey(Key.DirectionRight) }
            }
            compose.onTab(other.name).assertIsFocused().assertIsSelected()
            compose.pressBack()
            compose.onTab("Home").assertIsFocused().performKeyInput {
                pressKey(Key.DirectionRight)
                pressKey(Key.DirectionDown)
            }
            compose.onNodeWithContentDescription("third.txt").assertIsFocused()
        }
    }

    @Test
    fun anotherTabAndBackKeepTheSearchSectionOrResultThatHeldFocus() {
        val results = (1L..5L).map { id -> row(id, "clip $id.mp4").copy(type = PutioFileType.VIDEO) }
        val picked = TvPickedRow()
        compose.setTvContent {
            TvShell(
                account = TvAccount(userId = 1, username = "user", email = "user@example.com"),
                onSignOut = {},
                searchPane = { paneFocus ->
                    TvSearchScreen(
                        state = searchState(
                            query = "clip",
                            content = SearchContent.Ready(SearchTerm("clip"), results, SearchPaging.Complete),
                            recentTerms = emptyList(),
                            nextRequestValue = 2L,
                        ),
                        actions = TvSearchActions({}, {}, {}, {}, {}, TvRecentSearchActions({}, {}, {})),
                        modifier = Modifier.focusRequester(paneFocus),
                        pickedRow = picked,
                    )
                },
            )
        }
        compose.openTab(TvDestination.Search)
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).assertIsFocused().performKeyInput {
            repeat(3) { pressKey(Key.DirectionDown) }
        }
        compose.onNodeWithContentDescription("Play clip 3.mp4").assertIsFocused()
        compose.pressBack()
        visitNeighbourAndReturn("Search", TvDestination.History)
        compose.onNodeWithContentDescription("Play clip 3.mp4").assertIsFocused().performKeyInput {
            repeat(3) { pressKey(Key.DirectionUp) }
        }
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionUp)
        }
        visitNeighbourAndReturn("Search", TvDestination.History)
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).assertIsFocused()
    }

    @Test
    fun anotherTabAndBackKeepTheHistoryRowOrClearThatHeldFocus() {
        val now = Instant.parse("2026-09-30T12:00:00Z")
        val picked = TvPickedRow()
        val events = (1L..3L).map { id ->
            HistoryItem(
                HistoryEventId(id),
                "2026-09-30T11:5$id:00",
                HistoryEventKind.File(HistoryFileId(100 + id), "clip $id.mp4"),
            )
        }
        compose.setTvContent {
            TvShell(
                account = TvAccount(userId = 1, username = "user", email = "user@example.com"),
                onSignOut = {},
                historyPane = { paneFocus ->
                    TvHistoryScreen(
                        state = historyState(HistoryContent.Ready(events, HistoryPaging.Complete)),
                        onEvent = { true },
                        modifier = Modifier.focusRequester(paneFocus),
                        clock = Clock.fixed(now, ZoneOffset.UTC),
                        pickedRow = picked,
                    )
                },
            )
        }
        compose.openTab(TvDestination.History)
        compose.onNodeWithContentDescription("Open clip 1.mp4").assertIsFocused().performKeyInput {
            repeat(2) { pressKey(Key.DirectionDown) }
        }
        compose.onNodeWithContentDescription("Open clip 3.mp4").assertIsFocused()
        compose.pressBack()
        visitNeighbourAndReturn("History", TvDestination.Search)
        compose.onNodeWithContentDescription("Open clip 3.mp4").assertIsFocused().performKeyInput {
            repeat(3) { pressKey(Key.DirectionUp) }
        }
        compose.onNode(hasText("Clear") and hasClickAction()).assertIsFocused()
        compose.pressBack()
        visitNeighbourAndReturn("History", TvDestination.Account)
        compose.onNode(hasText("Clear") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun anotherTabAndBackKeepTheAccountRowThatHeldFocus() {
        compose.setTvContent {
            TvShell(
                account = TvAccount(userId = 1, username = "user", email = "user@example.com"),
                onSignOut = {},
                accountPane = { paneFocus ->
                    TvAccountScreen(
                        account = TvAccount(userId = 1, username = "user", email = "user@example.com"),
                        settingsState = readySettings(),
                        appConfigState = AndroidAppConfigReducer.start().state,
                        onSettingsEvent = { true },
                        onAppConfigEvent = { true },
                        onSignOut = {},
                        paneFocus = paneFocus,
                    )
                },
            )
        }
        compose.openTab(TvDestination.Account)
        compose.onNode(hasText("Library") and hasClickAction()).assertIsFocused().performKeyInput {
            repeat(2) { pressKey(Key.DirectionDown) }
            pressKey(Key.DirectionRight)
        }
        compose.onNodeWithText("Show subtitles").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Pick matching subtitles").assertIsFocused()
        compose.pressBack()
        visitNeighbourAndReturn("Account", TvDestination.History)
        compose.onNodeWithText("Pick matching subtitles").assertIsFocused()
    }

    @Test
    fun anotherTabAndBackKeepTheTrashRowOrRestoreAllThatHeldFocus() {
        val items = (1L..3L).map { id ->
            TrashItem(
                FilesItemId(id), FilesItemId(100 + id), "trashed-$id.txt", PutioFileType.TEXT, 10,
                "2026-09-06T10:00:00", "2026-09-20T10:00:00",
            )
        }
        var requested by mutableStateOf<TvDestination?>(null)
        compose.setTvContent {
            TvShell(
                account = TvAccount(userId = 1, username = "user", email = "user@example.com"),
                onSignOut = {},
                accountPane = { paneFocus ->
                    TvAccountScreen(
                        account = TvAccount(userId = 1, username = "user", email = "user@example.com"),
                        settingsState = readySettings(),
                        appConfigState = AndroidAppConfigReducer.start().state,
                        onSettingsEvent = { true },
                        onAppConfigEvent = { true },
                        onSignOut = {},
                        paneFocus = paneFocus,
                        trashPane = { trashFocus ->
                            TvTrashScreen(
                                state = TrashState(content = TrashContent.Loaded(items, null, 3, 30)),
                                onEvent = { true },
                                modifier = Modifier.focusRequester(trashFocus),
                            )
                        },
                    )
                },
                requestedDestination = requested,
                onDestinationRequestHandled = { requested = null },
            )
        }
        compose.openTab(TvDestination.Account)
        compose.onNode(hasText("Library") and hasClickAction()).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionRight)
        }
        compose.onNodeWithText("Manage your trash").assertIsFocused().performKeyInput { pressCenter() }
        compose.onNode(hasText("trashed-1.txt") and hasClickAction()).assertIsFocused().performKeyInput {
            repeat(2) { pressKey(Key.DirectionDown) }
        }
        compose.onNode(hasText("trashed-3.txt") and hasClickAction()).assertIsFocused()

        compose.runOnIdle { requested = TvDestination.History }
        compose.waitForIdle()
        compose.pressBack()
        compose.onTab("History").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onTab("Account").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNode(hasText("trashed-3.txt") and hasClickAction()).assertIsFocused().performKeyInput {
            repeat(3) { pressKey(Key.DirectionUp) }
        }
        compose.onNode(hasText("Restore all") and hasClickAction()).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionUp)
        }
        visitNeighbourAndReturn("Account", TvDestination.History)
        compose.onNode(hasText("Restore all") and hasClickAction()).assertIsFocused()
    }

    private fun visitNeighbourAndReturn(tab: String, neighbour: TvDestination) {
        val forward = neighbour.ordinal > TvDestination.valueOf(tab).ordinal
        compose.onTab(tab).assertIsFocused().performKeyInput {
            pressKey(if (forward) Key.DirectionRight else Key.DirectionLeft)
        }
        compose.onTab(neighbour.name).assertIsFocused().assertIsSelected().performKeyInput {
            pressKey(if (forward) Key.DirectionLeft else Key.DirectionRight)
        }
        compose.onTab(tab).assertIsFocused().assertIsSelected().performKeyInput { pressKey(Key.DirectionDown) }
    }

    private fun setShell(
        files: () -> FilesBrowserState,
        memory: MutableMap<Long, Long> = mutableMapOf(),
        onFilesBack: (() -> Unit)? = null,
        onExit: () -> Unit = {},
    ) {
        compose.setContent {
            // Registered first, so it stands in for the system leaving the app.
            BackHandler(onBack = onExit)
            PutioTvTheme {
                TvShell(
                    account = TvAccount(userId = 1, username = "user", email = "user@example.com"),
                    onSignOut = {},
                    onFilesBack = onFilesBack.takeIf { files().canNavigateBack },
                    filesPane = { paneFocus ->
                        TvFilesScreen(
                            state = files(),
                            onEvent = { true },
                            onPlayMedia = {},
                            modifier = Modifier.focusRequester(paneFocus),
                            focusMemory = memory,
                        )
                    },
                )
            }
        }
    }

    private fun readySettings() =
        AccountSettingsReducer.reduce(
            AccountSettingsReducer.start().state,
            AccountSettingsEvent.LoadSucceeded(
                AccountSettingsRequestId(1),
                AccountSettingsPreferences(
                    historyEnabled = true,
                    trashEnabled = true,
                    showSubtitles = true,
                    autoSelectSubtitles = true,
                ),
            ),
        ).state
}
