package io.putdotio.android.tv.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.ContinueWatchingEntry
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.tv.setTvContent
import io.putdotio.sdk.files.PutioFileType
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
    private val actions = TvHomeActions(
        onResume = { opened += "resume ${it.name}" },
        onOpen = { opened += "open ${it.name}" },
        onBrowseFiles = { browsed++ },
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
            TvHomeScreen(listOf(entry), TvPinnedRow.Ready(emptyList()), actions, remember { FocusRequester() }) {}
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
            ) {}
        }

        compose.onAllNodesWithText("Continue watching").assertCountEquals(0)
        compose.onNodeWithText("Pinned").assertIsDisplayed()
        compose.onNodeWithContentDescription("Big Buck Bunny").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("open Big Buck Bunny"), opened)
    }

    private val entry = ContinueWatchingEntry(FilesItemId(7L), "Sintel.2010.1080p.mkv", 356.0, 891.0, 5L)
}
