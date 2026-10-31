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
import io.putdotio.android.settings.AccountSettingsEvent
import io.putdotio.android.settings.AccountSettingsPreferences
import io.putdotio.android.settings.AccountSettingsReducer
import io.putdotio.android.settings.AccountSettingsRequestId
import io.putdotio.android.settings.AndroidAppConfigReducer
import io.putdotio.android.trash.TrashContent
import io.putdotio.android.trash.TrashState
import io.putdotio.android.tv.TvDestination
import io.putdotio.android.tv.TvShell
import io.putdotio.android.tv.account.TvAccountScreen
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.android.tv.files.TvFilesScreen
import io.putdotio.android.tv.pressCenter
import io.putdotio.android.tv.setTvContent
import io.putdotio.android.tv.trash.TvTrashScreen
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
        compose.onNodeWithText("Choose your proxy").assertIsFocused().performKeyInput {
            repeat(5) { pressKey(Key.DirectionDown) }
        }
        compose.onNodeWithText("Manage your trash").assertIsFocused().performKeyInput { pressCenter() }
        compose.onNodeWithText("Your trash is empty").assertIsDisplayed()
        compose.onNode(hasText("Refresh") and hasClickAction()).assertIsFocused().performKeyInput {
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
        compose.onNode(hasText("Refresh") and hasClickAction()).assertIsFocused()

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
