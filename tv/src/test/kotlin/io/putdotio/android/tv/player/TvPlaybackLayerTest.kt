package io.putdotio.android.tv.player

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.design.putioTvDarkColorScheme
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.tv.TvShell
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.android.tv.files.TvFilesScreen
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class TvPlaybackLayerTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun selectingAVideoPlaysItAndBackReturnsToItsFilesRow() {
        val player = FakePlayer()
        val files = filesBrowserState(
            stack = listOf(
                FilesFolderState(
                    FilesFolder.Root,
                    FilesContent.Ready(
                        listOf(row(1, "notes.txt", PutioFileType.TEXT), row(9, "Sintel.mp4", PutioFileType.VIDEO)),
                        FilesPaging.Complete,
                    ),
                ),
            ),
            nextRequestValue = 10L,
        )
        // Owned by the session in the app, so it outlives the shell while playback shows.
        val focusMemory = mutableMapOf<Long, Long>()
        var playing by mutableStateOf<FilesItem?>(null)
        setFilesWithPlayback(files, focusMemory, player, playing = { playing }, onPlaying = { playing = it })
        compose.onNodeWithContentDescription("notes.txt").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithContentDescription("Play Sintel.mp4").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onNodeWithTag(TV_PLAYER_TAG).assertIsFocused()
        compose.onNodeWithContentDescription("Play Sintel.mp4").assertDoesNotExist()
        compose.runOnIdle { assertTrue(player.playWhenReady) }

        // The first Back hides the controls; the second leaves.
        compose.back()
        assertTrue(playing != null)
        compose.back()

        assertNull(playing)
        assertTrue(player.released)
        compose.onNodeWithContentDescription("Play Sintel.mp4").assertIsFocused()
    }

    @Test
    fun backOnTheShellAfterPlaybackStillReachesTheShell() {
        // Playback registers its own Back; once it is gone the shell's handlers own Back again.
        // The first Back hides the controls, the second leaves playback.
        var shellBacks = 0
        var playing by mutableStateOf(true)
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvPlaybackLayer(
                    playing = playing,
                    player = {
                        TvPlayerScreen(
                            state = readyState(),
                            onBack = { playing = false },
                            onRetry = {},
                            onResume = {},
                            onRestart = {},
                            onPlayerFailure = { _, _ -> },
                            playerFactory = { _, _ -> FakePlayer() },
                        )
                    },
                ) {
                    BackHandler { shellBacks += 1 }
                }
            }
        }
        compose.back()
        compose.back()
        assertEquals(0, shellBacks)
        compose.back()
        assertEquals(1, shellBacks)
    }

    @Test
    fun theShellKeepsItsSavedStateWhilePlaybackShows() {
        var playing by mutableStateOf(false)
        // A fresh shell would read the next value; a restored one keeps the first.
        var nextValue = 3
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvPlaybackLayer(
                    playing = playing,
                    player = { Text("Player") },
                ) {
                    // Stands in for the shell's saved destination.
                    val saved by rememberSaveable { mutableIntStateOf(nextValue++) }
                    Text("Shell $saved", modifier = Modifier.testTag("shell"))
                }
            }
        }
        compose.onNodeWithText("Shell 3").assertIsDisplayed()
        playing = true
        compose.onNodeWithText("Player").assertIsDisplayed()
        compose.onNodeWithTag("shell").assertDoesNotExist()
        playing = false
        compose.onNodeWithText("Shell 3").assertIsDisplayed()
    }

    private fun setFilesWithPlayback(
        files: FilesBrowserState,
        focusMemory: MutableMap<Long, Long>,
        player: FakePlayer,
        playing: () -> FilesItem?,
        onPlaying: (FilesItem?) -> Unit,
    ) {
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvPlaybackLayer(
                    playing = playing() != null,
                    player = {
                        val item = checkNotNull(playing())
                        TvPlayerScreen(
                            state = readyState(name = item.name),
                            onBack = { onPlaying(null) },
                            onRetry = {},
                            onResume = {},
                            onRestart = {},
                            onPlayerFailure = { _, _ -> },
                            playerFactory = { _, _ -> player },
                        )
                    },
                ) {
                    TvShell(
                        account = TvAccount(userId = 1, username = "user", email = "user@example.com"),
                        onSignOut = {},
                        filesPane = { paneFocus ->
                            TvFilesScreen(
                                state = files,
                                onEvent = { true },
                                onPlayMedia = { onPlaying(it) },
                                modifier = Modifier.focusRequester(paneFocus),
                                focusMemory = focusMemory,
                            )
                        },
                    )
                }
            }
        }
    }

    private fun row(id: Long, name: String, type: PutioFileType) = FilesItem(
        id = FilesItemId(id),
        parentId = FilesFolder.Root.id,
        name = name,
        type = type,
        sizeBytes = 1L,
        createdAt = "2026-04-20T10:00:00Z",
    )
}
