package io.putdotio.android.tv.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.PutioFailure
import io.putdotio.android.files.ContinueWatchingEntry
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.tv.pressCenter
import io.putdotio.android.tv.setTvContent
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.files.PutioFileType
import io.putdotio.sdk.files.PutioFolderType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television")
class TvHomeScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val opened = mutableListOf<String>()
    private var browsed = 0
    private var retried = 0
    private val actions = TvHomeActions(
        onResume = { opened += "resume ${it.name}" },
        onOpen = { opened += "open ${it.name}" },
        onBrowseFiles = { browsed++ },
        onRetryPinned = { retried++ },
    )

    @Test
    fun bothRowsEmptyShowsWhatHomeIsForAndBrowsesFiles() {
        var pinned by mutableStateOf<TvPinnedRow>(TvPinnedRow.Loading)
        compose.setTvContent {
            TvHomeScreen(emptyList(), pinned, actions, remember { FocusRequester() }, onShown = {})
        }
        compose.onAllNodesWithText("Nothing here yet.").assertCountEquals(0)

        pinned = TvPinnedRow.Ready(emptyList())

        compose.onNodeWithText("Nothing here yet.").assertIsDisplayed()
        compose.onNodeWithText("Videos you start come back here. Press Menu on a folder or file to pin it.")
            .assertIsDisplayed()
        compose.onNodeWithText("Browse your files").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, browsed)
    }

    @Test
    fun continueWatchingShowsTimeLeftAndAnEmptyPinnedRowSaysHowToPin() {
        compose.setTvContent {
            TvHomeScreen(
                listOf(entry),
                TvPinnedRow.Ready(emptyList()),
                actions,
                remember { FocusRequester() },
                onShown = {},
            )
        }

        compose.onNodeWithText("Continue watching").assertIsDisplayed()
        compose.onNodeWithContentDescription("Resume Sintel.2010.1080p.mkv, 8:55 left").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Nothing pinned yet.").assertIsDisplayed()
        compose.onNodeWithText("Pin a folder or file from its actions to keep it here.").assertIsDisplayed()
        compose.onAllNodesWithText("Nothing here yet.").assertCountEquals(0)
        assertEquals(listOf("resume Sintel.2010.1080p.mkv"), opened)
    }

    @Test
    fun aPinnedRowThatFailedOffersTryAgain() {
        compose.setTvContent {
            TvHomeScreen(
                listOf(entry),
                TvPinnedRow.Failed(PutioFailure.NetworkUnavailable(PutioConfigurationException("offline"))),
                actions,
                remember { FocusRequester() },
                onShown = {},
            )
        }

        compose.onNodeWithText("Couldn’t load your pins.").assertIsDisplayed()
        compose.onNodeWithText("Try again").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, retried)
    }

    @Test
    fun aSuccessfulPinnedRetryHandsFocusToThePinnedRow() {
        var pinned by mutableStateOf<TvPinnedRow>(
            TvPinnedRow.Failed(PutioFailure.NetworkUnavailable(PutioConfigurationException("offline"))),
        )
        val paneFocus = FocusRequester()
        compose.setTvContent { TvHomeScreen(listOf(entry), pinned, actions, paneFocus, onShown = {}) }
        compose.runOnIdle { paneFocus.requestFocus() }
        compose.onNodeWithContentDescription("Resume Sintel.2010.1080p.mkv, 8:55 left").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Try again").assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(1, retried)

        compose.runOnIdle { pinned = TvPinnedRow.Ready(listOf(TvPinnedCard(bunny, itemCount = 3))) }
        compose.onNodeWithContentDescription("Big Buck Bunny").assertIsFocused()
    }

    @Test
    fun aPinnedRetryThatFindsNoPinsHandsFocusToBrowseFiles() {
        var pinned by mutableStateOf<TvPinnedRow>(
            TvPinnedRow.Failed(PutioFailure.NetworkUnavailable(PutioConfigurationException("offline"))),
        )
        val paneFocus = FocusRequester()
        compose.setTvContent { TvHomeScreen(emptyList(), pinned, actions, paneFocus, onShown = {}) }
        compose.runOnIdle { paneFocus.requestFocus() }
        compose.onNodeWithText("Try again").assertIsFocused().performKeyInput { pressCenter() }

        compose.runOnIdle { pinned = TvPinnedRow.Loading }
        compose.runOnIdle { pinned = TvPinnedRow.Ready(emptyList()) }
        compose.onNodeWithText("Browse your files").assertIsFocused()
    }

    @Test
    fun pinnedCardsOpenTheirItemWithoutContinueWatching() {
        val folder = FilesItem(
            id = FilesItemId(5L),
            parentId = FilesItemId(0L),
            name = "Big Buck Bunny",
            type = PutioFileType.FOLDER,
            sizeBytes = 276_000_000L,
            createdAt = "2026-10-23T10:00:00Z",
        )
        compose.setTvContent {
            TvHomeScreen(
                emptyList(),
                TvPinnedRow.Ready(listOf(TvPinnedCard(folder, itemCount = 3))),
                actions,
                remember { FocusRequester() },
                onShown = {},
            )
        }

        compose.onAllNodesWithText("Continue watching").assertCountEquals(0)
        compose.onNodeWithText("Pinned").assertIsDisplayed()
        compose.onNodeWithContentDescription("Big Buck Bunny").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("open Big Buck Bunny"), opened)
    }

    @Test
    fun pinnedCardsSayWhatTheyHold() {
        fun folder(id: Long, name: String, folderType: PutioFolderType = PutioFolderType.REGULAR) = FilesItem(
            id = FilesItemId(id),
            parentId = FilesItemId(0L),
            name = name,
            type = PutioFileType.FOLDER,
            sizeBytes = 276_000_000L,
            createdAt = "2026-10-23T10:00:00Z",
            folderType = folderType,
        )
        val cards = listOf(
            TvPinnedCard(folder(1L, "Series"), itemCount = 12, folderCount = 12),
            TvPinnedCard(folder(2L, "Big Buck Bunny"), itemCount = 3, folderCount = 0),
            TvPinnedCard(folder(3L, "Items shared with you", PutioFolderType.SHARED_ROOT), itemCount = 2),
        )
        compose.setTvContent {
            TvHomeScreen(emptyList(), TvPinnedRow.Ready(cards), actions, remember { FocusRequester() }, onShown = {})
        }

        compose.onNodeWithText("12 folders").assertIsDisplayed()
        compose.onNodeWithText("3 items · 276MB").assertIsDisplayed()
        compose.onNodeWithText("From 2 friends").assertIsDisplayed()
    }

    @Test
    fun returningFromThePlayerFocusesTheCardThatPlayed() {
        val cards = (1L..8L).map { id ->
            TvPinnedCard(
                FilesItem(
                    id = FilesItemId(100L + id),
                    parentId = FilesItemId(0L),
                    name = "clip-$id.mp4",
                    type = PutioFileType.VIDEO,
                    sizeBytes = 1L,
                    createdAt = "2026-10-23T10:00:00Z",
                ),
                itemCount = null,
            )
        }
        var returned = 0
        compose.setTvContent {
            TvHomeScreen(
                listOf(entry),
                TvPinnedRow.Ready(cards),
                actions,
                remember { FocusRequester() },
                onShown = {},
                returnTo = FilesItemId(107L),
                onReturned = { returned++ },
            )
        }

        compose.onNodeWithContentDescription("clip-7.mp4").assertIsFocused()
        assertEquals(1, returned)
    }

    private val bunny = FilesItem(
        id = FilesItemId(5L),
        parentId = FilesItemId(0L),
        name = "Big Buck Bunny",
        type = PutioFileType.FOLDER,
        sizeBytes = 276_000_000L,
        createdAt = "2026-10-23T10:00:00Z",
    )

    private val entry = ContinueWatchingEntry(FilesItemId(7L), "Sintel.2010.1080p.mkv", 356.0, 891.0, 5L)
}
