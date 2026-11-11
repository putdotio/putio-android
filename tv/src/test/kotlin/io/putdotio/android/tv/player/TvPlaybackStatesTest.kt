package io.putdotio.android.tv.player

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.PutioFailure
import io.putdotio.android.design.PutioTvTheme
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PLAYBACK_CONVERSION_POLL_MILLIS
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackRequestId
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.playbackState
import io.putdotio.android.playback.toPlaybackFailure
import io.putdotio.android.putioErrorBody
import io.putdotio.android.putioRefusal
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.files.PlaybackConversionState
import io.putdotio.sdk.files.PutioFileType
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television")
class TvPlaybackStatesTest {
    @get:Rule
    val compose = createComposeRule()

    private var content by mutableStateOf<PlaybackContent>(PlaybackContent.Conversion(PlaybackConversionState.Queued))
    private var refreshes = 0
    private var starts = 0
    private var retries = 0
    private var vlcOpens = 0
    private var backs = 0
    private val lifecycleOwner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun aQueuedConversionSaysItKeepsGoingAndReadsItAgainEveryThreeSeconds() {
        show()
        compose.onNodeWithText(TITLE).assertIsDisplayed()
        compose.onNodeWithText("Converting to MP4").assertIsDisplayed()
        compose.onNodeWithText(RUNNING_MESSAGE).assertIsDisplayed()
        compose.onNodeWithTag(TV_CONVERSION_PROGRESS_TAG).assertIsDisplayed()
        compose.onNodeWithTag(TV_CONVERSION_PERCENT_TAG).assertDoesNotExist()
        compose.onNodeWithText("Check again").assertIsFocused()

        compose.mainClock.advanceTimeBy(PLAYBACK_CONVERSION_POLL_MILLIS - 100L)
        compose.runOnIdle { assertEquals(0, refreshes) }
        compose.mainClock.advanceTimeBy(200L)
        compose.runOnIdle { assertEquals(1, refreshes) }

        // While that read runs nothing else is asked; its answer starts the next wait.
        change(PlaybackContent.Conversion(PlaybackConversionState.Queued, PlaybackRequestId(2L)))
        compose.mainClock.advanceTimeBy(PLAYBACK_CONVERSION_POLL_MILLIS * 2)
        compose.runOnIdle { assertEquals(1, refreshes) }
        change(PlaybackContent.Conversion(PlaybackConversionState.Converting(42.4)))
        compose.onNodeWithTag(TV_CONVERSION_PERCENT_TAG).assertTextEquals("42%")
        compose.onNodeWithText(RUNNING_MESSAGE).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(PLAYBACK_CONVERSION_POLL_MILLIS + 100L)
        compose.runOnIdle { assertEquals(2, refreshes) }

        // Check again reads it now, and stays focused while that read runs.
        change(PlaybackContent.Conversion(PlaybackConversionState.Converting(42.4)))
        compose.onNodeWithText("Check again").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(3, refreshes) }
        change(PlaybackContent.Conversion(PlaybackConversionState.Converting(42.4), PlaybackRequestId(3L)))
        compose.onNodeWithText("Check again").assertIsFocused()
    }

    @Test
    fun aConversionIsNotReadAgainWhileTheAppIsInTheBackground() {
        show()
        compose.mainClock.advanceTimeBy(PLAYBACK_CONVERSION_POLL_MILLIS - 100L)
        moveTo(Lifecycle.State.CREATED)
        compose.mainClock.advanceTimeBy(PLAYBACK_CONVERSION_POLL_MILLIS * 3)
        compose.runOnIdle { assertEquals(0, refreshes) }

        // Back in the foreground, a full wait starts again.
        moveTo(Lifecycle.State.RESUMED)
        compose.mainClock.advanceTimeBy(PLAYBACK_CONVERSION_POLL_MILLIS - 100L)
        compose.runOnIdle { assertEquals(0, refreshes) }
        compose.mainClock.advanceTimeBy(200L)
        compose.runOnIdle { assertEquals(1, refreshes) }
    }

    @Test
    fun aFailedConversionOffersConvertAgainAndNeverPolls() {
        content = PlaybackContent.Conversion(PlaybackConversionState.Failed)
        show()
        compose.onNodeWithText("Conversion failed. Try again later.").assertIsDisplayed()
        compose.onNodeWithTag(TV_CONVERSION_PROGRESS_TAG).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(PLAYBACK_CONVERSION_POLL_MILLIS * 3)
        compose.runOnIdle { assertEquals(0, refreshes) }

        compose.onNodeWithText("Convert again").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, starts) }
    }

    @Test
    fun aConversionThatStaysCompletedOrUnknownWaitsForCheckAgain() {
        content = PlaybackContent.Conversion(PlaybackConversionState.Completed)
        show()
        compose.onNodeWithText("Conversion finished. Check again to play.").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(PLAYBACK_CONVERSION_POLL_MILLIS * 3)
        compose.runOnIdle { assertEquals(0, refreshes) }
        compose.onNodeWithText("Check again").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, refreshes) }

        change(PlaybackContent.Conversion(PlaybackConversionState.Unknown("PAUSED", null)))
        compose.onNodeWithText("Conversion status is unavailable.").assertIsDisplayed()
        compose.onNodeWithText("Check again").assertIsDisplayed()
    }

    @Test
    fun aStartingConversionShowsItRunningAndOffersOnlyCancel() {
        content = PlaybackContent.Conversion(PlaybackConversionState.NotAvailable, PlaybackRequestId(2L))
        show()
        compose.onNodeWithText(RUNNING_MESSAGE).assertIsDisplayed()
        compose.onNodeWithTag(TV_CONVERSION_PROGRESS_TAG).assertIsDisplayed()
        compose.onNodeWithText("Convert again").assertDoesNotExist()
        compose.onNodeWithText("Check again").assertDoesNotExist()
        compose.onNodeWithText("Cancel").assertIsFocused()

        change(PlaybackContent.Conversion(PlaybackConversionState.Queued))
        compose.onNodeWithText(RUNNING_MESSAGE).assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsFocused()
        compose.onNodeWithTag(TV_CONVERSION_PROGRESS_TAG).assertIsDisplayed()
    }

    @Test
    fun aQueuedConversionSaysItIsWaitingAndCancelLeavesThePlayer() {
        show()
        compose.onNodeWithTag(TV_CONVERSION_QUEUED_TAG).assertTextEquals("Waiting to convert…")
        compose.onNodeWithText("Check again").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Cancel").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, backs) }

        change(PlaybackContent.Conversion(PlaybackConversionState.Converting(42.4)))
        compose.onNodeWithTag(TV_CONVERSION_QUEUED_TAG).assertDoesNotExist()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun aStatusCheckThatLosesTheNetworkKeepsPollingAndOffersTryAgain() {
        content = PlaybackContent.Conversion(PlaybackConversionState.Converting(42.4), statusCheckFailed = true)
        show()
        compose.onNodeWithText("Converting to MP4").assertIsDisplayed()
        compose.onNodeWithText(STATUS_FAILED_MESSAGE).assertIsDisplayed()
        compose.onNodeWithText("Conversion failed. Try again later.").assertDoesNotExist()
        compose.onNodeWithTag(TV_CONVERSION_PROGRESS_TAG).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(PLAYBACK_CONVERSION_POLL_MILLIS * 3)
        compose.runOnIdle { assertEquals(3, refreshes) }

        compose.onNodeWithText("Try again").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle {
            assertEquals(4, refreshes)
            assertEquals(0, retries)
        }
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun aVideoThatCannotBeConvertedOffersNothingButBack() {
        // The app's own start still found no conversion.
        content = PlaybackContent.Conversion(PlaybackConversionState.NotAvailable)
        show()
        compose.onNodeWithText("This video cannot be converted.").assertIsDisplayed()
        compose.onNodeWithTag(TV_CONVERSION_PROGRESS_TAG).assertDoesNotExist()
        compose.onNodeWithText("Check again").assertDoesNotExist()
        compose.onNodeWithText("Convert again").assertDoesNotExist()
        compose.onNodeWithText("Play in VLC now").assertDoesNotExist()
        compose.onNodeWithText("Cancel").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(PLAYBACK_CONVERSION_POLL_MILLIS * 3)
        compose.runOnIdle { assertEquals(0, refreshes) }
    }

    @Test
    fun withVlcAConversionOffersPlayInVlcNowBesideItsActionInEveryState() {
        content = PlaybackContent.Conversion(PlaybackConversionState.Converting(42.4))
        show(playInVlc = { vlcOpens += 1 })
        compose.onNodeWithTag(TV_CONVERSION_PERCENT_TAG).assertTextEquals("42%")
        compose.onNodeWithText("Check again").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Play in VLC now").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, vlcOpens) }
        assertEquals(0, refreshes)

        change(PlaybackContent.Conversion(PlaybackConversionState.Failed))
        compose.onNodeWithText("Convert again").assertIsDisplayed()
        compose.onNodeWithText("Play in VLC now").assertIsFocused()

        change(PlaybackContent.Conversion(PlaybackConversionState.NotAvailable))
        compose.onNodeWithText("Convert again").assertDoesNotExist()
        compose.onNodeWithText("Play in VLC now").assertIsFocused()
    }

    @Test
    fun aNetworkFailureReadsConnectionLostAndOffersTryAgainThenGoBack() {
        content = PlaybackContent.Failed(PlaybackFailure.Putio(PutioFailure.NetworkUnavailable(IOException("offline"))))
        show()
        compose.onNodeWithText(TITLE).assertIsDisplayed()
        compose.onNodeWithText("Connection lost").assertIsDisplayed()
        compose.onNodeWithText("Check your internet connection.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithText("Try again").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Go back").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test
    fun goBackKeepsFocusWhenAFailureLosesItsTryAgain() {
        content = PlaybackContent.Failed(PlaybackFailure.Putio(PutioFailure.NetworkUnavailable(IOException("offline"))))
        show()
        compose.onNodeWithText("Try again").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Go back").assertIsFocused()

        val denied = PutioFailure.AccessDenied(PutioConfigurationException("test"))
        change(PlaybackContent.Failed(PlaybackFailure.Putio(denied)))
        compose.onNodeWithText("Try again").assertDoesNotExist()
        compose.onNodeWithText("Go back").assertIsFocused()
    }

    @Test
    fun aPlayerCrashReadsPlaybackErrorAndOffersTryAgainAndGoBack() {
        content = PlaybackContent.Failed(PlaybackFailure.Putio(PutioFailure.Unexpected(IllegalStateException("boom"))))
        show()
        compose.onNodeWithText("Playback error").assertIsDisplayed()
        compose.onNodeWithText("Something unexpected happened.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsFocused()
        compose.onNodeWithText("Go back").assertIsDisplayed()
    }

    @Test
    fun failuresExplainThemselvesAndAlwaysOfferAFocusedAction() {
        content = PlaybackContent.Failed(PlaybackFailure.MediaCredentialUnavailable(IOException()))
        show()
        compose.onNodeWithText("Couldn't play this file.").assertIsDisplayed()
        compose.onNodeWithText("The stream link expired. Try again to get a fresh one.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsFocused()
        compose.onNodeWithText("Go back").assertIsDisplayed()

        val cause = PutioConfigurationException("test")
        change(PlaybackContent.Failed(PlaybackFailure.Putio(PutioFailure.AuthenticationRequired(cause))))
        compose.onNodeWithText("Session expired. Please sign in again.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertDoesNotExist()
        compose.onNodeWithText("Go back").assertIsFocused()

        change(PlaybackContent.Failed(PlaybackFailure.Putio(PutioFailure.AccessDenied(cause))))
        compose.onNodeWithText("You don’t have access to this file.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertDoesNotExist()
        compose.onNodeWithText("Go back").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, backs) }

        // A refused request shows put.io's own reason; a bare error code keeps the app's copy.
        change(PlaybackContent.Failed(putioRefusal(400, putioErrorBody(400, "not a video")).toPlaybackFailure()))
        compose.onNodeWithText("not a video").assertIsDisplayed()
        compose.onNodeWithText("Go back").assertIsFocused()
        change(PlaybackContent.Failed(putioRefusal(400, putioErrorBody(400, "NOT_A_VIDEO")).toPlaybackFailure()))
        compose.onNodeWithText("put.io couldn’t prepare this file for playback.").assertIsDisplayed()
        change(PlaybackContent.Failed(putioRefusal(404, putioErrorBody(404, "NOT_FOUND")).toPlaybackFailure()))
        compose.onNodeWithText("File not found. This file may have been deleted.").assertIsDisplayed()
    }

    @Test
    fun aFormatThisTvCannotPlayOffersVlcWhereItIsWiredThenGoBack() {
        content = PlaybackContent.Failed(PlaybackFailure.MediaUnsupported(IOException()))
        show(openInVlc = { vlcOpens += 1 })
        compose.onNodeWithText("Can’t play this here").assertIsDisplayed()
        compose.onNodeWithText("This format needs another player.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertDoesNotExist()
        compose.onNodeWithText("Open in VLC").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, vlcOpens) }
        compose.onNodeWithText("Open in VLC").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Go back").assertIsFocused()
    }

    @Test
    fun withoutVlcACantPlayStateFocusesGoBack() {
        content = PlaybackContent.Failed(PlaybackFailure.MediaUnsupported(IOException()))
        show()
        compose.onNodeWithText("Can’t play this here").assertIsDisplayed()
        compose.onNodeWithText("Open in VLC").assertDoesNotExist()
        compose.onNodeWithText("Try again").assertDoesNotExist()
        compose.onNodeWithText("Go back").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test
    fun withoutVlcAFileWithNoConvertedVersionFocusesGoBack() {
        content = PlaybackContent.Unsupported(PutioFileType.VIDEO)
        show()
        compose.onNodeWithText("This TV can’t play the original file, and it has no converted version.")
            .assertIsDisplayed()
        compose.onNodeWithText("Open in VLC").assertDoesNotExist()
        compose.onNodeWithText("Go back").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test
    fun withVlcInstalledAFileWithNoConvertedVersionFocusesOpenInVlc() {
        content = PlaybackContent.Unsupported(PutioFileType.VIDEO)
        show(playInVlc = { vlcOpens += 1 })
        compose.onNodeWithText("Open in VLC").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, vlcOpens) }
        compose.onNodeWithText("Open in VLC").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Go back").assertIsFocused()
    }

    private fun change(next: PlaybackContent) {
        compose.runOnIdle {
            content = next
            // The clock is manual, so publish the write before the next frame.
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeBy(50L)
        compose.waitForIdle()
    }

    private fun moveTo(state: Lifecycle.State) {
        compose.runOnIdle { lifecycleOwner.registry.currentState = state }
        compose.mainClock.advanceTimeBy(50L)
        compose.waitForIdle()
    }

    private fun show(openInVlc: (() -> Unit)? = null, playInVlc: (() -> Unit)? = null) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                PutioTvTheme {
                    TvPlayerScreen(
                        state = playbackState(
                            target = PlaybackTarget(FilesItemId(9), TITLE, PlaybackMediaType.VIDEO),
                            content = content,
                            nextRequestValue = 3L,
                        ),
                        onBack = { backs += 1 },
                        onRetry = { retries += 1 },
                        onResume = {},
                        onRestart = {},
                        onPlayerFailure = { _, _ -> },
                        onRefreshConversion = { refreshes += 1 },
                        onStartConversion = { starts += 1 },
                        playerFactory = { _, _ -> error("No player before a source") },
                        onOpenInVlc = openInVlc,
                        onPlayInVlc = playInVlc,
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(50L)
        compose.waitForIdle()
    }

    private companion object {
        const val TITLE = "Big Buck Bunny.avi"
        const val RUNNING_MESSAGE = "It keeps going if you leave, and playback starts when it’s done."
        const val STATUS_FAILED_MESSAGE = "Can’t check conversion status. Check your internet connection."
    }
}
