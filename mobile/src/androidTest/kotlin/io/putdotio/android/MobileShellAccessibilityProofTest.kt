package io.putdotio.android

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.view.View
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.account.MOBILE_ACCOUNT_LIST_TAG
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.MOBILE_FILES_LIST_TAG
import io.putdotio.android.playback.MOBILE_NOW_PLAYING_TAG
import io.putdotio.android.playback.MOBILE_NOW_PLAYING_TOGGLE_TAG
import io.putdotio.android.playback.MobilePlayerFactory
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.search.MOBILE_SEARCH_FIELD_TAG
import io.putdotio.android.transfers.AppTransferStatus
import io.putdotio.android.transfers.MOBILE_TRANSFERS_LIST_TAG
import io.putdotio.android.transfers.MOBILE_TRANSFER_ADD_FIELD_TAG
import io.putdotio.android.transfers.MobileTransferDraft
import io.putdotio.android.transfers.TransferId
import io.putdotio.android.transfers.TransferItem
import io.putdotio.android.transfers.TransfersContent
import io.putdotio.android.transfers.TransfersEvent
import io.putdotio.android.transfers.TransfersPaging
import io.putdotio.android.transfers.parseMobileSharedTransfer
import io.putdotio.android.transfers.transfersState
import io.putdotio.android.transfers.mobileTransferCardTag
import io.putdotio.android.transfers.MOBILE_TRANSFER_ADD_CONFIRM_TAG
import java.io.Closeable
import java.io.File
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Actual shell/chrome with controlled content; no auth runtime, API calls or media-session service. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class MobileShellAccessibilityProofTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(accessibilityProofOptIn()).around(compose)

    @Before fun checkAccessibility() = compose.enableAccessibilityChecks()
    private lateinit var hostView: View
    private lateinit var backOwner: OnBackPressedDispatcherOwner
    private lateinit var capturePrefix: String
    private lateinit var navigation: MobileNavigationLayout
    private var proofStage = "start"
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun fullShellWithPrivateAudioKeepsNavigationAndTransfersReachable() {
        val fixture = localAudioFixture()
        var player: ExoPlayer? = null
        try {
            compose.runOnUiThread {
                player = ExoPlayer.Builder(context).build().apply {
                    volume = 0f
                    setMediaItem(MediaItem.Builder()
                        .setMediaId("147")
                        .setUri(Uri.fromFile(fixture))
                        .setMediaMetadata(MediaMetadata.Builder().setTitle("Rehearsal audio.m4a").build())
                        .build())
                    prepare()
                    play()
                }
            }
            val owned = checkNotNull(player)
            compose.waitUntil(15_000) {
                var ready = false
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    ready = owned.playbackState == Player.STATE_READY && owned.isPlaying
                }
                ready
            }
            runShellProof(owned)
        } finally {
            compose.runOnUiThread { player?.release() }
        }
    }

    private fun runShellProof(player: Player) {
        val orientation = requireOrientation()
        capturePrefix = "shell-$orientation-audio"
        val mounted = mutableStateOf(true)
        val events = mutableListOf<TransfersEvent>()
        val transferDraft = MobileTransferDraft()
        val factory = ShellProofPlayerFactory(player)
        compose.setContent {
            hostView = LocalView.current
            backOwner = checkNotNull(LocalOnBackPressedDispatcherOwner.current)
            PutioTheme {
                Surface(Modifier.fillMaxSize().testTag(SHELL_VIEWPORT_TAG)) {
                    if (mounted.value) {
                        MobileShell(
                            transferDraft = transferDraft,
                            filesState = accessibilityFiles(),
                            accountSettingsState = accessibilitySettings(),
                            appConfigState = accessibilityAppConfig(),
                            transfersState = shellTransfers(),
                            transfersSessionId = MobileAuthSessionId(147),
                            account = MobileAccount(147, "Shell accessibility proof", "proof@example.invalid"),
                            playbackRepository = NoShellProofPlayback,
                            playbackPlayerFactory = factory,
                            sessionId = MobileAuthSessionId(147),
                            onFilesEvent = { true },
                            onTransfersEvent = { event ->
                                if (event !is TransfersEvent.VisibilityChanged) events.add(event)
                            },
                            onAccountSettingsEvent = { error("Unexpected settings mutation") },
                            onPlaybackAuthenticationRequired = { error("Unexpected authentication request") },
                            onSignOut = { error("Unexpected sign out") },
                        )
                    }
                }
            }
        }
        try {
            navigation = shownNavigation()
            capturePrefix = "shell-$orientation-${navigation.name.lowercase()}-audio"
            assertFilesViewport()
            checkNowPlaying(player)
            capture("files")
            checkNavigationLabels()
            compose.onNodeWithTag(MOBILE_FILES_SEARCH_TAG).performClick()
            compose.onNodeWithTag(MOBILE_SEARCH_FIELD_TAG).assertIsDisplayed()
            capture("search")
            backOwner.onBackPressedDispatcher.let { dispatcher -> compose.runOnIdle { dispatcher.onBackPressed() } }
            navigate("Transfers")
            checkTransfers(events)
            navigate("Files")
            checkIncomingShares(transferDraft, events)
            navigate("Account")
            compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).assertIsDisplayed()
            capture("account")
            checkSettings()
            navigate("Files")
            assertFilesViewport()
            compose.onNodeWithTag(MOBILE_NOW_PLAYING_TAG).assertIsDisplayed()
        } catch (failure: Throwable) {
            try {
                capture("failure-$proofStage")
            } catch (captureFailure: Throwable) {
                failure.addSuppressed(captureFailure)
            }
            throw failure
        } finally {
            compose.runOnIdle { mounted.value = false }
            compose.waitForIdle()
        }
    }

    private fun checkNavigationLabels() {
        proofStage = "navigation-labels"
        if (navigation != MobileNavigationLayout.Modal) {
            DESTINATIONS.forEach { label ->
                assertCompleteLabel(label, navigationDestination(label).assertIsDisplayed())
            }
            return
        }
        compose.onNodeWithTag(MOBILE_NAV_MENU_TAG).performClick()
        capture("drawer")
        DESTINATIONS.forEach { label ->
            assertCompleteLabel(label, navigationDestination(label).performScrollTo().assertIsDisplayed())
        }
        capture("drawer-bottom")
        compose.runOnIdle { backOwner.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag(MOBILE_NAV_DRAWER_TAG).assertIsNotDisplayed()
        compose.onNodeWithText(ACCESSIBILITY_FILE_NAME).assertIsDisplayed()
    }

    private fun assertCompleteLabel(label: String, node: SemanticsNodeInteraction) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals("$label must not break into narrow fragments", 1, layout.lineCount)
        assertFalse("$label must remain complete", layout.isLineEllipsized(0))
        assertTrue("$label must fit horizontally", layout.getLineRight(0) <= layout.size.width + 1f)
        assertTrue("$label must fit vertically", layout.getLineBottom(0) <= layout.size.height + 1f)
    }

    private fun navigate(label: String) {
        proofStage = "navigate-${label.lowercase()}"
        if (navigation != MobileNavigationLayout.Modal) {
            navigationDestination(label).assertIsDisplayed().performClick()
            compose.waitForIdle()
            navigationDestination(label).assertIsSelected()
            return
        }
        compose.onNodeWithTag(MOBILE_NAV_MENU_TAG).performClick()
        navigationDestination(label).performScrollTo().assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(MOBILE_NAV_DRAWER_TAG).assertIsNotDisplayed()
        compose.onNodeWithTag(MOBILE_NAV_MENU_TAG).performClick()
        compose.waitForIdle()
        navigationDestination(label).performScrollTo().assertIsDisplayed().assertIsSelected()
        compose.runOnIdle { backOwner.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag(MOBILE_NAV_DRAWER_TAG).assertIsNotDisplayed()
    }

    private fun checkTransfers(events: MutableList<TransfersEvent>) {
        proofStage = "transfers-actions"
        val list = compose.onNodeWithTag(MOBILE_TRANSFERS_LIST_TAG).fetchSemanticsNode().boundsInRoot
        val minimum = context.resources.displayMetrics.density * MINIMUM_LIST_HEIGHT_DP
        assertTrue("The shell must leave usable scrolling space below its toolbar", list.height >= minimum)
        compose.onNodeWithText(TRANSFER_NAME).assertIsDisplayed()
        capture("transfers")
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Refresh").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Clear completed").assertIsDisplayed().assertIsEnabled()
        capture("transfer-actions")
        compose.onNodeWithText("Clear completed").performClick()
        compose.onNodeWithText("Clear completed transfers?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(events.isEmpty())
        compose.onNodeWithTag(mobileTransferCardTag(TransferId(147)))
            .performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Stop transfer").assertIsDisplayed().performClick()
        compose.onNodeWithText("Stop transfer?").assertIsDisplayed()
        compose.onNodeWithText("Stop transfer").performClick()
        assertEquals(listOf(TransfersEvent.Cancel(TransferId(147))), events)
        compose.onNode(hasContentDescription("Add transfer") or hasText("Add transfer")).performClick()
        proofStage = "transfer-keyboard"
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).performScrollTo().performClick()
            .performTextInput(SHARED_URL)
        compose.waitUntil(5_000) {
            compose.runOnIdle {
                ViewCompat.getRootWindowInsets(hostView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
        }
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).performScrollTo().assertIsDisplayed()
        capture("transfer-edit-keyboard")
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_CONFIRM_TAG).performScrollTo().assertIsDisplayed().assertIsEnabled()
        capture("transfer-add-keyboard")
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_CONFIRM_TAG).performClick()
        assertEquals(listOf(TransfersEvent.Cancel(TransferId(147)), TransfersEvent.Add(SHARED_URL)), events)
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
    }

    private fun checkIncomingShares(draft: MobileTransferDraft, events: List<TransfersEvent>) {
        val eventsBeforeShares = events.toList()
        compose.runOnIdle {
            assertEquals(SHARED_URL, draft.state.value.input)
            assertFalse(draft.state.value.open)
            draft.receive(parseMobileSharedTransfer(REJECTED_SHARED_URL))
        }
        proofStage = "share-keep-draft"
        checkReplacementMessage("share-keep")
        compose.onNodeWithText("Keep draft").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithText("Replace the current draft?").assertDoesNotExist()
        compose.onNode(hasContentDescription("Add transfer") or hasText("Add transfer"))
            .assertIsDisplayed()
            .performClick()
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).performScrollTo()
            .assertIsDisplayed().assertTextContains(SHARED_URL)
        compose.runOnIdle {
            assertEquals(SHARED_URL, draft.state.value.input)
            assertFalse(draft.state.value.pendingReplacement)
            assertEquals(eventsBeforeShares, events)
        }
        capture("share-kept-draft")

        proofStage = "share-use-link"
        compose.runOnIdle { draft.receive(parseMobileSharedTransfer(ACCEPTED_SHARED_URL)) }
        checkReplacementMessage("share-use")
        compose.onNodeWithText("Use shared link").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithText("Replace the current draft?").assertDoesNotExist()
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).performScrollTo()
            .assertIsDisplayed().assertTextContains(ACCEPTED_SHARED_URL)
        compose.runOnIdle {
            assertEquals(ACCEPTED_SHARED_URL, draft.state.value.input)
            assertFalse(draft.state.value.pendingReplacement)
            assertEquals(eventsBeforeShares, events)
        }
        capture("share-used-link")
        compose.onNodeWithText("Cancel").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(eventsBeforeShares, events) }
    }

    private fun checkReplacementMessage(label: String) {
        compose.onNodeWithText("Replace the current draft?").assertIsDisplayed()
        val message = compose.onNodeWithText(REPLACEMENT_MESSAGE, useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        message.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(REPLACEMENT_MESSAGE, layout.layoutInput.text.text)
        assertEquals(2f, layout.layoutInput.density.fontScale)
        repeat(layout.lineCount) { line ->
            assertFalse("The replacement message must not be ellipsized", layout.isLineEllipsized(line))
            assertTrue("The message must fit horizontally", layout.getLineRight(line) <= layout.size.width + 1f)
        }
        val before = message.fetchSemanticsNode()
        assertTrue("The start of the message must be visible",
            before.positionInRoot.y + layout.getLineTop(0) >= before.boundsInRoot.top - 1f)
        capture("$label-message-start")
        val lastLineBottom = layout.getLineBottom(layout.lineCount - 1)
        if (before.positionInRoot.y + lastLineBottom > before.boundsInRoot.bottom + 1f) {
            compose.onNode(
                hasScrollAction() and (hasText(REPLACEMENT_MESSAGE) or hasAnyDescendant(hasText(REPLACEMENT_MESSAGE))),
                useUnmergedTree = true,
            ).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, layout.size.height.toFloat()) }
            compose.waitForIdle()
        }
        val after = message.fetchSemanticsNode()
        assertTrue("The end of the replacement message must be reachable",
            after.positionInRoot.y + lastLineBottom <= after.boundsInRoot.bottom + 1f)
        compose.onNodeWithText("Keep draft").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Use shared link").assertIsDisplayed().assertIsEnabled()
        capture("$label-message-end")
    }

    private fun checkSettings() {
        proofStage = "settings"
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasText("Privacy controls"))
        compose.onNodeWithText("Privacy controls").assertIsDisplayed().performClick()
        compose.onNodeWithText("Always on").assertIsDisplayed()
        capture("settings-privacy")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasText("Video playback"))
        compose.onNodeWithText("Video playback").assertIsDisplayed().performClick()
        compose.onNodeWithText("Direct MP4").performScrollTo().assertIsDisplayed()
        capture("settings-playback-choice")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasText("Sign out"))
        compose.onNodeWithText("Sign out").assertIsDisplayed().assertIsEnabled()
        capture("settings-bottom")
    }

    /** The app picks the bar, the rail or the menu drawer from the window and its labels; the proof follows it. */
    private fun shownNavigation(): MobileNavigationLayout {
        val shown = NAVIGATION_TAGS.filterValues { tag ->
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }.keys
        assertEquals("The shell shows exactly one navigation surface", 1, shown.size)
        return shown.single()
    }

    private fun assertFilesViewport() {
        assertEquals(navigation, shownNavigation())
        val tag = checkNotNull(NAVIGATION_TAGS[navigation])
        val surface = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val root = compose.onNodeWithTag(SHELL_VIEWPORT_TAG).fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag(MOBILE_FILES_LIST_TAG).fetchSemanticsNode().boundsInRoot
        val contentWidth = if (navigation == MobileNavigationLayout.Rail) root.width - surface.width else root.width
        val density = context.resources.displayMetrics.density
        if (isCompactHeight((root.height / density).dp)) {
            // A phone in landscape keeps the bar whenever its labels fit, so Files gets what is left.
            assertTrue("Files keeps usable scrolling space", list.height >= density * MINIMUM_LIST_HEIGHT_DP)
        } else {
            assertTrue("Files keeps most of the shell height", list.height >= root.height / 2f)
        }
        assertTrue("Navigation does not squeeze the file rows", list.width >= contentWidth * MINIMUM_CONTENT_WIDTH)
    }

    private fun checkNowPlaying(player: Player) {
        compose.onNodeWithTag(MOBILE_NOW_PLAYING_TAG).assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_NOW_PLAYING_TOGGLE_TAG).performClick()
        compose.runOnIdle { assertFalse(player.playWhenReady) }
        compose.onNodeWithTag(MOBILE_NOW_PLAYING_TOGGLE_TAG).performClick()
        compose.waitUntil(5_000) { compose.runOnIdle { player.isPlaying } }
    }

    private fun navigationDestination(label: String): SemanticsNodeInteraction {
        val container = if (navigation == MobileNavigationLayout.Modal) {
            MOBILE_NAV_DRAWER_TAG
        } else {
            checkNotNull(NAVIGATION_TAGS[navigation])
        }
        return compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag(container)))
    }

    private fun capture(label: String) {
        compose.waitForIdle()
        accessibilityProofScreenshot("$capturePrefix-$label")
    }

    private fun requireOrientation(): String {
        val expected = InstrumentationRegistry.getArguments().getString("putio.accessibility.orientation")
        require(expected == "portrait" || expected == "landscape") { "Specify the expected shell orientation" }
        val actual = if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            "landscape"
        } else {
            "portrait"
        }
        require(actual == expected) { "Rotate the emulator to the expected orientation before this invocation" }
        return actual
    }

    private fun localAudioFixture(): File = proofFixture("putio.accessibility.audio")

    private companion object {
        const val SHELL_VIEWPORT_TAG = "shell-accessibility-viewport"
        const val TRANSFER_NAME = "Rehearsal documentary.mp4"
        const val SHARED_URL = "https://example.invalid/shell-accessibility.mp4"
        const val REJECTED_SHARED_URL = "https://example.invalid/keep-the-original.mp4"
        const val ACCEPTED_SHARED_URL = "https://example.invalid/use-this-replacement.mp4"
        const val REPLACEMENT_MESSAGE = "You already have an unfinished transfer. Choose which input to keep."
        const val MINIMUM_LIST_HEIGHT_DP = 96f
        const val MINIMUM_CONTENT_WIDTH = 0.9f
        val DESTINATIONS = listOf("Files", "History", "Transfers", "Downloads", "Account")
        val NAVIGATION_TAGS = mapOf(
            MobileNavigationLayout.Bar to MOBILE_NAV_BAR_TAG,
            MobileNavigationLayout.Rail to MOBILE_NAV_RAIL_TAG,
            MobileNavigationLayout.Modal to MOBILE_NAV_MENU_TAG,
        )
    }

    private fun shellTransfers() = transfersState(TransfersContent.Ready(listOf(TransferItem(
        id = TransferId(147), name = TRANSFER_NAME, status = AppTransferStatus.Downloading,
        fileId = null, sizeBytes = 128_000_000.0, percentDone = 42.0,
        downloadSpeedBytesPerSecond = 1_000_000.0, uploadSpeedBytesPerSecond = null,
        estimatedSecondsRemaining = 74.0, availability = null, errorMessage = null,
        createdAt = "2026-09-08T12:00:00Z", userFileExists = null,
    )), TransfersPaging.Complete))
}

internal class ShellProofPlayerFactory(private val player: Player?) : MobilePlayerFactory {
    override fun create(context: Context, mediaType: PlaybackMediaType): Player =
        error("The shell proof must not open the full player")

    override fun connectAudio(context: Context, onResult: (Result<Player>) -> Unit): Closeable {
        onResult(player?.let { Result.success(it) } ?: Result.failure(IllegalStateException("No proof audio")))
        return Closeable {}
    }
}

internal object NoShellProofPlayback : PlaybackRepository {
    override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> =
        error("The shell proof must not resolve playback")

    override suspend fun findNextVideo(target: PlaybackTarget) = PlaybackNextResult.Ended
}
