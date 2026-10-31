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
import io.putdotio.android.AccountStorage
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPage
import io.putdotio.android.history.HistoryEventId
import io.putdotio.android.history.HistoryEventKind
import io.putdotio.android.history.HistoryFileId
import io.putdotio.android.history.HistoryItem
import io.putdotio.android.history.HistoryTransferId
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.sdk.files.PutioFileType
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
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
class TvShellProofTest {
    private val arguments = InstrumentationRegistry.getArguments()
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("TV shell proof requires opt-in", arguments.getString("putio.tv.shell.enabled") == "true")
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
    fun historyTab() {
        mount(tab = "History")
        compose.waitUntil(5_000) { isFocused("Open $LIVE_ISO") }
        screenshot("androidtv-rde2-11-history")
    }

    @Test
    fun accountTab() {
        mount(tab = "Account")
        compose.waitUntil(5_000) { compose.onAllNodesWithText(PROXY_ROW).fetchSemanticsNodes().isNotEmpty() }
        screenshot("androidtv-rde2-12-account")
    }

    @Test
    fun folder() {
        mount(tab = "Files")
        compose.waitUntil(5_000) { isFocused("Open $FOLDER") }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { isFocused(SUBTITLES) }
        screenshot("androidtv-rde2-05-folder")
    }

    @Test
    fun focusedTab() {
        mount(tab = "Files")
        compose.waitUntil(5_000) { isFocused("Open $FOLDER") }
        compose.focusTvTab("Search")
        screenshot("androidtv-shell-search-tab-focused")
    }

    private fun mount(tab: String) {
        val folder = proofItem(FOLDER_ID, FOLDER, PutioFileType.FOLDER, FilesFolder.Root.id)
        val dependencies = tvProofDependencies(
            listings = mapOf(
                FilesFolder.Root.id to FilesPage(
                    listOf(
                        folder,
                        proofItem(11, "Series", PutioFileType.FOLDER, FilesFolder.Root.id),
                        proofItem(12, "Sintel.2010.1080p.mkv", PutioFileType.VIDEO, FilesFolder.Root.id),
                    ),
                    null,
                ),
                folder.id to FilesPage(
                    listOf(
                        proofItem(21, SUBTITLES, PutioFileType.TEXT, folder.id),
                        proofItem(22, VIDEO, PutioFileType.VIDEO, folder.id),
                        proofItem(23, "poster.jpg", PutioFileType.IMAGE, folder.id),
                    ),
                    null,
                ),
            ),
            searchResults = emptyList(),
            recentSearchStore = { ProofRecentSearchStore() },
            history = historyItems(),
        )
        compose.mountTvProofSession(dependencies, account = proofAccount(), tab = tab)
        compose.waitForIdle()
    }

    private fun historyItems(): List<HistoryItem> {
        val now = LocalDateTime.now(ZoneOffset.UTC)
        val stamp = { days: Long, hour: Int, minute: Int ->
            now.minusDays(days).withHour(hour).withMinute(minute).withSecond(0).format(STAMP)
        }
        return listOf(
            stamp(0, 7, 41) to HistoryEventKind.Transfer(HistoryTransferId(1), HistoryFileId(1), LIVE_ISO),
            stamp(1, 19, 24) to HistoryEventKind.File(HistoryFileId(2), "FFTT Tree Rings October 23, 2026.mp3"),
            stamp(11, 19, 40) to HistoryEventKind.File(HistoryFileId(3), "FFTT Full Report October 20, 2026.mp3"),
            stamp(11, 10, 14) to
                HistoryEventKind.Transfer(HistoryTransferId(4), HistoryFileId(4), "ubuntu-24.04.3-desktop-amd64.iso"),
        ).mapIndexed { index, (stamp, kind) -> HistoryItem(HistoryEventId(100L - index), stamp, kind) }
    }

    private fun proofAccount() = TvAccount(
        userId = 1,
        username = "devs-auto",
        email = "proof@example.invalid",
        historyEnabled = true,
        storage = AccountStorage(
            availableBytes = TOTAL_BYTES - USED_BYTES,
            sizeBytes = TOTAL_BYTES,
            usedBytes = USED_BYTES,
            showOptimisticUsage = false,
        ),
    )

    private fun isFocused(label: String) = compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes()
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
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "tv-shell-proof-${runId()}")
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

    private fun runId(): UUID = UUID.fromString(requireNotNull(arguments.getString("putio.tv.shell.runId")))

    private companion object {
        const val STEP_MILLIS = 400L
        const val FOLDER_ID = 10L
        const val FOLDER = "Big Buck Bunny"
        const val VIDEO = "Big Buck Bunny.mp4"
        const val SUBTITLES = "Big Buck Bunny.en.srt"
        const val LIVE_ISO = "ubuntu-24.04.3-live-server-amd64.iso"
        const val PROXY_ROW = "Choose your proxy"
        const val USED_BYTES = 2_000_000_000_000L
        const val TOTAL_BYTES = 11_000_000_000_000L
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    }
}
