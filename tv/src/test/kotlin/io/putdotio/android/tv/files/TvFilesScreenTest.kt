package io.putdotio.android.tv.files

import androidx.compose.runtime.getValue
import io.putdotio.android.tv.pressCenter
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderOperation
import io.putdotio.android.files.FilesFolderOperationIntent
import io.putdotio.android.files.FilesFolderOperationPhase
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesOpenOrigin
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.FilesPlaybackProgress
import io.putdotio.android.files.FilesRequestId
import io.putdotio.android.files.FilesSort
import io.putdotio.android.files.FilesSubtitleLanguages
import io.putdotio.android.files.FilesSubtitles
import io.putdotio.android.tv.TvPlayStart
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.onNodeWithTag
import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.android.files.FilesFolderCounts
import io.putdotio.android.tv.setTvContent
import io.putdotio.sdk.files.PutioFileType
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
@Config(sdk = [35], qualifiers = "en-rUS-w960dp-h540dp-television")
class TvFilesScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun rootListsRowsWithSizeAndTheWatchedEyeAndFocusesTheFirstRow() {
        val events = mutableListOf<FilesBrowserEvent>()
        compose.showFiles(
            state = {
                ready(
                    item(1, "tears_of_steel", PutioFileType.FOLDER, sizeBytes = 544_890_000),
                    item(
                        2,
                        "Tears of Steel.webm",
                        PutioFileType.VIDEO,
                        playback = FilesPlaybackProgress(243.0, 734.0),
                    ),
                    item(3, "notes.txt", PutioFileType.TEXT, sizeBytes = 1_137),
                )
            },
            onEvent = { events += it; true },
        )

