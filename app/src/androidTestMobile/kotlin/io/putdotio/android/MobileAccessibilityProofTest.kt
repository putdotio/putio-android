package io.putdotio.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.history.HistoryContent
import io.putdotio.android.history.HistoryState
import io.putdotio.android.search.RecentSearchEdit
import io.putdotio.android.search.SearchContent
import io.putdotio.android.search.SearchState
import io.putdotio.android.search.SearchTerm
import io.putdotio.android.transfers.AppTransferStatus
import io.putdotio.android.transfers.TransferId
import io.putdotio.android.transfers.TransferItem
import io.putdotio.android.transfers.TransfersPaging
import io.putdotio.android.transfers.TransfersContent
import io.putdotio.android.transfers.TransfersEvent
import io.putdotio.android.transfers.TransfersState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Controlled production surfaces at 200% system font with animations disabled. */
@RunWith(AndroidJUnit4::class)
class MobileAccessibilityProofTest {
    private val compose = createComposeRule()
    private lateinit var hostView: android.view.View
    @get:Rule val rules: RuleChain = RuleChain.outerRule(accessibilityProofOptIn()).around(compose)

    @Test
    fun compactAuthAndFilesDialogsKeepActionsReachable() {
        var stage by mutableStateOf(0)
        var retries = 0
        val events = mutableListOf<FilesBrowserEvent>()
        mount {
            when (stage) {
                0 -> MobileAuthMessageScreen(
                    title = "Sign in could not finish",
                    message = "The browser closed before authentication completed. Your files and account remain " +
                        "unchanged. Return to sign in when you are ready, or retry after checking your connection.",
                    actionLabel = "Try signing in again",
                    onAction = { retries += 1 },
                    modifier = Modifier.height(280.dp),
                )
                1 -> MobileLoadingState("Restoring your session")
                else -> MobileFilesScreen(accessibilityFiles(), events::add, {}, confirmedTrashEnabled = true)
            }
        }
        compose.onNodeWithText("Try signing in again").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, retries)
        capture("auth-error-action")
        compose.runOnIdle { stage = 1 }
        compose.onAllNodesWithText("Restoring your session").assertCountEquals(1)
        capture("auth-loading")
        compose.runOnIdle { stage = 2 }
        compose.onNodeWithText(ACCESSIBILITY_FILE_NAME).assertIsDisplayed()
        capture("files")
        compose.onNodeWithContentDescription("Actions for $ACCESSIBILITY_FILE_NAME").performClick()
        compose.onNodeWithText("Move to trash").performScrollTo().assertIsDisplayed()
        capture("files-actions-bottom")
        compose.onNodeWithText("Rename").performScrollTo().assertIsDisplayed()
        capture("files-actions")
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithTag(MOBILE_FILES_RENAME_FIELD_TAG).performClick().performTextInput(" edited")
        awaitKeyboard()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        capture("files-rename-keyboard")
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(events.none { it is FilesBrowserEvent.Rename }) }
    }

    @Test
    fun recentSearchActionsAndTransferSubmissionRemainReachableWithKeyboard() {
        var transfers by mutableStateOf(false)
        val terms = listOf(SearchTerm("Archive été"), SearchTerm("Bodrum film collection"))
        var recent by mutableStateOf(terms)
        val edits = mutableListOf<RecentSearchEdit>()
        val events = mutableListOf<TransfersEvent>()
        mount {
            if (transfers) {
                MobileTransfersScreen(TransfersState(TransfersContent.Ready(listOf(TransferItem(
                    id = TransferId(147), name = "Rehearsal documentary.mp4", status = AppTransferStatus.Downloading,
                    fileId = null, sizeBytes = 128_000_000.0, percentDone = 42.0,
                    downloadSpeedBytesPerSecond = 1_000_000.0, uploadSpeedBytesPerSecond = null,
                    estimatedSecondsRemaining = 74.0, availability = null, hasError = false,
                    createdAt = "2026-09-08T12:00:00Z", userFileExists = null,
                )), TransfersPaging.Complete)), events::add,
                    sessionId = MobileAuthSessionId(147))
            } else {
                MobileSearchHistoryScreen(
                    searchState = SearchState("", SearchContent.Idle, recent, emptySet(), 1),
                    historyState = HistoryState(HistoryContent.Disabled),
                    recentSearchFailure = null,
                    onSearchQueryChanged = {}, onSearchSubmit = {}, onSearchResult = {},
                    onSearchNextPage = {}, onSearchRetry = {}, onRecentSearch = {},
                    onRecentEdit = { edit ->
                        edits += edit
                        if (edit is RecentSearchEdit.Remove) recent = recent - edit.term
                    },
                    onRecentRetry = {}, onHistoryEvent = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Remove Archive été from recent searches").assertIsDisplayed()
        capture("search-recent")
        compose.onNodeWithContentDescription("Remove Archive été from recent searches").performClick()
        compose.runOnIdle { assertEquals(listOf(RecentSearchEdit.Remove(terms.first())), edits) }
        compose.onNodeWithText(terms.last().value).assertIsDisplayed()
        compose.runOnIdle { transfers = true }
        compose.onNodeWithContentDescription("Transfer actions").performClick()
        compose.onNodeWithText("Clean completed").assertIsDisplayed().performClick()
        compose.onNodeWithText("Clean completed transfers?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Rehearsal documentary.mp4").assertIsDisplayed()
        capture("transfers-ready")
        compose.onNodeWithText("Add transfer").performClick()
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).performClick()
            .performTextInput("https://example.invalid/accessibility-fixture.mp4")
        awaitKeyboard()
        compose.onNodeWithText("Add").performScrollTo().assertIsDisplayed().assertIsEnabled()
        capture("transfer-add-keyboard")
        compose.onNodeWithText("Add").performClick()
        compose.runOnIdle {
            assertEquals(listOf(TransfersEvent.Add("https://example.invalid/accessibility-fixture.mp4")), events)
        }
    }

    @Test
    fun trashAndSettingsRemainReadableAndScrollable() {
        var settings by mutableStateOf(false)
        mount {
            if (settings) {
                MobileAccountScreen(
                    account = MobileAccount(147, "Accessibility proof", "proof@example.invalid"),
                    sessionId = MobileAuthSessionId(147),
                    settingsState = accessibilitySettings(), appConfigState = accessibilityAppConfig(),
                    onSettingsEvent = {}, onAppConfigEvent = {}, onSignOut = {},
                )
            } else {
                MobileTrashScreen(accessibilityTrash(), onEvent = { true })
            }
        }
        compose.onNodeWithTag(MOBILE_TRASH_LIST_TAG).performScrollToNode(hasText(ACCESSIBILITY_FILE_NAME))
        compose.onNodeWithText(ACCESSIBILITY_FILE_NAME).assertIsDisplayed()
        capture("trash")
        compose.onNodeWithContentDescription("Actions for $ACCESSIBILITY_FILE_NAME").performClick()
        compose.onNodeWithText("Restore").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Delete permanently").performScrollTo().assertIsDisplayed()
        capture("trash-actions")
        compose.runOnIdle { settings = true }
        compose.onNodeWithTag(MOBILE_MANAGE_TRASH_TAG).assertIsDisplayed()
        capture("settings-account")
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasText("Video playback"))
        compose.onNodeWithText("Video playback").assertIsDisplayed().performClick()
        capture("settings-playback-choice")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasText("Sign out"))
        compose.onNodeWithText("Sign out").assertIsDisplayed().assertIsEnabled()
        capture("settings-bottom")
    }

    private fun mount(content: @Composable () -> Unit) {
        compose.setContent {
            hostView = LocalView.current
            PutioTheme {
                Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    Box(Modifier.fillMaxSize()) { content() }
                }
            }
        }
    }

    private fun awaitKeyboard() {
        compose.waitUntil(5_000) {
            compose.runOnIdle {
                ViewCompat.getRootWindowInsets(hostView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
        }
    }

    private fun capture(label: String) {
        compose.waitForIdle()
        accessibilityProofScreenshot(label)
    }
}
