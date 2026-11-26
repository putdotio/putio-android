package io.putdotio.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import io.putdotio.android.tv.setTvContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesItemResolver
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.FilesRequestId
import io.putdotio.android.history.HistoryContent
import io.putdotio.android.history.HistoryEventId
import io.putdotio.android.history.HistoryEventKind
import io.putdotio.android.history.HistoryFileId
import io.putdotio.android.history.HistoryItem
import io.putdotio.android.history.HistoryPaging
import io.putdotio.android.tv.TvDestination
import io.putdotio.android.tv.TvPickedRow
import io.putdotio.android.tv.TvShell
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.android.tv.files.TvFilesScreen
import io.putdotio.android.tv.history.TvHistoryFiles
import io.putdotio.android.tv.history.TvHistoryScreen
import io.putdotio.android.tv.player.TvMiniPlayer
import io.putdotio.android.tv.player.TvMiniPlayerState
import io.putdotio.sdk.errors.PutioConfigurationException
import androidx.compose.ui.test.onAllNodesWithText
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import io.putdotio.android.files.filesBrowserState
import io.putdotio.android.history.historyState

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television")
class TvShellFocusTest {

    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val pageFailed =
        FilesPaging.Failed(FilesCursor("c"), PutioFailure.NetworkUnavailable(PutioConfigurationException("x")))

    @Test
    fun tabBarRoundTripWithoutLeavingFilesReturnsToTheLiveRow() {
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
        compose.setTvContent {
            TvShell(
                account = TvAccount(userId = 1, username = "user", email = "user@example.com"),
                onSignOut = {},
                filesPane = { paneFocus ->
                    TvFilesScreen(
                        state = files,
                        onEvent = { true },
                        onPlayMedia = {},
                        modifier = Modifier.focusRequester(paneFocus),
                    )
                },
            )
        }
        compose.openTab(TvDestination.Files)
        compose.onNodeWithContentDescription("first.txt").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.pressBack()
        compose.onTab("Files").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithContentDescription("third.txt").assertIsFocused()
    }

    @Test
    fun tabBarRoundTripOnAnEmptyPageReturnsToItsPagingControl() {
        val files = filesBrowserState(
            stack = listOf(
                FilesFolderState(FilesFolder.Root, FilesContent.Empty(pageFailed)),
            ),
            nextRequestValue = 10L,
        )
        compose.setTvContent {
            TvShell(
                account = TvAccount(userId = 1, username = "user", email = "user@example.com"),
                onSignOut = {},
                filesPane = { paneFocus ->
                    TvFilesScreen(
                        state = files,
                        onEvent = { true },
                        onPlayMedia = {},
                        modifier = Modifier.focusRequester(paneFocus),
                    )
                },
            )
        }
        compose.openTab(TvDestination.Files)
        compose.onNodeWithText("Try again").assertIsFocused()
        compose.pressBack()
        compose.onTab("Files").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Try again").assertIsFocused()
    }

    @Test
    fun tabBarRoundTripFromTryAgainReturnsToTryAgain() {
        val files = filesBrowserState(
            stack = listOf(
                FilesFolderState(
                    FilesFolder.Root,
                    FilesContent.Ready(listOf(row(1, "first.txt")), pageFailed),
                ),
            ),
            nextRequestValue = 10L,
        )
        compose.setTvContent {
            TvShell(
                account = TvAccount(userId = 1, username = "user", email = "user@example.com"),
                onSignOut = {},
                filesPane = { paneFocus ->
                    TvFilesScreen(
                        state = files,
                        onEvent = { true },
                        onPlayMedia = {},
                        modifier = Modifier.focusRequester(paneFocus),
                    )
                },
            )
        }
        compose.openTab(TvDestination.Files)
        compose.onNodeWithContentDescription("first.txt")
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Try again").assertIsFocused()
        compose.pressBack()
        compose.onTab("Files").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Try again").assertIsFocused()
    }