        compose.onNodeWithText("Your Files").assertDoesNotExist()
        compose.onNodeWithText("All files").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open tears_of_steel").assertIsFocused()
        compose.onNodeWithContentDescription("Watched", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("33%", substring = true).assertCountEquals(0)
        compose.onNodeWithText("1.1 kB").assertIsDisplayed()

        compose.onNodeWithContentDescription("Open tears_of_steel").performKeyInput { pressCenter() }
        assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.OpenFolder(FilesItemId(1))), events)
    }

    @Test
    fun aPlayableRowPlaysAndAnUnsupportedRowExplainsThenGoesBack() {
        val played = mutableListOf<FilesItem>()
        compose.showFiles(
            state = { ready(item(2, "clip.mp4", PutioFileType.VIDEO), item(3, "notes.txt", PutioFileType.TEXT)) },
            onPlayMedia = { played += it },
        )

        compose.onNodeWithContentDescription("Play clip.mp4").performKeyInput { pressCenter() }
        assertEquals(listOf(FilesItemId(2)), played.map { it.id })

        compose.onNodeWithContentDescription("Play clip.mp4").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithContentDescription("notes.txt").assertIsFocused().performKeyInput { pressCenter() }
        compose.onNodeWithText("Unsupported file type").assertIsDisplayed()
        compose.onNodeWithText("Go back").assertIsFocused().performKeyInput { pressCenter() }
        compose.onNodeWithContentDescription("notes.txt").assertIsDisplayed()
    }

    @Test
    fun hardwareBackClosesTheUnsupportedScreenWithoutPoppingTheFolder() {
        val events = mutableListOf<FilesBrowserEvent>()
        compose.showFiles(
            state = { ready(item(2, "clip.mp4", PutioFileType.VIDEO), item(3, "notes.txt", PutioFileType.TEXT)) },
            onEvent = { events += it; true },
        )
        compose.onNodeWithContentDescription("Play clip.mp4").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithContentDescription("notes.txt").performKeyInput { pressCenter() }
        compose.onNodeWithText("Unsupported file type").assertIsDisplayed()

        compose.runOnUiThread { pressSystemBack() }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("notes.txt").assertIsFocused()
        assertEquals(emptyList<FilesBrowserEvent>(), events)
    }

    @Test
    fun anEmptyFolderHoldsFocusOnItsEmptyStateWithoutControls() {
        compose.showFiles(state = { state(FilesContent.Empty(FilesPaging.Complete)) })
        compose.onNodeWithText("Empty folder.").assertIsDisplayed().assertIsFocused()
        compose.onNodeWithText("Files you add to this folder on put.io show up here.").assertIsDisplayed()
        compose.onAllNodesWithText("Account default").assertCountEquals(0)
        compose.onAllNodesWithText("Refresh").assertCountEquals(0)
    }

    @Test
    fun aLoadingFolderDrawsSkeletonsWithoutTheSortPill() {
        compose.showFiles(state = { state(FilesContent.Loading(FilesRequestId(2))) })
        compose.onNodeWithContentDescription("Loading").assertIsDisplayed()
        compose.onNodeWithText("All files").assertIsDisplayed()
        compose.onAllNodesWithText("Account default").assertCountEquals(0)
    }

    @Test
    fun aFolderPutioDidNotAnswerForOffersTryAgain() {
        val events = mutableListOf<FilesBrowserEvent>()
        val failure = PutioFailure.ServerUnavailable(503, PutioConfigurationException("x"))
        compose.showFiles(state = { state(FilesContent.Failed(failure)) }, onEvent = { events += it; true })
        compose.onNodeWithText("Couldn't load your files.").assertIsDisplayed()
        compose.onNodeWithText("put.io didn't answer. Check the TV's connection and try again.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.Retry), events)
    }

    @Test
    fun aReloadKeepsFocusOnTheSortPillAndIgnoresItsPress() {
        var operation by mutableStateOf<FilesFolderOperation>(FilesFolderOperation.Idle)
        val events = mutableListOf<FilesBrowserEvent>()
        compose.showFiles(
            state = {
                state(
                    FilesContent.Ready(listOf(item(1, "a.txt", PutioFileType.TEXT)), FilesPaging.Complete),
                    operation = operation,
                )
            },
            onEvent = { events += it; true },
        )
        compose.onNodeWithContentDescription("a.txt").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNode(hasText("Account default") and hasClickAction()).assertIsFocused()
        operation = FilesFolderOperation.Loading(
            FilesRequestId(3),
            FilesFolderOperationIntent.Refresh,
            FilesFolderOperationPhase.RELOADING,
        )
        compose.onNode(hasText("Account default") and hasClickAction()).assertIsFocused()
            .performKeyInput { pressCenter() }
        compose.onAllNodesWithText("Sort by").assertCountEquals(0)
        assertEquals(emptyList<FilesBrowserEvent>(), events)
    }

    @Test
    fun backFromAFolderReturnsFocusToTheRowThatOpenedIt() {
        val root = ready(
            item(1, "first.txt", PutioFileType.TEXT),
            item(2, "Movies", PutioFileType.FOLDER),
            item(3, "third.txt", PutioFileType.TEXT),
        )
        val child = filesBrowserState(
            stack = root.stack + FilesFolderState(
                folder = FilesFolder(FilesItemId(2), "Movies"),
                content = FilesContent.Ready(listOf(item(4, "inner.mp4", PutioFileType.VIDEO)), FilesPaging.Complete),
            ),
            nextRequestValue = 11L,
        )
        var state by mutableStateOf(root)
        compose.showFiles(state = { state })
        compose.onNodeWithContentDescription("first.txt").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithContentDescription("Open Movies").assertIsFocused()

        state = child
        compose.onNodeWithContentDescription("Play inner.mp4").assertIsFocused()

        state = root
        compose.onNodeWithContentDescription("Open Movies").assertIsFocused()
    }

    @Test
    fun aFolderOpenedFromSearchNamesSearchInItsCrumbNotTheFolderBelowIt() {
        val root = ready(item(2, "Movies", PutioFileType.FOLDER))
        val state = filesBrowserState(
            stack = root.stack + FilesFolderState(
                folder = FilesFolder(FilesItemId(2), "Movies"),
                content = FilesContent.Ready(listOf(item(3, "trailer.mp4", PutioFileType.VIDEO)), FilesPaging.Complete),
            ) + FilesFolderState(
                folder = FilesFolder(FilesItemId(9), "Anime"),
                content = FilesContent.Ready(listOf(item(10, "pilot.mp4", PutioFileType.VIDEO)), FilesPaging.Complete),
                openedFrom = FilesOpenOrigin.SEARCH,
            ),
            nextRequestValue = 11L,
        )
        compose.showFiles(state = { state })

        compose.onNodeWithText("Anime").assertIsDisplayed()
        compose.onNodeWithText("Search").assertIsDisplayed()
        compose.onNodeWithText("Movies").assertDoesNotExist()
    }

    @Test
    fun theSortDialogClosesWhenTheFolderStartsAnOperation() {
        var operation by mutableStateOf<FilesFolderOperation>(FilesFolderOperation.Idle)
        compose.showFiles(
            state = {
                state(
                    FilesContent.Ready(listOf(item(1, "a.txt", PutioFileType.TEXT)), FilesPaging.Complete),
                    operation = operation,
                )
            },
        )
        compose.onNodeWithContentDescription("a.txt").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNode(hasText("Account default") and hasClickAction()).performKeyInput { pressCenter() }
        compose.onNodeWithText("Sort by").assertIsDisplayed()

        operation = FilesFolderOperation.Loading(
            FilesRequestId(3),
            FilesFolderOperationIntent.Refresh,
            FilesFolderOperationPhase.RELOADING,
        )
        compose.onAllNodesWithText("Sort by").assertCountEquals(0)
    }

    @Test
    fun anOfflineFolderOffersTryNowAndNoSort() {
        val events = mutableListOf<FilesBrowserEvent>()
        compose.showFiles(state = { state(FilesContent.Failed(networkFailure())) }, onEvent = { events += it; true })
        compose.onNodeWithText("Can’t reach put.io.").assertIsDisplayed()
        compose.onNodeWithText("Check the network and try again.").assertIsDisplayed()
        compose.onNodeWithText("Try now").assertIsFocused().performKeyInput { pressCenter() }
        compose.onAllNodesWithText("Account default").assertCountEquals(0)
        assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.Retry), events)
    }

    @Test
    fun aFailedRefreshShowsTheErrorWithRetryAndKeepsPagingEnabled() {
        val events = mutableListOf<FilesBrowserEvent>()
        compose.showFiles(
            state = {
                state(
                    FilesContent.Ready(
                        listOf(item(1, "a.txt", PutioFileType.TEXT)),
                        FilesPaging.Available(FilesCursor("c")),
                    ),
                    operation = FilesFolderOperation.Failed(
                        networkFailure(),
                        FilesFolderOperationIntent.Refresh,
                        FilesFolderOperationPhase.RELOADING,
                    ),
                )
            },
            onEvent = { events += it; true },
        )
        compose.onNodeWithText("Couldn’t refresh files.").assertIsDisplayed()
        compose.onNodeWithContentDescription("a.txt").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Load more").assertIsFocused().performKeyInput {
            pressCenter()
            pressKey(Key.DirectionUp)
            pressKey(Key.DirectionUp)
        }
        compose.onNodeWithText("Try again").assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.LoadNextPage, FilesBrowserEvent.Retry), events)
    }

    @Test
    fun theUnsupportedScreenDoesNotReturnOnItsOwnAfterARefresh() {
        val listed = ready(item(3, "notes.txt", PutioFileType.TEXT))
        var state by mutableStateOf(listed)
        compose.showFiles(state = { state })
        compose.onNodeWithContentDescription("notes.txt").performKeyInput { pressCenter() }
        compose.onNodeWithText("Unsupported file type").assertIsDisplayed()

        state = state(FilesContent.Loading(FilesRequestId(2)))
        compose.onAllNodesWithText("Unsupported file type").assertCountEquals(0)
        state = listed
        compose.onAllNodesWithText("Unsupported file type").assertCountEquals(0)
        compose.onNodeWithContentDescription("notes.txt").assertIsFocused()
    }

    @Test
    fun aSortThatMovesTheFocusedRowBelowTheFoldKeepsItFocusedAndVisible() {
        val rows = (1..30L).map { item(it, "file-%02d.txt".format(it), PutioFileType.TEXT) }
        var state by mutableStateOf(ready(*rows.toTypedArray()))
        compose.showFiles(state = { state })
        compose.onNodeWithContentDescription("file-01.txt").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithContentDescription("file-02.txt").assertIsFocused()

        state = ready(*rows.reversed().toTypedArray(), sort = FilesSort.NAME_DESCENDING)
        compose.onNodeWithContentDescription("file-02.txt").assertIsFocused().assertIsDisplayed()
    }

    @Test
    fun aRefreshThatDropsTheFocusedRowFallsBackToTheFirstRow() {
        var state by mutableStateOf(ready(item(1, "a.txt", PutioFileType.TEXT), item(2, "b.txt", PutioFileType.TEXT)))
        compose.showFiles(state = { state })
        compose.onNodeWithContentDescription("a.txt").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithContentDescription("b.txt").assertIsFocused()

        state = ready(item(1, "a.txt", PutioFileType.TEXT), item(3, "c.txt", PutioFileType.TEXT))
        compose.onNodeWithContentDescription("a.txt").assertIsFocused()
    }

    @Test
    fun aRefreshWhileOnTheSortPillKeepsFocusThere() {
        var state by mutableStateOf(ready(item(1, "a.txt", PutioFileType.TEXT), item(2, "b.txt", PutioFileType.TEXT)))
        compose.showFiles(state = { state })
        compose.onNodeWithContentDescription("a.txt").performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionUp)
            pressKey(Key.DirectionUp)
        }
        compose.onNode(hasText("Account default") and hasClickAction()).assertIsFocused()

        state = ready(
            item(1, "a.txt", PutioFileType.TEXT),
            item(2, "b.txt", PutioFileType.TEXT),
            item(3, "c.txt", PutioFileType.TEXT),
        )
        compose.onNode(hasText("Account default") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun theSortPillOpensTheSortSheetAndItsGroupsDispatchTheChoice() {
        val events = mutableListOf<FilesBrowserEvent>()
        compose.showFiles(
            state = { ready(item(1, "a.txt", PutioFileType.TEXT), sort = FilesSort.NAME_ASCENDING) },
            onEvent = { events += it; true },
        )

        compose.onNodeWithContentDescription("a.txt").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNode(hasText("Name, A–Z") and hasClickAction()).assertIsFocused().performKeyInput { pressCenter() }
        compose.onNodeWithText("Sort").assertIsDisplayed()
        compose.onNodeWithText("Sort by").assertIsDisplayed()
        compose.onNode(hasText("A to Z") and hasClickAction() and hasSelectedState()).assertIsDisplayed()
        compose.onNode(hasText("Name") and hasClickAction() and hasSelectedState()).assertIsFocused()
            .performKeyInput { repeat(6) { pressKey(Key.DirectionDown) } }
        compose.onNode(hasText("Z to A") and hasClickAction()).assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.SelectSort(FilesSort.NAME_DESCENDING)), events)
        compose.onAllNodesWithText("Sort by").assertCountEquals(0)
        compose.onNode(hasText("Name, A–Z") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun theDetailFollowsTheFocusedRowAndItsActionsResumeOrStartOver() {
        val played = mutableListOf<Pair<FilesItemId, TvPlayStart>>()
        compose.setTvContent {
            TvFilesScreen(
                state = ready(
                    item(1, "notes.txt", PutioFileType.TEXT),
                    item(
                        2,
                        "Blue.Lights.S04E01.mkv",
                        PutioFileType.VIDEO,
                        playback = FilesPlaybackProgress(1_122.0, 2_989.0),
                    ),
                ),
                onEvent = { true },
                onPlayMedia = {},
                onPlay = { item, start -> played += item.id to start },
            )
        }
        compose.onNodeWithTag(TV_FILES_DETAIL_PRIMARY_TAG).assert(hasText("Open"))
        compose.onNodeWithContentDescription("notes.txt").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Length").assertIsDisplayed()
        compose.onNodeWithText("49:49").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play Blue.Lights.S04E01.mkv").performKeyInput {
            pressKey(Key.DirectionRight)
        }
        compose.onNode(hasText("Resume from 18:42") and hasClickAction()).assertIsFocused().performKeyInput {
            pressCenter()
            pressKey(Key.DirectionRight)
        }
        compose.onNode(hasText("Start over") and hasClickAction()).assertIsFocused().performKeyInput {
            pressCenter()
            pressKey(Key.DirectionLeft)
            pressKey(Key.DirectionLeft)
        }
        compose.onNodeWithContentDescription("Play Blue.Lights.S04E01.mkv").assertIsFocused()
        assertEquals(
            listOf(FilesItemId(2) to TvPlayStart.Resume, FilesItemId(2) to TvPlayStart.StartOver),
            played,
        )
    }

    @Test
    fun anUnstartedVideoOffersPlayAndAFolderNamesItsCountAndOpens() {
        val events = mutableListOf<FilesBrowserEvent>()
        val played = mutableListOf<TvPlayStart>()
        val counts = FilesFolderCounts { PutioResult.Success(3) }
        compose.setTvContent {
            TvFilesScreen(
                state = ready(
                    item(1, "Big Buck Bunny", PutioFileType.FOLDER, sizeBytes = 276_000_000),
                    item(2, "Sintel.2010.1080p.mkv", PutioFileType.VIDEO),
                ),
                onEvent = { events += it; true },
                onPlayMedia = {},
                onPlay = { _, start -> played += start },
                detailSources = TvFilesDetailSources(folderCounts = counts),
            )
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("3 items · 276 MB").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Open Big Buck Bunny").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNode(hasText("Open") and hasClickAction()).assertIsFocused().performKeyInput {
            pressCenter()
            pressKey(Key.DirectionLeft)
        }
        assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.OpenFolder(FilesItemId(1))), events)

        compose.onNodeWithContentDescription("Open Big Buck Bunny").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithContentDescription("Play Sintel.2010.1080p.mkv").performKeyInput {
            pressKey(Key.DirectionRight)
        }
        compose.onNode(hasText("Play") and hasClickAction()).assertIsFocused().performKeyInput { pressCenter() }
        compose.onAllNodesWithText("Start over").assertCountEquals(0)
        assertEquals(listOf(TvPlayStart.Ask), played)
    }

    @Test
    fun aVideoNamesItsSubtitleLanguagesAndHowManyAreSidecarFiles() {
        val subtitles = mapOf(
            1L to FilesSubtitles(listOf("English"), sidecarFiles = 1),
            2L to FilesSubtitles(listOf("English", "Turkish")),
        )
        compose.setTvContent {
            TvFilesScreen(
                state = ready(
                    item(1, "Big Buck Bunny.mp4", PutioFileType.VIDEO),
                    item(2, "Sintel.2010.1080p.mkv", PutioFileType.VIDEO),
                ),
                onEvent = { true },
                onPlayMedia = {},
                onPlay = { _, _ -> },
                detailSources = TvFilesDetailSources(
                    subtitleLanguages = FilesSubtitleLanguages { id ->
                        PutioResult.Success(subtitles.getValue(id.value))
                    },
                ),
            )
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("English · 1 file").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Play Big Buck Bunny.mp4").performKeyInput {
            pressKey(Key.DirectionDown)
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("English, Turkish").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aStartedSongOffersPlayWithoutStartOver() {
        val played = mutableListOf<TvPlayStart>()
        compose.setTvContent {
            TvFilesScreen(
                state = ready(
                    item(1, "notes.txt", PutioFileType.TEXT),
                    item(2, "Tree Rings.mp3", PutioFileType.AUDIO, playback = FilesPlaybackProgress(600.0, 3_000.0)),
                ),
                onEvent = { true },
                onPlayMedia = {},
                onPlay = { _, start -> played += start },
            )
        }
        compose.onNodeWithContentDescription("notes.txt").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithContentDescription("Play Tree Rings.mp3").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNode(hasText("Play") and hasClickAction()).assertIsFocused().performKeyInput { pressCenter() }
        compose.onAllNodesWithText("Start over").assertCountEquals(0)
        assertEquals(listOf(TvPlayStart.Ask), played)
    }

    @Test
    fun aListingChangeLeavesFocusInTheDetail() {
        var position by mutableStateOf(1_122.0)
        compose.setTvContent {
            TvFilesScreen(
                state = ready(
                    item(1, "notes.txt", PutioFileType.TEXT),
                    item(
                        2,
                        "Blue.Lights.S04E01.mkv",
                        PutioFileType.VIDEO,
                        playback = FilesPlaybackProgress(position, 2_989.0),
                    ),
                ),
                onEvent = { true },
                onPlayMedia = {},
            )
        }
        compose.onNodeWithContentDescription("notes.txt").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithContentDescription("Play Blue.Lights.S04E01.mkv").performKeyInput {
            pressKey(Key.DirectionRight)
        }
        compose.onNodeWithTag(TV_FILES_DETAIL_PRIMARY_TAG).assertIsFocused()

        position = 1_500.0
        compose.mainClock.advanceTimeBy(1_000)

        compose.onNode(hasText("Resume from 25:00") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun closingTheActionsOpenedFromMoreFocusesMore() {
        val toggled = mutableListOf<Boolean>()
        compose.setTvContent {
            TvFilesScreen(
                state = ready(
                    item(2, "clip.mp4", PutioFileType.VIDEO, playback = FilesPlaybackProgress(30.0, 120.0)),
                    item(3, "notes.txt", PutioFileType.TEXT),
                ),
                onEvent = { true },
                onPlayMedia = {},
                watchedToggleEnabled = true,
                onSetWatched = { _, watched -> toggled += watched },
            )
        }
        compose.onNodeWithContentDescription("Play clip.mp4").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag(TV_FILES_DETAIL_PRIMARY_TAG).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionRight)
        }
        compose.onNodeWithContentDescription("More actions").assertIsFocused().performKeyInput { pressCenter() }
        compose.onNodeWithText("Open in VLC").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Mark as unwatched").assertIsFocused().performKeyInput { pressCenter() }

        assertEquals(listOf(false), toggled)
        compose.onNodeWithContentDescription("More actions").assertIsFocused()
    }

    @Test
    fun aFolderCountIsReadAgainOnceTheListingChanges() {
        var reads = 0
        val counts = FilesFolderCounts { PutioResult.Success(++reads) }
        var size by mutableStateOf(276_000_000L)
        compose.setTvContent {
            TvFilesScreen(
                state = ready(item(1, "Big Buck Bunny", PutioFileType.FOLDER, sizeBytes = size)),
                onEvent = { true },
                onPlayMedia = {},
                detailSources = remember { TvFilesDetailSources(folderCounts = counts) },
            )
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("1 item · 276 MB").fetchSemanticsNodes().isNotEmpty() }

        size = 138_000_000L

        compose.waitUntil(5_000) { compose.onAllNodesWithText("2 items · 138 MB").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, reads)
    }

    @Test
    fun enteringTheDetailWhileTheListingChangesKeepsFocusThere() {
        var size by mutableStateOf(128L)
        compose.setTvContent {
            TvFilesScreen(
                state = ready(item(2, "clip.mp4", PutioFileType.VIDEO, sizeBytes = size)),
                onEvent = { true },
                onPlayMedia = {},
            )
        }
        compose.onNodeWithContentDescription("Play clip.mp4").assertIsFocused()

        compose.mainClock.autoAdvance = false
        size = 2_048L
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription("Play clip.mp4").performKeyInput { pressKey(Key.DirectionRight) }
        compose.mainClock.autoAdvance = true
        compose.mainClock.advanceTimeBy(1_000)

        compose.onNodeWithTag(TV_FILES_DETAIL_PRIMARY_TAG).assertIsFocused()
    }

    @Test
    fun aFolderCountIsReadAgainAfterAReloadReturnsTheSameRows() {
        var reads = 0
        val counts = FilesFolderCounts { PutioResult.Success(++reads) }
        val rows = listOf(item(1, "Big Buck Bunny", PutioFileType.FOLDER, sizeBytes = 276_000_000L))
        var operation by mutableStateOf<FilesFolderOperation>(FilesFolderOperation.Idle)
        compose.setTvContent {
            TvFilesScreen(
                state = state(FilesContent.Ready(rows, FilesPaging.Complete), operation = operation),
                onEvent = { true },
                onPlayMedia = {},
                detailSources = remember { TvFilesDetailSources(folderCounts = counts) },
            )
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("1 item · 276 MB").fetchSemanticsNodes().isNotEmpty() }

        operation = FilesFolderOperation.Loading(
            FilesRequestId(7),
            FilesFolderOperationIntent.Refresh,
            FilesFolderOperationPhase.RELOADING,
        )
        compose.waitForIdle()
        operation = FilesFolderOperation.Idle

        compose.waitUntil(5_000) { compose.onAllNodesWithText("2 items · 276 MB").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, reads)
    }

    @Test
    fun comingBackFromThePlayerFocusesTheRowThatPlayed() {
        var mount by mutableIntStateOf(0)
        val memory = mutableMapOf<Long, Long>()
        compose.setTvContent {
            key(mount) {
                TvFilesScreen(
                    state = ready(
                        item(1, "first.mp4", PutioFileType.VIDEO),
                        item(2, "second.mp4", PutioFileType.VIDEO),
                    ),
                    onEvent = { true },
                    onPlayMedia = {},
                    onPlay = { _, _ -> mount++ },
                    focusMemory = memory,
                )
            }
        }
        compose.onNodeWithContentDescription("Play first.mp4").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithContentDescription("Play second.mp4").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNode(hasText("Play") and hasClickAction()).assertIsFocused().performKeyInput { pressCenter() }

        compose.onNodeWithContentDescription("Play second.mp4").assertIsFocused()
    }

    private fun hasSelectedState() = androidx.compose.ui.test.isSelected()

    private fun pressSystemBack() {
        compose.activity.onBackPressedDispatcher.onBackPressed()
    }
}
