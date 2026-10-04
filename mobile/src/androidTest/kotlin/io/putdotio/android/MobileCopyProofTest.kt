package io.putdotio.android

import android.graphics.Bitmap
import android.text.format.Formatter
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.account.MobileAccountScreen
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.history.HistoryContent
import io.putdotio.android.history.HistoryEventId
import io.putdotio.android.history.HistoryEventKind
import io.putdotio.android.history.HistoryFileId
import io.putdotio.android.history.HistoryItem
import io.putdotio.android.history.HistoryNoticeType
import io.putdotio.android.history.HistoryPaging
import io.putdotio.android.history.HistoryTransferId
import io.putdotio.android.history.historyState
import io.putdotio.android.search.MOBILE_HISTORY_LIST_TAG
import io.putdotio.android.search.MobileSearchHistoryScreen
import io.putdotio.android.search.SearchContent
import io.putdotio.android.search.searchState
import io.putdotio.android.trash.MOBILE_TRASH_LIST_TAG
import io.putdotio.android.trash.MobileTrashScreen
import io.putdotio.android.trash.TrashContent
import io.putdotio.android.trash.TrashItem
import io.putdotio.android.trash.TrashState
import io.putdotio.sdk.files.PutioFileType
import java.io.File
import java.util.UUID
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** Controlled-state captures of the History, quota and Trash copy (#241). No API calls. */
@RunWith(AndroidJUnit4::class)
class MobileCopyProofTest {
    private val compose = createComposeRule()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Copy proof requires opt-in", arguments.getString("putio.copy.enabled") == "true")
                runId()
                base.evaluate()
            }
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    @Test
    fun historyQuotaAndTrashUseTheReferenceCopy() {
        var screen by mutableStateOf(Screen.History)
        var history by mutableStateOf(historyState(HistoryContent.Ready(historyItems(), HistoryPaging.Complete)))
        var optimistic by mutableStateOf(false)
        var trash by mutableStateOf(TrashState(TrashContent.Loaded(listOf(trashItem()), null, 1, 52_428_800)))
        mount {
            when (screen) {
                Screen.History -> MobileSearchHistoryScreen(
                    searchState = searchState("", SearchContent.Idle, emptyList(), 1L),
                    historyState = history,
                    recentSearchFailure = null,
                    onSearchQueryChanged = {},
                    onSearchSubmit = {},
                    onSearchResult = {},
                    onSearchNextPage = {},
                    onSearchRetry = {},
                    onRecentSearch = {},
                    onRecentEdit = {},
                    onRecentRetry = {},
                    onHistoryEvent = {},
                )
                Screen.Account -> MobileAccountScreen(
                    account = account(optimistic),
                    sessionId = MobileAuthSessionId(241),
                    settingsState = accessibilitySettings(),
                    appConfigState = accessibilityAppConfig(),
                    onSettingsEvent = {},
                    onAppConfigEvent = {},
                    onSignOut = {},
                )
                Screen.Trash -> MobileTrashScreen(trash, onEvent = { true })
            }
        }

        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Shared file · ", substring = true).assertIsDisplayed()
        listOf(
            "Archive été 東京.zip",
            "Error in transfer Sample transfer",
            "We had to delete Old episode.mkv per your instructions, since there wasn’t enough free space.",
            "Sample feed is paused because we couldn’t reach the source",
            "Error in transfer from RSS for Feed item",
            "Error in transfer callback for Callback transfer",
            "No title",
        ).forEach { title ->
            compose.onNodeWithTag(MOBILE_HISTORY_LIST_TAG).performScrollToNode(hasText(title))
            compose.onNodeWithText(title).assertIsDisplayed()
        }
        capture("01-history-events")
        compose.runOnIdle { history = historyState(HistoryContent.Disabled) }
        compose.onNodeWithText("Turn on “Keep account history” in Account to see activity here.").assertIsDisplayed()
        capture("02-history-off")

        compose.runOnIdle { screen = Screen.Account }
        compose.onNodeWithText("${size(GIBIBYTE)} of ${size(4 * GIBIBYTE)} used").assertIsDisplayed()
        capture("03-quota-used")
        compose.runOnIdle { optimistic = true }
        compose.onNodeWithText("${size(3 * GIBIBYTE)} of ${size(4 * GIBIBYTE)} free").assertIsDisplayed()
        capture("04-quota-free")

        compose.runOnIdle { screen = Screen.Trash }
        compose.onNodeWithText("Heads up: Files in trash have an expiry date of 14 days.").assertIsDisplayed()
        capture("05-trash-retention")
        compose.runOnIdle { trash = TrashState(TrashContent.Loaded(emptyList(), null, 0, 0)) }
        compose.onNodeWithTag(MOBILE_TRASH_LIST_TAG).performScrollToNode(hasText("Your trash is empty"))
        compose.onNodeWithText("When you send files to trash, we keep them here for 14 days.").assertIsDisplayed()
        capture("06-trash-empty")
    }

    private enum class Screen { History, Account, Trash }

    private fun mount(content: @Composable () -> Unit) {
        compose.setContent {
            PutioTheme {
                Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) { content() }
            }
        }
    }

    private fun historyItems(): List<HistoryItem> {
        val stamp = "2026-09-30T09:00:00"
        return listOf(
            HistoryEventKind.File(HistoryFileId(1), "Harbor film.mp4"),
            HistoryEventKind.Transfer(HistoryTransferId(2), HistoryFileId(2), "Sample folder"),
            HistoryEventKind.Notice(HistoryNoticeType.Upload, "Archive été 東京.zip"),
            HistoryEventKind.Notice(HistoryNoticeType.TransferError, "Sample transfer"),
            HistoryEventKind.Notice(HistoryNoticeType.RssFileDeleted, "Old episode.mkv"),
            HistoryEventKind.Notice(HistoryNoticeType.RssFilterPaused, "Sample feed"),
            HistoryEventKind.Notice(HistoryNoticeType.RssTransferError, "Feed item"),
            HistoryEventKind.Notice(HistoryNoticeType.TransferCallbackError, "Callback transfer"),
            HistoryEventKind.Other("zip_created"),
        ).mapIndexed { index, kind -> HistoryItem(HistoryEventId(100L - index), stamp, kind) }
    }

    private fun account(optimistic: Boolean) = MobileAccount(
        userId = 241,
        username = "friend",
        email = "friend@example.invalid",
        historyEnabled = true,
        storage = AccountStorage(
            availableBytes = 3 * GIBIBYTE,
            sizeBytes = 4 * GIBIBYTE,
            usedBytes = GIBIBYTE,
            showOptimisticUsage = optimistic,
        ),
    )

    private fun trashItem() = TrashItem(
        FilesItemId(241), FilesFolder.Root.id, "Harbor film.mp4", PutioFileType.VIDEO,
        52_428_800, "2026-09-28T12:00:00Z", "2026-10-12T12:00:00Z",
    )

    private fun capture(label: String) {
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(100, 3_000)
        val directory = File(
            requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)),
            "copy-proof-${runId()}",
        )
        check(directory.mkdirs() || directory.isDirectory)
        val bitmap = requireNotNull(automation.takeScreenshot())
        try {
            File(directory, "$label.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun size(bytes: Long) =
        Formatter.formatShortFileSize(InstrumentationRegistry.getInstrumentation().targetContext, bytes)

    private fun runId(): UUID = UUID.fromString(requireNotNull(arguments.getString("putio.copy.runId")))
    private val arguments get() = InstrumentationRegistry.getArguments()

    private companion object {
        const val GIBIBYTE = 1_073_741_824L
    }
}