    @Test
    fun theLastPageLandingWhileOnTheTabBarDoesNotStealFocus() {
        var files by mutableStateOf(
            filesBrowserState(
                stack = listOf(
                    FilesFolderState(
                        FilesFolder.Root,
                        FilesContent.Ready(listOf(row(1, "first.txt")), pageFailed),
                    ),
                ),
                nextRequestValue = 10L,
            ),
        )
        compose.setTvContent {
            TvShell(
                account = TvAccount(userId = 1, username = "user", email = "user@example.com"),
                onSignOut = {},
                filesPane = { paneFocus ->
                    TvFilesScreen(
                        state = files,
                        onEvent = { true },
                        onPlayMedia = {},
                        modifier = Modifier.focusRequester(paneFocus),
                    )
                },
            )
        }
        compose.openTab(TvDestination.Files)
        compose.onNodeWithContentDescription("first.txt").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Try again").assertIsFocused()
        compose.pressBack()
        compose.onTab("Files").assertIsFocused()

        files = filesBrowserState(
            stack = listOf(
                FilesFolderState(
                    FilesFolder.Root,
                    FilesContent.Ready((1..30L).map { row(it, "file-$it.txt") }, FilesPaging.Complete),
                ),
            ),
            nextRequestValue = 11L,
        )
        compose.waitForIdle()
        compose.onTab("Files")
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithContentDescription("file-30.txt").assertIsFocused()
    }

    @Test
    fun aLoadingPaneHoldsFocusOnItsSkeletonUntilTheRowsArrive() {
        val ready = filesBrowserState(
            stack = listOf(
                FilesFolderState(
                    FilesFolder.Root,
                    FilesContent.Ready(listOf(row(1, "first.txt")), FilesPaging.Complete),
                ),
            ),
            nextRequestValue = 10L,
        )
        val loading = filesBrowserState(
            stack = listOf(FilesFolderState(FilesFolder.Root, FilesContent.Loading(FilesRequestId(11)))),
            nextRequestValue = 12L,
        )
        var files by mutableStateOf(ready)
        compose.setTvContent {
            TvShell(
                account = TvAccount(userId = 1, username = "user", email = "user@example.com"),
                onSignOut = {},
                filesPane = { paneFocus ->
                    TvFilesScreen(
                        state = files,
                        onEvent = { true },
                        onPlayMedia = {},
                        modifier = Modifier.focusRequester(paneFocus),
                    )
                },
            )
        }
        compose.openTab(TvDestination.Files)
        compose.onNodeWithContentDescription("first.txt").assertIsFocused()

        files = loading
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Loading").assertIsFocused()

        files = ready
        compose.waitForIdle()
        compose.onNodeWithContentDescription("first.txt").assertIsFocused()
    }

    @Test
    fun aFolderThatFinishesLoadingWhileOnTheTabBarDoesNotStealFocus() {
        val loading = filesBrowserState(
            stack = listOf(FilesFolderState(FilesFolder.Root, FilesContent.Loading(FilesRequestId(1)))),
            nextRequestValue = 10L,
        )
        val settled = listOf<Pair<FilesContent, () -> SemanticsNodeInteraction>>(
            FilesContent.Ready(listOf(row(1, "first.txt")), FilesPaging.Complete) to
                { compose.onNodeWithContentDescription("first.txt") },
            FilesContent.Failed(PutioFailure.NetworkUnavailable(PutioConfigurationException("x"))) to
                { compose.onNodeWithText("Try now") },
        )
        var mount by mutableIntStateOf(0)
        var files by mutableStateOf(loading)
        compose.setTvContent {
            key(mount) {
                TvShell(
                    account = TvAccount(userId = 1, username = "user", email = "user@example.com"),
                    onSignOut = {},
                    filesPane = { paneFocus ->
                        TvFilesScreen(
                            state = files,
                            onEvent = { true },
                            onPlayMedia = {},
                            modifier = Modifier.focusRequester(paneFocus),
                        )
                    },
                )
            }
        }
        settled.forEachIndexed { index, (content, rightTarget) ->
            compose.runOnIdle {
                mount = index
                files = loading
            }
            compose.openTab(TvDestination.Files)
            compose.onNodeWithContentDescription("Loading").assertIsFocused()
            compose.pressBack()
            compose.onTab("Files").assertIsFocused()

            files = filesBrowserState(
                stack = listOf(FilesFolderState(FilesFolder.Root, content)),
                nextRequestValue = 11L,
            )
            compose.waitForIdle()
            compose.onTab("Files")
                .assertIsFocused()
                .performKeyInput { pressKey(Key.DirectionDown) }
            rightTarget().assertIsFocused()
        }
    }

