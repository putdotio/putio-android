package io.putdotio.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import io.putdotio.android.design.putioTvDarkColorScheme
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Modifier
import io.putdotio.android.files.FilesBrowserEvent
import androidx.compose.ui.focus.focusRequester
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.tv.TvLinkScreen
import io.putdotio.android.tv.files.TvFilesScreen
import io.putdotio.sdk.files.PutioFileType
import io.putdotio.android.tv.TvShell
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.android.tv.auth.TvLinkPhase
import io.putdotio.android.tv.auth.TvLinkStop
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Local (JVM) proof of the two TV screens: the device-code screen shows the
 * code and put.io/link, and the signed-in shell lists the four drawer
 * destinations with focus starting in the pane. The on-device counterpart is
 * LaunchSmokeTest plus the harness recording.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television")
class TvShellTest {

    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun linkScreenShowsCodeAndLinkUrlWithNewCodeFocused() {
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvLinkScreen(phase = TvLinkPhase.AwaitingLink("GIQTKN"), sessionExpired = false, onRequestNewCode = {})
            }
        }

        compose.onNodeWithContentDescription("Activation code GIQTKN").assertIsDisplayed()
        compose.onNodeWithText("put.io/link").assertIsDisplayed()
        compose.onNodeWithText("Get new code").assertIsFocused()
    }

    @Test
    fun anExpiredSessionIsExplainedWhileTheNewCodeIsLive() {
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvLinkScreen(phase = TvLinkPhase.AwaitingLink("GIQTKN"), sessionExpired = true, onRequestNewCode = {})
            }
        }

        compose.onNodeWithText("Your session expired. Sign in again to continue.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Activation code GIQTKN").assertIsDisplayed()
    }

    @Test
    fun expiredCodeExplainsAndOffersANewOne() {
        var requests = 0
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvLinkScreen(
                    phase = TvLinkPhase.Stopped(TvLinkStop.CodeExpired),
                    sessionExpired = true,
                    onRequestNewCode = { requests += 1 },
                )
            }
        }

        compose.onNodeWithText("Your session expired. Sign in again to continue.").assertIsDisplayed()
        compose.onNodeWithText("That code expired. Get a new one to continue.").assertIsDisplayed()
        compose.onNodeWithText("Get new code").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(1, requests)
    }

    @Test
    fun returningToFilesLandsOnTheRowThatHeldFocus() {
        val memory = mutableMapOf<Long, Long>()
        val files = FilesBrowserState(
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
        val files = FilesBrowserState(
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
                        TvFilesScreen(state = files, onEvent = { true }, onPlayMedia = {}, modifier = Modifier.focusRequester(paneFocus))
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
    fun hardwareBackPopsTheFolderOnlyWhileFilesIsShowing() {
        val events = mutableListOf<FilesBrowserEvent>()
        val nested = FilesBrowserState(
            stack = listOf(
                FilesFolderState(FilesFolder.Root, FilesContent.Ready(listOf(row(1, "Movies")), FilesPaging.Complete)),
                FilesFolderState(FilesFolder(FilesItemId(1), "Movies"), FilesContent.Ready(listOf(row(2, "inner.txt")), FilesPaging.Complete)),
            ),
            nextRequestValue = 10L,
        )
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvShell(
                    account = TvAccount(userId = 1, username = "user", email = "user@example.com"),
                    onSignOut = {},
                    filesPane = { paneFocus ->
                        BackHandler(enabled = nested.canNavigateBack) { events += FilesBrowserEvent.NavigateBack }
                        TvFilesScreen(state = nested, onEvent = { events += it; true }, onPlayMedia = {}, modifier = Modifier.focusRequester(paneFocus))
                    },
                )
            }
        }
        compose.onNodeWithContentDescription("inner.txt").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionLeft)
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onNodeWithText("Sign out").assertIsFocused()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(emptyList<FilesBrowserEvent>(), events)

        compose.onNodeWithText("Sign out").performKeyInput {
            pressKey(Key.DirectionLeft)
            pressKey(Key.DirectionUp)
            pressKey(Key.DirectionUp)
            pressKey(Key.DirectionUp)
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onNodeWithContentDescription("inner.txt").assertIsFocused()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.NavigateBack), events)
    }

    private fun row(id: Long, name: String) = FilesItem(
        id = FilesItemId(id),
        parentId = FilesFolder.Root.id,
        name = name,
        type = PutioFileType.TEXT,
        sizeBytes = 1L,
        createdAt = "2026-04-20T10:00:00Z",
    )

    @Test
    fun shellListsDestinationsAndDpadMovesBetweenDrawerAndPane() {
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvShell(account = TvAccount(userId = 1, username = "user", email = "user@example.com"), onSignOut = {})
            }
        }

        compose.onNodeWithText("put.io").assertIsDisplayed()
        compose.onNodeWithText("Your files will show up here.").assertIsFocused()

        compose.onNodeWithText("Your files will show up here.").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNode(hasText("Files") and hasClickAction()).assertIsFocused()
        listOf("Files", "Search", "History", "Account").forEach {
            compose.onNode(hasText(it) and hasClickAction()).assertIsDisplayed()
        }

        compose.onNode(hasText("Files") and hasClickAction()).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.onNode(hasText("Account") and hasClickAction()).assertIsFocused()
        compose.onNode(hasText("Account") and hasClickAction()).performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onNodeWithText("Signed in as user").assertIsDisplayed()
        compose.onNodeWithText("Sign out").assertIsFocused()
    }
}
