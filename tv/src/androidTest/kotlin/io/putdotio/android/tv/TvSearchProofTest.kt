package io.putdotio.android.tv

import android.content.Context
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderCounts
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesItemResolver
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.FilesPlaybackProgress
import io.putdotio.android.files.FilesSort
import io.putdotio.android.files.FilesSubtitleLanguages
import io.putdotio.android.files.FilesSubtitles
import io.putdotio.android.search.SearchTerm
import io.putdotio.sdk.files.PutioFileType
import java.io.File
import java.util.UUID
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class TvSearchProofTest {
    private val arguments = InstrumentationRegistry.getArguments()
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("TV search proof requires opt-in", arguments.getString("putio.tv.search.enabled") == "true")
                runId()
                base.evaluate()
            }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    @Before
    fun leaveTouchMode() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
    }

    @Test
    fun results() {
        mount(bunnyResults())
        type("bunny")
        compose.waitUntil(5_000) { hasDescription("Play $BBB_VIDEO") }
        repeat(BBB_VIDEO_DOWNS) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        compose.waitUntil(5_000) { isFocused("Play $BBB_VIDEO") }
        waitForText("English · 1 file")
        screenshot("androidtv-rde2-10-search")
    }

    @Test
    fun noResults() {
        mount(emptyList())
        type("tears of stel")
        waitForText("Nothing matches.")
        compose.onNodeWithContentDescription(FIELD).assertIsFocused()
        screenshot("androidtv-st-s4-no-results")
    }

    @Test
    fun settings() {
        mount(bunnyResults())
        press(KeyEvent.KEYCODE_MENU)
        waitForText("Search settings")
        screenshot("search-settings")
    }

    @Test
    fun recentSearches() {
        mount(bunnyResults(), recent = listOf("bunny", "sintel", "tears of steel"))
        compose.waitUntil(5_000) { hasDescription("Search again for sintel") }
        repeat(2) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        compose.waitUntil(5_000) { isFocused("Search again for bunny") }
        screenshot("search-recent")
    }

    @Test
    fun itemActions() {
        mount(bunnyResults())
        type("bunny")
        compose.waitUntil(5_000) { hasDescription("Play $BBB_VIDEO") }
        repeat(BBB_VIDEO_DOWNS) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        compose.waitUntil(5_000) { isFocused("Play $BBB_VIDEO") }
        press(KeyEvent.KEYCODE_MENU)
        waitForText("Pin to Home")
        screenshot("search-item-actions")
    }

    private fun bunnyResults(): List<FilesItem> {
        val root = FilesFolder.Root.id
        val bbb = FilesItemId(BBB_ID)
        return listOf(
            item(BBB_ID, BBB, PutioFileType.FOLDER, 276_000_000, root),
            item(11, "Big Buck Bunny onZoX5Ov", PutioFileType.FOLDER, 31_000_000, root),
            item(22, BBB_VIDEO, PutioFileType.VIDEO, 276_000_000, bbb)
                .copy(playback = FilesPlaybackProgress(190.0, 634.0)),
            item(13, "Big_Buck_Bunny_1080_10s_30MB.mp4", PutioFileType.VIDEO, 31_000_000, root)
                .copy(playback = FilesPlaybackProgress(10.0, 10.0)),
            item(21, "Big Buck Bunny.en.srt", PutioFileType.TEXT, 140, bbb),
        )
    }

    private fun mount(searchResults: List<FilesItem>, recent: List<String> = emptyList()) {
        val root = FilesFolder.Root.id
        val proof = tvProofDependencies(
            listings = mapOf(root to FilesPage(emptyList(), null, sort = FilesSort.NAME_ASCENDING)),
            searchResults = searchResults,
            recentSearchStore = { ProofRecentSearchStore(recent.map(::SearchTerm)) },
        )
        val dependencies = proof.copy(
            folderCounts = FilesFolderCounts { PutioResult.Success(3) },
            subtitleLanguages = FilesSubtitleLanguages { id ->
                val found = if (id.value == 22L) listOf("English") else emptyList()
                PutioResult.Success(FilesSubtitles(found, sidecarFiles = found.size))
            },
            filesItemResolver = object : FilesItemResolver {
                override suspend fun resolveItem(itemId: FilesItemId): PutioResult<FilesItem> =
                    searchResults.firstOrNull { it.id == itemId }?.let { PutioResult.Success(it) }
                        ?: PutioResult.Failure(PutioFailure.Unexpected(IllegalStateException("No item $itemId")))
            },
        )
        compose.mountTvProofSession(dependencies, tab = "Search")
        compose.waitUntil(5_000) { isFocused(FIELD) }
    }

    private fun item(id: Long, name: String, type: PutioFileType, size: Long, parent: FilesItemId) = FilesItem(
        id = FilesItemId(id),
        parentId = parent,
        name = name,
        type = type,
        sizeBytes = size,
        createdAt = "2026-08-31T09:00:00",
    )

    private fun type(text: String) {
        compose.onNodeWithContentDescription(FIELD).assertIsFocused().performTextInput(text)
        compose.waitForIdle()
    }

    private fun hasDescription(label: String) =
        compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty()

    private fun isFocused(label: String) = compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes()
        .any { it.config.getOrNull(SemanticsProperties.Focused) == true }

    private fun waitForText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun press(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
        compose.waitForIdle()
        Thread.sleep(STEP_MILLIS)
    }

    private fun screenshot(label: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        Thread.sleep(SETTLE_MILLIS)
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "tv-search-proof-${runId()}")
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

    private fun runId(): UUID = UUID.fromString(requireNotNull(arguments.getString("putio.tv.search.runId")))

    private companion object {
        const val STEP_MILLIS = 400L
        const val SETTLE_MILLIS = 800L
        const val FIELD = "Search files"
        const val BBB_ID = 10L
        const val BBB = "Big Buck Bunny"
        const val BBB_VIDEO = "Big Buck Bunny.mp4"
        const val BBB_VIDEO_DOWNS = 4
    }
}
