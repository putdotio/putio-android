package io.putdotio.android.tv

import android.content.Context
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPage
import io.putdotio.android.history.HistoryEventId
import io.putdotio.android.history.HistoryEventKind
import io.putdotio.android.history.HistoryFileId
import io.putdotio.android.history.HistoryItem
import io.putdotio.android.history.HistoryNoticeType
import io.putdotio.android.history.HistoryPage
import io.putdotio.android.history.HistoryRepository
import io.putdotio.android.history.HistoryTransferId
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.sdk.files.PutioFileType
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * Board captures of History on the signed-in TV shell, and Clear history and Open folder driven
 * with the D-pad. Every repository is fake, so clearing never reaches an account. No API calls.
 */
@RunWith(AndroidJUnit4::class)
class TvHistoryProofTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("TV history proof requires opt-in", arguments.getString("putio.tv.history.enabled") == "true")
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
    fun historyListsEventsBesideTheFocusedEventsFile() {
        mount(FakeHistory(events()))
        compose.openTvTab("History")
        waitFor(hasText("Your Files"))
        waitFor(hasContentDescription("Open $SERVER_ISO") and isFocused())
        screenshot("androidtv-rde2-11-history")
    }

    @Test
    fun historyOffOpensAccount() {
        mount(FakeHistory(events()), historyEnabled = false)
        compose.openTvTab("History")
        waitFor(hasText("Open Account") and isFocused())
        screenshot("androidtv-st-s5-history-off")

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Keep account history"))
        screenshot("tv-history-off-opened-account")
    }

    @Test
    fun clearHistoryConfirmsWithTheRedButtonAndEmptiesTheList() {
        val history = FakeHistory(events())
        mount(history)
        compose.openTvTab("History")
        waitFor(hasContentDescription("Open $SERVER_ISO") and isFocused())

        press(KeyEvent.KEYCODE_DPAD_UP)
        waitFor(hasText("Clear history") and hasClickAction() and isFocused())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Clear history?"))
        screenshot("tv-history-clear-confirm")

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("No activity yet."))
        assertEquals(1, history.clears)
        screenshot("tv-history-cleared")
    }

    @Test
    fun openFolderShowsTheFileInFiles() {
        mount(FakeHistory(events()))
        compose.openTvTab("History")
        waitFor(hasText("Your Files"))
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFor(hasText("Open folder") and isFocused())
        screenshot("tv-history-open-folder-focused")

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasContentDescription(SERVER_ISO) and isFocused())
        screenshot("tv-history-opened-in-files")
    }

    private fun mount(history: HistoryRepository, historyEnabled: Boolean = true) {
        val dependencies = tvProofDependencies(
            listings = mapOf(FilesFolder.Root.id to FilesPage(listOf(serverIso()), null)),
            searchResults = emptyList(),
            recentSearchStore = { ProofRecentSearchStore() },
            historyEnabled = historyEnabled,
        ).copy(historyRepository = history)
        compose.mountTvProofSession(
            dependencies,
            account = TvAccount(
                userId = 1,
                username = "proof",
                email = "proof@example.invalid",
                historyEnabled = historyEnabled,
            ),
            tab = null,
        )
        compose.waitForIdle()
    }

    private fun events(): List<HistoryItem> = listOf(
        event(5, stamp(0, 10, 41), HistoryEventKind.Transfer(HistoryTransferId(1), HistoryFileId(SERVER_ID), SERVER_ISO)),
        event(4, stamp(1, 22, 24), HistoryEventKind.File(HistoryFileId(202), "FFTT Tree Rings October 23, 2026.mp3")),
        event(3, stamp(1, 5, 22), HistoryEventKind.Notice(HistoryNoticeType.Upload, "x2-truncated.mp4")),
        event(2, stamp(5, 22, 40), HistoryEventKind.File(HistoryFileId(203), "FFTT Full Report October 20, 2026.mp3")),
        event(
            1,
            stamp(5, 13, 14),
            HistoryEventKind.Transfer(HistoryTransferId(2), HistoryFileId(204), "ubuntu-24.04.3-desktop-amd64.iso"),
        ),
    )

    private fun event(id: Long, createdAt: String, kind: HistoryEventKind) =
        HistoryItem(HistoryEventId(id), createdAt, kind)

    private fun stamp(daysAgo: Long, hour: Int, minute: Int): String =
        LocalDate.now()
            .minusDays(daysAgo)
            .atTime(hour, minute)
            .atZone(ZoneId.systemDefault())
            .withZoneSameInstant(ZoneOffset.UTC)
            .format(STAMP)

    private fun serverIso() = FilesItem(
        id = FilesItemId(SERVER_ID),
        parentId = FilesFolder.Root.id,
        name = SERVER_ISO,
        type = PutioFileType.FILE,
        sizeBytes = 3_100_000_000L,
        createdAt = "2026-10-25T10:41:00Z",
    )

    private fun waitFor(matcher: SemanticsMatcher) {
        compose.waitUntil(5_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
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
        Thread.sleep(STEP_MILLIS)
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "tv-history-proof-${runId()}")
        check(directory.mkdirs() || directory.isDirectory)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "$label.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun runId(): UUID = UUID.fromString(requireNotNull(arguments.getString("putio.tv.history.runId")))
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private class FakeHistory(private var events: List<HistoryItem>) : HistoryRepository {
        var clears = 0
            private set

        override suspend fun load(before: HistoryEventId?) =
            PutioResult.Success(HistoryPage(if (before == null) events else emptyList(), hasMore = false))

        override suspend fun clear(): PutioResult<Unit> {
            clears += 1
            events = emptyList()
            return PutioResult.Success(Unit)
        }
    }

    private companion object {
        const val SERVER_ID = 201L
        const val SERVER_ISO = "ubuntu-24.04.3-live-server-amd64.iso"
        const val STEP_MILLIS = 400L
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    }
}
