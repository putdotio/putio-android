package io.putdotio.android.tv.history

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesItemResolver
import io.putdotio.android.history.HistoryContent
import io.putdotio.android.history.HistoryEventId
import io.putdotio.android.history.HistoryEventKind
import io.putdotio.android.history.HistoryFileId
import io.putdotio.android.history.HistoryItem
import io.putdotio.android.history.HistoryNoticeType
import io.putdotio.android.history.HistoryPaging
import io.putdotio.android.history.HistoryState
import io.putdotio.android.history.HistoryTransferId
import io.putdotio.android.history.historyState
import io.putdotio.android.tv.files.TvFilesActionContext
import io.putdotio.android.tv.files.TvFilesActionHandlers
import io.putdotio.android.tv.files.TvItemThumbnails
import io.putdotio.android.tv.files.tvActions
import io.putdotio.android.tv.setTvContent
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.files.PutioFileType
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w960dp-h540dp-television")
class TvHistorySharerAndSizeTest {
    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val clock = Clock.fixed(Instant.parse("2026-09-12T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun rowsShowSharerAndSizeAcrossSupportedEventTypes() {
        val entries = listOf(
            HistoryItem(
                HistoryEventId(1),
                "2026-09-12T05:22:00",
                HistoryEventKind.File(
                    HistoryFileId(1),
                    "shared.mkv",
                    sharedBy = "alice",
                    fileSizeBytes = 1_500_000_000L,
                ),
            ),
            HistoryItem(
                HistoryEventId(2),
                "2026-09-12T05:21:00",
                HistoryEventKind.Transfer(
                    HistoryTransferId(9),
                    HistoryFileId(2),
                    "movie.mkv",
                    transferSizeBytes = 3_100_000_000L,
                ),
            ),
            HistoryItem(
                HistoryEventId(3),
                "2026-09-12T05:20:00",
                HistoryEventKind.Notice(
                    HistoryNoticeType.Upload,
                    "clip.mp4",
                    HistoryFileId(3),
                    fileSizeBytes = 42_000_000L,
                ),
            ),
            HistoryItem(
                HistoryEventId(4),
                "2026-09-12T05:19:00",
                HistoryEventKind.Notice(HistoryNoticeType.RssFileDeleted, "old.mkv", fileSizeBytes = 850_000_000L),
            ),
        )
        show(historyState(HistoryContent.Ready(entries, HistoryPaging.Complete)))

        compose.onNodeWithContentDescription("Open shared.mkv")
            .assert(hasText("Shared by alice"))
            .assert(hasText("1.5 GB"))
        compose.onNodeWithContentDescription("Open movie.mkv")
            .assert(hasText("Transfer completed"))
            .assert(hasText("3.1 GB"))
        compose.onNodeWithContentDescription("Open clip.mp4")
            .assert(hasText("Uploaded"))
            .assert(hasText("42 MB"))
        compose.onNodeWithContentDescription("old.mkv")
            .assert(hasText("Deleted to free space"))
            .assert(hasText("850 MB"))
    }

    @Test
    fun rowsHideMissingOrNegativeSharerAndSize() {
        val entries = listOf(
            HistoryItem(
                HistoryEventId(1),
                "2026-09-12T05:22:00",
                HistoryEventKind.File(HistoryFileId(1), "shared.mkv", sharedBy = " ", fileSizeBytes = -1L),
            ),
            HistoryItem(
                HistoryEventId(2),
                "2026-09-12T05:21:00",
                HistoryEventKind.Transfer(HistoryTransferId(9), HistoryFileId(2), "movie.mkv", transferSizeBytes = -5L),
            ),
            HistoryItem(
                HistoryEventId(3),
                "2026-09-12T05:20:00",
                HistoryEventKind.Notice(HistoryNoticeType.Upload, "clip.mp4", HistoryFileId(3), fileSizeBytes = null),
            ),
            HistoryItem(
                HistoryEventId(4),
                "2026-09-12T05:19:00",
                HistoryEventKind.Notice(HistoryNoticeType.RssFileDeleted, "old.mkv", fileSizeBytes = -1L),
            ),
        )
        show(historyState(HistoryContent.Ready(entries, HistoryPaging.Complete)))

        compose.onNodeWithContentDescription("Open shared.mkv").assert(hasText("Shared with you"))
        compose.onAllNodes(hasText("Shared by", substring = true)).assertCountEquals(0)
        listOf("GB", "MB", "kB", " B").forEach { unit ->
            compose.onAllNodes(hasText(unit, substring = true)).assertCountEquals(0)
        }
    }

    @Test
    fun detailShowsSharedByAlongsideFoundFileFacts() {
        val shared = HistoryItem(
            HistoryEventId(1),
            "2026-09-12T05:22:00",
            HistoryEventKind.File(
                HistoryFileId(1),
                "shared.mkv",
                sharedBy = "alice",
                fileSizeBytes = 1_500_000_000L,
            ),
        )
        show(
            state = historyState(HistoryContent.Ready(listOf(shared), HistoryPaging.Complete)),
            files = TvHistoryFiles(resolver(FilesItemId(1) to file(1, "shared.mkv", FilesFolder.Root.id))),
            itemActions = historyActions(),
        )

        compose.waitUntil(5_000) { compose.onAllNodesWithText("Your files").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Shared by") and hasText("alice")).assertIsDisplayed()
        compose.onNode(hasText("Size") and hasText("3.1 GB")).assertIsDisplayed()
        compose.onNode(hasText("Saved to") and hasText("Your files")).assertIsDisplayed()
    }

    @Test
    fun detailShowsSharedByAndEventSizeFallbackWhenFileIsGoneOrAbsent() {
        val entries = listOf(
            HistoryItem(
                HistoryEventId(1),
                "2026-09-12T05:22:00",
                HistoryEventKind.File(
                    HistoryFileId(1),
                    "shared.mkv",
                    sharedBy = "alice",
                    fileSizeBytes = 1_500_000_000L,
                ),
            ),
            HistoryItem(
                HistoryEventId(2),
                "2026-09-12T05:21:00",
                HistoryEventKind.Notice(HistoryNoticeType.RssFileDeleted, "old.mkv", fileSizeBytes = 850_000_000L),
            ),
            HistoryItem(
                HistoryEventId(3),
                "2026-09-12T05:20:00",
                HistoryEventKind.File(HistoryFileId(3), "missing.mkv", sharedBy = null, fileSizeBytes = -1L),
            ),
        )
        show(
            state = historyState(HistoryContent.Ready(entries, HistoryPaging.Complete)),
            files = TvHistoryFiles(goneResolver()),
            itemActions = historyActions(),
        )

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("No longer in your files").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("Shared by") and hasText("alice")).assertIsDisplayed()
        compose.onNode(hasText("Size") and hasText("1.5 GB")).assertIsDisplayed()
        compose.onNode(hasText("File") and hasText("No longer in your files")).assertIsDisplayed()

        compose.onNodeWithContentDescription("Open shared.mkv").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithContentDescription("old.mkv").assertIsFocused()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Size") and hasText("850 MB")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("Size") and hasText("850 MB")).assertIsDisplayed()
        compose.onNode(hasText("Shared by")).assertDoesNotExist()
        compose.onNodeWithText("No longer in your files").assertDoesNotExist()

        compose.onNodeWithContentDescription("old.mkv").performKeyInput {
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithContentDescription("Open missing.mkv").assertIsFocused()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("No longer in your files").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("Shared by")).assertDoesNotExist()
        compose.onNode(hasText("Size")).assertDoesNotExist()
    }

    @Test
    fun rowSizeAndDetailRespectConfigurationLocale() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            val entries = listOf(
                HistoryItem(
                    HistoryEventId(1),
                    "2026-09-12T05:22:00",
                    HistoryEventKind.File(
                        HistoryFileId(1),
                        "shared.mkv",
                        sharedBy = "alice",
                        fileSizeBytes = 1_500_000_000L,
                    ),
                ),
            )
            compose.setTvContent {
                val config = Configuration(LocalConfiguration.current).apply { setLocale(Locale.GERMANY) }
                CompositionLocalProvider(LocalConfiguration provides config) {
                    TvHistoryScreen(
                        state = historyState(HistoryContent.Ready(entries, HistoryPaging.Complete)),
                        onEvent = { true },
                        clock = clock,
                        files = TvHistoryFiles(resolver(FilesItemId(1) to file(1, "shared.mkv", FilesItemId(0)))),
                        itemActions = historyActions(),
                    )
                }
            }

            compose.onNodeWithContentDescription("Open shared.mkv").assert(hasText("1,5 GB"))
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasText("Size") and hasText("3,1 GB")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(hasText("Size") and hasText("3,1 GB")).assertIsDisplayed()
            compose.onAllNodesWithText("1.5 GB", substring = true).assertCountEquals(0)
            compose.onAllNodesWithText("3.1 GB", substring = true).assertCountEquals(0)
        } finally {
            Locale.setDefault(previous)
        }
    }

    private fun show(
        state: HistoryState,
        files: TvHistoryFiles = TvHistoryFiles.None,
        itemActions: TvHistoryItemActions? = null,
    ) {
        compose.setTvContent {
            TvHistoryScreen(
                state = state,
                onEvent = { true },
                clock = clock,
                files = files,
                itemActions = itemActions,
            )
        }
    }

    private fun historyActions() = TvHistoryItemActions(
        resumePlayback = true,
        thumbnails = TvItemThumbnails.None,
        onPlay = {},
        actionsOf = { item ->
            item.tvActions(TvFilesActionContext(watchedToggleEnabled = false, trashEnabled = true, canDelete = true))
        },
        handlers = TvFilesActionHandlers(
            onPlay = { _, _ -> },
            onPlayWith = { _, _ -> },
            onOpenInVlc = {},
            onSetWatched = { _, _ -> },
            onSetPinned = { _, _ -> },
        ),
    )

    private fun goneResolver() = object : FilesItemResolver {
        override suspend fun resolveItem(itemId: FilesItemId) =
            PutioResult.Failure(PutioFailure.ApiRejected(404, null, PutioConfigurationException("gone"), 404))
    }

    private fun resolver(vararg known: Pair<FilesItemId, FilesItem>) = object : FilesItemResolver {
        override suspend fun resolveItem(itemId: FilesItemId) =
            known.toMap()[itemId]?.let { PutioResult.Success(it) }
                ?: PutioResult.Failure(PutioFailure.Unexpected(IllegalStateException("No item $itemId")))
    }

    private fun file(id: Long, name: String, parent: FilesItemId) = FilesItem(
        id = FilesItemId(id),
        parentId = parent,
        name = name,
        type = PutioFileType.VIDEO,
        sizeBytes = 3_100_000_000L,
        createdAt = "2026-09-12T10:00:00",
    )
}
