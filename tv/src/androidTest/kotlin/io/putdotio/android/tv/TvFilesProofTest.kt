package io.putdotio.android.tv

import android.content.Context
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.PutioResult
import io.putdotio.android.account.InactiveAccountNotice
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderCounts
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.FilesRepository
import io.putdotio.android.files.FilesPlaybackProgress
import io.putdotio.android.files.FilesSort
import io.putdotio.android.files.FilesSubtitleLanguages
import io.putdotio.android.files.FilesSubtitles
import io.putdotio.android.PutioFailure
import io.putdotio.android.files.FilesDeleteMode
import io.putdotio.android.files.FilesStreamUrlResult
import io.putdotio.android.files.FilesStreamUrls
import io.putdotio.android.settings.AccountSettingsPreferences
import io.putdotio.sdk.errors.PutioApiErrorEnvelope
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioRequestData
import io.putdotio.sdk.files.FileDeleteResult
import io.putdotio.sdk.files.PutioFileType
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.awaitCancellation
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
    fun longName() {
        val root = FilesFolder.Root.id
        val listings = listings()
        val listed = listings.getValue(root)
        val renamed = listed.items.map { if (it.id.value == BLUE_LIGHTS_ID) it.copy(name = LONG_NAME) else it }
        mount(listings + (root to listed.copy(items = renamed)))
        compose.waitUntil(5_000) { isFocused("Open $BBB") }
        repeat(BLUE_LIGHTS_INDEX) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        compose.waitUntil(5_000) { isFocused("Play $LONG_NAME") }
        waitForText("English, English · SDH")
        screenshot("tv-files-long-name")
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

    @Test
    fun loading() {
        val listings = listings()
        mount(
            listings,
            filesRepository = object : FilesRepository by ProofFilesRepository(listings) {
                override suspend fun loadFolder(folderId: FilesItemId): PutioResult<FilesPage> = awaitCancellation()
            },
        )
        compose.waitUntil(5_000) { isFocused(LOADING) }
        screenshot("androidtv-st-s1-loading")
    }

    @Test
    fun emptyFolder() {
        val root = FilesFolder.Root.id
        val empty = folder(EMPTY_ID, EMPTY, "2026-10-30T09:00:00", root)
        mount(
            mapOf(
                root to FilesPage(listOf(empty), null, sort = FilesSort.NAME_ASCENDING),
                empty.id to FilesPage(emptyList(), null, sort = FilesSort.NAME_ASCENDING, parent = empty, total = 0),
            ),
        )
        compose.waitUntil(5_000) { isFocused("Open $EMPTY") }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Empty folder.").fetchSemanticsNodes().any(::focused)
        }
        compose.focusTvTab("Files")
        screenshot("androidtv-st-s2-empty-folder")
    }

    @Test
    fun couldNotLoad() {
        mount(emptyMap())
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Try again").fetchSemanticsNodes().any(::focused) }
        screenshot("androidtv-st-s3-error")
    }

    @Test
    fun inactiveAccount() {
        val zone = ZoneId.systemDefault()
        val deletion = LocalDate.now(zone).plusDays(INACTIVE_DAYS).atTime(12, 0).atZone(zone).toInstant()
        mount(
            listings(),
            account = TvAccount(
                userId = 1,
                username = "proof",
                email = "proof@example.invalid",
                historyEnabled = true,
                inactiveNotice = InactiveAccountNotice.Deactivated(deletion),
            ),
        )
        compose.waitUntil(5_000) { isFocused("Open $BBB") }
        focusBlueLights()
        waitForText("English, English · SDH")
        screenshot("androidtv-st-s10-inactive")
    }

    @Test
    fun fileActions() {
        mount()
        focusBbbVideo()
        press(KeyEvent.KEYCODE_MENU)
        waitForText("Pin to Home")
        screenshot("androidtv-rde2-06-file-actions")
    }

    @Test
    fun movedToTrash() {
        val root = FilesFolder.Root.id
        val listings = listings()
        val notes = file(NOTES_ID, NOTES, 2_000, "2026-10-30T09:00:00", root).copy(type = PutioFileType.TEXT)
        val listed = listings.getValue(root)
        var trashed = false
        val proof = ProofFilesRepository(listings)
        mount(
            listings,
            filesRepository = object : FilesRepository by proof {
                override suspend fun loadFolder(folderId: FilesItemId): PutioResult<FilesPage> =
                    if (folderId == root && !trashed) {
                        PutioResult.Success(listed.copy(items = listed.items + notes))
                    } else {
                        proof.loadFolder(folderId)
                    }

                override suspend fun delete(itemIds: List<FilesItemId>, mode: FilesDeleteMode):
                    PutioResult<FileDeleteResult> {
                    check(itemIds == listOf(notes.id) && mode == FilesDeleteMode.TRASH)
                    trashed = true
                    return PutioResult.Success(FileDeleteResult(status = "OK"))
                }

                override suspend fun resolveItem(itemId: FilesItemId): PutioResult<FilesItem> =
                    PutioResult.Failure(notFound())
            },
        )
        compose.waitUntil(5_000) { isFocused("Open $BBB") }
        repeat(NOTES_INDEX) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        compose.waitUntil(5_000) { isFocused(NOTES) }
        press(KeyEvent.KEYCODE_MENU)
        waitForText("Pin to Home")
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitForText("Moved to Trash")
        compose.waitUntil(5_000) { isFocused("Open $BBB") }
        repeat(BLUE_LIGHTS_INDEX) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        compose.waitUntil(5_000) { isFocused("Play $BLUE_LIGHTS") }
        waitForText("English, English · SDH")
        screenshot("androidtv-st-t3-moved")
    }

    @Test
    fun deletePermanently() {
        mount(listings(), trashEnabled = false)
        compose.waitUntil(5_000) { isFocused("Open $BBB") }
        focusBbbVideo()
        press(KeyEvent.KEYCODE_MENU)
        waitForText("Delete permanently")
        chooseInSheet("Delete permanently")
        waitForText("Delete permanently?")
        screenshot("androidtv-st-t4-delete")
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { isFocused("Play $BBB_VIDEO") }
    }

    @Test
    fun vlcMissing() {
        mount(listings(), streamUrls = FilesStreamUrls { FilesStreamUrlResult.Ready(UNINSTALLED_VLC_URL) })
        compose.waitUntil(5_000) { isFocused("Open $BBB") }
        focusBbbVideo()
        press(KeyEvent.KEYCODE_MENU)
        waitForText("Open in VLC")
        chooseInSheet("Open in VLC")
        waitForText("VLC isn’t installed")
        screenshot("androidtv-st-d4-notice")
    }

    private fun focusBbbVideo() {
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { isFocused(SUBTITLES) }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.waitUntil(5_000) { isFocused("Play $BBB_VIDEO") }
        waitForText("English · 1 file")
    }

    private fun chooseInSheet(label: String) {
        repeat(SHEET_ROWS) {
            if (!compose.onAllNodesWithText(label).fetchSemanticsNodes().any(::focused)) {
                press(KeyEvent.KEYCODE_DPAD_DOWN)
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().any(::focused) }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    private fun notFound(): PutioFailure = PutioFailure.ApiRejected(
        404,
        "FileNotFound",
        PutioApiException(
            request = PutioRequestData("GET", "https://api.put.io/v2/files/$NOTES_ID"),
            resolvedStatusCode = 404,
            resolvedErrorType = "FileNotFound",
            envelope = PutioApiErrorEnvelope(errorType = "FileNotFound", statusCode = 404),
            responseBody = "{}",
            message = "Not found",
        ),
    )

    private fun focusBlueLights() {
        repeat(BLUE_LIGHTS_INDEX) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        compose.waitUntil(5_000) { isFocused("Play $BLUE_LIGHTS") }
    }

    private fun mount() {
        mount(listings())
        compose.waitUntil(5_000) { isFocused("Open $BBB") }
    }

    private fun listings(): Map<FilesItemId, FilesPage> {
        val root = FilesFolder.Root.id
        val bbb = folder(BBB_ID, BBB, "2026-08-31T09:00:00", root)
        return mapOf(
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
    }

    private fun mount(
        listings: Map<FilesItemId, FilesPage>,
        filesRepository: FilesRepository = ProofFilesRepository(listings),
        account: TvAccount = TvAccount(userId = 1, username = "proof", email = "proof@example.invalid", historyEnabled = true),
        trashEnabled: Boolean = true,
        streamUrls: FilesStreamUrls? = null,
    ) {
        val subtitles = mapOf(
            14L to FilesSubtitles(listOf("English", "English · SDH")),
            22L to FilesSubtitles(listOf("English"), sidecarFiles = 1),
        )
        val proof = tvProofDependencies(
            listings = listings,
            searchResults = emptyList(),
            recentSearchStore = { ProofRecentSearchStore() },
            settings = AccountSettingsPreferences(
                historyEnabled = true,
                trashEnabled = trashEnabled,
                showSubtitles = true,
                autoSelectSubtitles = true,
                resumePlayback = true,
            ),
        )
        val dependencies = proof.copy(
            streamUrls = streamUrls ?: proof.streamUrls,
            filesRepository = filesRepository,
            folderCounts = FilesFolderCounts { id -> PutioResult.Success(listings[id]?.items?.size ?: 0) },
            subtitleLanguages = FilesSubtitleLanguages { id ->
                PutioResult.Success(subtitles[id.value] ?: FilesSubtitles(emptyList()))
            },
        )
        compose.mountTvProofSession(dependencies, account = account)
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
        .any(::focused)

    private fun focused(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Focused) == true

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
        const val BLUE_LIGHTS_ID = 14L
        const val LONG_NAME = "Blue.Lights.S04E01.The.Longest.Night.In.Belfast.Extended.Directors.Cut." +
            "Remastered.From.The.Original.Negative.With.Commentary.By.The.Cast.And.Crew.Plus.Behind.The." +
            "Scenes.Featurette.1080p.HDTV.DDP5.1.H264-ORGANiC.mkv"
        const val EMPTY_ID = 30L
        const val EMPTY = "New folder"
        const val LOADING = "Loading"
        const val INACTIVE_DAYS = 12L
        const val NOTES_ID = 17L
        const val NOTES = "notes.txt"
        const val NOTES_INDEX = 7
        const val SHEET_ROWS = 6
        const val UNINSTALLED_VLC_URL = "https://example.invalid/file.mp4"
    }
}
