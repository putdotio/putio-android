package io.putdotio.android.tv

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.LibraryNotice
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.PlaybackUpNext
import io.putdotio.android.playback.PlaybackUpNextLookup
import io.putdotio.android.playback.playbackState
import io.putdotio.android.search.SearchContent
import io.putdotio.android.search.SearchPaging
import io.putdotio.android.search.SearchTerm
import io.putdotio.android.search.SearchTotal
import io.putdotio.android.search.searchState
import io.putdotio.android.tv.account.TvLibrariesSheet
import io.putdotio.android.tv.files.TvFilesNotice
import io.putdotio.android.tv.player.TV_CONVERSION_PERCENT_TAG
import io.putdotio.android.tv.player.TvPlayerOverlay
import io.putdotio.android.tv.player.TvPlayerScreen
import io.putdotio.android.tv.player.TvUpNextOverlay
import io.putdotio.android.tv.search.TvRecentSearchActions
import io.putdotio.android.tv.search.TvSearchActions
import io.putdotio.android.tv.search.TvSearchScreen
import io.putdotio.android.tv.search.tvSearchDetail
import io.putdotio.android.tv.search.tvSearchResults
import io.putdotio.sdk.files.PlaybackConversionState
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w960dp-h540dp-television")
class TvDesignPolishTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun filesErrorNoticeUsesRedTextSecondaryForContrast() {
        compose.setTvContent {
            TvFilesNotice(text = "Could not load files.", error = true)
        }
        val layout = compose.onNodeWithText("Could not load files.").textLayout()
        assertEquals(PutioDesignTokens.redTextSecondary, layout.layoutInput.style.color)
    }

    @Test
    fun conversionPercentPreservesMonoFontAndTabularNumbers() {
        val state = playbackState(
            target = PlaybackTarget(FilesItemId(1), "Sintel.mkv", PlaybackMediaType.VIDEO),
            content = PlaybackContent.Conversion(PlaybackConversionState.Converting(42.0)),
            nextRequestValue = 1L,
        )
        compose.setTvContent {
            TvPlayerScreen(state = state, onBack = {}, onRetry = {}, onPlayerFailure = { _, _ -> })
        }
        val style = compose.onNodeWithTag(TV_CONVERSION_PERCENT_TAG).textLayout().layoutInput.style
        assertEquals(PutioTypeScale.tvMono.fontFamily, style.fontFamily)
        assertEquals(PutioTypeScale.tvMono.fontFeatureSettings, style.fontFeatureSettings)
    }

    @Test
    fun upNextCountdownPreservesMonoFontAndTabularNumbers() {
        val next = PlaybackTarget(FilesItemId(2), "Episode 02.mkv", PlaybackMediaType.VIDEO)
        compose.setTvContent {
            TvUpNextOverlay(
                overlay = TvPlayerOverlay(upNext = true),
                lookup = PlaybackUpNextLookup.Found(PlaybackUpNext(next, folderName = "Season 1")),
                update = {},
            )
        }
        val text = compose.onNodeWithText("Playing in", substring = true).textLayout().layoutInput.text
        val monoSpan = text.spanStyles.firstOrNull {
            it.item.fontFamily == PutioTypeScale.tvMono.fontFamily &&
                it.item.fontFeatureSettings == PutioTypeScale.tvMono.fontFeatureSettings
        }
        assertTrue("Expected mono tnum span on Up Next countdown", monoSpan != null)
    }

    @Test
    fun searchTotalUsesMonoTabularSpan() {
        val item = FilesItem(
            id = FilesItemId(1),
            parentId = FilesItemId(0),
            name = "Sintel.mp4",
            type = PutioFileType.VIDEO,
            sizeBytes = 1024L,
            createdAt = "2026-04-20T10:00:00Z",
        )
        compose.setTvContent {
            TvSearchScreen(
                state = searchState(
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
                actions = TvSearchActions(
                    onQueryChanged = {},
                    onSubmit = {},
                    onResult = {},
                    onNextPage = {},
                    onRetry = {},
                    recent = TvRecentSearchActions({}, {}, {}),
                ),
                results = tvSearchResults(onFilter = {}, onPlay = { _, _ -> }),
                detail = tvSearchDetail(),
            )
        }
        val totalText = compose.onNodeWithText("1,000+ results").textLayout().layoutInput.text
        assertMonoSpan(totalText, "1,000+")
        val span = totalText.spanStyles.first { totalText.text.substring(it.start, it.end) == "1,000+" }
        assertEquals(PutioTypeScale.tvMono.fontSize, span.item.fontSize)
    }

    @Test
    fun librariesSheetUsesMonoSpanOnVersion() {
        val libraries = listOf(
            LibraryNotice(
                id = "org.example:demo",
                name = "DemoLib",
                version = "2.4.1",
                license = "MIT",
                url = "https://example.com",
            ),
        )
        compose.setTvContent {
            TvLibrariesSheet(
                libraries = libraries,
                selected = 0,
                onOpen = {},
                onDismiss = {},
                returnFocus = null,
            )
        }
        val labelText = compose.onNodeWithText("DemoLib 2.4.1").textLayout().layoutInput.text
        assertMonoSpan(labelText, "2.4.1")
    }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.first()
    }

    private fun assertMonoSpan(text: AnnotatedString, value: String) {
        val runs = text.spanStyles
            .filter {
                it.item.fontFamily == PutioTypeScale.tvMono.fontFamily &&
                    it.item.fontFeatureSettings == PutioTypeScale.tvMono.fontFeatureSettings
            }
            .map { text.text.substring(it.start, it.end) }
        assertTrue("Expected mono tnum span for '$value', got $runs in '${text.text}'", value in runs)
    }
}
