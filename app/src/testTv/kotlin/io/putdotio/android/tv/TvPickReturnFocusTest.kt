package io.putdotio.android.tv

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import io.putdotio.android.design.putioTvDarkColorScheme
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.history.HistoryContent
import io.putdotio.android.history.HistoryEventId
import io.putdotio.android.history.HistoryEventKind
import io.putdotio.android.history.HistoryFileId
import io.putdotio.android.history.HistoryItem
import io.putdotio.android.history.HistoryPaging
import io.putdotio.android.history.HistoryState
import io.putdotio.android.search.SearchContent
import io.putdotio.android.search.SearchPaging
import io.putdotio.android.search.SearchState
import io.putdotio.android.search.SearchTerm
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.android.tv.history.TvHistoryScreen
import io.putdotio.android.tv.player.TvPlaybackLayer
import io.putdotio.android.tv.search.TvSearchActions
import io.putdotio.android.tv.search.TvSearchScreen
import io.putdotio.sdk.files.PutioFileType
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A Search or History pick replaces the shell with the player; Back lands on the row it came from. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w960dp-h540dp-television")
class TvPickReturnFocusTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var playing by mutableStateOf(false)

    @Test
    fun backFromPlayingASearchResultFocusesThatResult() {
        val picked = TvPickedRow()
        val results = (1L..12L).map { result(it) }
        mount(TvDestination.Search) {
            searchPane = { paneFocus ->
                TvSearchScreen(
                    state = SearchState(
                        query = "clip",
                        content = SearchContent.Ready(SearchTerm("clip"), results, SearchPaging.Complete),
                        recentTerms = emptyList(),
                        consumedCursors = emptySet(),
                        nextRequestValue = 2L,
                    ),
                    actions = TvSearchActions({}, {}, { playing = true }, {}, {}, {}, {}, {}),
                    modifier = Modifier.focusRequester(paneFocus),
                    pickedRow = picked,
                )
            }
        }
        compose.onNodeWithContentDescription("Search files").assertIsFocused().performKeyInput {
            repeat(7) { pressKey(Key.DirectionDown) }
        }
        compose.onNodeWithContentDescription("Play clip 7.mp4").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionCenter)
        }
        compose.onNodeWithTag(PLAYER_TAG).assertIsFocused()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

        compose.onNodeWithContentDescription("Play clip 7.mp4").assertIsFocused()
    }

    @Test
    fun backFromPlayingAHistoryEventFocusesThatEvent() {
        val picked = TvPickedRow()
        val now = Instant.parse("2026-09-30T12:00:00Z")
        val events = (1L..12L).map { id ->
            HistoryItem(
                HistoryEventId(id),
                "2026-09-30T${(11 - id / 6).toString().padStart(2, '0')}:${(59 - id).toString().padStart(2, '0')}:00",
                HistoryEventKind.File(HistoryFileId(100 + id), "clip $id.mp4"),
            )
        }
        mount(TvDestination.History) {
            historyPane = { paneFocus ->
                TvHistoryScreen(
                    state = HistoryState(HistoryContent.Ready(events, HistoryPaging.Complete)),
                    onEvent = {
                        playing = true
                        true
                    },
                    modifier = Modifier.focusRequester(paneFocus),
                    clock = Clock.fixed(now, ZoneOffset.UTC),
                    pickedRow = picked,
                )
            }
        }
        compose.onNodeWithContentDescription("Open clip 1.mp4").assertIsFocused().performKeyInput {
            repeat(8) { pressKey(Key.DirectionDown) }
        }
        compose.onNodeWithContentDescription("Open clip 9.mp4").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionCenter)
        }
        compose.onNodeWithTag(PLAYER_TAG).assertIsFocused()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

        compose.onNodeWithContentDescription("Open clip 9.mp4").assertIsFocused()
    }

    private class Panes {
        var searchPane: @Composable (FocusRequester) -> Unit = {}
        var historyPane: @Composable (FocusRequester) -> Unit = {}
    }

    private fun mount(start: TvDestination, panes: Panes.() -> Unit) {
        val chosen = Panes().apply(panes)
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                var requested by remember { mutableStateOf<TvDestination?>(start) }
                TvPlaybackLayer(
                    playing = playing,
                    player = {
                        val focus = remember { FocusRequester() }
                        BackHandler { playing = false }
                        Box(Modifier.fillMaxSize().testTag(PLAYER_TAG).focusRequester(focus).focusable())
                        LaunchedEffect(Unit) { focus.requestFocus() }
                    },
                ) {
                    TvShell(
                        account = TvAccount(userId = 1, username = "user", email = "user@example.com"),
                        onSignOut = {},
                        searchPane = chosen.searchPane,
                        historyPane = chosen.historyPane,
                        requestedDestination = requested,
                        onDestinationRequestHandled = { requested = null },
                    )
                }
            }
        }
    }

    private fun result(id: Long) = FilesItem(
        id = FilesItemId(id),
        parentId = FilesItemId(0L),
        name = "clip $id.mp4",
        type = PutioFileType.VIDEO,
        sizeBytes = 1_000L,
        createdAt = "2026-09-30T10:00:00Z",
    )

    private companion object {
        const val PLAYER_TAG = "player"
    }
}
