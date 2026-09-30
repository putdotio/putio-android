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
import io.putdotio.android.files.FilesFailure
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
 * shell: fake repositories stand in for the account, the TV session and shell are the
 * production ones, and the player streams a caller-owned local fixture. No API calls.
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

        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
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
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_UP)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { hasContentDescription("Play Old clip.mp4") }
        compose.onNodeWithTag(io.putdotio.android.tv.files.TV_FILES_LIST_TAG).assertExists()
        screenshot("09-files-prior-location-kept")
    }

    private fun mount(): TvSession {
        val session = compose.mountTvProofSession(dependencies())
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
        return tvProofDependencies(
            listings = listings,
            searchResults = listOf(video, folder, document),
            recentSearchStore = { ProofRecentSearchStore() },
            playback = PlaybackResolution.Ready(localSource().copy(startFromSeconds = SAVED_SECONDS), useStartFrom = true),
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
        const val SAVED_SECONDS = 45.0
        const val CONTINUE_LABEL = "Continue playing from 00:45"
        const val MAX_STEPS = 8
        const val STEP_MILLIS = 400L
        const val PLAY_MILLIS = 3_000L
    }
}

private class ProofRecentSearchStore : RecentSearchStoreOwner {
    override val terms = MutableStateFlow<List<SearchTerm>>(emptyList())
    override val enabled = MutableStateFlow<Boolean?>(true)
    override val failure = MutableStateFlow<FilesFailure?>(null)

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
