package io.putdotio.android

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.account.MobileOpenSourceLicensesScreen
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.downloads.DownloadArtifact
import io.putdotio.android.downloads.DownloadEntry
import io.putdotio.android.downloads.DownloadPauseReason
import io.putdotio.android.downloads.DownloadStatus
import io.putdotio.android.downloads.DownloadsState
import io.putdotio.android.downloads.MOBILE_DOWNLOADS_CONCURRENCY_TAG
import io.putdotio.android.downloads.MOBILE_DOWNLOADS_OVERFLOW_TAG
import io.putdotio.android.downloads.MobileDownloadsScreen
import io.putdotio.android.downloads.MobileDownloadsTopBar
import io.putdotio.android.downloads.rememberDownloadsSelection
import io.putdotio.android.downloads.withEntries
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesTextPreview
import io.putdotio.android.search.MobileSearchScreen
import io.putdotio.android.search.SearchContent
import io.putdotio.android.search.SearchPaging
import io.putdotio.android.search.SearchTerm
import io.putdotio.android.search.SearchTotal
import io.putdotio.android.search.searchState
import io.putdotio.android.viewer.MobileTextLines
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w400dp-h1200dp")
class MobileDesignPolishTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun openSourceLicensesUsesMonoSpanForVersions() {
        compose.setContent { PutioTheme { MobileOpenSourceLicensesScreen() } }
        compose.waitForIdle()
        val notice = appSourceNotice(BuildConfig.VERSION_NAME)
        val label = "${notice.name} ${notice.version}"
        val text = compose.onNodeWithText(label, useUnmergedTree = true).textLayout().layoutInput.text
        assertMonoSpan(text, notice.version)
    }

    @Test
    fun searchTotalUsesMonoSpanForFormattedCount() {
        val item = FilesItem(
            id = FilesItemId(1),
            parentId = FilesItemId(0),
            name = "Sintel.mp4",
            type = PutioFileType.VIDEO,
            sizeBytes = 1024L,
            createdAt = "2026-04-20T10:00:00Z",
        )
        compose.setContent {
            PutioTheme {
                MobileSearchScreen(
                    searchState = searchState(
                        query = "sintel",
                        content = SearchContent.Ready(
                            SearchTerm("sintel"),
                            listOf(item),
                            SearchPaging.Complete,
                            SearchTotal.AtLeast(1000),
                        ),
                        recentTerms = emptyList(),
                        nextRequestValue = 2L,
                    ),
                    recentSearchFailure = null,
                    onSearchResult = {},
                    onSearchNextPage = {},
                    onSearchRetry = {},
                    onRecentSearch = {},
                    onRecentEdit = {},
                    onRecentRetry = {},
                )
            }
        }
        val text = compose.onNodeWithText("1,000+ results").textLayout().layoutInput.text
        assertMonoSpan(text, "1,000+")
    }

    @Test
    fun truncatedTextViewerUsesMonoSpanForByteLimit() {
        compose.setContent {
            PutioTheme {
                MobileTextLines(
                    preview = FilesTextPreview(listOf("line 1"), truncated = true),
                    onOpenWithAnotherApp = {},
                )
            }
        }
        val text = compose.onNodeWithText("Showing the first", substring = true).textLayout().layoutInput.text
        assertMonoSpan(text, "250 kB")
    }

    @Test
    fun downloadsConcurrencyChoiceAndSheetMetaUseMonoSpans() {
        val entry = DownloadEntry(
            fileId = FilesItemId(10L),
            name = "Episode.mkv",
            type = PutioFileType.VIDEO,
            artifact = DownloadArtifact.HLS,
            status = DownloadStatus.Paused(DownloadPauseReason.USER, bytesDownloaded = 2_400_000L),
            createdAt = 1L,
            queuedAt = 1L,
        )
        val state = DownloadsState(concurrency = 2).withEntries(listOf(entry))
        compose.setContent {
            PutioTheme {
                val selection = rememberDownloadsSelection()
                Column {
                    MobileDownloadsTopBar(
                        title = "Downloads",
                        summary = null,
                        state = state,
                        selection = selection,
                        onEvent = { true },
                    )
                    MobileDownloadsScreen(state = state, onEvent = { true }, onPlay = {})
                }
            }
        }
        compose.onNodeWithTag(MOBILE_DOWNLOADS_OVERFLOW_TAG).performClick()
        compose.onNodeWithTag(MOBILE_DOWNLOADS_CONCURRENCY_TAG).performClick()
        val concurrencyText = compose
            .onNodeWithText("2 at a time", useUnmergedTree = true)
            .textLayout()
            .layoutInput
            .text
        assertMonoSpan(concurrencyText, "2")
        compose.onNodeWithText("Cancel").performClick()

        compose.onNodeWithText("Episode.mkv").performClick()
        val sheetMeta = compose
            .onNodeWithText("Paused · 2.4 MB saved", useUnmergedTree = true)
            .textLayout()
            .layoutInput
            .text
        assertMonoSpan(sheetMeta, "2.4 MB")
    }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.first()
    }

    private fun assertMonoSpan(text: AnnotatedString, value: String) {
        val runs = text.spanStyles
            .filter {
                it.item.fontFamily == PutioTypeScale.mono.fontFamily &&
                    it.item.fontFeatureSettings?.contains("tnum") == true
            }
            .map { text.text.substring(it.start, it.end) }
        assertTrue("Expected mono tnum span for '$value', got $runs in '${text.text}'", value in runs)
    }
}
