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
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.transfers.AppTransferStatus
import io.putdotio.android.transfers.TransferId
import io.putdotio.android.transfers.TransferItem
import io.putdotio.android.transfers.TransfersContent
import io.putdotio.android.transfers.TransfersEvent
import io.putdotio.android.transfers.TransfersPaging
import io.putdotio.android.transfers.TransfersState
import java.io.Closeable
import java.io.File
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
    private lateinit var hostView: View
    private lateinit var backOwner: OnBackPressedDispatcherOwner
    private lateinit var capturePrefix: String
    private var proofStage = "start"
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun fullShellNavigationAndTransfersKeepActionsReachable() {
        runShellProof(player = null)
    }

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

    private fun runShellProof(player: Player?) {
        capturePrefix = "shell-${requireOrientation()}" + if (player == null) "" else "-audio"
        val mounted = mutableStateOf(true)
        val events = mutableListOf<TransfersEvent>()
        val factory = ShellProofPlayerFactory(player)
        compose.setContent {
            hostView = LocalView.current
            backOwner = checkNotNull(LocalOnBackPressedDispatcherOwner.current)
            PutioTheme {
                Surface(Modifier.fillMaxSize().testTag(SHELL_VIEWPORT_TAG)) {
                    if (mounted.value) {
                        MobileShell(
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
            assertFilesViewport()
            if (player != null) checkNowPlaying(player)
            capture("files")
            checkDrawerAndBack()
            navigate("Search")
            compose.onNodeWithTag(MOBILE_SEARCH_FIELD_TAG).assertIsDisplayed()
            capture("search")
            navigate("Transfers")
            checkTransfers(events)
            navigate("Account")
            compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).assertIsDisplayed()
            capture("account")
            checkSettings()
            navigate("Files")
            assertFilesViewport()
            if (player != null) compose.onNodeWithTag(MOBILE_NOW_PLAYING_TAG).assertIsDisplayed()
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

    private fun checkDrawerAndBack() {
        proofStage = "drawer-back"
        compose.onNodeWithTag(MOBILE_NAV_MENU_TAG).performClick()
        capture("drawer")
        listOf("Files", "Search", "Transfers", "Account").forEach { label ->
            val layouts = mutableListOf<TextLayoutResult>()
            drawerDestination(label).performScrollTo().assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertEquals("$label must not break into narrow fragments", 1, layout.lineCount)
            assertFalse("$label must remain complete", layout.isLineEllipsized(0))
            assertTrue("$label must fit horizontally", layout.getLineRight(0) <= layout.size.width + 1f)
            assertTrue("$label must fit vertically", layout.getLineBottom(0) <= layout.size.height + 1f)
        }
        capture("drawer-bottom")
        compose.runOnIdle { backOwner.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag(MOBILE_NAV_DRAWER_TAG).assertIsNotDisplayed()
        compose.onNodeWithText(ACCESSIBILITY_FILE_NAME).assertIsDisplayed()
    }

    private fun navigate(label: String) {
        proofStage = "navigate-${label.lowercase()}"
        compose.onNodeWithTag(MOBILE_NAV_MENU_TAG).performClick()
        drawerDestination(label).performScrollTo().assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(MOBILE_NAV_DRAWER_TAG).assertIsNotDisplayed()
        compose.onNodeWithTag(MOBILE_NAV_MENU_TAG).performClick()
        compose.waitForIdle()
        drawerDestination(label).performScrollTo().assertIsDisplayed().assertIsSelected()
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
        compose.onNodeWithContentDescription("Transfer actions").performClick()
        compose.onNodeWithText("Refresh").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Clean completed").assertIsDisplayed().assertIsEnabled()
        capture("transfer-actions")
        compose.onNodeWithText("Clean completed").performClick()
        compose.onNodeWithText("Clean completed transfers?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(events.isEmpty())
        compose.onNodeWithContentDescription("Cancel transfer $TRANSFER_NAME")
            .performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Stop transfer").assertIsDisplayed().performClick()
        assertEquals(listOf(TransfersEvent.Cancel(TransferId(147))), events)
        compose.onNodeWithText("Add transfer").performClick()
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
        compose.onNodeWithText("Add").performScrollTo().assertIsDisplayed().assertIsEnabled()
        capture("transfer-add-keyboard")
        compose.onNodeWithText("Add").performClick()
        assertEquals(listOf(TransfersEvent.Cancel(TransferId(147)), TransfersEvent.Add(SHARED_URL)), events)
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
    }

    private fun checkSettings() {
        proofStage = "settings"
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasText("Strictly necessary"))
        compose.onNodeWithText("Always on").assertIsDisplayed()
        capture("settings-privacy")
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasText("Video playback"))
        compose.onNodeWithText("Video playback").assertIsDisplayed().performClick()
        compose.onNodeWithText("Direct MP4").performScrollTo().assertIsDisplayed()
        capture("settings-playback-choice")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasText("Sign out"))
        compose.onNodeWithText("Sign out").assertIsDisplayed().assertIsEnabled()
        capture("settings-bottom")
    }

    private fun assertFilesViewport() {
        compose.onNodeWithTag(MOBILE_NAV_BAR_TAG).assertDoesNotExist()
        compose.onNodeWithTag(MOBILE_NAV_RAIL_TAG).assertDoesNotExist()
        compose.onNodeWithTag(MOBILE_NAV_MENU_TAG).assertIsDisplayed()
        val root = compose.onNodeWithTag(SHELL_VIEWPORT_TAG).fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag(MOBILE_FILES_LIST_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("Files keeps most of the shell height", list.height >= root.height / 2f)
        assertTrue("Navigation does not squeeze the file rows", list.width >= root.width * MINIMUM_CONTENT_WIDTH)
    }

    private fun checkNowPlaying(player: Player) {
        compose.onNodeWithTag(MOBILE_NOW_PLAYING_TAG).assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_NOW_PLAYING_TOGGLE_TAG).performClick()
        compose.runOnIdle { assertFalse(player.playWhenReady) }
        compose.onNodeWithTag(MOBILE_NOW_PLAYING_TOGGLE_TAG).performClick()
        compose.waitUntil(5_000) { compose.runOnIdle { player.isPlaying } }
    }

    private fun drawerDestination(label: String) =
        compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag(MOBILE_NAV_DRAWER_TAG)))

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

    private fun localAudioFixture(): File {
        val path = requireNotNull(InstrumentationRegistry.getArguments().getString("putio.accessibility.audio"))
        val file = File(path).canonicalFile
        val root = requireNotNull(context.getExternalFilesDir(null)).canonicalFile
        require(file.toPath().startsWith(root.toPath()) && file.isFile && file.canRead()) {
            "Audio must be a caller-owned readable fixture beneath the target app's external files directory"
        }
        return file
    }

    private companion object {
        const val SHELL_VIEWPORT_TAG = "shell-accessibility-viewport"
        const val TRANSFER_NAME = "Rehearsal documentary.mp4"
        const val SHARED_URL = "https://example.invalid/shell-accessibility.mp4"
        const val MINIMUM_LIST_HEIGHT_DP = 96f
        const val MINIMUM_CONTENT_WIDTH = 0.9f
    }

    private fun shellTransfers() = TransfersState(TransfersContent.Ready(listOf(TransferItem(
        id = TransferId(147), name = TRANSFER_NAME, status = AppTransferStatus.Downloading,
        fileId = null, sizeBytes = 128_000_000.0, percentDone = 42.0,
        downloadSpeedBytesPerSecond = 1_000_000.0, uploadSpeedBytesPerSecond = null,
        estimatedSecondsRemaining = 74.0, availability = null, hasError = false,
        createdAt = "2026-09-08T12:00:00Z", userFileExists = null,
    )), TransfersPaging.Complete))
}

private class ShellProofPlayerFactory(private val player: Player?) : MobilePlayerFactory {
    override fun create(context: Context, mediaType: PlaybackMediaType): Player =
        error("The shell proof must not open the full player")

    override fun connectAudio(context: Context, onResult: (Result<Player>) -> Unit): Closeable {
        onResult(player?.let { Result.success(it) } ?: Result.failure(IllegalStateException("No proof audio")))
        return Closeable {}
    }
}

private object NoShellProofPlayback : PlaybackRepository {
    override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> =
        error("The shell proof must not resolve playback")

    override suspend fun findNextVideo(target: PlaybackTarget) = PlaybackNextResult.Ended
}
