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
    fun aStartingConversionShowsItRunningAndOffersNothingToPress() {
        content = PlaybackContent.Conversion(PlaybackConversionState.NotAvailable, PlaybackRequestId(2L))
        show()
        compose.onNodeWithText(RUNNING_MESSAGE).assertIsDisplayed()
        compose.onNodeWithTag(TV_CONVERSION_PROGRESS_TAG).assertIsDisplayed()
        compose.onNodeWithText("Convert again").assertDoesNotExist()
        compose.onNodeWithText("Check again").assertDoesNotExist()

        change(PlaybackContent.Conversion(PlaybackConversionState.Queued))
        compose.onNodeWithText(RUNNING_MESSAGE).assertIsDisplayed()
        compose.onNodeWithTag(TV_CONVERSION_PROGRESS_TAG).assertIsDisplayed()
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
        compose.mainClock.advanceTimeBy(PLAYBACK_CONVERSION_POLL_MILLIS * 3)
        compose.runOnIdle { assertEquals(0, refreshes) }
    }

    @Test
    fun failuresExplainThemselvesAndOfferTryAgainOnlyWhereItCanWork() {
        content = PlaybackContent.Failed(PlaybackFailure.Putio(PutioFailure.NetworkUnavailable(IOException("offline"))))
        show()
        compose.onNodeWithText(TITLE).assertIsDisplayed()
        compose.onNodeWithText("Couldn't play this file.").assertIsDisplayed()
        compose.onNodeWithText("Check the network and try again.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, retries) }

        change(PlaybackContent.Failed(PlaybackFailure.MediaCredentialUnavailable(IOException())))
        compose.onNodeWithText("The stream link expired. Try again to get a fresh one.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsDisplayed()

        val cause = PutioConfigurationException("test")
        change(PlaybackContent.Failed(PlaybackFailure.Putio(PutioFailure.AuthenticationRequired(cause))))
        compose.onNodeWithText("Your session expired. Sign in again.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertDoesNotExist()

        change(PlaybackContent.Failed(PlaybackFailure.Putio(PutioFailure.AccessDenied(cause))))
        compose.onNodeWithText("You don’t have access to this file.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertDoesNotExist()

        // A refused request shows put.io's own reason; a bare error code keeps the app's copy.
        change(PlaybackContent.Failed(putioRefusal(400, putioErrorBody(400, "not a video")).toPlaybackFailure()))
        compose.onNodeWithText("not a video").assertIsDisplayed()
        change(PlaybackContent.Failed(putioRefusal(400, putioErrorBody(400, "NOT_A_VIDEO")).toPlaybackFailure()))
        compose.onNodeWithText("put.io couldn’t prepare this file for playback.").assertIsDisplayed()
    }

    @Test
    fun aFormatThisTvCannotPlayOffersVlcWhereItIsWired() {
        content = PlaybackContent.Failed(PlaybackFailure.MediaUnsupported(IOException()))
        show(openInVlc = { vlcOpens += 1 })
        compose.onNodeWithText("This TV can't play this file.").assertIsDisplayed()
        compose.onNodeWithText("Its format isn't supported here. VLC can usually play it.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertDoesNotExist()
        compose.onNodeWithText("Open in VLC").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, vlcOpens) }
    }

    @Test
    fun withoutVlcACantPlayStateOffersNothingButBack() {
        content = PlaybackContent.Failed(PlaybackFailure.MediaUnsupported(IOException()))
        show()
        compose.onNodeWithText("This TV can't play this file.").assertIsDisplayed()
        compose.onNodeWithText("Open in VLC").assertDoesNotExist()
        compose.onNodeWithText("Try again").assertDoesNotExist()
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

    private fun show(openInVlc: (() -> Unit)? = null) {
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
                        onBack = {},
                        onRetry = { retries += 1 },
                        onResume = {},
                        onRestart = {},
                        onPlayerFailure = { _, _ -> },
                        onRefreshConversion = { refreshes += 1 },
                        onStartConversion = { starts += 1 },
                        playerFactory = { _, _ -> error("No player before a source") },
                        onOpenInVlc = openInVlc,
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
    }
}
