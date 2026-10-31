package io.putdotio.android.tv

import android.content.Context
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderCounts
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.FilesPlaybackProgress
import io.putdotio.android.files.FilesSort
import io.putdotio.android.files.FilesSubtitleLanguages
import io.putdotio.android.files.FilesSubtitles
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
class TvFilesProofTest {
    private val arguments = InstrumentationRegistry.getArguments()
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("TV files proof requires opt-in", arguments.getString("putio.tv.files.enabled") == "true")
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
    fun files() {
        mount()
        focusBlueLights()
        waitForText("English, English · SDH")
        screenshot("androidtv-rde2-04-files")
    }

    @Test
    fun folder() {
        mount()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { isFocused(SUBTITLES) }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.waitUntil(5_000) { isFocused("Play $BBB_VIDEO") }
        waitForText("English · 1 file")
        screenshot("androidtv-rde2-05-folder")
    }

    @Test
    fun sort() {
        mount()
        focusBlueLights()
        repeat(BLUE_LIGHTS_INDEX + 1) { press(KeyEvent.KEYCODE_DPAD_UP) }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitForText("Sort by")
        screenshot("androidtv-st-d1-sort")
    }

    private fun focusBlueLights() {
        repeat(BLUE_LIGHTS_INDEX) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        compose.waitUntil(5_000) { isFocused("Play $BLUE_LIGHTS") }
    }

    private fun mount() {
        val root = FilesFolder.Root.id
        val bbb = folder(BBB_ID, BBB, "2026-08-31T09:00:00", root)
        val listings = mapOf(
            root to FilesPage(
                listOf(
                    bbb,
                    folder(11, "Series", "2026-10-13T09:00:00", root),
                    folder(12, "Items shared with you", "2026-10-02T09:00:00", root),
                    file(13, "Big_Buck_Bunny_1080_10s_30MB.mp4", 31_000_000, "2026-08-25T09:00:00", root)
                        .copy(type = PutioFileType.VIDEO, playback = FilesPlaybackProgress(10.0, 10.0)),
                    file(14, BLUE_LIGHTS, 3_200_000_000, "2026-10-13T09:00:00", root)
                        .copy(type = PutioFileType.VIDEO, playback = FilesPlaybackProgress(1_122.0, 2_989.0)),
                    file(15, "Sintel.2010.1080p.mkv", 1_100_000_000, "2026-09-09T09:00:00", root)
                        .copy(type = PutioFileType.VIDEO, playback = FilesPlaybackProgress(0.0, 888.0)),
                    file(16, "FFTT Tree Rings October 23, 2026.mp3", 48_000_000, "2026-10-23T09:00:00", root)
                        .copy(type = PutioFileType.AUDIO),
                ),
                null,
                sort = FilesSort.NAME_ASCENDING,
            ),
            bbb.id to FilesPage(
                listOf(
                    file(21, SUBTITLES, 140, "2026-08-31T09:00:00", bbb.id).copy(type = PutioFileType.TEXT),
                    file(22, BBB_VIDEO, 276_000_000, "2026-08-31T09:00:00", bbb.id)
                        .copy(type = PutioFileType.VIDEO, playback = FilesPlaybackProgress(190.0, 634.0)),
                    file(23, "poster.jpg", 310_000, "2026-08-31T09:00:00", bbb.id).copy(type = PutioFileType.IMAGE),
                ),
                null,
                sort = FilesSort.NAME_ASCENDING,
                parent = bbb,
                total = 3,
            ),
        )
        val subtitles = mapOf(
            14L to FilesSubtitles(listOf("English", "English · SDH")),
            22L to FilesSubtitles(listOf("English"), sidecarFiles = 1),
        )
        val dependencies = tvProofDependencies(
            listings = listings,
            searchResults = emptyList(),
            recentSearchStore = { ProofRecentSearchStore() },
        ).copy(
            folderCounts = FilesFolderCounts { id -> PutioResult.Success(listings[id]?.items?.size ?: 0) },
            subtitleLanguages = FilesSubtitleLanguages { id ->
                PutioResult.Success(subtitles[id.value] ?: FilesSubtitles(emptyList()))
            },
        )
        compose.mountTvProofSession(dependencies)
        compose.waitUntil(5_000) { isFocused("Open $BBB") }
    }

    private fun folder(id: Long, name: String, createdAt: String, parent: FilesItemId) =
        file(id, name, 276_000_000, createdAt, parent).copy(type = PutioFileType.FOLDER)

    private fun file(id: Long, name: String, size: Long, createdAt: String, parent: FilesItemId) = FilesItem(
        id = FilesItemId(id),
        parentId = parent,
        name = name,
        type = PutioFileType.FILE,
        sizeBytes = size,
        createdAt = createdAt,
    )

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
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "tv-files-proof-${runId()}")
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

    private fun runId(): UUID = UUID.fromString(requireNotNull(arguments.getString("putio.tv.files.runId")))

    private companion object {
        const val STEP_MILLIS = 400L
        const val SETTLE_MILLIS = 800L
        const val BBB_ID = 10L
        const val BBB = "Big Buck Bunny"
        const val BBB_VIDEO = "Big Buck Bunny.mp4"
        const val SUBTITLES = "Big Buck Bunny.en.srt"
        const val BLUE_LIGHTS = "Blue.Lights.S04E01.1080p.HDTV.H264-ORGANiC.mkv"
        const val BLUE_LIGHTS_INDEX = 4
    }
}