    @Test
    fun anEmptyHistoryHandsFocusToItsTabAndDownStaysThere() {
        var history by mutableStateOf(
            historyState(
                HistoryContent.Ready(
                    listOf(
                        HistoryItem(
                            HistoryEventId(1),
                            "2026-09-10T10:00:00",
                            HistoryEventKind.File(HistoryFileId(5), "Sintel.mp4"),
                        ),
                    ),
                    HistoryPaging.Complete,
                ),
            ),
        )
        compose.setTvContent {
            TvShell(
                account = TvAccount(userId = 1, username = "user", email = "user@example.com"),
                onSignOut = {},
                historyPane = { paneFocus ->
                    TvHistoryScreen(
                        state = history,
                        onEvent = { true },
                        modifier = Modifier.focusRequester(paneFocus),
                    )
                },
            )
        }
        compose.openTab(TvDestination.History)
        compose.onNodeWithContentDescription("Open Sintel.mp4").assertIsFocused()

        compose.runOnIdle { history = historyState(HistoryContent.Empty) }
        compose.onNodeWithText("No activity yet.").assertIsDisplayed()
        compose.onNodeWithText("Completed transfers and shared files will appear here.").assertIsDisplayed()
        compose.onTab("History").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
        }
        compose.onTab("History").assertIsFocused()
    }

    @Test
    fun historyTabBarRoundTripReturnsToTheLiveFocusedRow() {
        val now = Instant.parse("2026-09-30T12:00:00Z")
        val events = (1L..12L).map { id ->
            HistoryItem(
                HistoryEventId(id),
                "2026-09-30T${(11 - id / 6).toString().padStart(2, '0')}:${(59 - id).toString().padStart(2, '0')}:00",
                HistoryEventKind.File(HistoryFileId(100 + id), "clip $id.mp4"),
            )
        }
        val picked = TvPickedRow().apply { pick(2L) }
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
        compose.onNodeWithContentDescription("Open clip 2.mp4").assertIsFocused().performKeyInput {
            repeat(5) { pressKey(Key.DirectionDown) }
        }
        compose.onNodeWithContentDescription("Open clip 7.mp4").assertIsFocused()
        compose.pressBack()
        compose.onTab("History").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithContentDescription("Open clip 7.mp4").assertIsFocused()
    }

    @Test
    fun historyGoneRowRightDoesNotEscapeToMiniPlayer() {
        val now = Instant.parse("2026-09-30T12:00:00Z")
        val events = listOf(
            HistoryItem(
                HistoryEventId(1L),
                "2026-09-30T11:58:00",
                HistoryEventKind.File(HistoryFileId(101L), "gone.mp4"),
            ),
        )
        val gone = object : FilesItemResolver {
            override suspend fun resolveItem(itemId: FilesItemId) =
                PutioResult.Failure(PutioFailure.ApiRejected(404, null, PutioConfigurationException("gone"), 404))
        }
        compose.setTvContent {
            TvShell(
                account = TvAccount(userId = 1, username = "user", email = "user@example.com"),
                onSignOut = {},
                trailing = { TvMiniPlayer(title = "song.mp3", state = TvMiniPlayerState(), onOpen = {}) },
                historyPane = { paneFocus ->
                    TvHistoryScreen(
                        state = historyState(HistoryContent.Ready(events, HistoryPaging.Complete)),
                        onEvent = { true },
                        modifier = Modifier.focusRequester(paneFocus),
                        clock = Clock.fixed(now, ZoneOffset.UTC),
                        files = TvHistoryFiles(gone),
                    )
                },
            )
        }
        compose.openTab(TvDestination.History)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("No longer in your files").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Open gone.mp4").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionRight)
        }
        compose.onNodeWithContentDescription("Open gone.mp4").assertIsFocused()
    }
}
