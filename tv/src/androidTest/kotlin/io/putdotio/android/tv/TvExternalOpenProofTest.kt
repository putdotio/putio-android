package io.putdotio.android.tv

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.PutioFailure
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.FilesPlaybackProgress
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.search.RecentSearchStoreOwner
import io.putdotio.android.search.SearchTerm
import io.putdotio.android.tv.player.TV_PLAYER_TAG
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import io.putdotio.sdk.files.PutioFileType
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * Controlled-state proof that a Search pick opens the item itself on the real signed-in TV
 * shell, and that Back returns to Files: fake repositories stand in for the account, the TV
 * session and shell are the production ones, and the player streams a caller-owned local
 * fixture. No API calls.
 */
@RunWith(AndroidJUnit4::class)
class TvExternalOpenProofTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("TV open proof requires opt-in", arguments.getString("putio.tv.open.enabled") == "true")
                runId()
                base.evaluate()
            }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    @Test
    fun searchPicksPlayOrOpenTheItemAndBackReturnsToTheResults() {
        val session = mount()

        // The prior Files location, which the outside opens must keep.
        compose.onNodeWithContentDescription("Open Movies").assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { hasContentDescription("Play Old clip.mp4") }
        screenshot("01-files-prior-location")

        compose.openTvTab("Search")
        // Typed into the field, as `adb shell input text` does; the pane owns the field's text.
        compose.onNodeWithContentDescription("Search files").assertIsFocused().performTextInput("proof")
        compose.waitUntil(5_000) { hasContentDescription("Play $VIDEO") }

        // A video result plays at once, after the resume prompt.
        focus("Play $VIDEO")
        screenshot("02-search-results")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(CONTINUE_LABEL).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(CONTINUE_LABEL).assertIsFocused()
        screenshot("03-resume-prompt")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(TV_PLAYER_TAG).fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(PLAY_MILLIS)
        screenshot("04-playing")
        // Back hides the controls, then leaves playback.
        press(KeyEvent.KEYCODE_BACK)
        if (compose.runOnIdle { session.playback.value != null }) press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { compose.runOnIdle { session.playback.value == null } }
        compose.waitUntil(10_000) { isFocused("Play $VIDEO") }
        screenshot("05-back-on-the-played-result")

        // Any other file opens its folder, titled and focused on it; Back returns to the results.
        focus("Open $DOCUMENT")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { hasContentDescription(DOCUMENT) }
        compose.onNodeWithContentDescription(DOCUMENT).assertIsFocused()
        assertEquals(1, compose.onAllNodesWithText(FOLDER).fetchSemanticsNodes().size)
        screenshot("06-document-in-its-folder")
        press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { isFocused("Open $DOCUMENT") }
        screenshot("07-back-on-the-document-result")

        // A folder opens under its name; Back returns to the results.
        focus("Open $FOLDER")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { hasContentDescription(DOCUMENT) }
        assertEquals(1, compose.onAllNodesWithText(FOLDER).fetchSemanticsNodes().size)
        screenshot("08-folder")
        press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { isFocused("Open $FOLDER") }

        // Files still holds the location the viewer left.
        compose.openTvTab("Files")
        compose.waitUntil(5_000) { hasContentDescription("Play Old clip.mp4") }
        compose.onNodeWithTag(io.putdotio.android.tv.files.TV_FILES_LIST_TAG).assertExists()
        screenshot("09-files-prior-location-kept")
    }

    @Test
    fun backMovesToTheTabBarThenHomeAndEachTabKeepsItsPlace() {
        var exits = 0
        mount(onExit = { exits += 1 })
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithContentDescription("Open $FOLDER").assertIsFocused()
        screenshot("10-files-row-focused")

        listOf(
            "Search" to { compose.onNodeWithContentDescription(SEARCH_FIELD).assertIsFocused() },
            HISTORY to {
                compose.waitUntil(5_000) { compose.onAllNodesWithText(NO_HISTORY).fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(hasText(CLEAR) and hasClickAction()).assertIsFocused()
            },
            "Account" to {
                compose.waitUntil(5_000) { compose.onAllNodesWithText(PROXY_ROW).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(PROXY_ROW).assertIsFocused()
            },
        ).forEachIndexed { index, (tab, assertEntry) ->
            compose.openTvTab(tab)
            assertEntry()
            screenshot("${11 + index * 3}-${tab.lowercase()}-entered")
            press(KeyEvent.KEYCODE_BACK)
            compose.onTvTab(tab).assertIsFocused()
            screenshot("${12 + index * 3}-${tab.lowercase()}-back-focuses-its-tab")
            press(KeyEvent.KEYCODE_BACK)
            compose.onTvTab("Home").assertIsFocused()
            screenshot("${13 + index * 3}-${tab.lowercase()}-tab-bar-back-goes-home")
            compose.openTvTab("Files")
            compose.waitUntil(5_000) { isFocused("Open $FOLDER") }
        }
        assertEquals(0, exits)

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { isFocused(FIRST_SCAN) }
        screenshot("20-folder-under-its-crumb")
        press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { isFocused("Open $FOLDER") }
        screenshot("21-folder-popped-to-its-row")
        press(KeyEvent.KEYCODE_BACK)
        compose.onTvTab("Files").assertIsFocused()
        press(KeyEvent.KEYCODE_BACK)
        compose.onTvTab("Home").assertIsFocused()
        assertEquals(0, exits)
        press(KeyEvent.KEYCODE_BACK)
        assertEquals(1, exits)
    }

    @Test
    fun aPickOnALaterPageOfItsFolderOpensFocusedOnIt() {
        val session = compose.mountTvProofSession(pagedDependencies())
        compose.waitUntil(5_000) { hasContentDescription("Open $PAGED_FOLDER") }
        compose.openTvTab("Search")
        compose.onNodeWithContentDescription(SEARCH_FIELD).assertIsFocused().performTextInput("harbor")
        compose.waitUntil(5_000) { hasContentDescription("Open $PAGED_DOCUMENT") }
        focus("Open $PAGED_DOCUMENT")
        screenshot("22-paged-result")

        // Back while later pages are read leaves the folder and cancels the reads.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(LOADING).fetchSemanticsNodes().isNotEmpty() }
        screenshot("23-reading-later-pages")
        press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { isFocused("Open $PAGED_DOCUMENT") }
        assertEquals(listOf(FilesFolder.Root), compose.runOnIdle { session.files.state.value.path })
        screenshot("24-back-cancels-the-reads")

        // Left alone, the folder reads on to the document's page and opens focused on it.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { isFocused(PAGED_DOCUMENT) }
        assertEquals(1, compose.onAllNodesWithText(PAGED_FOLDER).fetchSemanticsNodes().size)
        screenshot("25-document-on-page-three-focused")
        press(KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithContentDescription("Scan $PAGED_DOCUMENT_INDEX.jpg").assertIsFocused()
        screenshot("26-rows-above-it-loaded")
    }

    private fun mount(onExit: (() -> Unit)? = null): TvSession {
        val session = compose.mountTvProofSession(dependencies(), onExit)
        compose.waitUntil(5_000) { hasContentDescription("Open Movies") }
        return session
    }

    private fun dependencies(): TvSessionDependencies {
        val movies = proofItem(MOVIES_ID, "Movies", PutioFileType.FOLDER, FilesFolder.Root.id)
        val folder = proofItem(FOLDER_ID, FOLDER, PutioFileType.FOLDER, FilesFolder.Root.id)
        val video = proofItem(VIDEO_ID, VIDEO, PutioFileType.VIDEO, folder.id)
        val document = proofItem(DOCUMENT_ID, DOCUMENT, PutioFileType.PDF, folder.id)
        val listings = mapOf(
            FilesFolder.Root.id to FilesPage(listOf(movies, folder), null),
            movies.id to FilesPage(
                listOf(proofItem(30, "Old clip.mp4", PutioFileType.VIDEO, movies.id)),
                null,
                parent = movies,
            ),
            folder.id to FilesPage(
                (1L..6L).map { proofItem(100 + it, "Scan $it.jpg", PutioFileType.IMAGE, folder.id) } + document + video,
                null,
                parent = folder,
            ),
            // Listing a file returns it as the parent with its duration, as put.io does.
            video.id to FilesPage(emptyList(), null, parent = video.copy(playback = FilesPlaybackProgress(0.0, 90.0))),
        )
        // Only the playback proof reads the fixture.
        return tvProofDependencies(
            listings = listings,
            searchResults = listOf(video, folder, document),
            recentSearchStore = { ProofRecentSearchStore() },
            playback = { PlaybackResolution.Ready(localSource().copy(startFromSeconds = SAVED_SECONDS), useStartFrom = true) },
        )
    }

    /**
     * [PAGED_FOLDER] lists 3 pages of 50 rows, each later page after a delay long enough to
     * press Back during it; [PAGED_DOCUMENT] is on the third page.
     */
    private fun pagedDependencies(): TvSessionDependencies {
        val folder = proofItem(PAGED_FOLDER_ID, PAGED_FOLDER, PutioFileType.FOLDER, FilesFolder.Root.id)
        val document = proofItem(PAGED_DOCUMENT_ID, PAGED_DOCUMENT, PutioFileType.PDF, folder.id)
        val scans = (1 until PAGE_COUNT * PAGE_SIZE).map {
            proofItem(200L + it, "Scan $it.jpg", PutioFileType.IMAGE, folder.id)
        }
        val rows = scans.take(PAGED_DOCUMENT_INDEX) + document + scans.drop(PAGED_DOCUMENT_INDEX)
        val pages = rows.chunked(PAGE_SIZE).mapIndexed { index, items ->
            FilesPage(
                items = items,
                nextCursor = FilesCursor("page-${index + 1}").takeIf { index + 1 < PAGE_COUNT },
                parent = folder.takeIf { index == 0 },
            )
        }
        return tvProofDependencies(
            listings = mapOf(FilesFolder.Root.id to FilesPage(listOf(folder), null), folder.id to pages.first()),
            continuations = pages.drop(1).mapIndexed { index, page -> FilesCursor("page-${index + 1}") to page }.toMap(),
            pageDelayMillis = PAGE_DELAY_MILLIS,
            searchResults = listOf(document),
            recentSearchStore = { ProofRecentSearchStore() },
        )
    }

    private fun hasContentDescription(label: String) =
        compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty()

    private fun isFocused(label: String) = compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes()
        .any { it.config.getOrNull(SemanticsProperties.Focused) == true }

    /** Walks the D-pad down, then up, until [label] holds focus. */
    private fun focus(label: String) {
        repeat(MAX_STEPS) { if (!isFocused(label)) press(KeyEvent.KEYCODE_DPAD_DOWN) }
        repeat(MAX_STEPS) { if (!isFocused(label)) press(KeyEvent.KEYCODE_DPAD_UP) }
        compose.onNodeWithContentDescription(label).assertIsFocused()
    }

    private fun press(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
        compose.waitForIdle()
        Thread.sleep(STEP_MILLIS)
    }

    /**
     * Copies the fixture adb pushed into the app's own files directory: a directory adb creates
     * under `Android/data` is not readable to the app, and the app cannot read `/data/local/tmp`.
     */
    private fun localSource(): PlaybackSource {
        val pushed = requireNotNull(arguments.getString("putio.tv.open.fixture"))
        val file = File(requireNotNull(context.getExternalFilesDir(null)), "tv-open-fixture.mp4")
        val copy = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("cp $pushed ${file.absolutePath}")
        ParcelFileDescriptor.AutoCloseInputStream(copy).use { it.readBytes() }
        require(file.isFile && file.canRead()) { "Fixture is not readable: $file (pushed as $pushed)" }
        // The SDK owns production URLs. This proof reads only its caller-owned local fixture.
        val url = PutioCredentialUrl::class.java.getDeclaredConstructor(String::class.java)
            .newInstance(Uri.fromFile(file).toString())
        return PlaybackSource(
            fileId = VIDEO_ID,
            kind = PlaybackSourceKind.ORIGINAL,
            url = url,
            startFromSeconds = 0.0,
            subtitles = PlaybackSubtitles.None,
        )
    }

    private fun screenshot(label: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "tv-open-proof-${runId()}")
        check(directory.mkdirs() || directory.isDirectory)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "$label.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun runId(): UUID = UUID.fromString(requireNotNull(arguments.getString("putio.tv.open.runId")))
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        const val MOVIES_ID = 5L
        const val FOLDER_ID = 44L
        const val VIDEO_ID = 9_350_001L
        const val DOCUMENT_ID = 9_350_002L
        const val FOLDER = "Documents"
        const val VIDEO = "TV open proof.mp4"
        const val DOCUMENT = "notes.pdf"
        const val FIRST_SCAN = "Scan 1.jpg"
        const val SEARCH_FIELD = "Search files"
        const val NO_HISTORY = "No activity yet."
        const val HISTORY = "History"
        const val CLEAR = "Clear"
        const val PROXY_ROW = "Choose your proxy"
        const val SAVED_SECONDS = 45.0
        const val CONTINUE_LABEL = "Continue playing from 00:45"
        const val PAGED_FOLDER_ID = 45L
        const val PAGED_FOLDER = "Sample folder"
        const val PAGED_DOCUMENT_ID = 9_350_003L
        const val PAGED_DOCUMENT = "Harbor notes.pdf"
        const val PAGE_COUNT = 3
        const val PAGE_SIZE = 50
        // The third page's 13th row.
        const val PAGED_DOCUMENT_INDEX = 112
        const val PAGE_DELAY_MILLIS = 1_500L
        const val LOADING = "Loading"
        const val MAX_STEPS = 8
        const val STEP_MILLIS = 400L
        const val PLAY_MILLIS = 3_000L
    }
}

internal class ProofRecentSearchStore : RecentSearchStoreOwner {
    override val terms = MutableStateFlow<List<SearchTerm>>(emptyList())
    override val enabled = MutableStateFlow<Boolean?>(true)
    override val failure = MutableStateFlow<PutioFailure?>(null)

    override fun record(term: SearchTerm) {
        terms.value = listOf(term) + terms.value.filterNot { it == term }
    }

    override fun remove(term: SearchTerm) {
        terms.value = terms.value.filterNot { it == term }
    }

    override fun clear() {
        terms.value = emptyList()
    }

    override fun setEnabled(enabled: Boolean) {
        this.enabled.value = enabled
    }

    override fun retry() = Unit

    override fun close() = Unit
}
