package io.putdotio.android

import androidx.activity.compose.BackHandler
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
import androidx.tv.material3.Text
import io.putdotio.android.design.putioTvDarkColorScheme
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.settings.AccountSettingsEvent
import io.putdotio.android.settings.AccountSettingsPreferences
import io.putdotio.android.settings.AccountSettingsReducer
import io.putdotio.android.settings.AccountSettingsRequestId
import io.putdotio.android.settings.AndroidAppConfigReducer
import io.putdotio.android.trash.TrashContent
import io.putdotio.android.trash.TrashState
import io.putdotio.android.tv.TvShell
import io.putdotio.android.tv.account.TvAccountScreen
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.android.tv.files.TvFilesScreen
import io.putdotio.android.tv.trash.TvTrashScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import io.putdotio.android.files.filesBrowserState

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television")
class TvShellBackTest {

    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun backOnTheRailReturnsToTheUnsupportedScreenBeforeDismissingIt() {
        val files = filesBrowserState(
            stack = listOf(
                FilesFolderState(
                    FilesFolder.Root,
                    FilesContent.Ready(listOf(row(1, "notes.txt")), FilesPaging.Complete),
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
        compose.onNodeWithContentDescription("notes.txt").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onNodeWithText("Go back").assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNode(hasText("Files") and hasClickAction()).assertIsFocused()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("Go back").assertIsFocused()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("notes.txt").assertIsFocused()
    }

    @Test
    fun backOnTheDrawerReturnsToTheFolderRowBeforePoppingIt() {
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
        compose.setContent {
            // Registered first, so it stands in for the system leaving the app.
            BackHandler { exits += 1 }
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvShell(
                    account = TvAccount(userId = 1, username = "user", email = "user@example.com"),
                    onSignOut = {},
                    onFilesBack = { events += FilesBrowserEvent.NavigateBack },
                    filesPane = { paneFocus ->
                        TvFilesScreen(
                            state = nested,
                            onEvent = { events += it; true },
                            onPlayMedia = {},
                            modifier = Modifier.focusRequester(paneFocus),
                        )
                    },
                )
            }
        }
        compose.onNodeWithContentDescription("inner.txt").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        compose.onNode(hasText("Files") and hasClickAction()).assertIsFocused()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("inner.txt").assertIsFocused()
        assertEquals(emptyList<FilesBrowserEvent>(), events)
        assertEquals(0, exits)

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.NavigateBack), events)
        assertEquals(0, exits)
    }

    @Test
    fun backFromAnotherDestinationKeepsTheFolderStackBehindIt() {
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
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvShell(
                    account = TvAccount(userId = 1, username = "user", email = "user@example.com"),
                    onSignOut = {},
                    onFilesBack = { events += FilesBrowserEvent.NavigateBack },
                    filesPane = { paneFocus ->
                        TvFilesScreen(
                            state = nested,
                            onEvent = { events += it; true },
                            onPlayMedia = {},
                            modifier = Modifier.focusRequester(paneFocus),
                        )
                    },
                )
            }
        }
        compose.onNodeWithContentDescription("inner.txt").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        compose.onNode(hasText("Files") and hasClickAction()).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onNodeWithText("Sign out").assertIsFocused()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("inner.txt").assertIsFocused()
        assertEquals(emptyList<FilesBrowserEvent>(), events)
    }

    @Test
    fun backOnTheDrawerOverAPaneWithNothingToFocusReturnsToFiles() {
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvShell(
                    account = TvAccount(userId = 1, username = "user", email = "user@example.com"),
                    onSignOut = {},
                    historyPane = { paneFocus -> Text("Nothing to focus here.", Modifier.focusRequester(paneFocus)) },
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
        compose.onNodeWithText("Nothing to focus here.").assertIsDisplayed()
        compose.onNode(hasText("History") and hasClickAction()).assertIsFocused()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("Your files will show up here.").assertIsFocused()
    }

    @Test
    fun backFromSearchHistoryAndAccountReturnsToTheFilesRowThatHeldFocus() {
        val memory = mutableMapOf<Long, Long>()
        var exits = 0
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
        setFilesShell(files, memory, onExit = { exits += 1 })
        compose.onNodeWithContentDescription("first.txt").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        // Back from the pane on Search, and from the drawer on History and Account. The drawer
        // restores focus to its last focused item, so the next destination is one step down.
        listOf(
            Triple("Search", "Search your files from here.", false),
            Triple("History", "Recent activity will show up here.", true),
            Triple("Account", "Sign out", true),
        ).zip(listOf("Files", "Search", "History")) { (destination, paneTarget, fromDrawer), drawerEntry ->
            compose.onNodeWithContentDescription("third.txt").assertIsFocused().performKeyInput {
                pressKey(Key.DirectionLeft)
            }
            compose.onNode(hasText(drawerEntry) and hasClickAction()).assertIsFocused().performKeyInput {
                pressKey(Key.DirectionDown)
                keyDown(Key.DirectionCenter)
                keyUp(Key.DirectionCenter)
            }
            compose.onNodeWithText(paneTarget).assertIsFocused()
            if (fromDrawer) {
                compose.onNodeWithText(paneTarget).performKeyInput { pressKey(Key.DirectionLeft) }
                compose.onNode(hasText(destination) and hasClickAction()).assertIsFocused()
            }
            if (fromDrawer) {
                // The drawer closes first, back on the pane's row.
                compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
                compose.waitForIdle()
                compose.onNodeWithText(paneTarget).assertIsFocused()
            }
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("third.txt").assertIsFocused()
        }
        assertEquals(0, exits)

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(1, exits)
    }

    @Test
    fun accountOpensTrashFromItsRowAndBackReturnsToThatRow() {
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
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
        }
        compose.onNodeWithText("Your files will show up here.").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNode(hasText("Files") and hasClickAction()).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onNodeWithText("Choose your proxy").assertIsFocused().performKeyInput {
            repeat(5) { pressKey(Key.DirectionDown) }
        }
        compose.onNodeWithText("Manage your trash").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onNodeWithText("Your trash is empty").assertIsDisplayed()
        compose.onNode(hasText("Refresh") and hasClickAction()).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        compose.onNode(hasText("Account") and hasClickAction()).assertIsFocused()
        // Back on the drawer returns to Trash; it does not close it behind the drawer.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNode(hasText("Refresh") and hasClickAction()).assertIsFocused()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Manage your trash").assertIsFocused()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Your files will show up here.").assertIsFocused()
    }

    private fun setFilesShell(files: FilesBrowserState, memory: MutableMap<Long, Long>, onExit: () -> Unit) {
        compose.setContent {
            // Registered first, so it stands in for the system leaving the app.
            BackHandler(onBack = onExit)
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
