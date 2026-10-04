package io.putdotio.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import io.putdotio.android.design.putioTvDarkColorScheme
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.FilesRequestId
import io.putdotio.android.history.HistoryContent
import io.putdotio.android.history.HistoryEventId
import io.putdotio.android.history.HistoryEventKind
import io.putdotio.android.history.HistoryFileId
import io.putdotio.android.history.HistoryItem
import io.putdotio.android.history.HistoryPaging
import io.putdotio.android.tv.TvShell
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.android.tv.files.TvFilesScreen
import io.putdotio.android.tv.history.TvHistoryScreen
import io.putdotio.sdk.errors.PutioConfigurationException
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

    @Test
    fun returningToFilesLandsOnTheRowThatHeldFocus() {
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
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvShell(
                    account = TvAccount(userId = 1, username = "user", email = "user@example.com"),
                    onSignOut = {},
                    filesPane = { paneFocus ->
                        TvFilesScreen(
                            state = files,
                            onEvent = { true },
                            onPlayMedia = {},
                            modifier = Modifier.focusRequester(paneFocus),
                            focusMemory = memory,
                        )
                    },
                )
            }
        }
        compose.onNodeWithContentDescription("first.txt").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithContentDescription("third.txt").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        compose.onNode(hasText("Files") and hasClickAction()).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onNodeWithText("Sign out").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        compose.onNode(hasText("Account") and hasClickAction()).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionUp)
            pressKey(Key.DirectionUp)
            pressKey(Key.DirectionUp)
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onNodeWithContentDescription("third.txt").assertIsFocused()
    }

    @Test
    fun railRoundTripWithoutLeavingFilesReturnsToTheLiveRow() {
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
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
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
        compose.onNodeWithContentDescription("first.txt").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionLeft)
        }
        compose.onNode(hasText("Files") and hasClickAction()).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionRight)
        }
        compose.onNodeWithContentDescription("third.txt").assertIsFocused()
    }

    @Test
    fun railRoundTripOnAnEmptyPageReturnsToItsPagingControl() {
        val files = filesBrowserState(
            stack = listOf(
                FilesFolderState(FilesFolder.Root, FilesContent.Empty(FilesPaging.Available(FilesCursor("c")))),
            ),
            nextRequestValue = 10L,
        )
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
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
        compose.onNodeWithText("Load more").assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNode(hasText("Files") and hasClickAction()).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionRight)
        }
        compose.onNodeWithText("Load more").assertIsFocused()
    }

    @Test
    fun railRoundTripFromLoadMoreReturnsToLoadMore() {
        val files = filesBrowserState(
            stack = listOf(
                FilesFolderState(
                    FilesFolder.Root,
                    FilesContent.Ready(listOf(row(1, "first.txt")), FilesPaging.Available(FilesCursor("c"))),
                ),
            ),
            nextRequestValue = 10L,
        )
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
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
        compose.onNodeWithContentDescription("first.txt")
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Load more").assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNode(hasText("Files") and hasClickAction()).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionRight)
        }
        compose.onNodeWithText("Load more").assertIsFocused()
    }

    @Test
    fun theLastPageLandingWhileOnTheRailDoesNotStealFocus() {
        var files by mutableStateOf(
            filesBrowserState(
                stack = listOf(
                    FilesFolderState(
                        FilesFolder.Root,
                        FilesContent.Ready(listOf(row(1, "first.txt")), FilesPaging.Available(FilesCursor("c"))),
                    ),
                ),
                nextRequestValue = 10L,
            ),
        )
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
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
        compose.onNodeWithContentDescription("first.txt").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Load more").assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNode(hasText("Files") and hasClickAction()).assertIsFocused()

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
        compose.onNode(hasText("Files") and hasClickAction())
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithContentDescription("file-30.txt").assertIsFocused()
    }

    @Test
    fun aFolderThatFinishesLoadingWhileOnTheRailDoesNotStealFocus() {
        var files by mutableStateOf(
            filesBrowserState(
                stack = listOf(FilesFolderState(FilesFolder.Root, FilesContent.Loading(FilesRequestId(1)))),
                nextRequestValue = 10L,
            ),
        )
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
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
        compose.onNode(hasText("Refresh") and hasClickAction())
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNode(hasText("Files") and hasClickAction()).assertIsFocused()

        files = filesBrowserState(
            stack = listOf(
                FilesFolderState(
                    FilesFolder.Root,
                    FilesContent.Ready(listOf(row(1, "first.txt")), FilesPaging.Complete),
                ),
            ),
            nextRequestValue = 11L,
        )
        compose.waitForIdle()
        compose.onNode(hasText("Files") and hasClickAction())
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithContentDescription("first.txt").assertIsFocused()
    }

    @Test
    fun aFolderThatFailsWhileOnTheRailDoesNotStealFocus() {
        var files by mutableStateOf(
            filesBrowserState(
                stack = listOf(FilesFolderState(FilesFolder.Root, FilesContent.Loading(FilesRequestId(1)))),
                nextRequestValue = 10L,
            ),
        )
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
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
        compose.onNode(hasText("Refresh") and hasClickAction())
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNode(hasText("Files") and hasClickAction()).assertIsFocused()

        files = filesBrowserState(
            stack = listOf(
                FilesFolderState(
                    FilesFolder.Root,
                    FilesContent.Failed(FilesFailure.NetworkUnavailable(PutioConfigurationException("x"))),
                ),
            ),
            nextRequestValue = 11L,
        )
        compose.waitForIdle()
        compose.onNode(hasText("Files") and hasClickAction())
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Try again").assertIsFocused()
    }

    @Test
    fun historyRowsThatGoWhileTheUserIsOnTheDrawerLeaveClearAsTheEntryPoint() {
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
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
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
        }
        compose.onNodeWithText("Your files will show up here.").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNode(hasText("Files") and hasClickAction()).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onNodeWithContentDescription("Open Sintel.mp4").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        compose.onNode(hasText("History") and hasClickAction()).assertIsFocused()

        compose.runOnIdle { history = historyState(HistoryContent.Empty) }
        compose.onNodeWithText("No activity yet.").assertIsDisplayed()
        // The rows went while the drawer held focus: nothing pulled it back.
        compose.onNode(hasText("History") and hasClickAction()).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionRight)
        }
        compose.onNode(hasText("Clear") and hasClickAction()).assertIsFocused()
    }
}
