package io.putdotio.android.tv

import android.content.Context
import android.graphics.Bitmap
import android.text.format.Formatter
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
import io.putdotio.android.history.HistoryNoticeType
import io.putdotio.android.history.HistoryTransferId
import io.putdotio.android.trash.TrashItem
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.sdk.files.PutioFileType
import java.io.File
import java.util.UUID
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * Controlled-state captures of the History, quota and Trash copy (#241) on the real signed-in
 * TV shell: History lists only shared files and completed transfers, the quota follows
 * `show_optimistic_usage`, Trash states its 14 days. No API calls.
 */
@RunWith(AndroidJUnit4::class)
class TvCopyProofTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("TV copy proof requires opt-in", arguments.getString("putio.tv.copy.enabled") == "true")
                runId()
                base.evaluate()
            }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    @Test
    fun historyKeepsSharedFilesAndTransfersAndTheQuotaAndTrashUseTheReferenceCopy() {
        mount(account(historyEnabled = true, optimistic = false), trash = listOf(trashItem()))

        openFromDrawer(steps = 2)
        compose.waitUntil(5_000) { hasDescription("Open $SHARED") }
        assertTrue(hasDescription("Open $TRANSFER"))
        assertTrue(compose.onAllNodesWithText(UPLOAD, substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText(FAILED, substring = true).fetchSemanticsNodes().isEmpty())
        screenshot("01-tv-history-shared-and-completed")

        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { hasText("${size(USED)} of ${size(SIZE)} used") }
        screenshot("02-tv-quota-used")

        focusText(MANAGE_TRASH)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { hasText(TRASH_INFO) }
        screenshot("03-tv-trash-retention")
    }

    @Test
    fun historyOffNamesTheToggleAndTheQuotaStatesWhatIsFree() {
        mount(account(historyEnabled = false, optimistic = true), trash = emptyList())

        openFromDrawer(steps = 2)
        compose.waitUntil(5_000) { hasText("Turn on “Keep account history” in Account to see activity here.") }
        screenshot("04-tv-history-off")

        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { hasText("${size(SIZE - USED)} of ${size(SIZE)} free") }
        screenshot("05-tv-quota-free")
    }

    private fun mount(account: TvAccount, trash: List<TrashItem>) {
        val folder = proofItem(10, "Sample folder", PutioFileType.FOLDER, FilesFolder.Root.id)
        val dependencies = tvProofDependencies(
            listings = mapOf(FilesFolder.Root.id to FilesPage(listOf(folder), null)),
            searchResults = emptyList(),
            recentSearchStore = { ProofRecentSearchStore() },
            history = history(),
            trash = trash,
            historyEnabled = account.historyEnabled,
        )
        compose.mountTvProofSession(dependencies, account = account)
        compose.waitUntil(5_000) { hasDescription("Open Sample folder") }
    }

    private fun history(): List<HistoryItem> {
        val stamp = "2026-09-30T09:00:00"
        return listOf(
            HistoryEventKind.File(HistoryFileId(1), SHARED),
            HistoryEventKind.Notice(HistoryNoticeType.Upload, UPLOAD),
            HistoryEventKind.Transfer(HistoryTransferId(2), HistoryFileId(2), TRANSFER),
            HistoryEventKind.Notice(HistoryNoticeType.TransferError, FAILED),
            HistoryEventKind.Other("zip_created"),
        ).mapIndexed { index, kind -> HistoryItem(HistoryEventId(100L - index), stamp, kind) }
    }

    private fun account(historyEnabled: Boolean, optimistic: Boolean) = TvAccount(
        userId = 1,
        username = "friend",
        email = "friend@example.invalid",
        historyEnabled = historyEnabled,
        storage = AccountStorage(
            availableBytes = SIZE - USED,
            sizeBytes = SIZE,
            usedBytes = USED,
            showOptimisticUsage = optimistic,
        ),
    )

    private fun trashItem() = TrashItem(
        FilesItemId(241), FilesFolder.Root.id, "Harbor film.mp4", PutioFileType.VIDEO,
        52_428_800, "2026-09-28T12:00:00Z", "2026-10-12T12:00:00Z",
    )

    private fun openFromDrawer(steps: Int) {
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        repeat(steps) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    /** Walks the D-pad down until the row reading [label] holds focus. */
    private fun focusText(label: String) {
        repeat(MAX_STEPS) {
            val focused = compose.onAllNodesWithText(label).fetchSemanticsNodes()
                .any { it.config.getOrNull(SemanticsProperties.Focused) == true }
            if (focused) return
            press(KeyEvent.KEYCODE_DPAD_DOWN)
        }
        error("$label never took focus")
    }

    private fun hasDescription(label: String) =
        compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty()

    private fun hasText(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)

    private fun press(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
        compose.waitForIdle()
        Thread.sleep(STEP_MILLIS)
    }

    private fun screenshot(label: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "tv-copy-proof-${runId()}")
        check(directory.mkdirs() || directory.isDirectory)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "$label.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun runId(): UUID = UUID.fromString(requireNotNull(arguments.getString("putio.tv.copy.runId")))
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        const val SHARED = "Harbor film.mp4"
        const val TRANSFER = "Sample folder archive"
        const val UPLOAD = "Archive été 東京.zip"
        const val FAILED = "Sample transfer"
        const val MANAGE_TRASH = "Manage your trash"
        const val TRASH_INFO = "Heads up: Files in trash have an expiry date of 14 days."
        const val SIZE = 1_000_000_000_000L
        const val USED = 250_000_000_000L
        const val MAX_STEPS = 20
        const val STEP_MILLIS = 250L
    }
}
