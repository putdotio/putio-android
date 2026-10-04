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
import androidx.tv.material3.MaterialTheme
import io.putdotio.android.design.putioTvDarkColorScheme
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

/** The TV player's conversion interstitial and typed failure screens. */
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
    private val lifecycleOwner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun aQueuedConversionShowsItsStatusAndReadsItAgainEveryThreeSeconds() {
        show()
        compose.onNodeWithText(TITLE).assertIsDisplayed()
        compose.onNodeWithText("CONVERSION STATUS").assertIsDisplayed()
        compose.onNodeWithTag(TV_CONVERSION_STATUS_TAG).assertTextEquals("In queue…")
        compose.onNodeWithText("Check again").assertDoesNotExist()

        compose.mainClock.advanceTimeBy(PLAYBACK_CONVERSION_POLL_MILLIS - 100L)
        compose.runOnIdle { assertEquals(0, refreshes) }
        compose.mainClock.advanceTimeBy(200L)
        compose.runOnIdle { assertEquals(1, refreshes) }

        // While that read runs nothing else is asked; its answer starts the next wait.
        change(PlaybackContent.Conversion(PlaybackConversionState.Queued, PlaybackRequestId(2L)))
        compose.mainClock.advanceTimeBy(PLAYBACK_CONVERSION_POLL_MILLIS * 2)
        compose.runOnIdle { assertEquals(1, refreshes) }
        change(PlaybackContent.Conversion(PlaybackConversionState.Converting(42.4)))
        compose.onNodeWithTag(TV_CONVERSION_STATUS_TAG).assertTextEquals("42%")
        compose.mainClock.advanceTimeBy(PLAYBACK_CONVERSION_POLL_MILLIS + 100L)
        compose.runOnIdle { assertEquals(2, refreshes) }
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
        compose.onNodeWithTag(TV_CONVERSION_STATUS_TAG).assertTextEquals("Failed")
        compose.mainClock.advanceTimeBy(PLAYBACK_CONVERSION_POLL_MILLIS * 3)
        compose.runOnIdle { assertEquals(0, refreshes) }

        compose.onNodeWithText("Convert again").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, starts) }
    }

    @Test
    fun aConversionThatStaysCompletedOrUnknownWaitsForCheckAgain() {
        content = PlaybackContent.Conversion(PlaybackConversionState.Completed)
        show()
        compose.onNodeWithTag(TV_CONVERSION_STATUS_TAG).assertTextEquals("Completed")
        compose.mainClock.advanceTimeBy(PLAYBACK_CONVERSION_POLL_MILLIS * 3)
        compose.runOnIdle { assertEquals(0, refreshes) }
        compose.onNodeWithText("Check again").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, refreshes) }

        change(PlaybackContent.Conversion(PlaybackConversionState.Unknown("PAUSED", null)))
        compose.onNodeWithTag(TV_CONVERSION_STATUS_TAG).assertTextEquals("PAUSED")
        compose.onNodeWithText("Check again").assertIsDisplayed()
    }

    @Test
    fun aStartingConversionSaysItHasStartedAndOffersNothingToPress() {
        content = PlaybackContent.Conversion(PlaybackConversionState.NotAvailable, PlaybackRequestId(2L))
        show()
        compose.onNodeWithText(STARTED_MESSAGE).assertIsDisplayed()
        compose.onNodeWithTag(TV_CONVERSION_STATUS_TAG).assertTextEquals("Starting…")
        compose.onNodeWithText("Convert again").assertDoesNotExist()
        compose.onNodeWithText("Check again").assertDoesNotExist()

        change(PlaybackContent.Conversion(PlaybackConversionState.Queued))
        compose.onNodeWithText(STARTED_MESSAGE).assertIsDisplayed()
        compose.onNodeWithTag(TV_CONVERSION_STATUS_TAG).assertTextEquals("In queue…")
    }

    @Test
    fun aVideoThatCannotBeConvertedOffersNothingButBack() {
        // The app's own start still found no conversion.
        content = PlaybackContent.Conversion(PlaybackConversionState.NotAvailable)
        show()
        compose.onNodeWithText("This video isn’t in a format this app can play, and it can’t be converted.")
            .assertIsDisplayed()
        compose.onNodeWithTag(TV_CONVERSION_STATUS_TAG).assertTextEquals("Not available")
        compose.onNodeWithText("Check again").assertDoesNotExist()
        compose.onNodeWithText("Convert again").assertDoesNotExist()
        compose.onNodeWithText("Convert").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(PLAYBACK_CONVERSION_POLL_MILLIS * 3)
        compose.runOnIdle { assertEquals(0, refreshes) }
    }

    @Test
    fun failuresExplainThemselvesAndOfferTryAgainOnlyWhereItCanWork() {
        content = PlaybackContent.Failed(PlaybackFailure.NetworkUnavailable(IOException("offline")))
        show()
        compose.onNodeWithText("Check the network and try again.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, retries) }

        change(PlaybackContent.Failed(PlaybackFailure.MediaCredentialUnavailable(IOException())))
        compose.onNodeWithText("The playback link expired. Try again for a new one.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsDisplayed()

        change(PlaybackContent.Failed(PlaybackFailure.MediaUnsupported(IOException())))
        compose.onNodeWithText("This file can’t be played").assertIsDisplayed()
        compose.onNodeWithText("This device can’t play its format.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertDoesNotExist()

        val cause = PutioConfigurationException("test")
        change(PlaybackContent.Failed(PlaybackFailure.AuthenticationRequired(cause)))
        compose.onNodeWithText("Your session expired. Sign in again.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertDoesNotExist()

        change(PlaybackContent.Failed(PlaybackFailure.AccessDenied(cause)))
        compose.onNodeWithText("You don’t have access to this file.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertDoesNotExist()

        // A refused request shows put.io's own reason; a bare error code keeps the app's copy.
        change(PlaybackContent.Failed(putioRefusal(400, putioErrorBody(400, "not a video")).toPlaybackFailure()))
        compose.onNodeWithText("not a video").assertIsDisplayed()
        change(PlaybackContent.Failed(putioRefusal(400, putioErrorBody(400, "NOT_A_VIDEO")).toPlaybackFailure()))
        compose.onNodeWithText("put.io couldn’t prepare this file for playback.").assertIsDisplayed()
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

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
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
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(50L)
        compose.waitForIdle()
    }

    private companion object {
        const val TITLE = "Big Buck Bunny.avi"
        const val STARTED_MESSAGE =
            "This video isn’t in a format this app can play yet, so its conversion has started. It plays here once it finishes."
    }
}
