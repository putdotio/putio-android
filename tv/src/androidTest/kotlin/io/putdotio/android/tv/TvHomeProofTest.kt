package io.putdotio.android.tv

import android.content.Context
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.PutioResult
import io.putdotio.android.files.ContinueWatchingEntry
import io.putdotio.android.files.ContinueWatchingStore
import io.putdotio.android.files.FakePinnedFiles
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderCounts
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesItemResolver
import io.putdotio.android.files.FilesPage
import io.putdotio.android.settings.AccountSettingsPreferences
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.android.tv.home.TvShowHomeStore
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
class TvHomeProofTest {
    private val arguments = InstrumentationRegistry.getArguments()
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("TV home proof requires opt-in", arguments.getString("putio.tv.home.enabled") == "true")
                runId()
                base.evaluate()
            }
        }
    }
    private var focusManager: FocusManager? = null

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    @Before
    fun leaveTouchMode() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
    }

    @Test
    fun home() {
        mount(entries = CONTINUE_WATCHING, pins = PINNED.map { it.id })
        compose.waitUntil(5_000) { hasText("Big Buck Bunny") }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.waitUntil(5_000) { isFocused("Big Buck Bunny") }
        screenshot("androidtv-rde2-02-home")
    }

    @Test
    fun homeWithNothingPinned() {
        mount(entries = CONTINUE_WATCHING, pins = emptyList())
        compose.waitUntil(5_000) { hasText("Nothing pinned yet.") }
        compose.runOnUiThread { checkNotNull(focusManager).clearFocus(force = true) }
        screenshot("androidtv-rde2-03-home-empty")
    }

    @Test
    fun firstRunHome() {
        mount(entries = emptyList(), pins = emptyList())
        compose.waitUntil(5_000) { hasText("Browse your files") }
        screenshot("home-first-run")
    }

    @Test
    fun showHomeSwitch() {
        mount(entries = CONTINUE_WATCHING, pins = emptyList(), tab = "Account")
        compose.waitUntil(5_000) { isFocused("Library") }
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        repeat(SHOW_HOME_ROW) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        compose.waitUntil(5_000) { isFocused("Show Home") }
        screenshot("account-library-show-home")
    }

    private fun mount(entries: List<ContinueWatchingEntry>, pins: List<FilesItemId>, tab: String? = null) {
        val items = (PINNED + entries.map { it.toFilesItem() }).associateBy { it.id }
        val dependencies = tvProofDependencies(
            listings = mapOf(FilesFolder.Root.id to FilesPage(PINNED, null)),
            searchResults = emptyList(),
            recentSearchStore = { ProofRecentSearchStore() },
            settings = AccountSettingsPreferences(
                historyEnabled = true,
                trashEnabled = true,
                showSubtitles = true,
                autoSelectSubtitles = true,
                resumePlayback = true,
            ),
        ).copy(
            filesItemResolver = object : FilesItemResolver {
                override suspend fun resolveItem(itemId: FilesItemId) = PutioResult.Success(items.getValue(itemId))
            },
            folderCounts = FilesFolderCounts { PutioResult.Success(FOLDER_COUNTS[it]) },
            pinnedFiles = { FakePinnedFiles(pins) },
            continueWatchingStore = { ProofContinueWatchingStore(entries) },
            showHomeStore = { TvShowHomeStore.Memory() },
        )
        compose.mountTvProofSession(
            dependencies,
            account = TvAccount(userId = 1, username = "devs-auto", email = "devs+auto@put.io", historyEnabled = true),
            tab = tab,
            overlay = { focusManager = LocalFocusManager.current },
        )
    }

    private class ProofContinueWatchingStore(private var entries: List<ContinueWatchingEntry>) : ContinueWatchingStore {
        override fun read(): List<ContinueWatchingEntry> = entries

        override fun write(entries: List<ContinueWatchingEntry>) {
            this.entries = entries
        }
    }

    private fun hasText(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun isFocused(label: String) =
        (compose.onAllNodesWithText(label).fetchSemanticsNodes() +
            compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes())
            .any { it.config.getOrNull(SemanticsProperties.Focused) == true }

    private fun press(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
        compose.waitForIdle()
        Thread.sleep(STEP_MILLIS)
    }

    private fun screenshot(label: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        Thread.sleep(STEP_MILLIS)
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "tv-home-proof-${runId()}")
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

    private fun runId(): UUID = UUID.fromString(requireNotNull(arguments.getString("putio.tv.home.runId")))

    private companion object {
        const val STEP_MILLIS = 400L
        const val SHOW_HOME_ROW = 4
        val ROOT = FilesFolder.Root.id

        fun entry(id: Long, name: String, position: Double, duration: Double, watchedAt: Long) =
            ContinueWatchingEntry(FilesItemId(id), name, position, duration, watchedAt)

        val CONTINUE_WATCHING = listOf(
            entry(101, "Tears.of.Steel.2012.1080p.mkv", 411.0, 663.0, 4L),
            entry(102, "Night.of.the.Living.Dead.1968.mkv", 970.0, 5_390.0, 3L),
            entry(103, "Sintel.2010.1080p.mkv", 356.0, 891.0, 2L),
            entry(104, "Cosmos.Laundromat.2015.mkv", 610.0, 735.0, 1L),
        )

        fun pinned(id: Long, name: String, type: PutioFileType, size: Long) =
            FilesItem(FilesItemId(id), ROOT, name, type, size, "2026-10-23T10:00:00Z")

        val PINNED = listOf(
            pinned(201, "Series", PutioFileType.FOLDER, 1_210_000_000_000L),
            pinned(202, "Big Buck Bunny", PutioFileType.FOLDER, 276_000_000L),
            pinned(203, "Items shared with you", PutioFileType.FOLDER, 0L),
            pinned(204, "FFTT Tree Rings October 23, 2026.mp3", PutioFileType.AUDIO, 48_000_000L),
        )

        val FOLDER_COUNTS = mapOf(FilesItemId(201) to 12, FilesItemId(202) to 3, FilesItemId(203) to 2)
    }
}
